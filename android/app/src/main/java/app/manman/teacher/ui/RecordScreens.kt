package app.manman.teacher.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.manman.teacher.MainUiState
import app.manman.teacher.MainViewModel
import app.manman.teacher.domain.AppTime
import app.manman.teacher.domain.Leave
import app.manman.teacher.domain.LeavePeriod
import app.manman.teacher.domain.MealAbsenceRules
import java.time.LocalDate
import java.time.format.DateTimeFormatter

@Composable
fun RecordsScreen(state: MainUiState, viewModel: MainViewModel, onLeave: () -> Unit, onEditLeave: (String) -> Unit,
    onMark: () -> Unit, onMarking: (String) -> Unit, onMealAbsences: (LocalDate) -> Unit,
    onCalendar: () -> Unit, onForest: () -> Unit, selectedDay: LocalDate?, onDay: (LocalDate?) -> Unit) {
    var selectedLeave by remember { mutableStateOf<Leave?>(null) }
    var deletingLeave by remember { mutableStateOf<Leave?>(null) }
    val names = remember(state.students) { state.students.associate { it.id to it.name } }
    val today = rememberToday()
    val day = selectedDay ?: today
    val dates = rememberWeekDates(day)
    val absentPeople = remember(day, state.students, state.leaves) { MealAbsenceRules.forDate(day, state.students, state.leaves) }
    val context = LocalContext.current
    val markings = remember(state.markings) { state.markings.groupBy { it.createdAt.atZone(AppTime.zone).toLocalDate() } }
    val leavesByDate = remember(dates, state.leaves) {
        val leaves = state.leaves.sortedByDescending(Leave::recordedAt)
        dates.associateWith { date -> leaves.filter { it.appearsOn(date) } }
    }
    val badges = remember(leavesByDate, markings) {
        dates.mapNotNull { date ->
            val leaveCount = leavesByDate.getValue(date).size
            val markingCount = markings[date]?.size ?: 0
            if (leaveCount + markingCount == 0) null else date to CalendarBadge(
                leaveCount + markingCount, "${leaveCount}条请假，${markingCount}条班级标记")
        }.toMap()
    }
    Column(Modifier.fillMaxSize()) {
        PageHeader("记录", action = { ActionIcon("todo", "待办日历", onCalendar) })
        key(day) {
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    Box(Modifier.padding(horizontal = 24.dp)) {
                        WeekCalendar(dates, today, day, badges) { date -> onDay(date.takeUnless { it == today }) }
                    }
                }
                item {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        val dateLabel = if (day.year == today.year) "M月d日" else "yyyy年M月d日"
                        Text(day.format(DateTimeFormatter.ofPattern(dateLabel)) + if (day == today) " · 今天" else "",
                            style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        FeedbackTextButton(onClick = onForest) { Text("课堂森林") }
                    }
                }
                val leaves = leavesByDate.getValue(day)
                item {
                    Box(Modifier.padding(horizontal = 16.dp)) {
                        SectionHeading("请假", if (day == today) "记录请假" else null, onLeave)
                    }
                }
                if (leaves.isEmpty()) item {
                    Text("当天没有请假记录。", Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall)
                }
                items(leaves, key = { "leave-${it.id}" }) { leave ->
                    Block(Modifier.padding(horizontal = 16.dp), onClick = { selectedLeave = leave }) {
                        Text(names[leave.studentId] ?: "已移出班级的学生", style = MaterialTheme.typography.bodyLarge)
                        val range = if (leave.start == leave.end) "" else {
                            val format = DateTimeFormatter.ofPattern(if (leave.start.year == leave.end.year) "M月d日" else "yyyy年M月d日")
                            "${leave.start.format(format)}至${leave.end.format(format)} · "
                        }
                        Text(range + if (leave.cancelled) "已撤销" else periodLabel(leave),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                item {
                    Box(Modifier.padding(horizontal = 16.dp)) {
                        SectionHeading("班级标记", if (day == today) "新建" else null, onMark)
                    }
                }
                val dailyMarkings = markings[day].orEmpty()
                if (dailyMarkings.isEmpty()) item {
                    Text("当天没有班级标记。", Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall)
                }
                items(dailyMarkings, key = { "marking-${it.id}" }) { marking ->
                    Block(Modifier.padding(horizontal = 16.dp), onClick = { onMarking(marking.id) }) {
                        Text(marking.title, style = MaterialTheme.typography.bodyLarge)
                        Text("${marking.firstLabel} ${marking.students.count { it.first }} 人 · ${marking.secondLabel} ${marking.students.count { !it.first }} 人",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                item {
                    Block(Modifier.padding(horizontal = 16.dp), onClick = { onMealAbsences(day) }) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(48.dp).background(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.shapes.medium),
                                contentAlignment = Alignment.Center) {
                                LineIcon("meal", color = MaterialTheme.colorScheme.primary)
                            }
                            Spacer(Modifier.width(8.dp))
                            Column(Modifier.weight(1f)) {
                                Text("不在校用餐名单", style = MaterialTheme.typography.titleMedium)
                                val dateLabel = if (day == today) "今天" else "${day.monthValue}月${day.dayOfMonth}日"
                                Text("$dateLabel ${absentPeople.size} 人 · 查看名单", style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            val copyLabel = if (day == today) "复制今日不在校用餐名单" else "复制所选日期不在校用餐名单"
                            ActionIcon("copy", copyLabel, enabled = absentPeople.isNotEmpty(), onClick = {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                clipboard.setPrimaryClip(ClipData.newPlainText("不在校用餐名单", absentPeople.joinToString("、") { it.name }))
                                Toast.makeText(context, "已复制名单", Toast.LENGTH_SHORT).show()
                            })
                        }
                    }
                }
                item { Spacer(Modifier.height(16.dp)) }
            }
        }
    }
    selectedLeave?.let { leave ->
        val recorded = leave.recordedAt.atZone(AppTime.zone).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
        AlertDialog(onDismissRequest = feedbackClick { selectedLeave = null }, title = { Text(names[leave.studentId] ?: "请假记录") },
            text = { Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                Text("${leave.start} 至 ${leave.end} · ${periodLabel(leave)}")
                if (leave.reason.isNotBlank()) Text("请假原因：${leave.reason}")
                Text("录入时间：$recorded")
                Text(if (leave.cancelled) "已撤销" else "撤销后，不在校用餐名单会自动更新。")
            } },
            confirmButton = {
                if (!leave.cancelled) Row {
                    FeedbackTextButton(onClick = { selectedLeave = null; onEditLeave(leave.id) }) { Text("修改") }
                    FeedbackTextButton(onClick = { selectedLeave = null; viewModel.saveLeave(leave.copy(cancelled = true)) }) { Text("撤销请假") }
                } else FeedbackTextButton(onClick = { selectedLeave = null; deletingLeave = leave }) { Text("删除记录") }
            },
            dismissButton = { FeedbackTextButton(onClick = { selectedLeave = null }) { Text("关闭") } })
    }
    deletingLeave?.let { leave ->
        AlertDialog(onDismissRequest = feedbackClick { deletingLeave = null }, title = { Text("删除已撤销的请假？") },
            text = { Text("这条记录将从列表中移除并同步删除。") },
            confirmButton = { FeedbackTextButton(onClick = { viewModel.deleteCancelledLeave(leave.id) { deletingLeave = null } },
                enabled = !state.busy) { Text("删除") } },
            dismissButton = { FeedbackTextButton(onClick = { deletingLeave = null }) { Text("取消") } })
    }
}

private fun Leave.appearsOn(day: LocalDate): Boolean =
    if (cancelled) day == start else day in start..end

private fun periodLabel(leave: Leave): String = when (leave.period) {
    LeavePeriod.ALL_DAY -> "全天"
    LeavePeriod.MORNING -> "上午"
    LeavePeriod.AFTERNOON -> "下午"
}
