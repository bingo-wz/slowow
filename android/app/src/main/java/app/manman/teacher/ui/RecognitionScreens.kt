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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.manman.teacher.recognition.NoticeSuggestion

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
                    FeedbackTextButton(onClick = { onSelect(index) }, enabled = !item.consumed) { Text(if (item.consumed) "已添加" else "编辑并添加") }
                }
            }
            Text("识别结果可能有误，原通知始终可以查看。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(16.dp))
        }
    }
    if (confirmExit) AlertDialog(
        onDismissRequest = feedbackClick { confirmExit = false }, title = { Text("放弃剩余候选待办？") },
        text = { Text("未添加的候选不会保存，已经添加的待办不受影响。") },
        confirmButton = { FeedbackTextButton(onClick = { confirmExit = false; back() }) { Text("放弃剩余候选") } },
        dismissButton = { FeedbackTextButton(onClick = { confirmExit = false }) { Text("继续核对") } },
    )
}
