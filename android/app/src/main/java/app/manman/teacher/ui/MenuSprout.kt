package app.manman.teacher.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

private val sproutPixels = listOf(
    "........................",
    ".....DDDDD..............",
    "...DDLLLLLDD....DDDDD...",
    "..DMLHHLLLMDD.DDLLLLLDD.",
    "..DMLLLLLLLMDDMLHHLLLMMD",
    "..DMLLLLLLLMDDMLLLLLLMMD",
    "...DMLLLLLMMMDMLLLLLMMD.",
    "....DDMMMMMMLLMMMMMDD...",
    "......DDDMMLLMMDDD......",
    ".........DMLLMD.........",
    "..........DMMMD.........",
    "..........DMMMD.........",
    "..........DMMMD.........",
    "..........DMMMD.........",
    "..........DMMMD.........",
    "..........DMMMD.........",
    ".........DDMMD..........",
    ".........DMMMD..........",
    ".........DDDDD..........",
    "........................",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MenuSprout(onClick: () -> Unit) {
    TooltipBox(positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
        tooltip = { PlainTooltip { Text("本周菜单") } }, state = rememberTooltipState()) {
        Box(Modifier.size(56.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer)
            .feedbackClickable(onClick = onClick).semantics { contentDescription = "本周菜单"; role = Role.Button },
            contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(44.dp)) {
                val unit = size.width / 24f
                val top = (size.height - unit * sproutPixels.size) / 2f
                sproutPixels.forEachIndexed { row, pixels ->
                    pixels.forEachIndexed { column, pixel ->
                        val color = when (pixel) {
                            'D' -> Color(0xFF536520)
                            'M' -> Color(0xFF95B43C)
                            'L' -> Color(0xFFCCDE6A)
                            'H' -> Color(0xFFF1F6B8)
                            else -> Color.Transparent
                        }
                        if (color != Color.Transparent) drawRect(color, Offset(column * unit, top + row * unit), Size(unit + 0.1f, unit + 0.1f))
                    }
                }
            }
        }
    }
}
