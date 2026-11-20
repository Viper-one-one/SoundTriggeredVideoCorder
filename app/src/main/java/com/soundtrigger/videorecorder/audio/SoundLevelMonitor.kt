package com.soundtrigger.videorecorder.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import com.soundtrigger.videorecorder.data.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

/** One ~50 ms measurement window. Values are in dBFS, clamped to [AppSettings.MIN_DB, 0]. */
data class LevelReading(val rmsDb: Float, val peakDb: Float) {
    companion object {
        val SILENT = LevelReading(AppSettings.MIN_DB, AppSettings.MIN_DB)
    }
}

/**
 * Continuously reads the microphone with [AudioRecord] and publishes loudness readings.
 * Uses the least-processed audio source available so that automatic gain control doesn't
 * flatten the levels the user is trying to tune a threshold against.
 */
class SoundLevelMonitor(private val context: Context) {

    private val _readings = MutableSharedFlow<LevelReading>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val readings: SharedFlow<LevelReading> = _readings.asSharedFlow()

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private var job: Job? = null

    @SuppressLint("MissingPermission") // Caller verifies RECORD_AUDIO before starting.
    fun start(scope: CoroutineScope) {
        if (job?.isActive == true) return
        job = scope.launch(Dispatchers.IO) {
            val chunk = SAMPLE_RATE / 20 // 50 ms of samples
            val minBuf = AudioRecord.getMinBufferSize(
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
            )
            val bufferBytes = max(minBuf, chunk * 2 * 4)
            val record = try {
                AudioRecord(
                    pickAudioSource(), SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, bufferBytes,
                )
            } catch (e: Exception) {
                _error.value = "Microphone unavailable: ${e.message}"
                return@launch
            }
            if (record.state != AudioRecord.STATE_INITIALIZED) {
                record.release()
                _error.value = "Microphone could not be initialised"
                return@launch
            }

            val buffer = ShortArray(chunk)
            try {
                record.startRecording()
                _error.value = null
                _running.value = true
                while (isActive) {
                    var filled = 0
                    while (filled < chunk && isActive) {
                        val n = record.read(buffer, filled, chunk - filled)
                        if (n <= 0) break
                        filled += n
                    }
                    if (filled == 0) continue
                    _readings.tryEmit(measure(buffer, filled))
                }
            } catch (e: Exception) {
                Log.e(TAG, "Audio monitor failed", e)
                _error.value = "Microphone error: ${e.message}"
            } finally {
                runCatching { record.stop() }
                record.release()
                _running.value = false
                _readings.tryEmit(LevelReading.SILENT)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    private fun pickAudioSource(): Int {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val unprocessed = am.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED)
        return if (unprocessed == "true") MediaRecorder.AudioSource.UNPROCESSED
        else MediaRecorder.AudioSource.VOICE_RECOGNITION
    }

    private fun measure(buf: ShortArray, n: Int): LevelReading {
        var sumSq = 0.0
        var peak = 0
        for (i in 0 until n) {
            val s = buf[i].toInt()
            sumSq += (s * s).toDouble()
            val a = abs(s)
            if (a > peak) peak = a
        }
        val rms = sqrt(sumSq / n) / 32768.0
        return LevelReading(toDb(rms), toDb(peak / 32768.0))
    }

    private fun toDb(linear: Double): Float {
        if (linear <= 0.0) return AppSettings.MIN_DB
        return (20.0 * log10(linear)).toFloat().coerceIn(AppSettings.MIN_DB, AppSettings.MAX_DB)
    }

    companion object {
        private const val TAG = "SoundLevelMonitor"
        const val SAMPLE_RATE = 44_100
        const val READINGS_PER_SECOND = 20
    }
}
