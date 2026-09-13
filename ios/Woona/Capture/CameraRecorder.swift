import AVFoundation
import SwiftUI

enum HostClock {
    static func nowNanoseconds() -> UInt64 {
        nanoseconds(CMClockGetTime(CMClockGetHostTimeClock()))
    }

    static func nanoseconds(_ time: CMTime) -> UInt64 {
        let seconds = CMTimeGetSeconds(time)
        guard seconds.isFinite, seconds >= 0 else { return 0 }
        return UInt64(seconds * 1_000_000_000)
    }
}

struct CameraCaptureInfo: Codable, Equatable {
    let requestedMonotonicNs: UInt64
    let writerStartedMonotonicNs: UInt64?
    let firstFrameMonotonicNs: UInt64?
    let firstFrameCameraTimestampNs: UInt64?
    let firstFrameCallbackMonotonicNs: UInt64?
    let firstVideoSamplePtsUs: UInt64?
    let timestampSource: String
    let clockQuality: String
    let width: Int
    let height: Int
    let frameRate: Int
    let durationNs: UInt64
}

enum CameraRecorderError: LocalizedError {
    case permissionDenied
    case unavailable
    case cannotConfigure
    case writer(String)

    var errorDescription: String? {
        switch self {
        case .permissionDenied: "Camera permission denied"
        case .unavailable: "Rear camera is unavailable"
        case .cannotConfigure: "Unable to configure camera capture"
        case .writer(let message): message
        }
    }
}

final class CameraRecorder: NSObject, AVCaptureVideoDataOutputSampleBufferDelegate {
    var onRecordingFailure: (@Sendable (String) -> Void)?
    let session = AVCaptureSession()
    lazy var previewLayer: AVCaptureVideoPreviewLayer = {
        let layer = AVCaptureVideoPreviewLayer(session: session)
        layer.videoGravity = .resizeAspect
        return layer
    }()

    private let queue = DispatchQueue(label: "com.woona.camera", qos: .userInitiated)
    private let output = AVCaptureVideoDataOutput()
    private var configured = false
    private var writer: AVAssetWriter?
    private var writerInput: AVAssetWriterInput?
    private var outputURL: URL?
    private var requestedMonotonicNs: UInt64 = 0
    private var writerStartedMonotonicNs: UInt64?
    private var firstFrameMonotonicNs: UInt64?
    private var firstFrameCameraTimestampNs: UInt64?
    private var firstFrameCallbackMonotonicNs: UInt64?
    private var firstVideoSamplePtsUs: UInt64?
    private var firstPresentationTime: CMTime?
    private var lastPresentationTime: CMTime?
    private var timestampSource = "unknown"
    private var clockQuality = "unavailable"
    private var width = 1_920
    private var height = 1_080
    private var isRecording = false
    private var writerFailure: CameraRecorderError?

    func prepare() async throws {
        switch AVCaptureDevice.authorizationStatus(for: .video) {
        case .authorized: break
        case .notDetermined:
            guard await AVCaptureDevice.requestAccess(for: .video) else { throw CameraRecorderError.permissionDenied }
        default: throw CameraRecorderError.permissionDenied
        }
        try await withCheckedThrowingContinuation { continuation in
            queue.async {
                do {
                    try self.configureIfNeeded()
                    if !self.session.isRunning { self.session.startRunning() }
                    continuation.resume()
                } catch {
                    continuation.resume(throwing: error)
                }
            }
        }
    }

    func startRecording(to url: URL) async throws -> UInt64 {
        try await prepare()
        return try await withCheckedThrowingContinuation { continuation in
            queue.async {
                do {
                    try? FileManager.default.removeItem(at: url)
                    let writer = try AVAssetWriter(outputURL: url, fileType: .mp4)
                    let input = AVAssetWriterInput(
                        mediaType: .video,
                        outputSettings: [
                            AVVideoCodecKey: AVVideoCodecType.h264,
                            AVVideoWidthKey: self.width,
                            AVVideoHeightKey: self.height,
                        ]
                    )
                    input.expectsMediaDataInRealTime = true
                    guard writer.canAdd(input) else { throw CameraRecorderError.cannotConfigure }
                    writer.add(input)
                    self.writer = writer
                    self.writerInput = input
                    self.outputURL = url
                    self.requestedMonotonicNs = HostClock.nowNanoseconds()
                    self.writerStartedMonotonicNs = nil
                    self.firstFrameMonotonicNs = nil
                    self.firstFrameCameraTimestampNs = nil
                    self.firstFrameCallbackMonotonicNs = nil
                    self.firstVideoSamplePtsUs = nil
                    self.firstPresentationTime = nil
                    self.lastPresentationTime = nil
                    self.timestampSource = "unknown"
                    self.clockQuality = "unavailable"
                    self.writerFailure = nil
                    self.isRecording = true
                    continuation.resume(returning: self.requestedMonotonicNs)
                } catch {
                    continuation.resume(throwing: error)
                }
            }
        }
    }

    func stopRecording() async throws -> CameraCaptureInfo? {
        try await withCheckedThrowingContinuation { continuation in
            queue.async {
                guard self.isRecording else {
                    if let writerFailure = self.writerFailure {
                        self.writer?.cancelWriting()
                        if let outputURL = self.outputURL { try? FileManager.default.removeItem(at: outputURL) }
                        self.writer = nil
                        self.writerInput = nil
                        self.outputURL = nil
                        self.writerFailure = nil
                        continuation.resume(throwing: writerFailure)
                    } else {
                        continuation.resume(returning: nil)
                    }
                    return
                }
                self.isRecording = false
                guard let writer = self.writer, let input = self.writerInput else {
                    continuation.resume(throwing: CameraRecorderError.writer("Video writer was not initialized"))
                    return
                }
                guard writer.status != .unknown else {
                    if let outputURL = self.outputURL { try? FileManager.default.removeItem(at: outputURL) }
                    self.writer = nil
                    self.writerInput = nil
                    self.outputURL = nil
                    continuation.resume(throwing: CameraRecorderError.writer("No camera frames were recorded"))
                    return
                }
                input.markAsFinished()
                writer.finishWriting {
                    self.queue.async {
                        defer { self.writer = nil; self.writerInput = nil; self.outputURL = nil; self.writerFailure = nil }
                        guard writer.status == .completed else {
                            continuation.resume(throwing: CameraRecorderError.writer(writer.error?.localizedDescription ?? "Video finalization failed"))
                            return
                        }
                        guard
                            let outputURL = self.outputURL,
                            let size = try? FileManager.default.attributesOfItem(atPath: outputURL.path)[.size] as? NSNumber,
                            size.int64Value > 0
                        else {
                            continuation.resume(throwing: CameraRecorderError.writer("Finalized video is empty"))
                            return
                        }
                        let first = self.firstPresentationTime.map(HostClock.nanoseconds) ?? 0
                        let last = self.lastPresentationTime.map(HostClock.nanoseconds) ?? first
                        continuation.resume(
                            returning: CameraCaptureInfo(
                                requestedMonotonicNs: self.requestedMonotonicNs,
                                writerStartedMonotonicNs: self.writerStartedMonotonicNs,
                                firstFrameMonotonicNs: self.firstFrameMonotonicNs,
                                firstFrameCameraTimestampNs: self.firstFrameCameraTimestampNs,
                                firstFrameCallbackMonotonicNs: self.firstFrameCallbackMonotonicNs,
                                firstVideoSamplePtsUs: self.firstVideoSamplePtsUs,
                                timestampSource: self.timestampSource,
                                clockQuality: self.clockQuality,
                                width: self.width,
                                height: self.height,
                                frameRate: 30,
                                durationNs: last >= first ? last - first : 0
                            )
                        )
                    }
                }
            }
        }
    }

    func stopPreview() {
        queue.async { if self.session.isRunning { self.session.stopRunning() } }
    }

    func captureOutput(_ output: AVCaptureOutput, didOutput sampleBuffer: CMSampleBuffer, from connection: AVCaptureConnection) {
        guard isRecording, CMSampleBufferDataIsReady(sampleBuffer), let writer, let writerInput else { return }
        let presentationTime = CMSampleBufferGetPresentationTimeStamp(sampleBuffer)
        let callbackTime = HostClock.nowNanoseconds()
        if writer.status == .unknown {
            guard writer.startWriting() else {
                let message = writer.error?.localizedDescription ?? "Video writer failed to start"
                writerFailure = .writer(message)
                isRecording = false
                onRecordingFailure?(message)
                return
            }
            writer.startSession(atSourceTime: presentationTime)
            writerStartedMonotonicNs = callbackTime
        }
        guard writer.status == .writing, writerInput.isReadyForMoreMediaData else { return }
        guard writerInput.append(sampleBuffer) else {
            let message = writer.error?.localizedDescription ?? "Video writer rejected a frame"
            writerFailure = .writer(message)
            isRecording = false
            onRecordingFailure?(message)
            return
        }
        if firstPresentationTime == nil {
            firstPresentationTime = presentationTime
            firstVideoSamplePtsUs = UInt64(max(CMTimeGetSeconds(presentationTime), 0) * 1_000_000)
            firstFrameCallbackMonotonicNs = callbackTime
            if let clock = session.synchronizationClock {
                let hostTime = clock.convertTime(presentationTime, to: CMClockGetHostTimeClock())
                firstFrameMonotonicNs = HostClock.nanoseconds(hostTime)
                firstFrameCameraTimestampNs = HostClock.nanoseconds(presentationTime)
                timestampSource = "avfoundation_session_clock"
                clockQuality = "hardware_monotonic"
            } else {
                firstFrameMonotonicNs = callbackTime
                timestampSource = "unknown"
                clockQuality = "callback_estimate"
            }
        }
        lastPresentationTime = presentationTime
    }

    private func configureIfNeeded() throws {
        guard !configured else { return }
        guard let camera = AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: .back) else {
            throw CameraRecorderError.unavailable
        }
        let input = try AVCaptureDeviceInput(device: camera)
        session.beginConfiguration()
        defer { session.commitConfiguration() }
        session.sessionPreset = session.canSetSessionPreset(.hd1920x1080) ? .hd1920x1080 : .high
        guard session.canAddInput(input), session.canAddOutput(output) else { throw CameraRecorderError.cannotConfigure }
        session.addInput(input)
        output.alwaysDiscardsLateVideoFrames = true
        output.videoSettings = [kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange]
        output.setSampleBufferDelegate(self, queue: queue)
        session.addOutput(output)
        if let connection = output.connection(with: .video), connection.isVideoRotationAngleSupported(0) {
            connection.videoRotationAngle = 0
        }
        let dimensions = CMVideoFormatDescriptionGetDimensions(camera.activeFormat.formatDescription)
        width = min(Int(dimensions.width), 1_920)
        height = min(Int(dimensions.height), 1_080)
        do {
            try camera.lockForConfiguration()
            camera.activeVideoMinFrameDuration = CMTime(value: 1, timescale: 30)
            camera.activeVideoMaxFrameDuration = CMTime(value: 1, timescale: 30)
            camera.unlockForConfiguration()
        } catch {
            // The selected format can reject 30 fps; capture still remains valid at its native rate.
        }
        configured = true
    }
}

struct CameraPreview: UIViewRepresentable {
    let recorder: CameraRecorder

    func makeUIView(context: Context) -> PreviewView {
        let view = PreviewView()
        view.layer.addSublayer(recorder.previewLayer)
        return view
    }

    func updateUIView(_ view: PreviewView, context: Context) {
        recorder.previewLayer.frame = view.bounds
    }

    final class PreviewView: UIView {
        override func layoutSubviews() {
            super.layoutSubviews()
            layer.sublayers?.forEach { $0.frame = bounds }
        }
    }
}
