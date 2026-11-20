package com.soundtrigger.videorecorder.data

import android.content.Context
import androidx.camera.video.Quality
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Which audio measurement is compared against the threshold. */
enum class DetectionMode(val label: String, val description: String) {
    RMS("Average (RMS)", "Sustained loudness – ignores brief clicks"),
    PEAK("Peak", "Instantaneous peaks – reacts to sharp, short sounds"),
}

enum class VideoQuality(val label: String, val cameraXQuality: Quality) {
    SD("SD 480p", Quality.SD),
    HD("HD 720p", Quality.HD),
    FHD("Full HD 1080p", Quality.FHD),
    UHD("4K UHD", Quality.UHD),
}

data class AppSettings(
    /** Trigger threshold in dBFS (0 = digital full scale, more negative = quieter). */
    val thresholdDb: Float = -30f,
    val detectionMode: DetectionMode = DetectionMode.RMS,
    /** Sound must stay above the threshold this long before recording starts. */
    val triggerHoldMs: Int = 100,
    /** Keep recording this long after the last loud sound. */
    val postTriggerSec: Int = 10,
    val minClipSec: Int = 5,
    val maxClipSec: Int = 120,
    /** Wait this long after a clip ends before a new trigger is allowed. */
    val cooldownSec: Int = 2,
    val recordAudio: Boolean = true,
    val useFrontCamera: Boolean = false,
    val quality: VideoQuality = VideoQuality.HD,
) {
    companion object {
        const val MIN_DB = -90f
        const val MAX_DB = 0f
    }
}

class SettingsRepository(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    fun update(transform: (AppSettings) -> AppSettings) {
        val new = transform(_settings.value)
        _settings.value = new
        save(new)
    }

    private fun load(): AppSettings {
        val d = AppSettings()
        return AppSettings(
            thresholdDb = prefs.getFloat("thresholdDb", d.thresholdDb),
            detectionMode = enumOrDefault(prefs.getString("detectionMode", null), d.detectionMode),
            triggerHoldMs = prefs.getInt("triggerHoldMs", d.triggerHoldMs),
            postTriggerSec = prefs.getInt("postTriggerSec", d.postTriggerSec),
            minClipSec = prefs.getInt("minClipSec", d.minClipSec),
            maxClipSec = prefs.getInt("maxClipSec", d.maxClipSec),
            cooldownSec = prefs.getInt("cooldownSec", d.cooldownSec),
            recordAudio = prefs.getBoolean("recordAudio", d.recordAudio),
            useFrontCamera = prefs.getBoolean("useFrontCamera", d.useFrontCamera),
            quality = enumOrDefault(prefs.getString("quality", null), d.quality),
        )
    }

    private fun save(s: AppSettings) {
        prefs.edit()
            .putFloat("thresholdDb", s.thresholdDb)
            .putString("detectionMode", s.detectionMode.name)
            .putInt("triggerHoldMs", s.triggerHoldMs)
            .putInt("postTriggerSec", s.postTriggerSec)
            .putInt("minClipSec", s.minClipSec)
            .putInt("maxClipSec", s.maxClipSec)
            .putInt("cooldownSec", s.cooldownSec)
            .putBoolean("recordAudio", s.recordAudio)
            .putBoolean("useFrontCamera", s.useFrontCamera)
            .putString("quality", s.quality.name)
            .apply()
    }

    private inline fun <reified T : Enum<T>> enumOrDefault(name: String?, default: T): T =
        name?.let { n -> enumValues<T>().firstOrNull { it.name == n } } ?: default
}
