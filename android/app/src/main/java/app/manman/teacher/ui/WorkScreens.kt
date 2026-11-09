package app.manman.teacher.ui

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.manman.teacher.MainUiState
import app.manman.teacher.MainViewModel
import app.manman.teacher.data.Notice
import app.manman.teacher.data.WorkTask
import app.manman.teacher.domain.AppTime
import app.manman.teacher.recognition.NoticeSuggestionItem
import java.time.Instant
import java.time.LocalDate
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID

private val momentFormat = DateTimeFormatter.ofPattern("M月d日 HH:mm")
private fun formatMoment(value: Instant?): String = value?.atZone(AppTime.zone)?.format(momentFormat) ?: "未设置"
private fun taskDue(task: WorkTask): String = task.dueAt?.let(::formatMoment) ?: task.dueDate?.toString()?.plus("（仅日期）") ?: "未设置时间"
@Composable
fun WorkScreen(state: MainUiState, viewModel: MainViewModel, showNotices: Boolean, setShowNotices: (Boolean) -> Unit,
    back: () -> Unit, onNotice: (String) -> Unit, onTask: (String) -> Unit, onAddNotice: () -> Unit, onAddTask: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        PageHeader(if (showNotices) "学校通知" else "全部待办", back = back, action = {
            Row {
                ActionIcon(if (showNotices) "list" else "notice", if (showNotices) "全部待办" else "学校通知", { setShowNotices(!showNotices) })
                ActionIcon("plus", if (showNotices) "添加通知" else "新建待办", if (showNotices) onAddNotice else onAddTask)
            }
        })
        if (showNotices) {
            LazyColumn(Modifier.weight(1f), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (state.notices.isEmpty()) item { Block { Text("还没有保存通知。", color = MaterialTheme.colorScheme.onSurfaceVariant) } }
                items(state.notices, key = { "notice-${it.id}" }) { notice ->
                    Block(onClick = { onNotice(notice.id) }) {
                        Text(notice.title, style = MaterialTheme.typography.bodyLarge)
                        Text(notice.content.ifBlank { "${notice.attachmentIds.size} 张图片" }, maxLines = 2, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                item { Spacer(Modifier.height(16.dp)) }
            }
        } else {
            TaskAgenda(state, viewModel, rememberToday(), onlyToday = false, onTask = onTask, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
fun NoticeEditorScreen(state: MainUiState, viewModel: MainViewModel, existing: Notice?, sharedText: String,
    sharedImages: List<String>, consumeSharedImages: () -> Unit, back: () -> Unit) {
    var title by rememberSaveable(existing?.id, sharedText) { mutableStateOf(existing?.title.orEmpty()) }
    var content by rememberSaveable(existing?.id, sharedText) { mutableStateOf(existing?.content ?: sharedText) }
    val draftId = rememberSaveable(existing?.id, sharedText) { UUID.randomUUID().toString() }
    var keptIds by rememberSaveable(existing?.id, sharedText) { mutableStateOf(existing?.attachmentIds?.joinToString(",").orEmpty()) }
    val kept = keptIds.split(',').filter(String::isNotBlank)
    val staged = state.imageDrafts[draftId].orEmpty()
    LaunchedEffect(sharedImages, state.busy) {
        if (sharedImages.isNotEmpty() && !state.busy) {
            if (sharedImages.size + kept.size + staged.size > 5) viewModel.reportMessage("一条通知最多添加 5 张图片，请重新分享。")
            else viewModel.stageImages(draftId, sharedImages.map(Uri::parse))
            consumeSharedImages()
        }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(5)) { uris ->
        if (uris.isNotEmpty()) {
            if (uris.size + kept.size + staged.size <= 5) viewModel.stageImages(draftId, uris)
            else viewModel.reportMessage("一条通知最多添加 5 张图片。")
        }
    }
    var confirmBack by remember { mutableStateOf(false) }
    val dirty = title != existing?.title.orEmpty() || content != (existing?.content ?: sharedText) ||
        (existing == null && sharedText.isNotBlank()) || kept != existing?.attachmentIds.orEmpty() || staged.isNotEmpty()
    fun leave() { if (dirty) confirmBack = true else back() }
    androidx.activity.compose.BackHandler { leave() }
    Column(Modifier.fillMaxSize()) {
        PageHeader(if (existing == null) "添加通知" else "编辑通知", back = ::leave)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            QuietTextField(title, { title = it.take(80) }, "通知标题")
            QuietTextField(content, { content = it }, "通知原文", singleLine = false, modifier = Modifier.height(240.dp))
            SectionHeading("通知图片")
            (kept + staged.map { it.id }).forEachIndexed { index, id ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("图片 ${index + 1}", Modifier.padding(top = 12.dp))
                    FeedbackTextButton(onClick = {
                        val newImage = staged.firstOrNull { it.id == id }
                        if (newImage != null) viewModel.discardImage(draftId, newImage)
                        else keptIds = kept.filterNot { it == id }.joinToString(",")
                    }) { Text("移除") }
                }
            }
            if (kept.size + staged.size < 5) FeedbackTextButton(onClick = {
                picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            }, enabled = !state.busy) { Text("添加图片") }
            Text("原文和图片会保存在你的账号下。保存后可手动创建待办或选择识别。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (content.length > 10000) Text("通知内容超过 10000 个字，请精简后保存。", style = MaterialTheme.typography.bodySmall)
        }
        MainButton("保存通知", {
            viewModel.saveNotice(Notice(existing?.id ?: UUID.randomUUID().toString(), title, content,
                existing?.createdAt ?: Instant.now(), kept + staged.map { it.id }), draftId, back)
        }, enabled = title.isNotBlank() && (content.isNotBlank() || kept.isNotEmpty() || staged.isNotEmpty()) && content.length <= 10000 && !state.busy,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp))
        Spacer(Modifier.height(16.dp))
    }
    if (confirmBack) androidx.compose.material3.AlertDialog(
        onDismissRequest = feedbackClick { confirmBack = false }, title = { Text("通知还没有保存") },
        text = { Text("离开后，未保存的内容不会保留。") },
        confirmButton = { FeedbackTextButton(onClick = { confirmBack = false; viewModel.discardDraft(draftId); back() }) { Text("放弃修改") } },
        dismissButton = { FeedbackTextButton(onClick = { confirmBack = false }) { Text("继续编辑") } },
    )
}

@Composable
fun NoticeDetailScreen(notice: Notice, state: MainUiState, back: () -> Unit, onEdit: () -> Unit, onNewTask: () -> Unit,
    onTask: (String) -> Unit, onImage: (String) -> Unit, onRecognize: () -> Unit) {
    var confirmRecognition by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        PageHeader(notice.title, "学校通知", back = back, action = { ActionIcon("edit", "编辑通知", onEdit) })
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            SectionHeading("通知原文")
            if (notice.content.isNotBlank()) Block { Text(notice.content) }
            if (notice.attachmentIds.isNotEmpty()) {
                SectionHeading("通知图片")
                notice.attachmentIds.forEachIndexed { index, id ->
                    Block(onClick = { onImage(id) }) { Text("查看图片 ${index + 1}  ›", color = MaterialTheme.colorScheme.primary) }
                }
            }
            FeedbackTextButton(onClick = { confirmRecognition = true }, enabled = !state.busy) { Text("识别通知中的待办") }
            SectionHeading("相关待办", "新建", onNewTask)
            val tasks = state.tasks.filter { it.noticeId == notice.id }
            if (tasks.isEmpty()) Text("还没有从这条通知添加待办。", color = MaterialTheme.colorScheme.onSurfaceVariant)
            tasks.forEach { task ->
                Block(onClick = { onTask(task.id) }) {
                    Text(task.title)
                    Text(if (task.done) "已完成" else "待处理 · ${taskDue(task)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
    if (confirmRecognition) androidx.compose.material3.AlertDialog(
        onDismissRequest = feedbackClick { confirmRecognition = false },
        title = { Text("发送这条通知进行识别？") },
        text = { Text("将这条通知的原文和 ${notice.attachmentIds.size} 张图片发送给小米 MiMo，可能产生 API 费用。识别结果需逐条检查，不会自动保存；图片需先同步到云端，单次合计不超过 12 MB。") },
        confirmButton = { FeedbackTextButton(onClick = { confirmRecognition = false; onRecognize() }) { Text("确认发送") } },
        dismissButton = { FeedbackTextButton(onClick = { confirmRecognition = false }) { Text("取消") } },
    )
}

@Composable
fun TaskDetailScreen(task: WorkTask, state: MainUiState, viewModel: MainViewModel, back: () -> Unit, onEdit: () -> Unit, onNotice: (String) -> Unit) {
    val notice = state.notices.firstOrNull { it.id == task.noticeId }
    var confirmDelete by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        PageHeader("待办详情", back = back, action = { Row {
            ActionIcon("edit", "编辑待办", onEdit)
            ActionIcon("delete", "删除待办", { confirmDelete = true }, enabled = !state.busy)
        } })
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Block {
                Text(task.title, style = MaterialTheme.typography.titleMedium)
                Text(if (task.done) "已完成" else "待处理", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("时间：${taskDue(task)}", style = MaterialTheme.typography.bodySmall)
                Text("提醒：${formatMoment(task.remindAt)}", style = MaterialTheme.typography.bodySmall)
            }
            if (notice != null) Block(onClick = { onNotice(notice.id) }) {
                Text("来自学校通知", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(notice.title)
                Text("查看原文  ›", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
            }
        }
        MainButton(if (task.done) "标记为待处理" else "完成待办", { viewModel.saveTask(task.copy(done = !task.done)) },
            enabled = !state.busy, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp))
        Spacer(Modifier.height(16.dp))
    }
    if (confirmDelete) androidx.compose.material3.AlertDialog(
        onDismissRequest = feedbackClick { confirmDelete = false }, title = { Text("删除这条待办？") },
        text = { Text("删除后，这条待办和它的提醒会从已同步的设备中移除。") },
        confirmButton = { FeedbackTextButton(onClick = { confirmDelete = false; viewModel.deleteTask(task, back) }) { Text("删除") } },
        dismissButton = { FeedbackTextButton(onClick = { confirmDelete = false }) { Text("取消") } },
    )
}

@Composable
fun TaskEditorScreen(state: MainUiState, viewModel: MainViewModel, existing: WorkTask?, noticeId: String?, suggestion: NoticeSuggestionItem?,
    back: () -> Unit, onSaved: () -> Unit = back, defaultDate: LocalDate? = null) {
    val context = LocalContext.current
    val initialDue = existing?.dueAt ?: if (suggestion?.date != null && suggestion.time != null) {
        suggestion.date.atTime(suggestion.time).atZone(AppTime.zone).toInstant()
    } else null
    val initialDate = existing?.dueDate ?: suggestion?.date ?: if (existing == null) defaultDate else null
    var title by rememberSaveable(existing?.id, suggestion?.title) { mutableStateOf(existing?.title ?: suggestion?.title.orEmpty()) }
    var due by rememberSaveable(existing?.id, suggestion?.title) { mutableStateOf(initialDue?.toString().orEmpty()) }
    var dueDate by rememberSaveable(existing?.id, suggestion?.title) { mutableStateOf(initialDate?.toString().orEmpty()) }
    var reminder by rememberSaveable(existing?.id, suggestion?.title) { mutableStateOf(existing?.remindAt?.toString().orEmpty()) }
    var confirmBack by remember { mutableStateOf(false) }
    val dirty = suggestion != null || title != existing?.title.orEmpty() || due != existing?.dueAt?.toString().orEmpty() ||
        dueDate != initialDate?.toString().orEmpty() || reminder != existing?.remindAt?.toString().orEmpty()
    fun leave() { if (dirty) confirmBack = true else back() }
    androidx.activity.compose.BackHandler { leave() }
    val notice = state.notices.firstOrNull { it.id == (existing?.noticeId ?: noticeId) }
    fun chooseMoment(value: String, onSelected: (String) -> Unit) {
        val current = value.takeIf(String::isNotBlank)?.let(Instant::parse)?.atZone(AppTime.zone)
            ?: ZonedDateTime.now(AppTime.zone).plusHours(1).withSecond(0).withNano(0)
        DatePickerDialog(context, { _, year, month, day ->
            TimePickerDialog(context, { _, hour, minute ->
                val selected = java.time.LocalDateTime.of(year, month + 1, day, hour, minute).atZone(AppTime.zone).toInstant()
                onSelected(selected.toString())
            }, current.hour, current.minute, true).show()
        }, current.year, current.monthValue - 1, current.dayOfMonth).show()
    }
    Column(Modifier.fillMaxSize()) {
        PageHeader(if (existing == null) "新建待办" else "编辑待办", back = ::leave)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            QuietTextField(title, { title = it.take(100) }, "待办事项")
            if (notice != null) Block { Text("来自通知：${notice.title}", style = MaterialTheme.typography.bodySmall) }
            Block {
                Text("事项时间", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row {
                    val label = if (due.isNotEmpty()) formatMoment(Instant.parse(due)) else dueDate.takeIf(String::isNotBlank)?.plus("（仅日期）") ?: "未设置"
                    FeedbackTextButton(onClick = { chooseMoment(due) { due = it; dueDate = Instant.parse(it).atZone(AppTime.zone).toLocalDate().toString() } }) { Text(label + "  ›") }
                    if (due.isNotEmpty() || dueDate.isNotEmpty()) FeedbackTextButton(onClick = { due = ""; dueDate = "" }) { Text("清除") }
                }
                FeedbackTextButton(onClick = {
                    val current = dueDate.takeIf(String::isNotBlank)?.let(LocalDate::parse) ?: LocalDate.now(AppTime.zone)
                    DatePickerDialog(context, { _, year, month, day -> dueDate = LocalDate.of(year, month + 1, day).toString(); due = "" },
                        current.year, current.monthValue - 1, current.dayOfMonth).show()
                }) { Text("只设置日期") }
            }
            Block {
                Text("提醒时间", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row {
                    FeedbackTextButton(onClick = { chooseMoment(reminder) { reminder = it } }) { Text(formatMoment(reminder.takeIf(String::isNotBlank)?.let(Instant::parse)) + "  ›") }
                    if (reminder.isNotEmpty()) FeedbackTextButton(onClick = { reminder = "" }) { Text("清除") }
                }
                if (reminder.isNotEmpty() && Instant.parse(reminder).isBefore(Instant.now())) {
                    Text("提醒时间已经过去，不会补发提醒。", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        MainButton("保存待办", {
            val updated = WorkTask(existing?.id ?: UUID.randomUUID().toString(), title, existing?.noticeId ?: noticeId,
                due.takeIf(String::isNotBlank)?.let(Instant::parse), reminder.takeIf(String::isNotBlank)?.let(Instant::parse),
                existing?.done ?: false, existing?.createdAt ?: Instant.now(), dueDate.takeIf(String::isNotBlank)?.let(LocalDate::parse))
            val order = existing?.sortOrder?.takeIf { app.manman.teacher.domain.TaskOrdering.date(updated) == app.manman.teacher.domain.TaskOrdering.date(existing) }
            viewModel.saveTask(updated.copy(sortOrder = order), onSaved)
        }, enabled = title.isNotBlank() && !state.busy, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp))
        Spacer(Modifier.height(16.dp))
    }
    if (confirmBack) androidx.compose.material3.AlertDialog(
        onDismissRequest = feedbackClick { confirmBack = false }, title = { Text("待办还没有保存") },
        text = { Text("离开后，未保存的内容不会保留。") },
        confirmButton = { FeedbackTextButton(onClick = { confirmBack = false; back() }) { Text("放弃修改") } },
        dismissButton = { FeedbackTextButton(onClick = { confirmBack = false }) { Text("继续编辑") } },
    )
}
