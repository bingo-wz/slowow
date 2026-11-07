package app.manman.teacher.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.manman.teacher.MainUiState
import app.manman.teacher.MainViewModel
import app.manman.teacher.recognition.MenuSuggestion
import app.manman.teacher.recognition.NoticeSuggestion
import org.json.JSONObject
import java.time.LocalDate

@Composable
fun MenuReviewScreen(result: MenuSuggestion, state: MainUiState, viewModel: MainViewModel, back: () -> Unit) {
    var contents by rememberSaveable(result.start.toString()) {
        mutableStateOf(JSONObject().apply { result.days.forEach { put(it.day.toString(), it.dishes.joinToString("\n")) } }.toString())
    }
    var confirmExit by remember { mutableStateOf(false) }
    var confirmReplace by remember { mutableStateOf(false) }
    val values = JSONObject(contents)
    val entries = result.days.map { it.day }.distinct().associateWith { values.optString(it.toString()).trim() }.filterValues(String::isNotBlank)
    fun leave() { confirmExit = true }
    fun save() { viewModel.saveRecognizedMenus(result.start, entries, back) }
    BackHandler { leave() }
    Column(Modifier.fillMaxSize()) {
        PageHeader("核对菜单", "逐日检查菜名，清空某天可跳过", back = ::leave)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (result.warnings.isNotEmpty()) Block {
                Text("需要留意", style = MaterialTheme.typography.titleMedium)
                result.warnings.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
            if (result.days.isEmpty()) Text("没有识别出可以确认的菜单，可返回后手动填写。", color = MaterialTheme.colorScheme.onSurfaceVariant)
            result.days.sortedBy { it.day }.forEach { item ->
                SectionHeading(item.day.toString())
                state.menus.firstOrNull { it.day == item.day }?.let { existing ->
                    Text("已有菜单：${existing.dishes.replace('\n', '、')}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                QuietTextField(values.optString(item.day.toString()), { changed ->
                    contents = JSONObject(contents).put(item.day.toString(), changed.take(3000)).toString()
                }, "识别出的菜名，每行一道", singleLine = false, modifier = Modifier.height(144.dp))
            }
            Text("识别可能有遗漏或错误，请对照原图确认。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(16.dp))
        }
        MainButton("确认保存菜单", {
            if (entries.any { (day, text) -> state.menus.any { it.day == day && it.dishes != text } }) confirmReplace = true else save()
        }, enabled = entries.isNotEmpty() && !state.busy, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp))
        Spacer(Modifier.height(16.dp))
    }
    if (confirmReplace) AlertDialog(
        onDismissRequest = { confirmReplace = false }, title = { Text("替换已有菜单？") },
        text = { Text("已填写过的对应日期会改为本次核对后的内容。") },
        confirmButton = { TextButton(onClick = { confirmReplace = false; save() }) { Text("确认替换") } },
        dismissButton = { TextButton(onClick = { confirmReplace = false }) { Text("继续核对") } },
    )
    if (confirmExit) AlertDialog(
        onDismissRequest = { confirmExit = false }, title = { Text("放弃本次识别结果？") },
        text = { Text("未保存的校对内容不会保留。") },
        confirmButton = { TextButton(onClick = { confirmExit = false; viewModel.clearMenuSuggestion(); back() }) { Text("放弃") } },
        dismissButton = { TextButton(onClick = { confirmExit = false }) { Text("继续核对") } },
    )
}

@Composable
fun NoticeReviewScreen(result: NoticeSuggestion, back: () -> Unit, onSelect: (Int) -> Unit) {
    var confirmExit by remember { mutableStateOf(false) }
    fun leave() { if (result.items.any { !it.consumed }) confirmExit = true else back() }
    BackHandler { leave() }
    Column(Modifier.fillMaxSize()) {
        PageHeader("核对待办", "逐条检查，编辑后才会保存", back = ::leave)
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (result.warnings.isNotEmpty()) Block {
                Text("需要留意", style = MaterialTheme.typography.titleMedium)
                result.warnings.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
            if (result.items.isEmpty()) Text("没有识别出待办，可返回通知手动添加。", color = MaterialTheme.colorScheme.onSurfaceVariant)
            result.items.forEachIndexed { index, item ->
                Block {
                    Text(item.title, style = MaterialTheme.typography.titleMedium)
                    Text("日期：${item.date ?: "未识别"} · 时刻：${item.time ?: "未识别"}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (item.evidence.isNotBlank()) Text("依据：${item.evidence}", style = MaterialTheme.typography.bodySmall)
                    if (item.uncertain) Text("时间或内容需要特别核对", style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { onSelect(index) }, enabled = !item.consumed) { Text(if (item.consumed) "已添加" else "编辑并添加") }
                }
            }
            Text("识别结果可能有误，原通知始终可以查看。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(16.dp))
        }
    }
    if (confirmExit) AlertDialog(
        onDismissRequest = { confirmExit = false }, title = { Text("放弃剩余候选待办？") },
        text = { Text("未添加的候选不会保存，已经添加的待办不受影响。") },
        confirmButton = { TextButton(onClick = { confirmExit = false; back() }) { Text("放弃剩余候选") } },
        dismissButton = { TextButton(onClick = { confirmExit = false }) { Text("继续核对") } },
    )
}
