package app.manman.teacher.ui

import android.app.TimePickerDialog
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.manman.teacher.MainUiState
import app.manman.teacher.MainViewModel
import app.manman.teacher.data.Lesson
import app.manman.teacher.data.TimeSlot
import app.manman.teacher.domain.LunchRules
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.UUID

private val weekNames = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")

@Composable
fun ScheduleScreen(state: MainUiState, viewModel: MainViewModel, back: () -> Unit) {
    var weekday by remember { mutableIntStateOf(LocalDate.now(LunchRules.defaultZone).dayOfWeek.value) }
    var editSlot by remember { mutableStateOf<TimeSlot?>(null) }
    var newSlot by remember { mutableStateOf(false) }
    var lessonSlot by remember { mutableStateOf<TimeSlot?>(null) }
    Column(Modifier.fillMaxSize()) {
        PageHeader("课程与作息", "先设置节次，再安排每周课程", back = back,
            action = { ActionIcon("plus", "添加节次", { newSlot = true }) })
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                weekNames.forEachIndexed { index, label ->
                    FilterChip(selected = weekday == index + 1, onClick = { weekday = index + 1 }, label = { Text(label) }, border = null,
                        colors = FilterChipDefaults.filterChipColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer))
                }
            }
            if (state.timeSlots.isEmpty()) {
                EmptyHint("还没有作息时间", "添加节次和上下课时间后，再为每个星期安排课程。", "添加节次") { newSlot = true }
            } else {
                SectionHeading("${weekNames[weekday - 1]}的课程")
                state.timeSlots.filter { it.weekdays.isEmpty() || weekday in it.weekdays }.forEach { slot ->
                    val lesson = state.lessons.firstOrNull { it.weekday == weekday && it.slotId == slot.id }
                    Block {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Column(Modifier.weight(1f)) {
                                Text(slot.title, style = MaterialTheme.typography.titleMedium)
                                Text("${slot.start.format(DateTimeFormatter.ofPattern("HH:mm"))} - ${slot.end.format(DateTimeFormatter.ofPattern("HH:mm"))}",
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            ActionIcon("edit", "调整${slot.title}时间", { editSlot = slot })
                        }
                        Text(lesson?.subject ?: "尚未安排课程", style = MaterialTheme.typography.bodyLarge)
                        ActionIcon(if (lesson == null) "plus" else "edit", if (lesson == null) "添加${slot.title}课程" else "修改${slot.title}课程", { lessonSlot = slot })
                    }
                }
                Text("调整时间会影响使用同一节次的课程。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(16.dp))
            }
        }
    }
    if (newSlot || editSlot != null) {
        TimeSlotDialog(editSlot, onDismiss = { newSlot = false; editSlot = null }) { slot ->
            viewModel.saveTimeSlot(slot) { newSlot = false; editSlot = null }
        }
    }
    lessonSlot?.let { slot ->
        val lesson = state.lessons.firstOrNull { it.weekday == weekday && it.slotId == slot.id }
        var subject by remember(slot.id, weekday) { mutableStateOf(lesson?.subject.orEmpty()) }
        AlertDialog(
            onDismissRequest = { lessonSlot = null },
            title = { Text("${weekNames[weekday - 1]} · ${slot.title}") },
            text = { QuietTextField(subject, { subject = it.take(50) }, "课程名称") },
            confirmButton = { TextButton(onClick = {
                val id = lesson?.id ?: UUID.nameUUIDFromBytes("lesson:$weekday:${slot.id}".toByteArray(Charsets.UTF_8)).toString()
                viewModel.saveLesson(Lesson(id, weekday, slot.id, subject)) { lessonSlot = null }
            }, enabled = subject.isNotBlank() && !state.busy) { Text("保存") } },
            dismissButton = {
                Row {
                    if (lesson != null) TextButton(onClick = { viewModel.deleteLesson(lesson) { lessonSlot = null } }) { Text("移除课程") }
                    TextButton(onClick = { lessonSlot = null }) { Text("取消") }
                }
            },
        )
    }
}

@Composable
private fun TimeSlotDialog(existing: TimeSlot?, onDismiss: () -> Unit, onSave: (TimeSlot) -> Unit) {
    val context = LocalContext.current
    var title by remember(existing?.id) { mutableStateOf(existing?.title.orEmpty()) }
    var start by remember(existing?.id) { mutableStateOf(existing?.start ?: LocalTime.of(8, 0)) }
    var end by remember(existing?.id) { mutableStateOf(existing?.end ?: LocalTime.of(8, 40)) }
    fun chooseTime(current: LocalTime, onSelected: (LocalTime) -> Unit) {
        TimePickerDialog(context, { _, hour, minute -> onSelected(LocalTime.of(hour, minute)) }, current.hour, current.minute, true).show()
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "添加节次" else "调整作息时间") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                QuietTextField(title, { title = it.take(30) }, "节次名称，例如第一节")
                TextButton(onClick = { chooseTime(start) { start = it } }) { Text("开始时间  $start") }
                TextButton(onClick = { chooseTime(end) { end = it } }) { Text("结束时间  $end") }
                if (end <= start) Text("结束时间应晚于开始时间。", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = { onSave(TimeSlot(existing?.id ?: UUID.randomUUID().toString(), title, start, end, existing?.weekdays ?: emptySet())) },
            enabled = title.isNotBlank() && end > start) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
