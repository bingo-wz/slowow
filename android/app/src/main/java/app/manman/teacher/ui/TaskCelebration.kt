package app.manman.teacher.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

@Composable
fun TaskCelebration(sequence: Int, count: Int, modifier: Modifier = Modifier) {
    val scale = remember { Animatable(0.8f) }
    val opacity = remember { Animatable(0f) }
    var visible by remember { mutableStateOf(false) }
    val entrance = MaterialTheme.motionScheme.fastSpatialSpec<Float>()
    val exit = MaterialTheme.motionScheme.fastEffectsSpec<Float>()
    LaunchedEffect(sequence) {
        if (sequence == 0) return@LaunchedEffect
        visible = true
        scale.snapTo(0.8f)
        opacity.snapTo(1f)
        scale.animateTo(1f, entrance)
        delay(500)
        opacity.animateTo(0f, exit)
        visible = false
    }
    if (visible) {
        Surface(
            modifier = modifier.graphicsLayer {
                alpha = opacity.value
                scaleX = scale.value
                scaleY = scale.value
            }.semantics { liveRegion = LiveRegionMode.Polite },
            color = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            shape = MaterialTheme.shapes.large,
        ) {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val green = MaterialTheme.colorScheme.primary
                Canvas(Modifier.size(32.dp)) {
                    val u = size.width / 32f
                    drawLine(green, Offset(16f * u, 25f * u), Offset(16f * u, 13f * u), 2.2f * u, StrokeCap.Round)
                    drawOval(green, Offset(6f * u, 7f * u), Size(10f * u, 7f * u))
                    drawOval(green, Offset(16f * u, 6f * u), Size(10f * u, 7f * u))
                    drawLine(green, Offset(10f * u, 26f * u), Offset(22f * u, 26f * u), 2.2f * u, StrokeCap.Round)
                    drawLine(green, Offset(5f * u, 17f * u), Offset(5f * u, 20f * u), 1.5f * u, StrokeCap.Round)
                    drawLine(green, Offset(3.5f * u, 18.5f * u), Offset(6.5f * u, 18.5f * u), 1.5f * u, StrokeCap.Round)
                    drawLine(green, Offset(27f * u, 15f * u), Offset(27f * u, 19f * u), 1.5f * u, StrokeCap.Round)
                    drawLine(green, Offset(25f * u, 17f * u), Offset(29f * u, 17f * u), 1.5f * u, StrokeCap.Round)
                }
                Text(if (count == 1) "完成啦" else "完成啦 · $count 项", style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}
