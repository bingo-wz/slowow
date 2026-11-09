package app.manman.teacher.ui

import android.app.TimePickerDialog
import android.net.Uri
import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.manman.teacher.MainUiState
import app.manman.teacher.MainViewModel
import app.manman.teacher.data.TimeSlot
import app.manman.teacher.domain.AppTime
import app.manman.teacher.ScheduleProgressState
import app.manman.teacher.recognition.SchedulePhase
import kotlinx.coroutines.delay
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

@Composable
fun ScheduleScreen(state: MainUiState, viewModel: MainViewModel, back: () -> Unit, onReview: () -> Unit) {
    var weekday by remember { mutableIntStateOf(LocalDate.now(AppTime.zone).dayOfWeek.value) }
    var editSlot by remember { mutableStateOf<TimeSlot?>(null) }
    var newSlot by remember { mutableStateOf(false) }
    var lessonSlot by remember { mutableStateOf<TimeSlot?>(null) }
    var images by rememberSaveable { mutableStateOf<List<String>>(emptyList()) }
    var replaceImages by rememberSaveable { mutableStateOf(false) }
    var confirmRecognition by rememberSaveable { mutableStateOf(false) }
    var confirmStop by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { selected ->
        if (selected.isNotEmpty()) {
            val combined = ((if (replaceImages) emptyList() else images) + selected.map(Uri::toString)).distinct()
            if (combined.size > 2) viewModel.reportMessage("一次最多选择两张图片。")
            else { images = combined; confirmRecognition = true }
        }
        replaceImages = false
    }
    fun choose(replace: Boolean) { replaceImages = replace; picker.launch(arrayOf("image/jpeg", "image/png", "image/webp")) }
    fun goBack() { if (state.scheduleProgress != null) confirmStop = true else back() }
    BackHandler(enabled = state.scheduleProgress != null) { confirmStop = true }
    var editProfile by remember { mutableStateOf(false) }
    val context = LocalContext.current
    TeacherSchedulePage(state, images, onBack = ::goBack,
        onImport = { choose(images.isNotEmpty()) }, onAddImages = { choose(false) },
        onRecognize = { confirmRecognition = true }, onStopRecognition = { confirmStop = true }, onReview = onReview,
        onAddSlot = { newSlot = true }, onEditSlot = { editSlot = it.slot },
        onEditLesson = { weekday = it.date.dayOfWeek.value; lessonSlot = it.slot },
        onEditProfile = { editProfile = true },
        onPrint = { week, items, teacher, profile -> printTeacherSchedule(context, week, items, teacher, profile) })
    if (editProfile) ScheduleProfileDialog(state.scheduleProfile, state.busy, onDismiss = { editProfile = false }) {
        viewModel.saveScheduleProfile(it) { editProfile = false }
    }
    if (confirmRecognition && images.isNotEmpty()) AlertDialog(
        onDismissRequest = feedbackClick { confirmRecognition = false },
        title = { Text("发送图片进行识别？") },
        text = { Text("已选择 ${images.size} 张图片。图片将发送给小米 MiMo，可能产生 API 费用；图片中的姓名等内容也会一并发送。识别后请核对，再决定是否保存。每张不超过 8 MB，合计不超过 12 MB。" + if (state.scheduleSuggestion != null) "重新识别会替换上次尚未保存的识别结果。" else "") },
        confirmButton = { FeedbackTextButton(onClick = {
            confirmRecognition = false
            viewModel.recognizeSchedule(images.map(Uri::parse), onReview)
        }) { Text("确认发送") } },
        dismissButton = { Column {
            if (images.size < 2) FeedbackTextButton(onClick = { confirmRecognition = false; choose(false) }) { Text("再添加一张") }
            FeedbackTextButton(onClick = { confirmRecognition = false }) { Text("取消") }
        } },
    )
    if (confirmStop) AlertDialog(onDismissRequest = feedbackClick { confirmStop = false }, title = { Text("停止识别？") },
        text = { Text("本次结果不会保存；已发送给模型的请求可能已产生费用。") },
        confirmButton = { FeedbackTextButton(onClick = { confirmStop = false; viewModel.cancelScheduleRecognition() }) { Text("停止") } },
        dismissButton = { FeedbackTextButton(onClick = { confirmStop = false }) { Text("继续识别") } })
    if (newSlot || editSlot != null) {
        TimeSlotDialog(editSlot, onDismiss = { newSlot = false; editSlot = null }) { slot ->
            viewModel.saveTimeSlot(slot) { newSlot = false; editSlot = null }
        }
    }
    lessonSlot?.let { slot ->
        val lesson = state.lessons.firstOrNull { it.weekday == weekday && it.slotId == slot.id }
        ScheduleLessonDialog(slot, weekday, lesson, state.timeSlots, state.busy,
            onDismiss = { lessonSlot = null },
            onDelete = if (lesson == null) null else ({ viewModel.deleteLesson(lesson) { lessonSlot = null } }),
            onSave = { selected -> viewModel.saveLesson(selected) { lessonSlot = null } })
    }
}

@Composable
fun ScheduleProgress(progress: ScheduleProgressState) {
    var elapsed by remember(progress.startedAt) { mutableIntStateOf(0) }
    LaunchedEffect(progress.startedAt) {
        while (true) {
            elapsed = ((SystemClock.elapsedRealtime() - progress.startedAt) / 1000).toInt()
            delay(1000)
        }
    }
    val update = progress.update
    if (update.phase == SchedulePhase.UPLOADING && update.total > 0) {
        LinearProgressIndicator(progress = { (update.current.toFloat() / update.total).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
    } else LinearProgressIndicator(Modifier.fillMaxWidth())
    val label = when (update.phase) {
        SchedulePhase.PREPARING -> if (update.total > 0) "正在读取第 ${update.current}/${update.total} 张图片" else "正在准备图片"
        SchedulePhase.CONNECTING -> "正在连接识别服务"
        SchedulePhase.RECONNECTING -> "连接暂时中断，正在重试（${update.current}/${update.total}）"
        SchedulePhase.UPLOADING -> "正在发送图片 ${update.current}%"
        SchedulePhase.WAITING -> "图片已发送，正在等待 AI 分析表格"
        SchedulePhase.RECEIVING -> "AI 正在返回识别内容，完成后即可核对"
    }
    val announcement = if (update.phase == SchedulePhase.UPLOADING) Modifier else Modifier.semantics { liveRegion = LiveRegionMode.Polite }
    key(label) { Text(label, modifier = announcement, style = MaterialTheme.typography.bodyMedium) }
    key(elapsed) {
        Text("已用 $elapsed 秒 · 结果不会自动保存", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun TimeSlotDialog(existing: TimeSlot?, onDismiss: () -> Unit, onSave: (TimeSlot) -> Unit) {
    val context = LocalContext.current
    var title by remember(existing?.id) { mutableStateOf(existing?.title.orEmpty()) }
    var start by remember(existing?.id) { mutableStateOf(existing?.start ?: LocalTime.of(8, 0)) }
    var end by remember(existing?.id) { mutableStateOf(existing?.end ?: LocalTime.of(8, 40)) }
    var weekdays by rememberSaveable(existing?.id) {
        mutableStateOf(ArrayList(existing?.weekdays?.takeIf(Set<Int>::isNotEmpty) ?: (1..7).toList()))
    }
    val dialogHeight = (LocalConfiguration.current.screenHeightDp * 0.65f).dp
    fun chooseTime(current: LocalTime, onSelected: (LocalTime) -> Unit) {
        TimePickerDialog(context, { _, hour, minute -> onSelected(LocalTime.of(hour, minute)) }, current.hour, current.minute, true).show()
    }
    AlertDialog(
        onDismissRequest = feedbackClick(onDismiss),
        title = { Text(if (existing == null) "添加节次" else "调整作息时间") },
        text = {
            Column(Modifier.heightIn(max = dialogHeight).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                QuietTextField(title, { title = it.take(30) }, "节次名称，例如第一节")
                FeedbackTextButton(onClick = { chooseTime(start) { start = it } }) { Text("开始时间  $start") }
                FeedbackTextButton(onClick = { chooseTime(end) { end = it } }) { Text("结束时间  $end") }
                Text("适用星期", style = MaterialTheme.typography.titleMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    scheduleDayNames.forEachIndexed { index, name ->
                        val day = index + 1
                        FilterChip(selected = day in weekdays, onClick = feedbackClick {
                            weekdays = ArrayList(if (day in weekdays) weekdays - day else weekdays + day)
                        }, label = { Text(name) })
                    }
                }
                if (end <= start) Text("结束时间应晚于开始时间。", style = MaterialTheme.typography.bodySmall)
                if (weekdays.isEmpty()) Text("请至少选择一天。", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { FeedbackTextButton(onClick = { onSave(TimeSlot(existing?.id ?: UUID.randomUUID().toString(), title, start, end,
            if (weekdays.size == 7) emptySet() else weekdays.toSet())) },
            enabled = title.isNotBlank() && end > start && weekdays.isNotEmpty()) { Text("保存") } },
        dismissButton = { FeedbackTextButton(onClick = onDismiss) { Text("取消") } },
    )
}
