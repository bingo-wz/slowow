package app.manman.teacher.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.manman.teacher.fonts.AppTextSize
import app.manman.teacher.fonts.AppTextSizes

@Composable
fun AppTextSizeProvider(size: AppTextSize, content: @Composable () -> Unit) {
    val systemDensity = LocalDensity.current
    val density = remember(systemDensity, size) { size.scale?.let { Density(systemDensity.density, it) } ?: systemDensity }
    CompositionLocalProvider(LocalDensity provides density, content = content)
}

@Composable
fun TextSizeSetting(sizes: AppTextSizes) {
    val selected by sizes.state.collectAsStateWithLifecycle()
    var choosing by remember { mutableStateOf(false) }
    Block(onClick = { choosing = true }) {
        Text("字体大小", style = MaterialTheme.typography.bodyLarge)
        Text("${selected.title}  ›", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (choosing) AlertDialog(onDismissRequest = feedbackClick { choosing = false }, title = { Text("字体大小") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("只调整慢慢的文字大小，不改变手机系统设置。", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                AppTextSize.entries.forEach { size ->
                    Row(Modifier.fillMaxWidth().feedbackClickable { sizes.select(size) }.padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = selected == size, onClick = null)
                        Text(size.title, Modifier.padding(start = 8.dp), style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        },
        confirmButton = { FeedbackTextButton(onClick = { choosing = false }) { Text("完成") } },
    )
}
