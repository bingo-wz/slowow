package app.manman.teacher.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun TaskCelebration(sequence: Int, count: Int, modifier: Modifier = Modifier) {
    val progress = remember { Animatable(0f) }
    val opacity = remember { Animatable(0f) }
    var visible by remember { mutableStateOf(false) }
    val burst = MaterialTheme.motionScheme.defaultSpatialSpec<Float>()
    val fade = MaterialTheme.motionScheme.fastEffectsSpec<Float>()
    LaunchedEffect(sequence) {
        if (sequence == 0) return@LaunchedEffect
        visible = true
        progress.snapTo(0f)
        opacity.snapTo(1f)
        progress.animateTo(1f, burst)
        delay(450)
        opacity.animateTo(0f, fade)
        visible = false
    }
    if (visible) {
        val description = if (count == 1) "已完成，礼花庆祝" else "已完成 $count 项，礼花庆祝"
        Canvas(modifier.fillMaxWidth().fillMaxHeight(.6f).semantics {
            contentDescription = description
            liveRegion = LiveRegionMode.Polite
        }) {
            val p = progress.value.coerceIn(0f, 1f)
            val a = opacity.value.coerceIn(0f, 1f)
            val palette = listOf(Color(0xFF3D9658), Color(0xFFE95978), Color(0xFFF1AB30),
                Color(0xFF3B9EC8), Color(0xFF9470D0))
            val bursts = listOf(Triple(.18f, .36f, .12f), Triple(.48f, .20f, .17f), Triple(.79f, .33f, .14f),
                Triple(.32f, .61f, .12f), Triple(.72f, .62f, .12f))
            bursts.forEachIndexed { burstIndex, (x, y, radius) ->
                val center = Offset(size.width * x, size.height * y)
                val scale = .25f + .75f * p
                repeat(12) { index ->
                    val angle = (2.0 * PI * index / 12.0) + burstIndex * .12
                    val direction = Offset(cos(angle).toFloat(), sin(angle).toFloat())
                    val inner = size.width * radius * scale * .5f
                    val outer = size.width * radius * scale
                    val color = palette[(index + burstIndex) % palette.size].copy(alpha = a)
                    drawLine(color, center + direction * inner, center + direction * outer,
                        strokeWidth = 2.5.dp.toPx(), cap = StrokeCap.Round)
                    if (index % 2 == 0) drawCircle(color, 2.dp.toPx(), center + direction * (outer + 3.dp.toPx()))
                }
            }
            repeat(35) { index ->
                val sideways = (index % 11 - 5) / 5f
                val height = .24f + (index % 7) * .055f
                val x = size.width * (.5f + sideways * (.08f + .39f * p))
                val y = size.height * (.92f - height * p + .09f * p * p)
                val color = palette[index % palette.size].copy(alpha = a)
                if (index % 3 == 0) drawCircle(color, 2.5.dp.toPx(), Offset(x, y))
                else drawRect(color, Offset(x, y), Size(3.dp.toPx(), 6.dp.toPx()))
            }
            val cone = Path().apply {
                moveTo(size.width * .46f, size.height * .88f)
                lineTo(size.width * .54f, size.height * .88f)
                lineTo(size.width * .50f, size.height * .98f)
                close()
            }
            drawPath(cone, palette[2].copy(alpha = a))
            drawLine(palette[1].copy(alpha = a), Offset(size.width * .46f, size.height * .88f),
                Offset(size.width * .54f, size.height * .88f), strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
        }
    }
}
