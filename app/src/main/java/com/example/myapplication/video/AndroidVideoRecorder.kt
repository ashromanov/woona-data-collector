package com.example.myapplication.video

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.display.DisplayManager
import android.media.MediaRecorder
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Range
import android.util.Size
import android.view.Display
import android.view.Surface
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

data class AndroidVideoStartInfo(
    val firstFrameMonotonicNs: Long,
    val firstFrameCameraTimestampNs: Long,
    val cameraTimestampSource: String,
    val synchronizationQuality: String,
    val cameraId: String,
    val lensFacing: String,
    val width: Int,
    val height: Int,
    val frameRate: Int,
    val rotationDegrees: Int,
)

data class AndroidVideoStopInfo(
    val stoppedMonotonicNs: Long,
    val durationNs: Long?,
)

class AndroidVideoRecorder(
    context: Context,
    private val monotonicNanos: () -> Long = SystemClock::elapsedRealtimeNanos,
) : AutoCloseable {
    private val display = context.getSystemService(DisplayManager::class.java)
        ?.getDisplay(Display.DEFAULT_DISPLAY)
    private val appContext = context.applicationContext
    private val cameraManager = appContext.getSystemService(CameraManager::class.java)
    private val cameraThread = HandlerThread("woona-video").apply { start() }
    private val cameraHandler = Handler(cameraThread.looper)
    private val mainHandler = Handler(appContext.mainLooper)
    private val lock = Any()

    @Volatile
    private var active = false
    @Volatile
    private var stopRequested = false
    private var camera: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var recorder: MediaRecorder? = null
    private var outputFile: File? = null
    private var firstFrameMonotonicNs: Long? = null
    private var onStarted: ((AndroidVideoStartInfo) -> Unit)? = null
    private var onError: ((String, Throwable?) -> Unit)? = null

    fun start(
        file: File,
        onStarted: (AndroidVideoStartInfo) -> Unit,
        onError: (String, Throwable?) -> Unit,
    ): Boolean {
        synchronized(lock) {
            if (active) return false
            active = true
            stopRequested = false
            outputFile = file
            firstFrameMonotonicNs = null
            this.onStarted = onStarted
            this.onError = onError
        }
        file.parentFile?.mkdirs()
        if (file.exists()) file.delete()
        cameraHandler.post(::openCamera)
        return true
    }

    fun stop(timeoutMillis: Long = 4_000L): AndroidVideoStopInfo? {
        if (!active) return null
        stopRequested = true
        val result = AtomicReference<AndroidVideoStopInfo?>()
        val latch = CountDownLatch(1)
        cameraHandler.post {
            result.set(stopOnCameraThread())
            latch.countDown()
        }
        latch.await(timeoutMillis, TimeUnit.MILLISECONDS)
        return result.get()
    }

    fun isActive(): Boolean = active

    @SuppressLint("MissingPermission")
    private fun openCamera() {
        try {
            val cameraId = selectCameraId()
            cameraManager.openCamera(
                cameraId,
                object : CameraDevice.StateCallback() {
                    override fun onOpened(device: CameraDevice) {
                        if (stopRequested || !active) {
                            device.close()
                            stopOnCameraThread()
                            return
                        }
                        camera = device
                        configureSession(device, cameraId)
                    }

                    override fun onDisconnected(device: CameraDevice) {
                        device.close()
                        fail("Camera disconnected", null)
                    }

                    override fun onError(device: CameraDevice, error: Int) {
                        device.close()
                        fail("Camera error $error", null)
                    }
                },
                cameraHandler,
            )
        } catch (exception: Exception) {
            fail("Unable to open camera", exception)
        }
    }

    private fun configureSession(device: CameraDevice, cameraId: String) {
        try {
            val characteristics = cameraManager.getCameraCharacteristics(cameraId)
            val size = selectVideoSize(characteristics)
            val frameRate = selectFrameRate(characteristics)
            val rotation = videoRotation(characteristics)
            val mediaRecorder = MediaRecorder(appContext).apply {
                setVideoSource(MediaRecorder.VideoSource.SURFACE)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setOutputFile(requireNotNull(outputFile).absolutePath)
                setVideoEncodingBitRate(6_000_000)
                setVideoFrameRate(frameRate.upper)
                setVideoSize(size.width, size.height)
                setVideoEncoder(MediaRecorder.VideoEncoder.H264)
                setOrientationHint(rotation)
                prepare()
            }
            recorder = mediaRecorder
            val recorderSurface = mediaRecorder.surface
            device.createCaptureSession(
                listOf(recorderSurface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(captureSession: CameraCaptureSession) {
                        if (stopRequested) {
                            stopOnCameraThread()
                            return
                        }
                        session = captureSession
                        try {
                            val request = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                                addTarget(recorderSurface)
                                set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                                set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, frameRate)
                            }.build()
                            captureSession.setRepeatingRequest(
                                request,
                                firstFrameCallback(cameraId, characteristics, size, frameRate, rotation),
                                cameraHandler,
                            )
                            mediaRecorder.start()
                        } catch (exception: Exception) {
                            fail("Unable to start video", exception)
                        }
                    }

                    override fun onConfigureFailed(captureSession: CameraCaptureSession) {
                        fail("Unable to configure camera", null)
                    }
                },
                cameraHandler,
            )
        } catch (exception: Exception) {
            fail("Unable to prepare video", exception)
        }
    }

    private fun firstFrameCallback(
        cameraId: String,
        characteristics: CameraCharacteristics,
        size: Size,
        frameRate: Range<Int>,
        rotation: Int,
    ) = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureStarted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            timestamp: Long,
            frameNumber: Long,
        ) {
            if (firstFrameMonotonicNs != null) return
            val timestampSource = characteristics.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE)
            val isRealtime = timestampSource == CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME
            val monotonicTimestamp = if (isRealtime) timestamp else monotonicNanos()
            firstFrameMonotonicNs = monotonicTimestamp
            val info = AndroidVideoStartInfo(
                firstFrameMonotonicNs = monotonicTimestamp,
                firstFrameCameraTimestampNs = timestamp,
                cameraTimestampSource = if (isRealtime) "realtime" else "unknown",
                synchronizationQuality = if (isRealtime) "hardware_monotonic" else "callback_estimate",
                cameraId = cameraId,
                lensFacing = lensFacing(characteristics),
                width = size.width,
                height = size.height,
                frameRate = frameRate.upper,
                rotationDegrees = rotation,
            )
            mainHandler.post { onStarted?.invoke(info) }
        }
    }

    private fun stopOnCameraThread(): AndroidVideoStopInfo? {
        val stoppedAt = monotonicNanos()
        var validVideo = recorder != null && firstFrameMonotonicNs != null
        try {
            session?.stopRepeating()
            session?.abortCaptures()
        } catch (_: Exception) {
        }
        try {
            recorder?.stop()
        } catch (_: Exception) {
            validVideo = false
        }
        releaseSession()
        validVideo = validVideo && outputFile?.let { it.exists() && it.length() > 0L } == true
        if (!validVideo) outputFile?.delete()
        val startedAt = firstFrameMonotonicNs
        synchronized(lock) {
            active = false
            stopRequested = false
        }
        return if (validVideo) AndroidVideoStopInfo(
            stoppedMonotonicNs = stoppedAt,
            durationNs = startedAt?.let { (stoppedAt - it).coerceAtLeast(0L) },
        ) else null
    }

    private fun fail(message: String, throwable: Throwable?) {
        releaseSession()
        outputFile?.delete()
        synchronized(lock) {
            active = false
            stopRequested = false
        }
        mainHandler.post { onError?.invoke(message, throwable) }
    }

    private fun releaseSession() {
        try {
            session?.close()
        } catch (_: Exception) {
        }
        try {
            recorder?.reset()
            recorder?.release()
        } catch (_: Exception) {
        }
        try {
            camera?.close()
        } catch (_: Exception) {
        }
        session = null
        recorder = null
        camera = null
    }

    private fun selectCameraId(): String {
        return cameraManager.cameraIdList.firstOrNull { id ->
            cameraManager.getCameraCharacteristics(id)
                .get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        } ?: cameraManager.cameraIdList.firstOrNull()
        ?: error("No camera available")
    }

    private fun selectVideoSize(characteristics: CameraCharacteristics): Size {
        val sizes = characteristics
            .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?.getOutputSizes(MediaRecorder::class.java)
            .orEmpty()
        return sizes
            .filter { it.width <= 1920 && it.height <= 1080 }
            .maxByOrNull { it.width.toLong() * it.height }
            ?: sizes.maxByOrNull { it.width.toLong() * it.height }
            ?: error("Camera has no video size")
    }

    private fun selectFrameRate(characteristics: CameraCharacteristics): Range<Int> {
        val ranges = characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES).orEmpty()
        return ranges
            .filter { it.contains(30) }
            .minByOrNull { (it.upper - it.lower) * 1000 + it.upper }
            ?: ranges.maxByOrNull { it.upper }
            ?: Range(30, 30)
    }

    private fun videoRotation(characteristics: CameraCharacteristics): Int {
        val sensor = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
        val displayDegrees = when (display?.rotation ?: Surface.ROTATION_0) {
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
        return if (
            characteristics.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT
        ) {
            (sensor + displayDegrees) % 360
        } else {
            (sensor - displayDegrees + 360) % 360
        }
    }

    private fun lensFacing(characteristics: CameraCharacteristics): String {
        return when (characteristics.get(CameraCharacteristics.LENS_FACING)) {
            CameraCharacteristics.LENS_FACING_FRONT -> "front"
            CameraCharacteristics.LENS_FACING_BACK -> "back"
            CameraCharacteristics.LENS_FACING_EXTERNAL -> "external"
            else -> "unknown"
        }
    }

    override fun close() {
        stop()
        cameraThread.quitSafely()
    }
}
