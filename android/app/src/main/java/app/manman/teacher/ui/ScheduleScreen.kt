package app.manman.teacher.ui

import android.app.TimePickerDialog
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.platform.LocalConfiguration
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
fun ScheduleScreen(state: MainUiState, viewModel: MainViewModel, back: () -> Unit, onReview: () -> Unit) {
    val dialogHeight = (LocalConfiguration.current.screenHeightDp * 0.45f).dp
    var weekday by remember { mutableIntStateOf(LocalDate.now(LunchRules.defaultZone).dayOfWeek.value) }
    var editSlot by remember { mutableStateOf<TimeSlot?>(null) }
    var newSlot by remember { mutableStateOf(false) }
    var lessonSlot by remember { mutableStateOf<TimeSlot?>(null) }
    var files by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var pdfRotation by remember { mutableIntStateOf(0) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { selected ->
        val combined = (files + selected).distinct()
        if (combined.size > 7) viewModel.reportMessage("一次最多选择 7 个文件。") else files = combined
    }
    fun chooseFiles() = picker.launch(arrayOf("image/*", "application/pdf", "application/vnd.ms-excel", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
    Column(Modifier.fillMaxSize()) {
        PageHeader("课程与作息", "先设置节次，再安排每周课程", back = back,
            action = { TextButton(onClick = { newSlot = true }) { Text("添加节次") } })
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Block {
                Text("导入课程表和作息表", style = MaterialTheme.typography.titleMedium)
                Text("可同时选择图片、PDF 和 Excel；识别后核对再保存。", style = MaterialTheme.typography.bodySmall)
                if (state.busy) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text("正在处理，请稍候；识别较多页面可能需要一两分钟。", style = MaterialTheme.typography.bodySmall)
                }
                TextButton(onClick = { chooseFiles() }, enabled = !state.busy) { Text("选择文件识别") }
            }
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
                            TextButton(onClick = { editSlot = slot }) { Text("调整时间") }
                        }
                        Text(lesson?.subject ?: "尚未安排课程", style = MaterialTheme.typography.bodyLarge)
                        TextButton(onClick = { lessonSlot = slot }) { Text(if (lesson == null) "添加课程" else "修改课程") }
                    }
                }
                Text("调整时间会影响使用同一节次的星期；单独导入的节次仅在对应星期生效。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(16.dp))
            }
        }
    }
    if (files.isNotEmpty()) AlertDialog(
        onDismissRequest = { files = emptyList() },
        title = { Text("发送文件进行识别？") },
        text = { Column(Modifier.heightIn(max = dialogHeight).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("已选择 ${files.size} 个文件，可继续从其他文件夹添加。将图片、PDF 页面及 Excel 表格内容发送给小米 MiMo，可能产生费用；文件中如有姓名也会一并发送。一次最多识别 5 页图片或 PDF，合计不超过 12 MB。识别结果需核对后保存。")
            Text("PDF 如果横着显示，可以旋转页面：", style = MaterialTheme.typography.bodySmall)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(0 to "原方向", 90 to "顺时针", 270 to "逆时针").forEach { (rotation, label) ->
                    FilterChip(selected = pdfRotation == rotation, onClick = { pdfRotation = rotation }, label = { Text(label) })
                }
            }
        } },
        confirmButton = { TextButton(onClick = { val selected = files; files = emptyList(); viewModel.recognizeSchedule(selected, pdfRotation, onReview) }) { Text("确认发送") } },
        dismissButton = { Column { TextButton(onClick = { chooseFiles() }) { Text("继续添加文件") }; TextButton(onClick = { files = emptyList() }) { Text("取消") } } },
    )
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
