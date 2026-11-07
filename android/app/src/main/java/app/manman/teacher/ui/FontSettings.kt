package app.manman.teacher.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.manman.teacher.fonts.AppFont
import app.manman.teacher.fonts.AppFonts

@Composable
fun FontSetting(fonts: AppFonts) {
    val state by fonts.state.collectAsStateWithLifecycle()
    var chooseFont by remember { mutableStateOf(false) }
    var showLicense by remember { mutableStateOf(false) }
    val context = LocalContext.current
    Block(onClick = { chooseFont = true }) {
        Text("应用字体", style = MaterialTheme.typography.bodyLarge)
        Text("${state.selected.title}  ›", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (chooseFont) AlertDialog(
        onDismissRequest = { chooseFont = false; fonts.clearError() },
        title = { Text("选择字体") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("字体只影响这部手机，下载后可离线使用。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                AppFont.entries.forEach { font ->
                    Row(Modifier.fillMaxWidth().clickable(enabled = state.downloading == null) { fonts.select(font) }.padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = state.selected == font, onClick = null, enabled = state.downloading == null)
                        Column(Modifier.weight(1f).padding(start = 8.dp)) {
                            Text(font.title, style = MaterialTheme.typography.bodyLarge)
                            Text(if (state.downloading == font) "正在准备…" else font.detail,
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                state.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                TextButton(onClick = { showLicense = true }) { Text("查看字体来源与许可") }
            }
        },
        confirmButton = { TextButton(onClick = { chooseFont = false; fonts.clearError() }) { Text("完成") } },
    )
    if (showLicense) {
        val text = remember {
            listOf("mashanzheng.txt", "zcoolkuaile.txt", "longcang.txt").joinToString("\n\n") { name ->
                context.assets.open("font-licenses/$name").bufferedReader().use { it.readText() }
            }
        }
        AlertDialog(onDismissRequest = { showLicense = false }, title = { Text("字体来源与许可") },
            text = { Text("马善政楷书、站酷快乐体、龙藏体均采用 SIL Open Font License 1.1。\n\n$text",
                Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState()), style = MaterialTheme.typography.bodySmall) },
            confirmButton = { TextButton(onClick = { showLicense = false }) { Text("关闭") } })
    }
}
