package com.soundtrigger.videorecorder

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.viewModelScope
import com.soundtrigger.videorecorder.audio.LevelReading
import com.soundtrigger.videorecorder.audio.SoundLevelMonitor
import com.soundtrigger.videorecorder.camera.CameraController
import com.soundtrigger.videorecorder.camera.RecEvent
import com.soundtrigger.videorecorder.camera.RecState
import com.soundtrigger.videorecorder.data.AppSettings
import com.soundtrigger.videorecorder.data.DetectionMode
import com.soundtrigger.videorecorder.data.SettingsRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.min

/** Rolling statistics shown on the Sound Level Test screen. */
data class LevelStats(
    val min: Float = AppSettings.MAX_DB,
    val max: Float = AppSettings.MIN_DB,
    val avg: Float = AppSettings.MIN_DB,
    val samples: Int = 0,
)

/** Result of the ambient-noise calibration routine. */
sealed interface CalibrationState {
    data object Idle : CalibrationState
    data class Running(val progress: Float) : CalibrationState
    data class Done(val ambientMaxDb: Float, val ambientAvgDb: Float) : CalibrationState
}

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val settingsRepo = SettingsRepository(app)
    val settings: StateFlow<AppSettings> = settingsRepo.settings

    private val monitor = SoundLevelMonitor(app)
    val monitorError: StateFlow<String?> = monitor.error

    val camera = CameraController(app)
    val recState: StateFlow<RecState> = camera.state

    private val _level = MutableStateFlow(LevelReading.SILENT)
    val level: StateFlow<LevelReading> = _level.asStateFlow()

    /** Last ~10 s of detection-metric values for the history graph. */
    private val historyBuffer = ArrayDeque<Float>()
    private val _history = MutableStateFlow<List<Float>>(emptyList())
    val history: StateFlow<List<Float>> = _history.asStateFlow()

    private val _armed = MutableStateFlow(false)
    val armed: StateFlow<Boolean> = _armed.asStateFlow()

    /** True while the current sound satisfies threshold + hold time (i.e. it *would* trigger). */
    private val _triggerActive = MutableStateFlow(false)
    val triggerActive: StateFlow<Boolean> = _triggerActive.asStateFlow()

    private val _testTriggerCount = MutableStateFlow(0)
    val testTriggerCount: StateFlow<Int> = _testTriggerCount.asStateFlow()

    private val _stats = MutableStateFlow(LevelStats())
    val stats: StateFlow<LevelStats> = _stats.asStateFlow()
    private var statsSum = 0.0

    private val _calibration = MutableStateFlow<CalibrationState>(CalibrationState.Idle)
    val calibration: StateFlow<CalibrationState> = _calibration.asStateFlow()
    private var calibrationJob: Job? = null
    private val calibrationSamples = mutableListOf<Float>()

    private val _clipsSaved = MutableStateFlow(0)
    val clipsSaved: StateFlow<Int> = _clipsSaved.asStateFlow()

    private val _lastMessage = MutableStateFlow<String?>(null)
    val lastMessage: StateFlow<String?> = _lastMessage.asStateFlow()

    // Trigger engine state
    private var aboveSince = 0L
    private var lastLoudAt = 0L
    private var cooldownUntil = 0L
    private var manualRecording = false

    init {
        viewModelScope.launch { monitor.readings.collect(::onReading) }
        viewModelScope.launch {
            camera.events.collect { e ->
                when (e) {
                    is RecEvent.Saved -> {
                        _clipsSaved.value++
                        _lastMessage.value = "Saved ${e.name}.mp4 (${e.durationMs / 1000}s) to Movies/SoundTriggerCam"
                    }
                    is RecEvent.Failed -> _lastMessage.value = e.message
                }
                cooldownUntil = SystemClock.elapsedRealtime() + settings.value.cooldownSec * 1000L
            }
        }
    }

    // ---- lifecycle hooks called from the UI -------------------------------------------------

    fun onForeground(owner: LifecycleOwner) {
        monitor.start(viewModelScope)
        bindCamera(owner)
    }

    fun onBackground() {
        _armed.value = false
        camera.stopRecording()
        monitor.stop()
        cancelCalibration()
    }

    fun bindCamera(owner: LifecycleOwner) {
        val s = settings.value
        camera.bind(owner, s.useFrontCamera, s.quality)
    }

    // ---- user actions -----------------------------------------------------------------------

    fun setArmed(value: Boolean) {
        _armed.value = value
        if (value) {
            // Require the sound to (re)cross the threshold after arming.
            aboveSince = 0L
            cooldownUntil = SystemClock.elapsedRealtime() + 500
            _lastMessage.value = null
        } else {
            camera.stopRecording()
        }
    }

    /** Manual clips ignore the quiet-timeout and run until stopped (or max clip length). */
    fun manualRecordToggle() {
        when (recState.value) {
            is RecState.Recording -> camera.stopRecording()
            RecState.Idle -> {
                manualRecording = camera.startRecording(settings.value.recordAudio)
            }
            else -> Unit
        }
    }

    fun updateSettings(transform: (AppSettings) -> AppSettings) = settingsRepo.update(transform)

    fun setThreshold(db: Float) = updateSettings {
        it.copy(thresholdDb = db.coerceIn(AppSettings.MIN_DB, AppSettings.MAX_DB))
    }

    fun resetStats() {
        statsSum = 0.0
        _stats.value = LevelStats()
        _testTriggerCount.value = 0
    }

    fun startCalibration(durationMs: Long = 5_000) {
        calibrationJob?.cancel()
        calibrationSamples.clear()
        _calibration.value = CalibrationState.Running(0f)
        calibrationJob = viewModelScope.launch {
            val start = SystemClock.elapsedRealtime()
            while (true) {
                val elapsed = SystemClock.elapsedRealtime() - start
                if (elapsed >= durationMs) break
                _calibration.value = CalibrationState.Running(elapsed / durationMs.toFloat())
                delay(100)
            }
            val samples = calibrationSamples.toList()
            _calibration.value = if (samples.isEmpty()) {
                CalibrationState.Idle
            } else {
                CalibrationState.Done(samples.max(), samples.average().toFloat())
            }
        }
    }

    fun cancelCalibration() {
        calibrationJob?.cancel()
        calibrationJob = null
        _calibration.value = CalibrationState.Idle
    }

    fun dismissCalibration() {
        _calibration.value = CalibrationState.Idle
    }

    // ---- core trigger engine ----------------------------------------------------------------

    private fun metric(r: LevelReading, s: AppSettings): Float =
        if (s.detectionMode == DetectionMode.PEAK) r.peakDb else r.rmsDb

    private fun onReading(r: LevelReading) {
        val s = settings.value
        val value = metric(r, s)
        val now = SystemClock.elapsedRealtime()
        _level.value = r

        // History for graph
        historyBuffer.addLast(value)
        while (historyBuffer.size > HISTORY_SIZE) historyBuffer.removeFirst()
        _history.value = historyBuffer.toList()

        // Stats
        val st = _stats.value
        statsSum += value
        val n = st.samples + 1
        _stats.value = LevelStats(min(st.min, value), max(st.max, value), (statsSum / n).toFloat(), n)

        if (_calibration.value is CalibrationState.Running) calibrationSamples += value

        // Threshold + hold-time evaluation
        val above = value >= s.thresholdDb
        if (above) {
            if (aboveSince == 0L) aboveSince = now
            lastLoudAt = now
        } else {
            aboveSince = 0L
        }
        val triggered = above && now - aboveSince >= s.triggerHoldMs
        if (triggered && !_triggerActive.value) _testTriggerCount.value++
        _triggerActive.value = triggered

        when (val rs = recState.value) {
            RecState.Idle -> {
                if (_armed.value && triggered && now >= cooldownUntil) {
                    manualRecording = false
                    camera.startRecording(s.recordAudio)
                }
            }
            is RecState.Recording -> {
                val elapsed = now - rs.startedAtElapsed
                val quietFor = now - lastLoudAt
                val reachedMax = elapsed >= s.maxClipSec * 1000L
                val quietLongEnough = !manualRecording &&
                    elapsed >= s.minClipSec * 1000L &&
                    quietFor >= s.postTriggerSec * 1000L
                if (reachedMax || quietLongEnough) camera.stopRecording()
            }
            else -> Unit
        }
    }

    override fun onCleared() {
        monitor.stop()
        camera.stopRecording()
    }

    companion object {
        const val HISTORY_SIZE = SoundLevelMonitor.READINGS_PER_SECOND * 10
    }
}
