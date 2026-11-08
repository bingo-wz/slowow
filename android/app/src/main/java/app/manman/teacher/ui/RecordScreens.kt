package app.manman.teacher.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.manman.teacher.MainUiState
import app.manman.teacher.MainViewModel
import app.manman.teacher.domain.Leave
import app.manman.teacher.domain.LunchRules
import java.time.format.DateTimeFormatter

@Composable
fun RecordsScreen(state: MainUiState, viewModel: MainViewModel, onLeave: () -> Unit, onEditLeave: (String) -> Unit,
    onMark: () -> Unit, onMarking: (String) -> Unit, onLunchReview: (String) -> Unit) {
    var selectedLeave by remember { mutableStateOf<Leave?>(null) }
    val names = state.students.associate { it.id to it.name }
    Column(Modifier.fillMaxSize()) {
        PageHeader("记录", "请假与班级事项")
        LazyColumn(Modifier.weight(1f), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item { SectionHeading("请假", "记录请假", onLeave) }
            if (state.leaves.isEmpty()) {
                item { Block { Text("还没有请假记录。", color = MaterialTheme.colorScheme.onSurfaceVariant) } }
            } else {
                items(state.leaves.sortedByDescending(Leave::recordedAt), key = Leave::id) { leave ->
                    Block(onClick = { selectedLeave = leave }) {
                        Text(names[leave.studentId] ?: "已移出班级的学生", style = MaterialTheme.typography.bodyLarge)
                        Text("${leave.start} 至 ${leave.end} · ${if (leave.cancelled) "已撤销" else periodLabel(leave)}",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            item { Spacer(Modifier.height(8.dp)); SectionHeading("午餐核验记录") }
            if (state.reviews.isEmpty()) item { Block { Text("还没有午餐核验记录。", color = MaterialTheme.colorScheme.onSurfaceVariant) } }
            items(state.reviews, key = { "review-${it.id}" }) { review ->
                Block(onClick = { onLunchReview(review.id) }) {
                    Text("${review.day} · 在校用餐 ${review.students.count { it.eats }} 人")
                    Text("查看当时的名单  ›", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                }
            }
            item { Spacer(Modifier.height(8.dp)); SectionHeading("班级标记", "新建", onMark) }
            if (state.markings.isEmpty()) {
                item { Block { Text("还没有班级标记。", color = MaterialTheme.colorScheme.onSurfaceVariant) } }
            } else {
                items(state.markings, key = { it.id }) { marking ->
                    Block(onClick = { onMarking(marking.id) }) {
                        Text(marking.title, style = MaterialTheme.typography.bodyLarge)
                        Text(marking.createdAt.atZone(LunchRules.defaultZone).format(DateTimeFormatter.ofPattern("yyyy年M月d日 HH:mm")),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("${marking.firstLabel} ${marking.students.count { it.first }} 人 · ${marking.secondLabel} ${marking.students.count { !it.first }} 人",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            item { Spacer(Modifier.height(16.dp)) }
        }
    }
    selectedLeave?.let { leave ->
        val recorded = leave.recordedAt.atZone(LunchRules.defaultZone).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
        AlertDialog(
            onDismissRequest = { selectedLeave = null },
            title = { Text(names[leave.studentId] ?: "请假记录") },
            text = { Text("${leave.start} 至 ${leave.end} · ${periodLabel(leave)}\n录入时间：$recorded\n${if (leave.cancelled) "已撤销" else "撤销后，午餐名单会重新计算。"}") },
            confirmButton = {
                if (!leave.cancelled) androidx.compose.foundation.layout.Row {
                    TextButton(onClick = { selectedLeave = null; onEditLeave(leave.id) }) { Text("修改") }
                    TextButton(onClick = { selectedLeave = null; viewModel.saveLeave(leave.copy(cancelled = true)) }) { Text("撤销请假") }
                }
            },
            dismissButton = { TextButton(onClick = { selectedLeave = null }) { Text("关闭") } },
        )
    }
}

private fun periodLabel(leave: Leave): String = when (leave.period) {
    app.manman.teacher.domain.LeavePeriod.ALL_DAY -> "全天"
    app.manman.teacher.domain.LeavePeriod.MORNING -> "上午"
    app.manman.teacher.domain.LeavePeriod.AFTERNOON -> "下午"
}
