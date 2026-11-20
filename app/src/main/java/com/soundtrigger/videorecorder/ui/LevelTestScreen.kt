package com.soundtrigger.videorecorder.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.soundtrigger.videorecorder.CalibrationState
import com.soundtrigger.videorecorder.MainViewModel
import com.soundtrigger.videorecorder.audio.SoundLevelMonitor
import com.soundtrigger.videorecorder.data.AppSettings
import com.soundtrigger.videorecorder.data.DetectionMode
import kotlin.math.roundToInt

/**
 * Sound level test: shows live levels, history, statistics and a "would trigger" lamp so the
 * user can tune the threshold without actually recording anything.
 */
@Composable
fun LevelTestScreen(vm: MainViewModel) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val level by vm.level.collectAsStateWithLifecycle()
    val history by vm.history.collectAsStateWithLifecycle()
    val stats by vm.stats.collectAsStateWithLifecycle()
    val triggerActive by vm.triggerActive.collectAsStateWithLifecycle()
    val triggerCount by vm.testTriggerCount.collectAsStateWithLifecycle()
    val calibration by vm.calibration.collectAsStateWithLifecycle()
    val armed by vm.armed.collectAsStateWithLifecycle()

    val currentDb = if (settings.detectionMode == DetectionMode.PEAK) level.peakDb else level.rmsDb
    // Peak-hold over the last 1.5 s
    val peakHold = history.takeLast(SoundLevelMonitor.READINGS_PER_SECOND * 3 / 2).maxOrNull()

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Sound Level Test", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(
            "Make the sounds you want to capture (and the ones you want to ignore) and adjust the " +
                "threshold until only the right sounds light the trigger lamp. Nothing is recorded here.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (armed) {
            Text(
                "Note: the trigger is armed – sounds above the threshold will start a recording.",
                style = MaterialTheme.typography.bodySmall,
                color = MeterYellow,
            )
        }

        // Big readout + trigger lamp
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    formatDb(currentDb),
                    fontSize = 48.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (currentDb >= settings.thresholdDb) MeterRed else MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    "RMS ${formatDb(level.rmsDb)}  •  Peak ${formatDb(level.peakDb)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TriggerLamp(active = triggerActive, count = triggerCount)
        }

        LevelMeter(
            levelDb = currentDb,
            peakHoldDb = peakHold,
            thresholdDb = settings.thresholdDb,
            onThresholdChange = vm::setThreshold,
        )
        Text(
            "Tap or drag on the meter to place the threshold. White line = 1.5 s peak hold.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        LevelHistoryGraph(
            history = history,
            capacity = MainViewModel.HISTORY_SIZE,
            thresholdDb = settings.thresholdDb,
            modifier = Modifier.height(160.dp),
        )

        ThresholdSlider(settings.thresholdDb, vm::setThreshold)

        // Detection mode
        Text("Measure", style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DetectionMode.entries.forEach { mode ->
                FilterChip(
                    selected = settings.detectionMode == mode,
                    onClick = { vm.updateSettings { it.copy(detectionMode = mode) } },
                    label = { Text(mode.label) },
                )
            }
        }
        Text(
            settings.detectionMode.description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        LabeledIntSlider(
            label = "Trigger hold time",
            value = settings.triggerHoldMs,
            range = 0..2000,
            step = 50,
            format = { "$it ms" },
            help = "How long the sound must stay above the threshold to trigger.",
            onChange = { v -> vm.updateSettings { it.copy(triggerHoldMs = v) } },
        )

        // Stats
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Column(Modifier.padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Session statistics", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    TextButton(onClick = vm::resetStats) { Text("Reset") }
                }
                if (stats.samples == 0) {
                    Text("Collecting…", style = MaterialTheme.typography.bodySmall)
                } else {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        StatCell("Min", formatDb(stats.min))
                        StatCell("Average", formatDb(stats.avg))
                        StatCell("Max", formatDb(stats.max))
                        StatCell("Triggers", triggerCount.toString())
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = { vm.setThreshold((stats.max + 3f).coerceAtMost(AppSettings.MAX_DB)) },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Set threshold just above session max (${formatDb(stats.max + 3f)})") }
                }
            }
        }

        CalibrationCard(vm, calibration)

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun CalibrationCard(vm: MainViewModel, calibration: CalibrationState) {
    var margin by rememberSaveable { mutableIntStateOf(10) }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Auto-calibrate to background noise", style = MaterialTheme.typography.titleSmall)
            Text(
                "Keep the room in its normal 'quiet' state for 5 seconds. The threshold will be set " +
                    "to the loudest background level plus a safety margin.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            LabeledIntSlider(
                label = "Safety margin",
                value = margin,
                range = 3..30,
                format = { "+$it dB" },
                onChange = { margin = it },
            )
            when (calibration) {
                CalibrationState.Idle -> Button(onClick = { vm.startCalibration() }, modifier = Modifier.fillMaxWidth()) {
                    Text("Measure background (5 s)")
                }
                is CalibrationState.Running -> {
                    LinearProgressIndicator(progress = { calibration.progress }, modifier = Modifier.fillMaxWidth())
                    TextButton(onClick = vm::cancelCalibration) { Text("Cancel") }
                }
                is CalibrationState.Done -> {
                    val suggested = (calibration.ambientMaxDb + margin).coerceAtMost(AppSettings.MAX_DB)
                    Text(
                        "Background: avg ${formatDb(calibration.ambientAvgDb)}, max ${formatDb(calibration.ambientMaxDb)}\n" +
                            "Suggested threshold: ${formatDb(suggested)}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = {
                            vm.setThreshold(suggested.roundToInt().toFloat())
                            vm.dismissCalibration()
                        }) { Text("Apply") }
                        OutlinedButton(onClick = { vm.startCalibration() }) { Text("Re-measure") }
                        TextButton(onClick = vm::dismissCalibration) { Text("Dismiss") }
                    }
                }
            }
        }
    }
}

@Composable
private fun TriggerLamp(active: Boolean, count: Int) {
    val color by animateColorAsState(if (active) MeterRed else Color(0xFF37474F), label = "lamp")
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .size(64.dp)
                .background(color, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(if (active) "TRIG" else "", color = Color.White, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "Would trigger\n×$count",
            style = MaterialTheme.typography.labelSmall,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun StatCell(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(72.dp)) {
        Text(value, fontWeight = FontWeight.Bold)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
