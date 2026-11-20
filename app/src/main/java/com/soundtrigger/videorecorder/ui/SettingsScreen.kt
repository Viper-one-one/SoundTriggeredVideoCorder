package com.soundtrigger.videorecorder.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.soundtrigger.videorecorder.MainViewModel
import com.soundtrigger.videorecorder.camera.RecState
import com.soundtrigger.videorecorder.data.VideoQuality

@Composable
fun SettingsScreen(vm: MainViewModel) {
    val s by vm.settings.collectAsStateWithLifecycle()
    val recState by vm.recState.collectAsStateWithLifecycle()
    val cameraLocked = recState is RecState.Recording || recState is RecState.Finalizing

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))

        SectionTitle("Clip length")
        LabeledIntSlider(
            label = "Keep recording after last sound",
            value = s.postTriggerSec, range = 1..60, format = { "${it}s" },
            help = "Recording stops once it has been quiet (below threshold) this long.",
            onChange = { v -> vm.updateSettings { it.copy(postTriggerSec = v) } },
        )
        LabeledIntSlider(
            label = "Minimum clip length",
            value = s.minClipSec, range = 1..60, format = { "${it}s" },
            onChange = { v -> vm.updateSettings { it.copy(minClipSec = v, maxClipSec = maxOf(it.maxClipSec, v)) } },
        )
        LabeledIntSlider(
            label = "Maximum clip length",
            value = s.maxClipSec, range = 10..600, step = 10, format = { "${it / 60}m ${it % 60}s" },
            help = "Long events are split into multiple clips of at most this length.",
            onChange = { v -> vm.updateSettings { it.copy(maxClipSec = v, minClipSec = minOf(it.minClipSec, v)) } },
        )
        LabeledIntSlider(
            label = "Cooldown between clips",
            value = s.cooldownSec, range = 0..60, format = { "${it}s" },
            onChange = { v -> vm.updateSettings { it.copy(cooldownSec = v) } },
        )

        HorizontalDivider(Modifier.padding(vertical = 12.dp))
        SectionTitle("Video")
        SwitchRow(
            "Record audio in video", s.recordAudio,
            onChange = { v -> vm.updateSettings { it.copy(recordAudio = v) } },
        )
        SwitchRow(
            "Use front camera", s.useFrontCamera, enabled = !cameraLocked,
            onChange = { v -> vm.updateSettings { it.copy(useFrontCamera = v) } },
        )
        Spacer(Modifier.height(8.dp))
        Text("Quality", style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            VideoQuality.entries.forEach { q ->
                FilterChip(
                    selected = s.quality == q,
                    enabled = !cameraLocked,
                    onClick = { vm.updateSettings { it.copy(quality = q) } },
                    label = { Text(q.label.substringBefore(' ')) },
                )
            }
        }
        Text(
            "Falls back to the nearest quality the camera supports. Videos are saved to Movies/SoundTriggerCam.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        HorizontalDivider(Modifier.padding(vertical = 12.dp))
        Text(
            "Tip: tune the threshold, detection mode and hold time on the Level Test tab.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}
