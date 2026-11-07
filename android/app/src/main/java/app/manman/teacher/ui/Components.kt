package app.manman.teacher.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

@Composable
fun PageHeader(title: String, subtitle: String? = null, back: (() -> Unit)? = null, action: (@Composable () -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (back != null) {
            Box(Modifier.size(48.dp).clickable(onClick = back).semantics { contentDescription = "返回" }, contentAlignment = Alignment.Center) {
                LineIcon("back")
            }
            Spacer(Modifier.width(8.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.headlineSmall)
            if (subtitle != null) {
                Spacer(Modifier.height(4.dp))
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        action?.invoke()
    }
}

@Composable
fun Block(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    color: Color = MaterialTheme.colorScheme.surfaceContainer,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = MaterialTheme.shapes.medium
    val base = modifier.fillMaxWidth().background(color, shape)
    Column(
        modifier = (if (onClick != null) base.clickable(onClick = onClick) else base).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        content = content,
    )
}

@Composable
fun SectionHeading(title: String, action: String? = null, onAction: (() -> Unit)? = null) {
    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, itemVerticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        if (action != null && onAction != null) {
            TextButton(onClick = onAction) { Text(action, style = MaterialTheme.typography.labelMedium) }
        }
    }
}

@Composable
fun MainButton(text: String, onClick: () -> Unit, enabled: Boolean = true, modifier: Modifier = Modifier) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = 48.dp),
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.buttonColors(),
    ) { Text(text, style = MaterialTheme.typography.labelLarge) }
}

@Composable
fun EmptyHint(title: String, detail: String, action: String, onAction: () -> Unit) {
    Block {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton(onClick = onAction) { Text(action) }
    }
}

@Composable
fun SmallBadge(text: String) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = RoundedCornerShape(8.dp)) {
        Text(text, Modifier.padding(horizontal = 8.dp, vertical = 4.dp), style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
fun LineIcon(name: String, modifier: Modifier = Modifier) {
    val color = MaterialTheme.colorScheme.onSurface
    Canvas(modifier.size(24.dp)) {
        val unit = size.width / 24f
        val stroke = 1.8f * unit
        fun line(x1: Float, y1: Float, x2: Float, y2: Float) =
            drawLine(color, Offset(x1 * unit, y1 * unit), Offset(x2 * unit, y2 * unit), stroke, StrokeCap.Round)
        when (name) {
            "home" -> {
                val roof = Path().apply { moveTo(3f * unit, 11f * unit); lineTo(12f * unit, 4f * unit); lineTo(21f * unit, 11f * unit) }
                drawPath(roof, color, style = Stroke(stroke, cap = StrokeCap.Round))
                line(6f, 10f, 6f, 20f); line(6f, 20f, 18f, 20f); line(18f, 20f, 18f, 10f)
                line(10f, 20f, 10f, 15f); line(14f, 15f, 14f, 20f)
            }
            "records" -> {
                drawRoundRect(color, Offset(5f * unit, 3f * unit), Size(14f * unit, 18f * unit), androidx.compose.ui.geometry.CornerRadius(2f * unit), style = Stroke(stroke))
                line(8f, 8f, 16f, 8f); line(8f, 12f, 16f, 12f); line(8f, 16f, 13f, 16f)
            }
            "class" -> {
                drawCircle(color, 3f * unit, Offset(12f * unit, 8f * unit), style = Stroke(stroke))
                drawArc(color, 190f, 160f, false, Offset(5f * unit, 11f * unit), Size(14f * unit, 11f * unit), style = Stroke(stroke, cap = StrokeCap.Round))
                drawCircle(color, 2f * unit, Offset(4.5f * unit, 9f * unit), style = Stroke(stroke))
                drawCircle(color, 2f * unit, Offset(19.5f * unit, 9f * unit), style = Stroke(stroke))
            }
            "back" -> { line(19f, 12f, 5f, 12f); line(5f, 12f, 11f, 6f); line(5f, 12f, 11f, 18f) }
            "plus" -> { line(12f, 5f, 12f, 19f); line(5f, 12f, 19f, 12f) }
            "check" -> { line(5f, 12f, 10f, 17f); line(10f, 17f, 19f, 7f) }
            else -> { line(6f, 12f, 18f, 12f); line(13f, 7f, 18f, 12f); line(18f, 12f, 13f, 17f) }
        }
    }
}
