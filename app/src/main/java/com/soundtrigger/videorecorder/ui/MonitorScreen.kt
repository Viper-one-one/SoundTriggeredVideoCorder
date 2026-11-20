package com.soundtrigger.videorecorder.ui

import androidx.camera.view.PreviewView
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Hearing
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.soundtrigger.videorecorder.MainViewModel
import com.soundtrigger.videorecorder.camera.RecState
import com.soundtrigger.videorecorder.data.DetectionMode
import kotlinx.coroutines.delay

@Composable
fun MonitorScreen(vm: MainViewModel) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val level by vm.level.collectAsStateWithLifecycle()
    val armed by vm.armed.collectAsStateWithLifecycle()
    val recState by vm.recState.collectAsStateWithLifecycle()
    val clipsSaved by vm.clipsSaved.collectAsStateWithLifecycle()
    val lastMessage by vm.lastMessage.collectAsStateWithLifecycle()
    val micError by vm.monitorError.collectAsStateWithLifecycle()

    val currentDb = if (settings.detectionMode == DetectionMode.PEAK) level.peakDb else level.rmsDb
    val recording = recState is RecState.Recording

    Column(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(Color.Black),
        ) {
            CameraPreview(vm, Modifier.fillMaxSize())

            Row(
                Modifier
                    .align(Alignment.TopStart)
                    .padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                StatusChip(
                    text = when {
                        recording -> "REC"
                        recState is RecState.Finalizing -> "SAVING…"
                        armed -> "ARMED – LISTENING"
                        else -> "STANDBY"
                    },
                    color = when {
                        recording -> MeterRed
                        armed -> MeterGreen
                        else -> Color.DarkGray
                    },
                    blinking = recording,
                )
                (recState as? RecState.Recording)?.let { RecordingTimer(it.startedAtElapsed) }
            }

            if (clipsSaved > 0) {
                Text(
                    "Clips: $clipsSaved",
                    color = Color.White,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(12.dp)
                        .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(6.dp))
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }

            (recState as? RecState.Error)?.let {
                Text(it.message, color = MeterRed, modifier = Modifier.align(Alignment.Center).padding(24.dp))
            }
        }

        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Mic level (${settings.detectionMode.label})",
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    formatDb(currentDb),
                    fontWeight = FontWeight.Bold,
                    color = if (currentDb >= settings.thresholdDb) MeterRed else MaterialTheme.colorScheme.onSurface,
                )
            }
            Spacer(Modifier.height(6.dp))
            LevelMeter(
                levelDb = currentDb,
                peakHoldDb = null,
                thresholdDb = settings.thresholdDb,
                onThresholdChange = vm::setThreshold,
            )
            Spacer(Modifier.height(8.dp))
            ThresholdSlider(settings.thresholdDb, vm::setThreshold)

            micError?.let { Text(it, color = MeterRed, style = MaterialTheme.typography.bodySmall) }

            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = { vm.setArmed(!armed) },
                    enabled = recState !is RecState.NotReady && recState !is RecState.Error,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (armed) MeterRed else MaterialTheme.colorScheme.primary,
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .height(56.dp),
                ) {
                    Icon(if (armed) Icons.Filled.Stop else Icons.Filled.Hearing, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(if (armed) "Disarm" else "Arm sound trigger", fontWeight = FontWeight.Bold)
                }
                OutlinedButton(
                    onClick = vm::manualRecordToggle,
                    enabled = recState is RecState.Idle || recording,
                    modifier = Modifier.height(56.dp),
                ) {
                    Icon(
                        if (recording) Icons.Filled.Stop else Icons.Filled.FiberManualRecord,
                        contentDescription = if (recording) "Stop recording" else "Record manually",
                        tint = MeterRed,
                    )
                }
            }

            Spacer(Modifier.height(8.dp))
            Text(
                lastMessage ?: "Recording starts when the level crosses the blue threshold for " +
                    "${settings.triggerHoldMs} ms and stops ${settings.postTriggerSec}s after the last loud sound.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun CameraPreview(vm: MainViewModel, modifier: Modifier) {
    val context = LocalContext.current
    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }
    DisposableEffect(previewView) {
        vm.camera.setSurfaceProvider(previewView.surfaceProvider)
        onDispose { vm.camera.setSurfaceProvider(null) }
    }
    AndroidView(factory = { previewView }, modifier = modifier)
}

@Composable
private fun StatusChip(text: String, color: Color, blinking: Boolean) {
    val transition = rememberInfiniteTransition(label = "blink")
    val alpha by transition.animateFloat(
        initialValue = 1f,
        targetValue = if (blinking) 0.2f else 1f,
        animationSpec = infiniteRepeatable(tween(600), RepeatMode.Reverse),
        label = "blinkAlpha",
    )
    Row(
        Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(Color.Black.copy(alpha = 0.55f))
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(10.dp)
                .alpha(alpha)
                .background(color, CircleShape),
        )
        Spacer(Modifier.width(6.dp))
        Text(text, color = Color.White, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun RecordingTimer(startedAtElapsed: Long) {
    var now by remember { mutableLongStateOf(android.os.SystemClock.elapsedRealtime()) }
    LaunchedEffect(startedAtElapsed) {
        while (true) {
            now = android.os.SystemClock.elapsedRealtime()
            delay(250)
        }
    }
    val secs = ((now - startedAtElapsed) / 1000).coerceAtLeast(0)
    Text(
        "%02d:%02d".format(secs / 60, secs % 60),
        color = Color.White,
        fontWeight = FontWeight.Bold,
        modifier = Modifier
            .background(MeterRed.copy(alpha = 0.8f), RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp),
    )
}
