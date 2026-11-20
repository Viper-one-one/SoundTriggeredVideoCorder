package com.soundtrigger.videorecorder.camera

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.soundtrigger.videorecorder.data.VideoQuality
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

sealed interface RecState {
    data object NotReady : RecState
    data object Idle : RecState
    data class Recording(val startedAtElapsed: Long) : RecState
    data object Finalizing : RecState
    data class Error(val message: String) : RecState
}

sealed interface RecEvent {
    data class Saved(val uri: Uri, val name: String, val durationMs: Long) : RecEvent
    data class Failed(val message: String) : RecEvent
}

/** Owns the CameraX Preview + VideoCapture use cases and the active [Recording]. */
class CameraController(private val context: Context) {

    private val mainExecutor = ContextCompat.getMainExecutor(context)
    private val preview = Preview.Builder().build()
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    private var currentName: String = ""

    private var boundOwner: LifecycleOwner? = null
    private var boundConfig: Pair<Boolean, VideoQuality>? = null

    private val _state = MutableStateFlow<RecState>(RecState.NotReady)
    val state: StateFlow<RecState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<RecEvent>(
        extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val events: SharedFlow<RecEvent> = _events.asSharedFlow()

    fun setSurfaceProvider(provider: Preview.SurfaceProvider?) {
        preview.setSurfaceProvider(provider)
    }

    /** Binds (or re-binds when the configuration changed) camera use cases to [owner]. */
    fun bind(owner: LifecycleOwner, frontCamera: Boolean, quality: VideoQuality) {
        if (recording != null) return // never rebind mid-recording
        val config = frontCamera to quality
        if (owner === boundOwner && config == boundConfig && videoCapture != null) return

        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            try {
                val provider = future.get()
                val recorder = Recorder.Builder()
                    .setQualitySelector(
                        QualitySelector.from(
                            quality.cameraXQuality,
                            FallbackStrategy.lowerQualityOrHigherThan(quality.cameraXQuality),
                        ),
                    )
                    .build()
                val capture = VideoCapture.withOutput(recorder)
                val selector = if (frontCamera && provider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)) {
                    CameraSelector.DEFAULT_FRONT_CAMERA
                } else {
                    CameraSelector.DEFAULT_BACK_CAMERA
                }
                provider.unbindAll()
                provider.bindToLifecycle(owner, selector, preview, capture)
                videoCapture = capture
                boundOwner = owner
                boundConfig = config
                _state.value = RecState.Idle
            } catch (e: Exception) {
                Log.e(TAG, "Camera bind failed", e)
                videoCapture = null
                _state.value = RecState.Error("Camera unavailable: ${e.message}")
            }
        }, mainExecutor)
    }

    /** Starts a new clip. Returns false if the camera isn't ready or a clip is already running. */
    @SuppressLint("MissingPermission") // Caller verifies RECORD_AUDIO when withAudio = true.
    fun startRecording(withAudio: Boolean): Boolean {
        val capture = videoCapture ?: return false
        if (recording != null || _state.value != RecState.Idle) return false

        currentName = "STC_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, currentName)
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/SoundTriggerCam")
            }
        }
        val output = MediaStoreOutputOptions.Builder(
            context.contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
        ).setContentValues(values).build()

        return try {
            var pending = capture.output.prepareRecording(context, output)
            if (withAudio) pending = pending.withAudioEnabled()
            _state.value = RecState.Recording(SystemClock.elapsedRealtime())
            recording = pending.start(mainExecutor) { event -> onRecordEvent(event) }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start recording", e)
            recording = null
            _state.value = RecState.Idle
            _events.tryEmit(RecEvent.Failed("Could not start recording: ${e.message}"))
            false
        }
    }

    fun stopRecording() {
        val rec = recording ?: return
        _state.value = RecState.Finalizing
        rec.stop()
        recording = null
    }

    private fun onRecordEvent(event: VideoRecordEvent) {
        if (event !is VideoRecordEvent.Finalize) return
        recording = null
        val durationMs = event.recordingStats.recordedDurationNanos / 1_000_000
        if (event.hasError() && event.error != VideoRecordEvent.Finalize.ERROR_SOURCE_INACTIVE) {
            Log.w(TAG, "Recording finalized with error ${event.error}", event.cause)
            _events.tryEmit(RecEvent.Failed("Recording failed (code ${event.error})"))
        } else {
            _events.tryEmit(RecEvent.Saved(event.outputResults.outputUri, currentName, durationMs))
        }
        _state.value = if (videoCapture != null) RecState.Idle else RecState.NotReady
    }

    companion object {
        private const val TAG = "CameraController"
    }
}
