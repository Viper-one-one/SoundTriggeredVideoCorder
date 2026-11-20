package com.soundtrigger.videorecorder.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.soundtrigger.videorecorder.data.AppSettings
import kotlin.math.roundToInt

private const val MIN_DB = AppSettings.MIN_DB
private const val MAX_DB = AppSettings.MAX_DB

private fun dbToFraction(db: Float) = ((db - MIN_DB) / (MAX_DB - MIN_DB)).coerceIn(0f, 1f)
private fun fractionToDb(f: Float) = MIN_DB + f.coerceIn(0f, 1f) * (MAX_DB - MIN_DB)

val MeterGreen = Color(0xFF43A047)
val MeterYellow = Color(0xFFFDD835)
val MeterRed = Color(0xFFE53935)
val ThresholdColor = Color(0xFF4FC3F7)

fun formatDb(db: Float): String = if (db <= MIN_DB) "-∞ dB" else "${db.roundToInt()} dB"

/**
 * Horizontal level meter with a threshold marker. Tap or drag on the meter to move the
 * threshold when [onThresholdChange] is supplied.
 */
@Composable
fun LevelMeter(
    levelDb: Float,
    peakHoldDb: Float?,
    thresholdDb: Float,
    modifier: Modifier = Modifier,
    onThresholdChange: ((Float) -> Unit)? = null,
) {
    val onChange by rememberUpdatedState(onThresholdChange)
    val gestureModifier = if (onThresholdChange != null) {
        Modifier
            .pointerInput(Unit) {
                detectTapGestures { pos -> onChange?.invoke(fractionToDb(pos.x / size.width)) }
            }
            .pointerInput(Unit) {
                detectDragGestures { change, _ ->
                    onChange?.invoke(fractionToDb(change.position.x / size.width))
                }
            }
    } else Modifier

    Canvas(
        modifier
            .fillMaxWidth()
            .height(36.dp)
            .then(gestureModifier),
    ) {
        val radius = CornerRadius(8.dp.toPx())
        drawRoundRect(Color(0xFF263238), cornerRadius = radius)

        val levelFrac = dbToFraction(levelDb)
        if (levelFrac > 0f) {
            drawRoundRect(
                brush = Brush.horizontalGradient(
                    0f to MeterGreen, 0.65f to MeterYellow, 1f to MeterRed,
                    startX = 0f, endX = size.width,
                ),
                size = Size(size.width * levelFrac, size.height),
                cornerRadius = radius,
            )
        }

        // tick marks every 10 dB
        var tick = MIN_DB + 10f
        while (tick < MAX_DB) {
            val x = size.width * dbToFraction(tick)
            drawLine(Color.White.copy(alpha = 0.15f), Offset(x, 0f), Offset(x, size.height), 1.dp.toPx())
            tick += 10f
        }

        peakHoldDb?.let {
            val x = size.width * dbToFraction(it)
            drawLine(Color.White, Offset(x, 0f), Offset(x, size.height), 2.dp.toPx())
        }

        val tx = size.width * dbToFraction(thresholdDb)
        drawLine(ThresholdColor, Offset(tx, -4.dp.toPx()), Offset(tx, size.height + 4.dp.toPx()), 4.dp.toPx())
    }
}

/** Scrolling line graph of recent level history with the threshold drawn as a dashed line. */
@Composable
fun LevelHistoryGraph(
    history: List<Float>,
    capacity: Int,
    thresholdDb: Float,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier.fillMaxWidth()) {
        drawRoundRect(Color(0xFF1C252C), cornerRadius = CornerRadius(8.dp.toPx()))

        fun yFor(db: Float) = size.height * (1f - dbToFraction(db))

        // grid every 10 dB
        var g = MIN_DB + 10f
        while (g < MAX_DB) {
            drawLine(Color.White.copy(alpha = 0.08f), Offset(0f, yFor(g)), Offset(size.width, yFor(g)), 1.dp.toPx())
            g += 10f
        }

        val ty = yFor(thresholdDb)
        // Shade region above threshold
        drawRect(MeterRed.copy(alpha = 0.08f), size = Size(size.width, ty))

        if (history.size >= 2) {
            val step = size.width / (capacity - 1).coerceAtLeast(1)
            val offset = (capacity - history.size) * step
            val path = Path()
            history.forEachIndexed { i, db ->
                val x = offset + i * step
                val y = yFor(db)
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(path, MeterGreen, style = Stroke(width = 2.dp.toPx()))

            // Highlight points above threshold
            history.forEachIndexed { i, db ->
                if (db >= thresholdDb) {
                    drawCircle(MeterRed, radius = 2.5.dp.toPx(), center = Offset(offset + i * step, yFor(db)))
                }
            }
        }

        drawLine(
            ThresholdColor, Offset(0f, ty), Offset(size.width, ty), 2.dp.toPx(),
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 8f)),
        )
    }
}

@Composable
fun ThresholdSlider(thresholdDb: Float, onChange: (Float) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Trigger threshold", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            Text(
                formatDb(thresholdDb),
                color = ThresholdColor,
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.titleMedium,
            )
        }
        Slider(
            value = thresholdDb,
            onValueChange = { onChange(it.roundToInt().toFloat()) },
            valueRange = MIN_DB..MAX_DB,
        )
    }
}

@Composable
fun LabeledIntSlider(
    label: String,
    value: Int,
    range: IntRange,
    format: (Int) -> String,
    onChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    step: Int = 1,
    help: String? = null,
) {
    Column(modifier.padding(vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            Text(format(value), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        }
        if (help != null) {
            Text(help, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        val steps = ((range.last - range.first) / step - 1).coerceAtLeast(0)
        Slider(
            value = value.toFloat(),
            onValueChange = { v ->
                val snapped = (((v - range.first) / step).roundToInt() * step + range.first)
                onChange(snapped.coerceIn(range))
            },
            valueRange = range.first.toFloat()..range.last.toFloat(),
            steps = steps,
        )
    }
}
