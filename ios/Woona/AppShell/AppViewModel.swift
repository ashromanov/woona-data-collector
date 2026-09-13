import BackgroundTasks
import Foundation

@MainActor
final class AppViewModel: ObservableObject {
    @Published var selectedTab: AppTab = .overview
    @Published private(set) var isReplayRunning = false
    @Published private(set) var isConnected = false
    @Published private(set) var isConnectionActive = false
    @Published private(set) var isScanning = false
    @Published private(set) var statusText = "Idle"
    @Published private(set) var foundDevices: [BleDevice] = []
    @Published private(set) var packetsReceived: UInt64 = 0
    @Published private(set) var packetsLost: UInt64 = 0
    @Published private(set) var packetsRejected: UInt64 = 0
    @Published private(set) var timerRegressionRejects: UInt64 = 0
    @Published private(set) var fragmentsReceived: UInt64 = 0
    @Published private(set) var rawBytesReceived: UInt64 = 0
    @Published private(set) var lastPacketIssue: String?
    @Published private(set) var rejectionBreakdown: String?
    @Published private(set) var diagnosticMessages: [String] = []
    @Published private(set) var errorMessage: String?
    @Published private(set) var selectedTransportProfile: BleTransportProfile = .iosManaged
    @Published var selectedThemeMode: AppThemeMode = .system {
        didSet { UserDefaults.standard.set(selectedThemeMode.rawValue, forKey: "themeMode") }
    }
    @Published var selectedLanguage: AppLanguage = .english {
        didSet { UserDefaults.standard.set(selectedLanguage.rawValue, forKey: "language") }
    }
    @Published private(set) var selectedSensorType = 2
    @Published private(set) var selectedChannel = 1
    @Published private(set) var chart = ChartUiState()
    @Published private(set) var exportPhase: ExportPhase?
    @Published private(set) var preparedExport: PreparedExport?
    @Published private(set) var dogProfiles: [DogProfile] = []
    @Published private(set) var selectedDogProfile: DogProfile?
    @Published private(set) var recentRecordings: [WoonaRecording] = []
    @Published private(set) var serverRecordings: [ServerRecordingSummary] = []
    @Published private(set) var downloadableRecordingIDs: Set<UUID> = []
    @Published private(set) var downloadingRecordingID: UUID?
    @Published private(set) var activeRecording: WoonaRecording?
    @Published private(set) var isCaptureReady = false
    @Published private(set) var isRecording = false
    @Published private(set) var isCameraDegraded = false
    @Published var serverBaseURL = ""
    @Published var serverToken = ""
    @Published var serverWifiOnly = true

    let cameraRecorder = CameraRecorder()

    private lazy var packetProcessor = makePacketProcessor()
    private lazy var packetUpdateBatcher = makePacketUpdateBatcher()
    private lazy var packetFragmentSubmitter = makePacketFragmentSubmitter()
    private lazy var bleSessionManager = makeBleSessionManager()
    private lazy var replayController = makeReplayController()
    private let packetIngressGate = PacketIngressGate()
    private let keychainTokenStore = KeychainTokenStore()
    private let store: WoonaStore?
    private var currentSessionWallClockStartMillis: Int64?
    private var chartHistory: [ChartStreamKey: [ChartPoint]] = [:]
    private var sessionStartMillis: UInt64?
    private var latestChartTimeMillis: UInt64?
    private var chartWindowPreset: ChartWindowPreset = .thirtySeconds
    private var isFollowingLive = true
    private var manualViewportEndMillis: UInt64?
    private var chartVerticalZoomFactor: Float = 1
    private var chartVerticalCenterOverride: Float?
    private var hasInitializedBleSessionManager = false
    private var isCaptureDiagnosticsActive = false
    private var finishCaptureProcessingTask: Task<Void, Never>?
    private var finishCaptureProcessingTaskID: UInt64 = 0
    private var preparedExportSnapshotDirectory: URL?
    private var cameraCaptureInfo: CameraCaptureInfo?
    private var captureErrorCode: String?
    private var captureErrorMessage: String?
    private var captureSessionZeroAtUTC: String?
    private var captureSessionZeroWallClockMs: Int64?
    private var captureSessionZeroMonotonicNs: UInt64?
    private var captureSessionZeroUncertaintyNs: UInt64?
    private var captureStopInProgress = false
    private var hasPreparedActiveCapture = false

    init(store: WoonaStore? = try? WoonaStore()) {
        self.store = store
        let defaults = UserDefaults.standard
        selectedThemeMode = defaults.string(forKey: "themeMode").flatMap(AppThemeMode.init(rawValue:)) ?? .system
        selectedLanguage = defaults.string(forKey: "language").flatMap(AppLanguage.init(rawValue:)) ?? .english
        serverBaseURL = defaults.string(forKey: "serverBaseURL") ?? "http://127.0.0.1:8080"
        serverWifiOnly = defaults.object(forKey: "serverWifiOnly") as? Bool ?? true
        serverToken = keychainTokenStore.load()
        cameraRecorder.onRecordingFailure = { [weak self] message in
            Task { @MainActor in self?.handleCameraFailure(message) }
        }
        reloadProfiles(selecting: defaults.string(forKey: "selectedDogID").flatMap(UUID.init(uuidString:)))
        if store == nil { errorMessage = "Unable to open local Woona database" }
        registerBackgroundSync()
        Task { await resumePendingSync() }
    }

    enum AppTab: Hashable {
        case overview
        case charts
        case settings
    }

    var currentTransportProfileTitle: String {
        selectedTransportProfile.title
    }

    func saveDogProfile(_ questionnaire: DogQuestionnaire, replacing profile: DogProfile? = nil) {
        guard let store else { return }
        do {
            let saved = try store.saveProfile(questionnaire, replacing: profile)
            reloadProfiles(selecting: saved.id)
            Task { await uploadProfile(saved) }
        } catch { showError("Failed to save dog profile", error: error) }
    }

    func selectDogProfile(_ profile: DogProfile) {
        selectedDogProfile = profile
        UserDefaults.standard.set(profile.id.uuidString, forKey: "selectedDogID")
        reloadRecentRecordings()
        Task { await reloadServerRecordings() }
    }

    func saveServerSettings() {
        serverBaseURL = serverBaseURL.trimmingCharacters(in: .whitespacesAndNewlines)
        serverToken = serverToken.trimmingCharacters(in: .whitespacesAndNewlines)
        UserDefaults.standard.set(serverBaseURL, forKey: "serverBaseURL")
        UserDefaults.standard.set(serverWifiOnly, forKey: "serverWifiOnly")
        do { try keychainTokenStore.save(serverToken) }
        catch { showError("Failed to store server token", error: error) }
    }

    func refreshFromServer() {
        saveServerSettings()
        guard let store else { return }
        Task {
            do {
                let client = try serverClient()
                _ = try await client.deviceID()
                let restored = try await client.restoreProfiles(into: store)
                _ = try await client.restoreRecordings(dogIDs: restored.map(\.id), into: store)
                reloadProfiles(selecting: selectedDogProfile?.id)
                await reloadServerRecordings()
                appendDiagnostic("Server restore completed")
            } catch { showError("Server restore failed", error: error) }
        }
    }

    func resumePendingSync() async {
        guard !serverToken.isEmpty, let store else { return }
        do {
            for recording in try store.pendingRecordings() {
                await sync(recording: recording)
            }
            reloadRecentRecordings()
        } catch { appendDiagnostic("Pending sync unavailable: \(error.localizedDescription)") }
    }

    func downloadRecording(_ recordingID: UUID) {
        guard downloadingRecordingID == nil, let store else { return }
        downloadingRecordingID = recordingID
        Task {
            defer { downloadingRecordingID = nil }
            do {
                let client = try serverClient()
                for artifact in try store.missingArtifacts(recordingID: recordingID) {
                    let target = store.rootDirectory.appendingPathComponent(artifact.relativePath)
                    try FileManager.default.createDirectory(at: target.deletingLastPathComponent(), withIntermediateDirectories: true)
                    try await client.download(
                        artifactID: artifact.id,
                        to: target,
                        expectedSize: artifact.sizeBytes,
                        sha256: artifact.sha256
                    )
                }
                reloadRecentRecordings()
                appendDiagnostic("Server files downloaded and verified")
            } catch { showError("Server download failed", error: error) }
        }
    }

    func retryFailedSync() {
        guard let store else { return }
        do {
            try store.resetFailedSync()
            Task { await resumePendingSync() }
        } catch { showError("Failed to retry synchronization", error: error) }
    }

    func scheduleBackgroundSync() {
        let request = BGProcessingTaskRequest(identifier: Self.backgroundSyncIdentifier)
        request.requiresNetworkConnectivity = true
        request.requiresExternalPower = false
        try? BGTaskScheduler.shared.submit(request)
    }

    func applyConnectionState(isConnected: Bool) {
        guard !isReplayRunning else { return }
        self.isConnected = isConnected
        isConnectionActive = isConnected
        statusText = isConnected ? "Connected" : "Disconnected"
    }

    func addFoundDevice(_ device: BleDevice) {
        if let existingIndex = foundDevices.firstIndex(where: { $0.id == device.id }) {
            foundDevices[existingIndex] = device
        } else {
            foundDevices.append(device)
        }
    }

    func startScanning() {
        foundDevices.removeAll(keepingCapacity: true)
        errorMessage = nil
        _ = bleSessionManager
        bleSessionManager.startScanning(useServiceFilter: false)
    }

    func stopScanning() {
        bleSessionManager.stopScanning()
    }

    func connect(to device: BleDevice) {
        Task {
            var didPrepareCapture = false
            let didStartConnection = await bleSessionManager.connect(to: device.id) {
                didPrepareCapture = true
                await prepareCaptureForConnection()
            }
            if didPrepareCapture, !didStartConnection {
                await finishCaptureProcessing()
            }
            if !didStartConnection, activeRecording != nil {
                stopActiveCapture(status: "failed")
            }
        }
    }

    func connect(to device: BleDevice, questionnaire: SessionQuestionnaire) {
        guard activeRecording == nil, !captureStopInProgress else { return }
        guard let profile = selectedDogProfile, let store else {
            showError("Select or create a dog profile first", error: nil)
            return
        }
        do {
            activeRecording = try store.createRecording(profile: profile, source: "live", questionnaire: questionnaire)
            isCaptureReady = false
            isRecording = false
            isCameraDegraded = false
            cameraCaptureInfo = nil
            captureErrorCode = nil
            captureErrorMessage = nil
            captureStopInProgress = false
            hasPreparedActiveCapture = false
            resetCaptureClockAnchor()
            connect(to: device)
        } catch { showError("Failed to create recording", error: error) }
    }

    func disconnect() {
        if activeRecording != nil {
            stopActiveCapture(status: isRecording ? "completed" : "interrupted")
            return
        }
        bleSessionManager.disconnect()
        finishCaptureProcessingIfNeeded()
    }

    func startReplay(from fileURL: URL) {
        Task {
            do {
                let didAccess = fileURL.startAccessingSecurityScopedResource()
                defer {
                    if didAccess {
                        fileURL.stopAccessingSecurityScopedResource()
                    }
                }
                let bytes = try [UInt8](Data(contentsOf: fileURL))
                packetUpdateBatcher.clearPending()
                await packetProcessor.resetSession()
                hasPreparedActiveCapture = true
                packetUpdateBatcher.clearPending()
                await replayController.startReplay(bytes)
            } catch {
                await packetProcessor.resetSession()
                showError("Failed to load replay file", error: error)
                if activeRecording?.source == "replay" { stopActiveCapture(status: "failed") }
            }
        }
    }

    func startReplay(from fileURL: URL, questionnaire: SessionQuestionnaire) {
        guard activeRecording == nil, !captureStopInProgress else { return }
        guard let profile = selectedDogProfile, let store else {
            showError("Select or create a dog profile first", error: nil)
            return
        }
        do {
            activeRecording = try store.createRecording(profile: profile, source: "replay", questionnaire: questionnaire)
            cameraCaptureInfo = nil
            captureErrorCode = nil
            captureErrorMessage = nil
            captureStopInProgress = false
            hasPreparedActiveCapture = false
            resetCaptureClockAnchor()
            startReplay(from: fileURL)
        } catch { showError("Failed to create replay recording", error: error) }
    }

    func startActiveCapture() {
        guard isCaptureReady, !isRecording, !captureStopInProgress,
              let recording = activeRecording, let store else { return }
        do { try store.markRecording(recording.id, status: "recording") }
        catch { showError("Failed to start recording", error: error); return }
        isRecording = true
        statusText = "Recording"
        setCaptureClockAnchor()
        packetIngressGate.start()
        guard recording.videoRequested else { return }
        guard !isCameraDegraded else { return }
        let output = store.directory(for: recording).appendingPathComponent("video.mp4")
        Task {
            do { _ = try await cameraRecorder.startRecording(to: output) }
            catch {
                handleCameraFailure(error.localizedDescription)
            }
        }
    }

    func stopActiveCapture(status: String = "completed") {
        guard let recording = activeRecording, !captureStopInProgress else { return }
        captureStopInProgress = true
        packetIngressGate.stop()
        isRecording = false
        statusText = "Finalizing"
        Task {
            if recording.videoRequested {
                do { cameraCaptureInfo = try await cameraRecorder.stopRecording() }
                catch {
                    handleCameraFailure(error.localizedDescription)
                }
            }
            cameraRecorder.stopPreview()
            if recording.source == "live" { bleSessionManager.disconnect() }
            else { await replayController.stop() }
            await finishCaptureProcessing()
            await finalizeActiveRecording(recording, requestedStatus: status)
        }
    }

    func stopReplay() {
        Task {
            await replayController.stop()
        }
    }

    func startReplaySession() {
        setCaptureClockAnchor()
        currentSessionWallClockStartMillis = Self.currentWallClockMillis()
        clearChartHistory()
        resetCounters()
        selectedTab = .overview
        isReplayRunning = true
        isConnected = false
        isConnectionActive = false
        isScanning = false
        statusText = "Replay"
        foundDevices.removeAll(keepingCapacity: true)
        errorMessage = nil
    }

    func finishReplaySession() {
        packetUpdateBatcher.flushNow()
        isReplayRunning = false
        if let recording = activeRecording {
            guard !captureStopInProgress else { return }
            captureStopInProgress = true
            Task {
                await finishCaptureProcessing()
                await finalizeActiveRecording(recording, requestedStatus: packetsReceived > 0 ? "completed" : "failed")
            }
        } else {
            statusText = "Idle"
        }
    }

    func stopCaptureSession() {
        selectedTab = .overview
        isReplayRunning = false
        isConnected = false
        isConnectionActive = false
        isScanning = false
        statusText = "Idle"
        foundDevices.removeAll(keepingCapacity: true)
    }

    func enterBackground() {
        if activeRecording != nil {
            stopActiveCapture(status: "interrupted")
            return
        }
        if hasInitializedBleSessionManager {
            bleSessionManager.stopScanning()
            bleSessionManager.disconnect()
        }
        Task {
            await replayController.stop()
            await finishCaptureProcessing()
        }
        isScanning = false
        isConnected = false
        isConnectionActive = false
        statusText = "Background"
    }

    func reportError(_ message: String, error: Error? = nil) {
        showError(message, error: error)
    }

    func text(_ key: AppTextKey) -> String {
        key.localized(language: selectedLanguage)
    }

    func title(for exportKind: ExportKind) -> String {
        exportKind.localizedTitle(language: selectedLanguage)
    }

#if DEBUG
    func applyPacketUpdateForTesting(_ update: PacketProcessingUpdate) {
        applyPacketUpdate(update)
    }

    func beginCaptureDiagnosticsForTesting() async {
        await prepareCaptureForConnection()
    }

    func submitPacketFragmentForTesting(_ fragment: [UInt8]) {
        packetFragmentSubmitter.enqueue(fragment)
    }

    func applyBleStateForTesting(_ state: BleSessionState) {
        applyBleState(state)
    }

    func finishCaptureProcessingForTesting() async {
        await finishCaptureProcessing()
    }

    func hasOpenOutputFilesForTesting() async -> Bool {
        await packetProcessor.hasOpenOutputFiles()
    }

    func currentPacketFileForTesting() async -> URL? {
        await packetProcessor.currentPacketFile()
    }
#endif

    func selectSensorType(_ sensorType: Int) {
        selectedSensorType = sensorType
        selectedChannel = 1
        chartVerticalCenterOverride = nil
        rebuildChartState()
    }

    func selectChannel(_ channel: Int) {
        selectedChannel = channel
        chartVerticalCenterOverride = nil
        rebuildChartState()
    }

    func selectChartWindow(_ preset: ChartWindowPreset) {
        chartWindowPreset = preset
        chartVerticalCenterOverride = nil
        rebuildChartState()
    }

    func setFollowLive(_ enabled: Bool) {
        isFollowingLive = enabled
        manualViewportEndMillis = enabled ? nil : latestChartTimeMillis
        chartVerticalCenterOverride = nil
        rebuildChartState()
    }

    func jumpToLive() {
        setFollowLive(true)
    }

    func panChartLeft() {
        guard let latest = latestChartTimeMillis, let sessionStartMillis else { return }
        let currentEnd = resolveViewportEnd(latest: latest)
        let duration = effectiveViewportDurationMillis(viewportEnd: currentEnd)
        guard duration > 0 else { return }
        let minViewportEnd = min(sessionStartMillis + duration, latest)
        let panStep = chartPanStepMillis(viewportEnd: currentEnd)
        let shiftedEnd = currentEnd > panStep ? currentEnd - panStep : 0
        chartVerticalCenterOverride = nil
        isFollowingLive = false
        manualViewportEndMillis = max(shiftedEnd, minViewportEnd)
        rebuildChartState()
    }

    func panChartRight() {
        guard let latest = latestChartTimeMillis else { return }
        let currentEnd = resolveViewportEnd(latest: latest)
        let shiftedEnd = min(currentEnd + chartPanStepMillis(viewportEnd: currentEnd), latest)
        chartVerticalCenterOverride = nil
        isFollowingLive = shiftedEnd >= latest
        manualViewportEndMillis = isFollowingLive ? nil : shiftedEnd
        rebuildChartState()
    }

    func panChart(byFraction deltaFraction: Double) {
        guard
            let latest = latestChartTimeMillis
        else { return }
        let viewportEnd = resolveViewportEnd(latest: latest)

        let duration = effectiveViewportDurationMillis(viewportEnd: viewportEnd)
        guard duration > 0 else { return }
        let deltaMillis = Int64(Double(duration) * deltaFraction)
        guard deltaMillis != 0, let sessionStartMillis else { return }

        let minViewportEnd = min(sessionStartMillis + duration, latest)
        let shiftedEnd: UInt64
        if deltaMillis < 0 {
            shiftedEnd = viewportEnd > UInt64(abs(deltaMillis)) ? viewportEnd - UInt64(abs(deltaMillis)) : 0
        } else {
            shiftedEnd = viewportEnd + UInt64(deltaMillis)
        }

        chartVerticalCenterOverride = nil
        let clampedEnd = min(max(shiftedEnd, minViewportEnd), latest)
        isFollowingLive = clampedEnd >= latest
        manualViewportEndMillis = isFollowingLive ? nil : clampedEnd
        rebuildChartState()
    }

    func zoomChart(scaleFactor: Float, anchorFractionY: Float = 0.5) {
        guard scaleFactor > 0 else { return }
        let currentRange = chart.yAxisAbsRange
        let currentCenter = chart.yAxisCenter
        let nextZoomFactor = min(max(chartVerticalZoomFactor * scaleFactor, 1), 64)
        guard nextZoomFactor != chartVerticalZoomFactor else { return }

        let nextRange = chartYAxisAbsRange(zoomFactor: nextZoomFactor)
        chartVerticalZoomFactor = nextZoomFactor
        if nextZoomFactor == 1 {
            chartVerticalCenterOverride = nil
        } else {
            let clampedAnchor = min(max(anchorFractionY, 0), 1)
            let anchorCoefficient = 1 - (2 * clampedAnchor)
            chartVerticalCenterOverride = currentCenter + ((currentRange - nextRange) * anchorCoefficient)
        }
        rebuildChartState()
    }

    func zoomChartIn() {
        zoomChart(scaleFactor: 2)
    }

    func zoomChartOut() {
        zoomChart(scaleFactor: 0.5)
    }

    func resetChartY() {
        chartVerticalZoomFactor = 1
        chartVerticalCenterOverride = nil
        rebuildChartState()
    }

    func prepareExport(_ kind: ExportKind) {
        Task {
            do {
                exportPhase = .preparingSnapshots
                discardPreparedExportSnapshot()
                preparedExport = nil
                await packetProcessor.flush()
                packetUpdateBatcher.flushNow()

                let snapshot = try snapshotExportArtifacts(
                    packetFile: await packetProcessor.currentPacketFile(),
                    rawFile: await packetProcessor.currentRawFile(),
                    logFile: await packetProcessor.currentLogFile()
                )
                preparedExportSnapshotDirectory = snapshot.directory

                let packetFile = snapshot.packetFile
                let rawFile = snapshot.rawFile
                let logFile = snapshot.logFile
                var urls: [URL] = []

                switch kind {
                case .all:
                    urls.append(contentsOf: [packetFile, rawFile, logFile].compactMap { $0 })
                    if let csv = try await createCsvExport(packetFile: packetFile) {
                        urls.append(csv)
                    }
                case .packetDump:
                    urls.append(contentsOf: [packetFile].compactMap { $0 })
                case .channelCsv:
                    if let csv = try await createCsvExport(packetFile: packetFile) {
                        urls.append(csv)
                    }
                case .rawFragments:
                    urls.append(contentsOf: [rawFile].compactMap { $0 })
                case .diagnosticLog:
                    urls.append(contentsOf: [logFile].compactMap { $0 })
                }

                guard !urls.isEmpty else {
                    exportPhase = nil
                    discardPreparedExportSnapshot()
                    showError("No \(kind.title.lowercased()) artifact is available yet", error: nil)
                    return
                }

                exportPhase = .openingShareSheet
                preparedExport = PreparedExport(kind: kind, urls: urls)
            } catch {
                exportPhase = nil
                discardPreparedExportSnapshot()
                showError("Failed to prepare \(kind.title.lowercased()) export", error: error)
            }
        }
    }

    func clearPreparedExport() {
        preparedExport = nil
        exportPhase = nil
        discardPreparedExportSnapshot()
    }

    private func reloadProfiles(selecting preferredID: UUID? = nil) {
        guard let store else { return }
        do {
            dogProfiles = try store.profiles()
            let id = preferredID ?? selectedDogProfile?.id
            selectedDogProfile = id.flatMap { id in dogProfiles.first { $0.id == id } } ?? dogProfiles.first
            if let selectedDogProfile {
                UserDefaults.standard.set(selectedDogProfile.id.uuidString, forKey: "selectedDogID")
            }
            reloadRecentRecordings()
        } catch { errorMessage = "Failed to load profiles: \(error.localizedDescription)" }
    }

    private func reloadRecentRecordings() {
        guard let store, let selectedDogProfile else { recentRecordings = []; downloadableRecordingIDs = []; return }
        do {
            recentRecordings = try store.recentRecordings(dogID: selectedDogProfile.id)
            downloadableRecordingIDs = Set(
                try recentRecordings.compactMap { recording in
                    try store.missingArtifacts(recordingID: recording.id).isEmpty ? nil : recording.id
                }
            )
        }
        catch { showError("Failed to load recordings", error: error) }
    }

    private func serverClient() throws -> WoonaServerClient {
        try WoonaServerClient(
            configuration: ServerConfiguration(baseURL: serverBaseURL, wifiOnly: serverWifiOnly),
            token: serverToken
        )
    }

    private func uploadProfile(_ profile: DogProfile) async {
        guard !serverToken.isEmpty, let store else { return }
        do {
            let revision = try await serverClient().upload(profile: profile)
            try store.setServerRevision(dogID: profile.id, revision: revision)
            reloadProfiles(selecting: profile.id)
        } catch { appendDiagnostic("Profile sync pending: \(error.localizedDescription)") }
    }

    private func reloadServerRecordings() async {
        guard !serverToken.isEmpty, let profile = selectedDogProfile else { serverRecordings = []; return }
        do { serverRecordings = try await serverClient().recordings(dogID: profile.id, limit: 10) }
        catch { appendDiagnostic("Server recordings unavailable: \(error.localizedDescription)") }
    }

    private func finalizeActiveRecording(_ recording: WoonaRecording, requestedStatus: String) async {
        guard activeRecording?.id == recording.id, let store else { captureStopInProgress = false; return }
        defer { captureStopInProgress = false }
        var packetFile: URL?
        var rawFile: URL?
        var logFile: URL?
        var timelineFile: URL?
        if hasPreparedActiveCapture {
            packetFile = await packetProcessor.currentPacketFile()
            rawFile = await packetProcessor.currentRawFile()
            logFile = await packetProcessor.currentLogFile()
            timelineFile = await packetProcessor.currentTimelineFile()
        }
        var files: [(type: String, source: URL, name: String, mime: String)] = []
        if let packetFile { files.append(("packet", packetFile, "packets.bin", "application/octet-stream")) }
        if let timelineFile { files.append(("packet_timeline", timelineFile, "packet_timeline.bin", "application/octet-stream")) }
        if let rawFile { files.append(("raw", rawFile, "raw_fragments.binlog", "application/octet-stream")) }
        if let logFile { files.append(("diagnostic", logFile, "diagnostics.log", "text/plain")) }
        let video = store.directory(for: recording).appendingPathComponent("video.mp4")
        if FileManager.default.fileExists(atPath: video.path), !isCameraDegraded {
            files.append(("video", video, "video.mp4", "video/mp4"))
        }
        let sensorTime = packetIngressGate.firstTimestamp
        let lastSensorTime = packetIngressGate.lastTimestamp
        let info = cameraCaptureInfo
        let fallbackWallClockMs = Int64((ISO8601DateFormatter().date(from: recording.startedAtUTC)?.timeIntervalSince1970 ?? 0) * 1_000)
        var sync: [String: Any] = [
            "schemaVersion": 2,
            "monotonicClock": "ios.CMClock.hostTime",
            "sessionZeroAtUtc": captureSessionZeroAtUTC ?? recording.startedAtUTC,
            "sessionZeroWallClockMs": captureSessionZeroWallClockMs ?? fallbackWallClockMs,
            "sessionZeroMonotonicNs": captureSessionZeroMonotonicNs ?? 0,
            "sessionZeroUncertaintyNs": captureSessionZeroUncertaintyNs ?? 0,
            "cameraClockQuality": info?.clockQuality ?? "unavailable",
            "sensorClockQuality": sensorTime == nil ? "unavailable" : "first_packet_arrival",
            "overallSyncQuality": sensorTime != nil && info?.clockQuality == "callback_estimate" ? "callback_estimate" : (sensorTime != nil ? "arrival_aligned" : "unavailable"),
            "calibrationOffsetNs": 0,
        ]
        if let sensorTime { sync["firstSensorPacketMonotonicNs"] = sensorTime }
        if let lastSensorTime { sync["lastSensorPacketMonotonicNs"] = lastSensorTime }
        if let info {
            sync["videoRequestedMonotonicNs"] = info.requestedMonotonicNs
            sync["mediaRecorderStartedMonotonicNs"] = info.writerStartedMonotonicNs
            sync["videoFirstFrameMonotonicNs"] = info.firstFrameMonotonicNs
            sync["videoFirstFrameCameraTimestampNs"] = info.firstFrameCameraTimestampNs
            sync["videoFirstFrameCallbackMonotonicNs"] = info.firstFrameCallbackMonotonicNs
            sync["videoFirstSamplePtsUs"] = info.firstVideoSamplePtsUs
            if let sensorTime, let frame = info.firstFrameMonotonicNs {
                sync["videoOffsetFromSensorNs"] = Int64(bitPattern: frame &- sensorTime)
            }
            sync["cameraTimestampSource"] = info.timestampSource
        }
        if let captureErrorCode { sync["captureErrorCode"] = captureErrorCode }
        if let captureErrorMessage { sync["captureErrorMessage"] = captureErrorMessage }
        do {
            let data = try JSONSerialization.data(withJSONObject: sync, options: [.sortedKeys, .withoutEscapingSlashes])
            let finalStatus = requestedStatus == "completed" && packetsReceived == 0 ? "failed" : requestedStatus
            try store.finalize(recording: recording, status: finalStatus, files: files, syncJSON: data)
            activeRecording = nil
            hasPreparedActiveCapture = false
            isCaptureReady = false
            isRecording = false
            statusText = finalStatus.capitalized
            reloadRecentRecordings()
            if let finalized = try store.recording(id: recording.id) {
                await sync(recording: finalized)
            }
        } catch { showError("Failed to finalize recording", error: error) }
    }

    private func sync(recording: WoonaRecording) async {
        guard !serverToken.isEmpty, let store else { return }
        do {
            guard let profile = try store.profile(dogID: recording.dogID, versionID: recording.profileVersionID) else {
                throw WoonaStoreError.invalidData("Recording profile version is missing")
            }
            try await serverClient().upload(recording: recording, profile: profile, store: store)
            reloadRecentRecordings()
            await reloadServerRecordings()
        } catch { appendDiagnostic("Recording sync pending: \(error.localizedDescription)") }
        scheduleBackgroundSync()
    }

    private func registerBackgroundSync() {
        BGTaskScheduler.shared.register(forTaskWithIdentifier: Self.backgroundSyncIdentifier, using: nil) { [weak self] task in
            guard let task = task as? BGProcessingTask else { return }
            let operation = Task { @MainActor [weak self] in
                await self?.resumePendingSync()
                task.setTaskCompleted(success: !Task.isCancelled)
            }
            task.expirationHandler = { operation.cancel() }
        }
    }

    private func makeBleSessionManager() -> BleSessionManager {
        hasInitializedBleSessionManager = true
        let submitter = packetFragmentSubmitter
        let processor = packetProcessor
        let ingressGate = packetIngressGate
        return BleSessionManager(
            onDeviceFound: { [weak self] device in
                self?.addFoundDevice(device)
            },
            onPacketReceived: { data in
                guard let timestamp = ingressGate.timestampIfActive() else { return }
                submitter.enqueue(data, receivedAtMonotonicNs: timestamp)
            },
            onDiagnosticMessage: { [weak self] message in
                self?.appendBleDiagnostic(message, packetProcessor: processor)
            },
            onCaptureReady: { [weak self] in
                guard let self else { return }
                self.isCaptureReady = true
                self.statusText = "Ready"
                self.appendBleDiagnostic("BLE capture ready", packetProcessor: processor)
                if self.activeRecording?.videoRequested == true {
                    Task {
                        do { try await self.cameraRecorder.prepare() }
                        catch {
                            self.handleCameraFailure(error.localizedDescription)
                        }
                    }
                }
            },
            onStateChanged: { [weak self] state in
                self?.applyBleState(state)
            },
            onError: { [weak self] message, error in
                self?.showError(message, error: error)
            }
        )
    }

    private func makeReplayController() -> PacketReplayController {
        let processor = packetProcessor
        return PacketReplayController(
            submitFragment: { bytes in
                await processor.submit(bytes)
            },
            onReplayStarted: { [weak self] in
                await self?.startReplaySession()
            },
            onReplayCompleted: { [weak self] in
                await self?.finishReplaySession()
            },
            onReplayStopped: { [weak self] in
                await self?.finishReplaySession()
            },
            onError: { [weak self] message, error in
                await self?.showError(message, error: error)
            }
        )
    }

    private func makePacketProcessor() -> PacketCaptureProcessor {
        let directory = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)
            .first?
            .appendingPathComponent("Woona", isDirectory: true)
            ?? FileManager.default.temporaryDirectory.appendingPathComponent("Woona", isDirectory: true)
        let updateBatcher = packetUpdateBatcher

        return PacketCaptureProcessor(
            packetFileStore: PacketFileStore(directory: directory),
            rawFragmentFileStore: RawFragmentFileStore(directory: directory),
            packetTimelineFileStore: PacketTimelineFileStore(directory: directory),
            diagnosticLogFileStore: DiagnosticLogFileStore(directory: directory),
            onUpdate: { update in
                await updateBatcher.submit(update)
            },
            onError: { [weak self] message, error in
                await self?.showError(message, error: error)
            }
        )
    }

    private func makePacketUpdateBatcher() -> PacketProcessingUpdateBatcher {
        PacketProcessingUpdateBatcher { [weak self] update in
            self?.applyPacketUpdate(update)
        }
    }

    private func makePacketFragmentSubmitter() -> PacketFragmentSubmitter {
        PacketFragmentSubmitter(
            packetProcessor: packetProcessor,
            maxPendingFragments: Self.maxPendingPacketFragments,
            onOverflow: { [weak self] in
                guard let self else { return }
                await MainActor.run {
                    self.handleCaptureQueueOverflow()
                }
            }
        )
    }

    private func applyBleState(_ state: BleSessionState) {
        guard !isReplayRunning else { return }
        switch state {
        case .idle:
            isScanning = false
            isConnected = false
            isConnectionActive = false
            statusText = "Idle"
        case .scanning:
            isScanning = true
            isConnected = false
            isConnectionActive = false
            statusText = "Scanning"
        case .connecting:
            isScanning = false
            isConnected = false
            isConnectionActive = true
            statusText = "Connecting"
        case .connected:
            isScanning = false
            isConnected = true
            isConnectionActive = true
            statusText = "Connected"
        case .disconnected:
            isScanning = false
            isConnected = false
            isConnectionActive = false
            statusText = "Disconnected"
            isCaptureReady = false
            if activeRecording != nil { stopActiveCapture(status: "interrupted") }
            else { finishCaptureProcessingIfNeeded() }
        case .failed:
            isScanning = false
            isConnected = false
            isConnectionActive = false
            statusText = "Error"
            isCaptureReady = false
            if activeRecording != nil { stopActiveCapture(status: "failed") }
            else { finishCaptureProcessingIfNeeded() }
        }
    }

    private func applyPacketUpdate(_ update: PacketProcessingUpdate) {
        appendChartSamples(update.chartSamplesByStream)
        packetsReceived = update.packetsReceived
        packetsLost = update.packetsLost
        packetsRejected = update.packetsRejected
        timerRegressionRejects = update.timerRegressionRejects
        fragmentsReceived = update.fragmentsReceived
        rawBytesReceived = update.rawBytesReceived
        lastPacketIssue = update.lastPacketIssue ?? lastPacketIssue
        rejectionBreakdown = update.rejectionBreakdown ?? rejectionBreakdown
        rebuildChartState()
        for event in update.diagnosticEvents {
            appendDiagnostic(event.message)
        }
    }

    private func prepareCaptureForConnection() async {
        await waitForCaptureFinishIfNeeded()
        packetUpdateBatcher.clearPending()
        await packetFragmentSubmitter.reset()
        await packetProcessor.resetSession()
        hasPreparedActiveCapture = true
        packetUpdateBatcher.clearPending()
        resetSessionUi(keepConnectionState: true)
        isCaptureDiagnosticsActive = true
    }

    private func finishCaptureProcessing() async {
        let (taskID, task) = ensureFinishCaptureProcessingTask()
        await task.value
        clearFinishedCaptureProcessingTask(id: taskID)
    }

    private func waitForCaptureFinishIfNeeded() async {
        while let task = finishCaptureProcessingTask {
            let taskID = finishCaptureProcessingTaskID
            await task.value
            clearFinishedCaptureProcessingTask(id: taskID)
        }
    }

    private func ensureFinishCaptureProcessingTask() -> (UInt64, Task<Void, Never>) {
        if let finishCaptureProcessingTask {
            return (finishCaptureProcessingTaskID, finishCaptureProcessingTask)
        }

        finishCaptureProcessingTaskID += 1
        let taskID = finishCaptureProcessingTaskID
        let task = Task { [weak self] in
            guard let self else { return }
            await self.runCaptureFinish()
        }
        finishCaptureProcessingTask = task
        return (taskID, task)
    }

    private func clearFinishedCaptureProcessingTask(id taskID: UInt64) {
        guard finishCaptureProcessingTaskID == taskID else { return }
        finishCaptureProcessingTask = nil
    }

    private func runCaptureFinish() async {
        await packetFragmentSubmitter.finishCaptureProcessing()
        packetUpdateBatcher.flushNow()
        isCaptureDiagnosticsActive = false
    }

    private func finishCaptureProcessingIfNeeded() {
        guard isCaptureDiagnosticsActive else { return }
        isCaptureDiagnosticsActive = false
        let (taskID, task) = ensureFinishCaptureProcessingTask()
        Task { [weak self] in
            await task.value
            self?.clearFinishedCaptureProcessingTask(id: taskID)
        }
    }

    private func handleCaptureQueueOverflow() {
        showError("Capture queue overflow; stopped capture to preserve packet ordering", error: nil)
        if activeRecording != nil { stopActiveCapture(status: "failed") }
        else { bleSessionManager.disconnect(); finishCaptureProcessingIfNeeded() }
    }

    private func showError(_ message: String, error: Error?) {
        if let error {
            errorMessage = "\(message): \(error.localizedDescription)"
        } else {
            errorMessage = message
        }
        appendDiagnostic(errorMessage ?? message)
    }

    private func handleCameraFailure(_ message: String) {
        let isDuplicate = isCameraDegraded && captureErrorMessage == message
        isCameraDegraded = true
        captureErrorCode = "camera_failed"
        captureErrorMessage = message
        if isRecording { statusText = "Recording — camera degraded" }
        if !isDuplicate { appendDiagnostic("Camera degraded: \(message)") }
    }

    private func appendDiagnostic(_ message: String) {
        diagnosticMessages.insert(message, at: 0)
        if diagnosticMessages.count > 40 {
            diagnosticMessages.removeLast(diagnosticMessages.count - 40)
        }
    }

    private func appendBleDiagnostic(_ message: String, packetProcessor: PacketCaptureProcessor) {
        appendDiagnostic(message)
        guard isCaptureDiagnosticsActive else { return }

        Task {
            await packetProcessor.recordDiagnosticEvent(type: .info, message: "BLE: \(message)", publish: false)
        }
    }

    private func snapshotExportArtifacts(packetFile: URL?, rawFile: URL?, logFile: URL?) throws -> ExportArtifactSnapshot {
        let fileManager = FileManager.default
        let directory = fileManager.temporaryDirectory
            .appendingPathComponent("WoonaExport-\(UUID().uuidString)", isDirectory: true)
        try fileManager.createDirectory(at: directory, withIntermediateDirectories: true)

        return ExportArtifactSnapshot(
            directory: directory,
            packetFile: try copyExportArtifact(packetFile, to: directory, fileManager: fileManager),
            rawFile: try copyExportArtifact(rawFile, to: directory, fileManager: fileManager),
            logFile: try copyExportArtifact(logFile, to: directory, fileManager: fileManager)
        )
    }

    private func copyExportArtifact(_ source: URL?, to directory: URL, fileManager: FileManager) throws -> URL? {
        guard let source, fileManager.fileExists(atPath: source.path) else { return nil }
        let target = directory.appendingPathComponent(source.lastPathComponent)
        try fileManager.copyItem(at: source, to: target)
        return target
    }

    private func discardPreparedExportSnapshot() {
        guard let preparedExportSnapshotDirectory else { return }
        try? FileManager.default.removeItem(at: preparedExportSnapshotDirectory)
        self.preparedExportSnapshotDirectory = nil
    }

    private func createCsvExport(packetFile: URL?) async throws -> URL? {
        guard let packetFile else { return nil }
        exportPhase = .generatingCsv
        let csvURL = packetFile
            .deletingPathExtension()
            .appendingPathExtension("csv")
        let sessionStart = exportSessionStartMillis(packetFile: packetFile)
        return try CsvExporter().export(
            packetFile: packetFile,
            sessionStartMillis: sessionStart,
            targetFile: csvURL
        )
    }

    private func exportSessionStartMillis(packetFile: URL) -> Int64 {
        if let currentSessionWallClockStartMillis {
            return currentSessionWallClockStartMillis
        }
        if
            let attributes = try? FileManager.default.attributesOfItem(atPath: packetFile.path),
            let creationDate = attributes[.creationDate] as? Date
        {
            return Int64(creationDate.timeIntervalSince1970 * 1_000)
        }
        return Self.currentWallClockMillis()
    }

    private func appendChartSamples(_ samplesByStream: [ChartStreamKey: [ChartPoint]]) {
        guard !samplesByStream.isEmpty else { return }

        for (streamKey, points) in samplesByStream where !points.isEmpty {
            chartHistory[streamKey, default: []].append(contentsOf: points)

            if let first = points.first?.timeMillis {
                sessionStartMillis = min(sessionStartMillis ?? first, first)
            }
            if let last = points.last?.timeMillis {
                latestChartTimeMillis = max(latestChartTimeMillis ?? last, last)
            }
        }

        trimChartHistory()
    }

    private func clearChartHistory() {
        chartHistory.removeAll(keepingCapacity: true)
        sessionStartMillis = nil
        latestChartTimeMillis = nil
        chartWindowPreset = .thirtySeconds
        isFollowingLive = true
        manualViewportEndMillis = nil
        chartVerticalZoomFactor = 1
        chartVerticalCenterOverride = nil
        chart = ChartUiState()
    }

    private func trimChartHistory() {
        guard let latestChartTimeMillis else { return }
        let cutoff = latestChartTimeMillis > Self.maxRetainedHistoryMillis
            ? latestChartTimeMillis - Self.maxRetainedHistoryMillis
            : 0

        for key in Array(chartHistory.keys) {
            guard let firstPoint = chartHistory[key]?.first, firstPoint.timeMillis < cutoff else { continue }
            guard let firstRetainedIndex = chartHistory[key]?.firstIndex(where: { $0.timeMillis >= cutoff }) else {
                chartHistory.removeValue(forKey: key)
                continue
            }
            if firstRetainedIndex > 0 {
                chartHistory[key]?.removeSubrange(0..<firstRetainedIndex)
            }
        }

        sessionStartMillis = chartHistory.values.compactMap(\.first?.timeMillis).min()
        self.latestChartTimeMillis = chartHistory.values.compactMap(\.last?.timeMillis).max()
        if !isFollowingLive {
            manualViewportEndMillis = manualViewportEndMillis.map { max($0, sessionStartMillis ?? $0) }
        }
    }

    private func rebuildChartState() {
        let sessionStart = sessionStartMillis
        let latest = latestChartTimeMillis
        let viewportEnd = latest.map { resolveViewportEnd(latest: $0) }
        let viewportDuration = viewportEnd.map(effectiveViewportDurationMillis) ?? chartWindowPreset.durationMillis
        let viewportStart: UInt64?
        if let viewportEnd {
            viewportStart = max(sessionStart ?? viewportEnd, viewportEnd > viewportDuration ? viewportEnd - viewportDuration : 0)
        } else {
            viewportStart = nil
        }

        let streamPoints = chartHistory[ChartStreamKey(sensorType: selectedSensorType, channel: selectedChannel)] ?? []
        let visiblePoints: [ChartPoint]
        if let viewportStart, let viewportEnd {
            visiblePoints = downsampleVisiblePoints(
                Array(slicePoints(streamPoints, startMillis: viewportStart, endMillis: viewportEnd)),
                maxPoints: Self.maxRenderedChartPoints
            )
        } else {
            visiblePoints = []
        }

        chart = ChartUiState(
            points: visiblePoints,
            windowPreset: chartWindowPreset,
            isFollowingLive: isFollowingLive,
            canPanLeft: viewportStart != nil && sessionStart != nil && viewportStart! > sessionStart!,
            canPanRight: !isFollowingLive && viewportEnd != nil && latest != nil && viewportEnd! < latest!,
            canZoomIn: chartVerticalZoomFactor < 64,
            canZoomOut: chartVerticalZoomFactor > 1,
            yAxisAbsRange: chartYAxisAbsRange(),
            yAxisCenter: chartYAxisCenter(points: visiblePoints),
            viewportStartMillis: viewportStart,
            viewportEndMillis: viewportEnd,
            sessionStartMillis: sessionStart,
            latestPointMillis: latest
        )
    }

    private func resolveViewportEnd(latest: UInt64) -> UInt64 {
        if isFollowingLive {
            return latest
        }
        return min(manualViewportEndMillis ?? latest, latest)
    }

    private func chartPanStepMillis(viewportEnd: UInt64) -> UInt64 {
        max(effectiveViewportDurationMillis(viewportEnd: viewportEnd) / 4, 1_000)
    }

    private func effectiveViewportDurationMillis(viewportEnd: UInt64) -> UInt64 {
        let sessionStart = sessionStartMillis ?? viewportEnd
        let availableDuration = viewportEnd > sessionStart ? viewportEnd - sessionStart : 0
        return min(chartWindowPreset.durationMillis, availableDuration)
    }

    private func chartYAxisAbsRange(zoomFactor: Float? = nil) -> Float {
        max(32_768 / (zoomFactor ?? chartVerticalZoomFactor), 256)
    }

    private func chartYAxisCenter(points: [ChartPoint]) -> Float {
        if let chartVerticalCenterOverride {
            return chartVerticalCenterOverride
        }
        guard !points.isEmpty else { return 0 }
        let values = points.map(\.value)
        return ((values.min() ?? 0) + (values.max() ?? 0)) / 2
    }

    private func slicePoints(_ points: [ChartPoint], startMillis: UInt64, endMillis: UInt64) -> ArraySlice<ChartPoint> {
        guard !points.isEmpty else { return [] }
        guard let firstIndex = points.firstIndex(where: { $0.timeMillis >= startMillis }) else { return [] }
        guard let lastIndex = points.lastIndex(where: { $0.timeMillis <= endMillis }), lastIndex >= firstIndex else { return [] }
        let startIndex = max(points.startIndex, firstIndex - 1)
        return points[startIndex...lastIndex]
    }

    private func downsampleVisiblePoints(_ points: [ChartPoint], maxPoints: Int) -> [ChartPoint] {
        guard points.count > maxPoints else { return points }
        let step = max(Int(ceil(Double(points.count) / Double(maxPoints))), 1)
        var result: [ChartPoint] = []
        var pendingSegmentBreak = false

        for (index, point) in points.enumerated() {
            pendingSegmentBreak = pendingSegmentBreak || point.startsNewSegment
            if index == 0 || index == points.count - 1 || index.isMultiple(of: step) {
                result.append(
                    ChartPoint(
                        timeMillis: point.timeMillis,
                        value: point.value,
                        startsNewSegment: result.isEmpty ? false : pendingSegmentBreak
                    )
                )
                pendingSegmentBreak = false
            }
        }
        return result
    }

    private func resetSessionUi(keepConnectionState: Bool = false) {
        currentSessionWallClockStartMillis = Self.currentWallClockMillis()
        clearChartHistory()
        resetCounters()
        errorMessage = nil
        preparedExport = nil
        exportPhase = nil
        discardPreparedExportSnapshot()
        isCaptureDiagnosticsActive = false
        if !keepConnectionState {
            isConnected = false
            isConnectionActive = false
        }
    }

    private func resetCounters() {
        packetsReceived = 0
        packetsLost = 0
        packetsRejected = 0
        timerRegressionRejects = 0
        fragmentsReceived = 0
        rawBytesReceived = 0
        lastPacketIssue = nil
        rejectionBreakdown = nil
        diagnosticMessages.removeAll(keepingCapacity: true)
    }

    private func setCaptureClockAnchor() {
        let before = HostClock.nowNanoseconds()
        let wallClockMs = Self.currentWallClockMillis()
        let after = HostClock.nowNanoseconds()
        captureSessionZeroAtUTC = WoonaStore.iso8601(Date(timeIntervalSince1970: Double(wallClockMs) / 1_000))
        captureSessionZeroWallClockMs = wallClockMs
        captureSessionZeroMonotonicNs = before + (after - before) / 2
        captureSessionZeroUncertaintyNs = (after - before) / 2
    }

    private func resetCaptureClockAnchor() {
        captureSessionZeroAtUTC = nil
        captureSessionZeroWallClockMs = nil
        captureSessionZeroMonotonicNs = nil
        captureSessionZeroUncertaintyNs = nil
    }

    private static let maxRenderedChartPoints = 1_200
    private static let maxRetainedHistoryMillis: UInt64 = 15 * 60_000
    private static let maxPendingPacketFragments = 512
    private static let backgroundSyncIdentifier = "com.woona.apps.sync"

    private static func currentWallClockMillis() -> Int64 {
        Int64(Date().timeIntervalSince1970 * 1_000)
    }
}

private final class PacketFragmentSubmitter: @unchecked Sendable {
    private let packetProcessor: PacketCaptureProcessor
    private let maxPendingFragments: Int
    private let onOverflow: @Sendable () async -> Void
    private let lock = NSLock()

    private var pendingFragments: [QueuedPacketFragment] = []
    private var pendingHeadIndex = 0
    private var isDraining = false
    private var isFinishing = false
    private var generation: UInt64 = 0

    init(
        packetProcessor: PacketCaptureProcessor,
        maxPendingFragments: Int,
        onOverflow: @escaping @Sendable () async -> Void
    ) {
        self.packetProcessor = packetProcessor
        self.maxPendingFragments = max(0, maxPendingFragments)
        self.onOverflow = onOverflow
    }

    func enqueue(_ data: Data, receivedAtMonotonicNs: UInt64? = nil) {
        enqueue([UInt8](data), receivedAtMonotonicNs: receivedAtMonotonicNs)
    }

    func enqueue(_ bytes: [UInt8], receivedAtMonotonicNs: UInt64? = nil) {
        guard !bytes.isEmpty else { return }

        let action = lock.withLock {
            guard !isFinishing else { return EnqueueAction.none }
            guard pendingQueueDepthLocked() < maxPendingFragments else {
                stopAfterOverflowLocked(completeActiveDrain: false)
                return EnqueueAction.overflow
            }
            pendingFragments.append(
                QueuedPacketFragment(
                    generation: generation,
                    bytes: bytes,
                    receivedAtMonotonicNs: receivedAtMonotonicNs
                )
            )
            guard !isDraining else { return EnqueueAction.none }
            isDraining = true
            return EnqueueAction.startDrain
        }

        switch action {
        case .none:
            break
        case .startDrain:
            Task(priority: .userInitiated) {
                await drain()
            }
        case .overflow:
            Task(priority: .userInitiated) {
                await onOverflow()
            }
        }
    }

    func reset() async {
        lock.withLock {
            generation += 1
            pendingFragments.removeAll(keepingCapacity: true)
            pendingHeadIndex = 0
            isFinishing = false
        }
        await waitForDrain()
    }

    func finishCaptureProcessing() async {
        lock.withLock {
            isFinishing = true
        }
        await waitForDrain()
        await packetProcessor.finishCaptureSession()
        lock.withLock {
            generation += 1
            pendingFragments.removeAll(keepingCapacity: true)
            pendingHeadIndex = 0
            isFinishing = false
        }
    }

    private func drain() async {
        while true {
            guard let fragment = nextFragment() else {
                let shouldContinue = lock.withLock {
                    if pendingHeadIndex < pendingFragments.count {
                        return true
                    }
                    isDraining = false
                    compactPendingFragmentsLocked(force: true)
                    return false
                }
                if shouldContinue {
                    continue
                }
                return
            }

            let submitResult = await packetProcessor.submit(
                fragment.bytes,
                receivedAtMonotonicNs: fragment.receivedAtMonotonicNs
            )
            if submitResult == .overflow {
                stopAfterOverflowFromDrain()
                await onOverflow()
                return
            }
        }
    }

    private func nextFragment() -> QueuedPacketFragment? {
        lock.withLock {
            while pendingHeadIndex < pendingFragments.count {
                let fragment = pendingFragments[pendingHeadIndex]
                pendingHeadIndex += 1
                compactPendingFragmentsLocked(force: false)
                if fragment.generation == generation {
                    return fragment
                }
            }
            return nil
        }
    }

    private func waitForDrain() async {
        while lock.withLock({ isDraining }) {
            await Task.yield()
        }
    }

    private func stopAfterOverflowFromDrain() {
        lock.withLock {
            stopAfterOverflowLocked(completeActiveDrain: true)
        }
    }

    private func stopAfterOverflowLocked(completeActiveDrain: Bool) {
        generation += 1
        pendingFragments.removeAll(keepingCapacity: true)
        pendingHeadIndex = 0
        isFinishing = true
        if completeActiveDrain {
            isDraining = false
        }
    }

    private func pendingQueueDepthLocked() -> Int {
        max(pendingFragments.count - pendingHeadIndex, 0)
    }

    private func compactPendingFragmentsLocked(force: Bool) {
        guard pendingHeadIndex > 0 else { return }
        guard force || pendingHeadIndex >= 128 else { return }
        pendingFragments.removeSubrange(0..<pendingHeadIndex)
        pendingHeadIndex = 0
    }

    private struct QueuedPacketFragment {
        let generation: UInt64
        let bytes: [UInt8]
        let receivedAtMonotonicNs: UInt64?
    }

    private enum EnqueueAction {
        case none
        case startDrain
        case overflow
    }
}

private final class PacketIngressGate: @unchecked Sendable {
    private let lock = NSLock()
    private var active = false
    private var first: UInt64?
    private var last: UInt64?

    var firstTimestamp: UInt64? { lock.withLock { first } }
    var lastTimestamp: UInt64? { lock.withLock { last } }

    func start() {
        lock.withLock {
            first = nil
            last = nil
            active = true
        }
    }

    func stop() { lock.withLock { active = false } }

    func timestampIfActive() -> UInt64? {
        lock.withLock {
            guard active else { return nil }
            let timestamp = HostClock.nowNanoseconds()
            if first == nil { first = timestamp }
            last = timestamp
            return timestamp
        }
    }
}

private extension NSLock {
    func withLock<T>(_ body: () throws -> T) rethrows -> T {
        lock()
        defer { unlock() }
        return try body()
    }
}

enum ChartWindowPreset: String, CaseIterable, Equatable, Sendable {
    case thirtySeconds = "30s"
    case sixtySeconds = "60s"
    case fiveMinutes = "5m"
    case fifteenMinutes = "15m"

    var durationMillis: UInt64 {
        switch self {
        case .thirtySeconds:
            30_000
        case .sixtySeconds:
            60_000
        case .fiveMinutes:
            300_000
        case .fifteenMinutes:
            900_000
        }
    }
}

struct ChartUiState: Equatable {
    let points: [ChartPoint]
    let windowPreset: ChartWindowPreset
    let isFollowingLive: Bool
    let canPanLeft: Bool
    let canPanRight: Bool
    let canZoomIn: Bool
    let canZoomOut: Bool
    let yAxisAbsRange: Float
    let yAxisCenter: Float
    let viewportStartMillis: UInt64?
    let viewportEndMillis: UInt64?
    let sessionStartMillis: UInt64?
    let latestPointMillis: UInt64?

    init(
        points: [ChartPoint] = [],
        windowPreset: ChartWindowPreset = .thirtySeconds,
        isFollowingLive: Bool = true,
        canPanLeft: Bool = false,
        canPanRight: Bool = false,
        canZoomIn: Bool = true,
        canZoomOut: Bool = false,
        yAxisAbsRange: Float = 32_768,
        yAxisCenter: Float = 0,
        viewportStartMillis: UInt64? = nil,
        viewportEndMillis: UInt64? = nil,
        sessionStartMillis: UInt64? = nil,
        latestPointMillis: UInt64? = nil
    ) {
        self.points = points
        self.windowPreset = windowPreset
        self.isFollowingLive = isFollowingLive
        self.canPanLeft = canPanLeft
        self.canPanRight = canPanRight
        self.canZoomIn = canZoomIn
        self.canZoomOut = canZoomOut
        self.yAxisAbsRange = yAxisAbsRange
        self.yAxisCenter = yAxisCenter
        self.viewportStartMillis = viewportStartMillis
        self.viewportEndMillis = viewportEndMillis
        self.sessionStartMillis = sessionStartMillis
        self.latestPointMillis = latestPointMillis
    }
}

enum AppThemeMode: String, CaseIterable, Equatable {
    case system = "System"
    case light = "Light"
    case dark = "Dark"
}

enum AppLanguage: String, CaseIterable, Equatable {
    case english = "English"
    case russian = "Russian"
}

enum AppTextKey {
    case overview
    case charts
    case settings
    case session
    case status
    case replay
    case packets
    case lost
    case rejected
    case fragments
    case rawBytes
    case timerRegressions
    case lastIssue
    case rejects
    case none
    case captureControls
    case scan
    case stopScan
    case uploadBinary
    case stopReplay
    case disconnect
    case devices
    case noDevices
    case export
    case diagnostics
    case noDiagnosticEvents
    case sensor
    case channel
    case window
    case follow
    case yReset
    case noChartData
    case waitingForData
    case live
    case history
    case bleTransport
    case profile
    case transportAdvisory
    case appearance
    case theme
    case language

    func localized(language: AppLanguage) -> String {
        switch language {
        case .english:
            english
        case .russian:
            russian
        }
    }

    private var english: String {
        switch self {
        case .overview: "Overview"
        case .charts: "Charts"
        case .settings: "Settings"
        case .session: "Session"
        case .status: "Status"
        case .replay: "Replay"
        case .packets: "Packets"
        case .lost: "Lost"
        case .rejected: "Rejected"
        case .fragments: "Fragments"
        case .rawBytes: "Raw bytes"
        case .timerRegressions: "Timer regressions"
        case .lastIssue: "Last issue"
        case .rejects: "Rejects"
        case .none: "None"
        case .captureControls: "Capture Controls"
        case .scan: "Scan"
        case .stopScan: "Stop Scan"
        case .uploadBinary: "Upload Binary"
        case .stopReplay: "Stop Replay"
        case .disconnect: "Disconnect"
        case .devices: "Devices"
        case .noDevices: "No devices found"
        case .export: "Export"
        case .diagnostics: "Diagnostics"
        case .noDiagnosticEvents: "No diagnostic events"
        case .sensor: "Sensor"
        case .channel: "Channel"
        case .window: "Window"
        case .follow: "Follow"
        case .yReset: "Y Reset"
        case .noChartData: "No Chart Data"
        case .waitingForData: "Waiting for data"
        case .live: "Live"
        case .history: "History"
        case .bleTransport: "BLE Transport"
        case .profile: "Profile"
        case .transportAdvisory: "iOS manages connection priority, MTU, and PHY automatically. Device-side connection parameters control sustained reliability."
        case .appearance: "Appearance"
        case .theme: "Theme"
        case .language: "Language"
        }
    }

    private var russian: String {
        switch self {
        case .overview: "Обзор"
        case .charts: "Графики"
        case .settings: "Настройки"
        case .session: "Сессия"
        case .status: "Статус"
        case .replay: "Повтор"
        case .packets: "Пакеты"
        case .lost: "Потеряно"
        case .rejected: "Отклонено"
        case .fragments: "Фрагменты"
        case .rawBytes: "Сырые байты"
        case .timerRegressions: "Регрессии таймера"
        case .lastIssue: "Последняя проблема"
        case .rejects: "Отклонения"
        case .none: "Нет"
        case .captureControls: "Управление захватом"
        case .scan: "Сканировать"
        case .stopScan: "Стоп скан"
        case .uploadBinary: "Загрузить бинарник"
        case .stopReplay: "Остановить повтор"
        case .disconnect: "Отключиться"
        case .devices: "Устройства"
        case .noDevices: "Устройства не найдены"
        case .export: "Экспорт"
        case .diagnostics: "Диагностика"
        case .noDiagnosticEvents: "Нет событий диагностики"
        case .sensor: "Сенсор"
        case .channel: "Канал"
        case .window: "Окно"
        case .follow: "Следить"
        case .yReset: "Сброс Y"
        case .noChartData: "Нет данных графика"
        case .waitingForData: "Ожидание данных"
        case .live: "Вживую"
        case .history: "История"
        case .bleTransport: "BLE транспорт"
        case .profile: "Профиль"
        case .transportAdvisory: "iOS управляет приоритетом соединения, MTU и PHY автоматически. Надежность длительной передачи задается параметрами устройства."
        case .appearance: "Внешний вид"
        case .theme: "Тема"
        case .language: "Язык"
        }
    }
}

enum ExportPhase: String, Equatable {
    case preparingSnapshots = "Preparing files"
    case generatingCsv = "Generating CSV"
    case openingShareSheet = "Opening share sheet"
}

enum ExportKind: String, CaseIterable, Identifiable, Equatable {
    case all
    case packetDump
    case channelCsv
    case rawFragments
    case diagnosticLog

    var id: String { rawValue }

    var title: String {
        localizedTitle(language: .english)
    }

    func localizedTitle(language: AppLanguage) -> String {
        switch language {
        case .english:
            englishTitle
        case .russian:
            russianTitle
        }
    }

    private var englishTitle: String {
        switch self {
        case .all:
            "All artifacts"
        case .packetDump:
            "Packet dump"
        case .channelCsv:
            "Channel CSV"
        case .rawFragments:
            "Raw fragments"
        case .diagnosticLog:
            "Diagnostic log"
        }
    }

    private var russianTitle: String {
        switch self {
        case .all:
            "Все артефакты"
        case .packetDump:
            "Дамп пакетов"
        case .channelCsv:
            "CSV канала"
        case .rawFragments:
            "Сырые фрагменты"
        case .diagnosticLog:
            "Диагностический лог"
        }
    }
}

struct PreparedExport: Identifiable, Equatable {
    let id = UUID()
    let kind: ExportKind
    let urls: [URL]
}

private struct ExportArtifactSnapshot {
    let directory: URL
    let packetFile: URL?
    let rawFile: URL?
    let logFile: URL?
}
