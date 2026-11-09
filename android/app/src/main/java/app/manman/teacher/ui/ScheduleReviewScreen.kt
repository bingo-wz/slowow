package app.manman.teacher.ui

import android.app.TimePickerDialog
import android.net.Uri
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.manman.teacher.MainUiState
import app.manman.teacher.MainViewModel
import app.manman.teacher.recognition.ScheduleLessonSuggestion
import app.manman.teacher.recognition.ScheduleSlotSuggestion
import app.manman.teacher.recognition.ScheduleSuggestion
import java.time.LocalTime

private val reviewDays = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")

@Composable
fun ScheduleReviewScreen(suggestion: ScheduleSuggestion, state: MainUiState, viewModel: MainViewModel, back: () -> Unit) {
    val slots = suggestion.slots
    val lessons = suggestion.lessons
    var day by rememberSaveable { mutableIntStateOf(1) }
    var editSlot by remember { mutableIntStateOf(-1) }
    var editLesson by remember { mutableIntStateOf(-1) }
    var newSlot by remember { mutableStateOf(false) }
    var newLesson by remember { mutableStateOf(false) }
    var chooseSave by remember { mutableStateOf(false) }
    var confirmReplace by remember { mutableStateOf(false) }
    var sourceImage by rememberSaveable { mutableStateOf<String?>(null) }
    val hasUnknownDay = slots.any { it.weekday !in 1..7 } || lessons.any { it.weekday !in 1..7 }
    fun update(value: ScheduleSuggestion) = viewModel.updateScheduleSuggestion(value)
    fun save(replace: Boolean) = viewModel.saveRecognizedSchedule(suggestion, replace, back)
    Column(Modifier.fillMaxSize()) {
        PageHeader("核对课程与作息", "按星期核对，点击项目可修改", back = back)
        LazyColumn(Modifier.weight(1f).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                Block {
                    Text("识别到 ${slots.size} 个作息、${lessons.size} 项课程", style = MaterialTheme.typography.titleMedium)
                    Text("请对照图片检查是否有遗漏；空白和不确定的内容需要补全。", style = MaterialTheme.typography.bodySmall)
                    if (state.scheduleSourceUris.isNotEmpty()) Row {
                        state.scheduleSourceUris.forEachIndexed { index, uri ->
                            FeedbackTextButton(onClick = { sourceImage = uri }) { Text("原图 ${index + 1}") }
                        }
                    }
                    if (suggestion.warnings.isNotEmpty()) Text(suggestion.warnings.joinToString("\n"), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
            item {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    reviewDays.forEachIndexed { index, label ->
                        FilterChip(selected = day == index + 1, onClick = feedbackClick { day = index + 1 }, label = { Text(label) })
                    }
                    if (hasUnknownDay) FilterChip(selected = day == 0, onClick = feedbackClick { day = 0 }, label = { Text("待确认") })
                }
            }
            item { SectionHeading("作息时间", "添加") { newSlot = true } }
            val daySlots = slots.withIndex().filter { if (day == 0) it.value.weekday !in 1..7 else it.value.weekday == day }
                .sortedBy { it.value.start ?: LocalTime.MAX }
            val hasOverlap = daySlots.indices.any { first ->
                daySlots.indices.any { second ->
                    if (first >= second) false else {
                        val a = daySlots[first].value
                        val b = daySlots[second].value
                        a.start != null && a.end != null && b.start != null && b.end != null && a.start < b.end && b.start < a.end
                    }
                }
            }
            if (hasOverlap) item { Text("这一天有作息时间重叠，请核对，避免同一活动重复提醒。", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            if (daySlots.isEmpty()) item { Text("这一天没有识别到作息。", style = MaterialTheme.typography.bodySmall) }
            items(daySlots, key = { "slot-${it.index}" }) { indexed ->
                val item = indexed.value
                val invalid = item.weekday !in 1..7 || item.title.isBlank() || item.start == null || item.end == null || item.start >= item.end
                Block(onClick = { editSlot = indexed.index }) {
                    Text(item.title.ifBlank { "节次名称待补充" }, style = MaterialTheme.typography.bodyLarge)
                    Text(if (item.start != null && item.end != null) "${item.start} - ${item.end}" else "开始或结束时间待补充",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (invalid) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            item { SectionHeading("课程与活动", "添加") { newLesson = true } }
            val dayLessons = lessons.withIndex().filter { if (day == 0) it.value.weekday !in 1..7 else it.value.weekday == day }
            if (dayLessons.isEmpty()) item { Text("这一天没有识别到课程。", style = MaterialTheme.typography.bodySmall) }
            items(dayLessons, key = { "lesson-${it.index}" }) { indexed ->
                val item = indexed.value
                val matched = slots.any { it.weekday == item.weekday && it.title == item.slot }
                val existing = state.timeSlots.any { (it.weekdays.isEmpty() || item.weekday in it.weekdays) && it.title == item.slot }
                Block(onClick = { editLesson = indexed.index }) {
                    Text(item.subject.ifBlank { "课程名称待补充" }, style = MaterialTheme.typography.bodyLarge)
                    Text(item.slot.ifBlank { "节次名称待补充" } + if (!matched && !existing) " · 还需补充对应作息" else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (!matched && !existing) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        MainButton("保存识别结果", {
            if (state.timeSlots.isNotEmpty() || state.lessons.isNotEmpty()) chooseSave = true else save(false)
        }, enabled = !state.busy && (slots.isNotEmpty() || lessons.isNotEmpty()), modifier = Modifier.fillMaxWidth().padding(16.dp))
    }
    sourceImage?.let { ScheduleSourceImage(Uri.parse(it)) { sourceImage = null } }
    val existingSlot = slots.getOrNull(editSlot)
    if (newSlot || existingSlot != null) {
        val item = existingSlot ?: ScheduleSlotSuggestion(day.takeIf { it in 1..7 } ?: 1, "", null, null)
        SlotReviewDialog(item, onDismiss = { editSlot = -1; newSlot = false },
            onRemove = if (existingSlot == null) null else ({
                update(suggestion.copy(slots = slots.toMutableList().apply { removeAt(editSlot) }))
                editSlot = -1
            })) { updated ->
            if (existingSlot == null) update(suggestion.copy(slots = slots + updated))
            else {
                val linked = lessons.withIndex().filter { it.value.weekday == item.weekday && it.value.slot == item.title }
                val nextLessons = lessons.toMutableList()
                if (linked.size == 1) nextLessons[linked.single().index] = linked.single().value.copy(weekday = updated.weekday, slot = updated.title)
                update(suggestion.copy(slots = slots.toMutableList().apply { this[editSlot] = updated }, lessons = nextLessons))
            }
            editSlot = -1; newSlot = false
        }
    }
    val existingLesson = lessons.getOrNull(editLesson)
    if (newLesson || existingLesson != null) {
        val item = existingLesson ?: ScheduleLessonSuggestion(day.takeIf { it in 1..7 } ?: 1, "", "")
        LessonReviewDialog(item, onDismiss = { editLesson = -1; newLesson = false },
            onRemove = if (existingLesson == null) null else ({
                update(suggestion.copy(lessons = lessons.toMutableList().apply { removeAt(editLesson) }))
                editLesson = -1
            })) { updated ->
            val next = if (existingLesson == null) lessons + updated else lessons.toMutableList().apply { this[editLesson] = updated }
            update(suggestion.copy(lessons = next))
            editLesson = -1; newLesson = false
        }
    }
    if (chooseSave) AlertDialog(onDismissRequest = feedbackClick { chooseSave = false }, title = { Text("怎样保存课程与作息？") },
        text = { Text("合并会保留其他已有内容，并更新同一天同一节次的课程；如果已有节次时间不同，需要先调整或选择替换。") },
        confirmButton = { FeedbackTextButton(onClick = { chooseSave = false; save(false) }) { Text("合并保存") } },
        dismissButton = { Column {
            FeedbackTextButton(onClick = { chooseSave = false; confirmReplace = true }) { Text("替换全部") }
            FeedbackTextButton(onClick = { chooseSave = false }) { Text("取消") }
        } })
    if (confirmReplace) AlertDialog(onDismissRequest = feedbackClick { confirmReplace = false }, title = { Text("替换全部课程与作息？") },
        text = { Text("现有课程和作息会被这次结果替换；图片中遗漏的内容不会保留。请确认已核对每一天。") },
        confirmButton = { FeedbackTextButton(onClick = { confirmReplace = false; save(true) }) { Text("确认替换") } },
        dismissButton = { FeedbackTextButton(onClick = { confirmReplace = false }) { Text("取消") } })
}

@Composable
private fun ReviewDayPicker(day: Int, onDay: (Int) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        reviewDays.forEachIndexed { index, label -> FilterChip(selected = day == index + 1, onClick = feedbackClick { onDay(index + 1) }, label = { Text(label) }) }
    }
}

@Composable
private fun SlotReviewDialog(item: ScheduleSlotSuggestion, onDismiss: () -> Unit, onRemove: (() -> Unit)?, onSave: (ScheduleSlotSuggestion) -> Unit) {
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
    AlertDialog(onDismissRequest = feedbackClick(onDismiss), title = { Text("修改作息") }, text = {
        Column(Modifier.heightIn(max = dialogHeight).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ReviewDayPicker(day) { day = it }
            QuietTextField(title, { title = it.take(30) }, "节次名称")
            FeedbackTextButton(onClick = { pick(start) { start = it } }) { Text("开始时间  ${start ?: "请选择"}") }
            FeedbackTextButton(onClick = { pick(end) { end = it } }) { Text("结束时间  ${end ?: "请选择"}") }
            Text("课程和作息的节次名称需要一致。", style = MaterialTheme.typography.bodySmall)
        }
    }, confirmButton = { FeedbackTextButton(onClick = { onSave(ScheduleSlotSuggestion(day, title.trim(), start, end)) },
        enabled = day in 1..7 && title.isNotBlank() && start != null && end != null && end!! > start!!) { Text("完成") } },
        dismissButton = { Row { if (onRemove != null) FeedbackTextButton(onClick = onRemove) { Text("移除") }; FeedbackTextButton(onClick = onDismiss) { Text("取消") } } })
}

@Composable
private fun LessonReviewDialog(item: ScheduleLessonSuggestion, onDismiss: () -> Unit, onRemove: (() -> Unit)?, onSave: (ScheduleLessonSuggestion) -> Unit) {
    val dialogHeight = (LocalConfiguration.current.screenHeightDp * 0.45f).dp
    var day by remember { mutableIntStateOf(item.weekday) }
    var slot by remember { mutableStateOf(item.slot) }
    var subject by remember { mutableStateOf(item.subject) }
    AlertDialog(onDismissRequest = feedbackClick(onDismiss), title = { Text("修改课程") }, text = {
        Column(Modifier.heightIn(max = dialogHeight).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ReviewDayPicker(day) { day = it }
            QuietTextField(slot, { slot = it.take(30) }, "节次名称")
            QuietTextField(subject, { subject = it.take(50) }, "课程名称")
            Text("课程和作息的节次名称需要一致。", style = MaterialTheme.typography.bodySmall)
        }
    }, confirmButton = { FeedbackTextButton(onClick = { onSave(ScheduleLessonSuggestion(day, slot.trim(), subject.trim())) },
        enabled = day in 1..7 && slot.isNotBlank() && subject.isNotBlank()) { Text("完成") } },
        dismissButton = { Row { if (onRemove != null) FeedbackTextButton(onClick = onRemove) { Text("移除") }; FeedbackTextButton(onClick = onDismiss) { Text("取消") } } })
}
