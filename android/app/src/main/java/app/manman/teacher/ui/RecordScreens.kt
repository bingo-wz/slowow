package app.manman.teacher.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import app.manman.teacher.MainUiState
import app.manman.teacher.MainViewModel
import app.manman.teacher.domain.AppTime
import app.manman.teacher.domain.Leave
import app.manman.teacher.domain.LeavePeriod
import app.manman.teacher.domain.MealAbsenceRules
import java.time.format.DateTimeFormatter

@Composable
fun RecordsScreen(state: MainUiState, viewModel: MainViewModel, onLeave: () -> Unit, onEditLeave: (String) -> Unit,
    onMark: () -> Unit, onMarking: (String) -> Unit, onMealAbsences: () -> Unit) {
    var selectedLeave by remember { mutableStateOf<Leave?>(null) }
    var deletingLeave by remember { mutableStateOf<Leave?>(null) }
    var toggledDays by rememberSaveable { mutableStateOf(arrayListOf<String>()) }
    val names = state.students.associate { it.id to it.name }
    val today = rememberToday()
    val absentCount = remember(today, state.students, state.leaves) { MealAbsenceRules.forDate(today, state.students, state.leaves).size }
    val groups = state.markings.groupBy { it.createdAt.atZone(AppTime.zone).toLocalDate() }.toSortedMap(compareByDescending { it })
    val newest = groups.keys.firstOrNull()
    Column(Modifier.fillMaxSize()) {
        PageHeader("记录", "请假与班级事项")
        LazyColumn(Modifier.weight(1f), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                Block(onClick = onMealAbsences) {
                    Text("不在校用餐名单", style = MaterialTheme.typography.titleMedium)
                    Text("今天 $absentCount 人  ›", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                }
            }
            item { SectionHeading("请假", "记录请假", onLeave) }
            if (state.leaves.isEmpty()) item { Block { Text("还没有请假记录。", color = MaterialTheme.colorScheme.onSurfaceVariant) } }
            items(state.leaves.sortedByDescending(Leave::recordedAt), key = { "leave-${it.id}" }) { leave ->
                Block(onClick = { selectedLeave = leave }) {
                    Text(names[leave.studentId] ?: "已移出班级的学生", style = MaterialTheme.typography.bodyLarge)
                    Text("${leave.start} 至 ${leave.end} · ${if (leave.cancelled) "已撤销" else periodLabel(leave)}",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            item { Spacer(Modifier.height(8.dp)); SectionHeading("班级标记", "新建", onMark) }
            if (groups.isEmpty()) item { Block { Text("还没有班级标记。", color = MaterialTheme.colorScheme.onSurfaceVariant) } }
            groups.forEach { (day, markings) ->
                val key = day.toString()
                val expanded = (day == newest) xor (key in toggledDays)
                item(key = "marking-day:$key") {
                    Row(Modifier.fillMaxWidth().semantics { stateDescription = if (expanded) "已展开" else "已折叠" }
                        .clickable { toggledDays = ArrayList(if (key in toggledDays) toggledDays - key else toggledDays + key) }
                        .padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(day.format(DateTimeFormatter.ofPattern("yyyy年M月d日")), style = MaterialTheme.typography.titleMedium)
                            Text("${markings.size} 条记录", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        LineIcon(if (expanded) "up" else "down")
                    }
                }
                if (expanded) items(markings, key = { "marking-${it.id}" }) { marking ->
                    Block(onClick = { onMarking(marking.id) }) {
                        Text(marking.title, style = MaterialTheme.typography.bodyLarge)
                        Text("${marking.firstLabel} ${marking.students.count { it.first }} 人 · ${marking.secondLabel} ${marking.students.count { !it.first }} 人",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            item { Spacer(Modifier.height(16.dp)) }
        }
    }
    selectedLeave?.let { leave ->
        val recorded = leave.recordedAt.atZone(AppTime.zone).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
        AlertDialog(onDismissRequest = { selectedLeave = null }, title = { Text(names[leave.studentId] ?: "请假记录") },
            text = { Text("${leave.start} 至 ${leave.end} · ${periodLabel(leave)}\n录入时间：$recorded\n${if (leave.cancelled) "已撤销" else "撤销后，不在校用餐名单会自动更新。"}") },
            confirmButton = {
                if (!leave.cancelled) Row {
                    TextButton(onClick = { selectedLeave = null; onEditLeave(leave.id) }) { Text("修改") }
                    TextButton(onClick = { selectedLeave = null; viewModel.saveLeave(leave.copy(cancelled = true)) }) { Text("撤销请假") }
                } else TextButton(onClick = { selectedLeave = null; deletingLeave = leave }) { Text("删除记录") }
            },
            dismissButton = { TextButton(onClick = { selectedLeave = null }) { Text("关闭") } })
    }
    deletingLeave?.let { leave ->
        AlertDialog(onDismissRequest = { deletingLeave = null }, title = { Text("删除已撤销的请假？") },
            text = { Text("这条记录将从列表中移除并同步删除。") },
            confirmButton = { TextButton(onClick = { viewModel.deleteCancelledLeave(leave.id) { deletingLeave = null } },
                enabled = !state.busy) { Text("删除") } },
            dismissButton = { TextButton(onClick = { deletingLeave = null }) { Text("取消") } })
    }
}

private fun periodLabel(leave: Leave): String = when (leave.period) {
    LeavePeriod.ALL_DAY -> "全天"
    LeavePeriod.MORNING -> "上午"
    LeavePeriod.AFTERNOON -> "下午"
}
