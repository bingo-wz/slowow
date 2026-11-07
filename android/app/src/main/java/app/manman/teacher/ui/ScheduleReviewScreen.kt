package app.manman.teacher.ui

import android.app.TimePickerDialog
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import app.manman.teacher.MainUiState
import app.manman.teacher.MainViewModel
import app.manman.teacher.recognition.ScheduleLessonSuggestion
import app.manman.teacher.recognition.ScheduleSlotSuggestion
import app.manman.teacher.recognition.ScheduleSuggestion
import java.time.LocalTime

private val days = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")

@Composable
fun ScheduleReviewScreen(suggestion: ScheduleSuggestion, state: MainUiState, viewModel: MainViewModel, back: () -> Unit) {
    val slots = remember(suggestion) { mutableStateListOf<ScheduleSlotSuggestion>().apply { addAll(suggestion.slots.sortedWith(compareBy({ it.weekday }, { it.start }))) } }
    val lessons = remember(suggestion) { mutableStateListOf<ScheduleLessonSuggestion>().apply { addAll(suggestion.lessons.sortedBy { it.weekday }) } }
    var editSlot by remember { mutableIntStateOf(-1) }
    var editLesson by remember { mutableIntStateOf(-1) }
    var confirmReplace by remember { mutableStateOf(false) }
    fun save(replace: Boolean) = viewModel.saveRecognizedSchedule(ScheduleSuggestion(slots.toList(), lessons.toList(), suggestion.warnings), replace, back)
    Column(Modifier.fillMaxSize()) {
        PageHeader("核对课程与作息", "点击每项可修改或移除", back = back)
        LazyColumn(Modifier.weight(1f).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                Block {
                    Text("已识别 ${slots.size} 个作息、${lessons.size} 项课程", style = MaterialTheme.typography.titleMedium)
                    Text("请核对每个星期、节次和时间。合并保存会更新同一天同一节次的课程。", style = MaterialTheme.typography.bodySmall)
                    if (suggestion.warnings.isNotEmpty()) Text(suggestion.warnings.joinToString("\n"), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
            item { SectionHeading("作息时间") }
            if (slots.isEmpty()) item { Text("未识别到作息；可使用已保存的同名节次。", style = MaterialTheme.typography.bodySmall) }
            itemsIndexed(slots) { index, item ->
                Block(onClick = { editSlot = index }) {
                    Text("${days.getOrElse(item.weekday - 1) { "星期待确认" }} · ${item.title}", style = MaterialTheme.typography.titleMedium)
                    Text(if (item.start != null && item.end != null) "${item.start} - ${item.end}" else "时间待补充", style = MaterialTheme.typography.bodySmall,
                        color = if (item.start == null || item.end == null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            item { SectionHeading("每周课程") }
            if (lessons.isEmpty()) item { Text("未识别到课程，可以只保存作息。", style = MaterialTheme.typography.bodySmall) }
            itemsIndexed(lessons) { index, item ->
                Block(onClick = { editLesson = index }) {
                    Text("${days.getOrElse(item.weekday - 1) { "星期待确认" }} · ${item.slot}", style = MaterialTheme.typography.bodySmall)
                    Text(item.subject, style = MaterialTheme.typography.bodyLarge)
                }
            }
            if (state.timeSlots.isNotEmpty() || state.lessons.isNotEmpty()) item {
                TextButton(onClick = { confirmReplace = true }, enabled = !state.busy) { Text("用识别结果替换全部课程与作息") }
            }
        }
        MainButton("合并并保存", { save(false) }, enabled = !state.busy && (slots.isNotEmpty() || lessons.isNotEmpty()),
            modifier = Modifier.fillMaxWidth().padding(16.dp))
    }
    slots.getOrNull(editSlot)?.let { item ->
        SlotReviewDialog(item, onDismiss = { editSlot = -1 }, onRemove = { slots.removeAt(editSlot); editSlot = -1 }) { updated -> slots[editSlot] = updated; editSlot = -1 }
    }
    lessons.getOrNull(editLesson)?.let { item ->
        LessonReviewDialog(item, onDismiss = { editLesson = -1 }, onRemove = { lessons.removeAt(editLesson); editLesson = -1 }) { updated -> lessons[editLesson] = updated; editLesson = -1 }
    }
    if (confirmReplace) AlertDialog(
        onDismissRequest = { confirmReplace = false },
        title = { Text("替换全部课程与作息？") },
        text = { Text("将移除当前所有课程与作息，并用这次核对的结果替换。未识别到的内容不会保留；午餐核验时间不受影响。") },
        confirmButton = { TextButton(onClick = { confirmReplace = false; save(true) }) { Text("确认替换") } },
        dismissButton = { TextButton(onClick = { confirmReplace = false }) { Text("取消") } },
    )
}

@Composable
private fun DayPicker(day: Int, onDay: (Int) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        days.forEachIndexed { index, label -> FilterChip(selected = day == index + 1, onClick = { onDay(index + 1) }, label = { Text(label) }) }
    }
}

@Composable
private fun SlotReviewDialog(item: ScheduleSlotSuggestion, onDismiss: () -> Unit, onRemove: () -> Unit, onSave: (ScheduleSlotSuggestion) -> Unit) {
    val context = LocalContext.current
    val dialogHeight = (LocalConfiguration.current.screenHeightDp * 0.45f).dp
    var day by remember { mutableIntStateOf(item.weekday) }
    var title by remember { mutableStateOf(item.title) }
    var start by remember { mutableStateOf(item.start) }
    var end by remember { mutableStateOf(item.end) }
    fun pick(value: LocalTime?, onTime: (LocalTime) -> Unit) {
        val current = value ?: LocalTime.of(8, 0)
        TimePickerDialog(context, { _, hour, minute -> onTime(LocalTime.of(hour, minute)) }, current.hour, current.minute, true).show()
    }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("修改作息") }, text = {
        Column(Modifier.heightIn(max = dialogHeight).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            DayPicker(day) { day = it }
            QuietTextField(title, { title = it.take(30) }, "节次名称")
            TextButton(onClick = { pick(start) { start = it } }) { Text("开始时间  ${start ?: "请选择"}") }
            TextButton(onClick = { pick(end) { end = it } }) { Text("结束时间  ${end ?: "请选择"}") }
            Text("课程和作息的节次名称需要一致。", style = MaterialTheme.typography.bodySmall)
        }
    }, confirmButton = { TextButton(onClick = { onSave(ScheduleSlotSuggestion(day, title.trim(), start, end)) },
        enabled = title.isNotBlank() && start != null && end != null && end!! > start!!) { Text("完成") } },
        dismissButton = { Row { TextButton(onClick = onRemove) { Text("移除") }; TextButton(onClick = onDismiss) { Text("取消") } } })
}

@Composable
private fun LessonReviewDialog(item: ScheduleLessonSuggestion, onDismiss: () -> Unit, onRemove: () -> Unit, onSave: (ScheduleLessonSuggestion) -> Unit) {
    val dialogHeight = (LocalConfiguration.current.screenHeightDp * 0.45f).dp
    var day by remember { mutableIntStateOf(item.weekday) }
    var slot by remember { mutableStateOf(item.slot) }
    var subject by remember { mutableStateOf(item.subject) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("修改课程") }, text = {
        Column(Modifier.heightIn(max = dialogHeight).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            DayPicker(day) { day = it }
            QuietTextField(slot, { slot = it.take(30) }, "节次名称")
            QuietTextField(subject, { subject = it.take(50) }, "课程名称")
            Text("课程和作息的节次名称需要一致。", style = MaterialTheme.typography.bodySmall)
        }
    }, confirmButton = { TextButton(onClick = { onSave(ScheduleLessonSuggestion(day, slot.trim(), subject.trim())) },
        enabled = slot.isNotBlank() && subject.isNotBlank()) { Text("完成") } },
        dismissButton = { Row { TextButton(onClick = onRemove) { Text("移除") }; TextButton(onClick = onDismiss) { Text("取消") } } })
}
