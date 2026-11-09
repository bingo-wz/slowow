package app.manman.teacher.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.manman.teacher.MainViewModel
import app.manman.teacher.IncomingShare

private enum class Page {
    TODAY, RECORDS, CLASS, SETTINGS, IMPORT, STUDENT, LEAVE, MEAL_ABSENCES, MARKING, MARKING_DETAIL,
    MENU, SCHEDULE, SCHEDULE_REVIEW, WORK, NOTICE_DETAIL, NOTICE_EDIT, TASK_DETAIL, TASK_EDIT, IMAGE, NOTICE_REVIEW,
}

@Composable
fun ManmanApp(viewModel: MainViewModel, shared: IncomingShare? = null, consumeSharedContent: () -> Unit = {}) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var pageName by rememberSaveable { mutableStateOf(Page.TODAY.name) }
    var selectedId by rememberSaveable { mutableStateOf("") }
    var selectedNoticeId by rememberSaveable { mutableStateOf("") }
    var selectedTaskId by rememberSaveable { mutableStateOf("") }
    var taskReturn by rememberSaveable { mutableStateOf(Page.WORK.name) }
    var markingReturn by rememberSaveable { mutableStateOf(Page.TODAY.name) }
    var showNotices by rememberSaveable { mutableStateOf(false) }
    var imageId by rememberSaveable { mutableStateOf("") }
    var imageReturn by rememberSaveable { mutableStateOf(Page.MENU.name) }
    var leaveReturn by rememberSaveable { mutableStateOf(Page.TODAY.name) }
    var suggestionIndex by rememberSaveable { mutableStateOf(-1) }
    var sharedDraft by rememberSaveable { mutableStateOf("") }
    var sharedImages by rememberSaveable { mutableStateOf<List<String>>(emptyList()) }
    var confirmShare by rememberSaveable { mutableStateOf(false) }
    // 覆盖安装时，已移除页面的恢复状态回到对应的普通页面。
    val page = when (pageName) {
        "MENU_REVIEW" -> Page.MENU
        "LUNCH", "LUNCH_HISTORY" -> Page.MEAL_ABSENCES
        else -> Page.entries.firstOrNull { it.name == pageName } ?: Page.TODAY
    }
    val snackbar = remember { SnackbarHostState() }
    val message = state.error ?: state.message
    LaunchedEffect(message) {
        if (message != null) {
            snackbar.showSnackbar(message)
            viewModel.clearMessage()
        }
    }
    LaunchedEffect(state.accountId) {
        if (state.accountId == null) pageName = Page.TODAY.name
    }
    LaunchedEffect(page, state.restoring, state.accountId, state.scheduleSuggestion, state.busy) {
        if (page == Page.SCHEDULE_REVIEW && !state.restoring && state.accountId != null && state.scheduleSuggestion == null && !state.busy) {
            pageName = Page.SCHEDULE.name
        }
    }
    LaunchedEffect(shared, state.accountId) {
        if (shared != null && state.accountId != null) {
            if (page in setOf(Page.TODAY, Page.RECORDS, Page.CLASS, Page.WORK)) {
                sharedDraft = shared.text.orEmpty()
                sharedImages = shared.images
                selectedNoticeId = ""
                showNotices = true
                pageName = Page.NOTICE_EDIT.name
                consumeSharedContent()
            } else confirmShare = true
        }
    }
    fun go(pageToOpen: Page) { pageName = pageToOpen.name }
    fun back() {
        if (page == Page.NOTICE_REVIEW) viewModel.clearNoticeSuggestion()
        go(when (page) {
            Page.IMPORT, Page.STUDENT -> Page.CLASS
            Page.MARKING -> Page.valueOf(markingReturn)
            Page.MARKING_DETAIL -> Page.RECORDS
            Page.LEAVE -> Page.valueOf(leaveReturn)
            Page.SETTINGS -> Page.TODAY
            Page.CLASS, Page.SCHEDULE -> Page.SETTINGS
            Page.SCHEDULE_REVIEW -> Page.SCHEDULE
            Page.MEAL_ABSENCES -> Page.RECORDS
            Page.NOTICE_REVIEW -> Page.NOTICE_DETAIL
            Page.MENU, Page.WORK -> Page.TODAY
            Page.NOTICE_DETAIL -> Page.WORK
            Page.NOTICE_EDIT -> if (selectedNoticeId.isEmpty()) Page.WORK else Page.NOTICE_DETAIL
            Page.TASK_DETAIL -> Page.valueOf(taskReturn)
            Page.TASK_EDIT -> if (selectedTaskId.isNotEmpty()) Page.TASK_DETAIL else if (suggestionIndex >= 0) Page.NOTICE_REVIEW else if (selectedNoticeId.isNotEmpty()) Page.NOTICE_DETAIL else Page.valueOf(taskReturn)
            Page.IMAGE -> Page.valueOf(imageReturn)
            else -> Page.TODAY
        })
    }
    BackHandler(enabled = state.accountId != null && page !in setOf(Page.TODAY, Page.RECORDS)) { back() }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            if (state.accountId != null && page in setOf(Page.TODAY, Page.RECORDS)) {
                NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
                    listOf(Triple(Page.TODAY, "今天", "home"), Triple(Page.RECORDS, "记录", "records")).forEach { (destination, label, icon) ->
                        NavigationBarItem(
                            selected = page == destination,
                            onClick = { go(destination) },
                            icon = { LineIcon(icon) },
                            label = { Text(label, style = MaterialTheme.typography.labelSmall) },
                            colors = NavigationBarItemDefaults.colors(indicatorColor = MaterialTheme.colorScheme.primaryContainer),
                        )
                    }
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                state.restoring -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                state.accountId == null -> LoginScreen(state, viewModel)
                else -> when (page) {
                    Page.TODAY -> TodayScreen(state, viewModel,
                        onSettings = { go(Page.SETTINGS) }, onMenu = { go(Page.MENU) },
                        onWork = { showNotices = false; go(Page.WORK) }, onNotices = { showNotices = true; go(Page.WORK) },
                        onAddTask = { selectedTaskId = ""; selectedNoticeId = ""; suggestionIndex = -1; taskReturn = Page.TODAY.name; go(Page.TASK_EDIT) },
                        onTask = { selectedTaskId = it; selectedNoticeId = ""; suggestionIndex = -1; taskReturn = Page.TODAY.name; go(Page.TASK_DETAIL) })
                    Page.RECORDS -> RecordsScreen(state, viewModel,
                        onLeave = { selectedId = ""; leaveReturn = Page.RECORDS.name; go(Page.LEAVE) },
                        onEditLeave = { selectedId = it; leaveReturn = Page.RECORDS.name; go(Page.LEAVE) },
                        onMark = { selectedId = ""; markingReturn = Page.RECORDS.name; go(Page.MARKING) }, onMarking = { selectedId = it; go(Page.MARKING_DETAIL) },
                        onMealAbsences = { go(Page.MEAL_ABSENCES) })
                    Page.CLASS -> ClassScreen(state, ::back, { go(Page.IMPORT) }, { selectedId = it; go(Page.STUDENT) },
                        { selectedId = ""; markingReturn = Page.CLASS.name; go(Page.MARKING) })
                    Page.SETTINGS -> SettingsScreen(state, viewModel, ::back, onClass = { go(Page.CLASS) }, onSchedule = { go(Page.SCHEDULE) })
                    Page.IMPORT -> ImportStudentsScreen(state, viewModel, ::back)
                    Page.STUDENT -> state.students.firstOrNull { it.id == selectedId }?.let { StudentScreen(it, state, viewModel, ::back) }
                    Page.LEAVE -> LeaveScreen(state, viewModel, state.leaves.firstOrNull { it.id == selectedId }, ::back) { go(Page.IMPORT) }
                    Page.MEAL_ABSENCES -> MealAbsencesScreen(state, ::back)
                    Page.MARKING -> MarkingScreen(state, viewModel, state.markings.firstOrNull { it.id == selectedId }, ::back) { go(Page.IMPORT) }
                    Page.MARKING_DETAIL -> state.markings.firstOrNull { it.id == selectedId }?.let {
                        MarkingDetailScreen(it, state, viewModel, ::back) { markingReturn = Page.MARKING_DETAIL.name; go(Page.MARKING) }
                    }
                    Page.MENU -> MenuScreen(state, viewModel, ::back)
                    Page.SCHEDULE -> ScheduleScreen(state, viewModel, ::back) { go(Page.SCHEDULE_REVIEW) }
                    Page.SCHEDULE_REVIEW -> state.scheduleSuggestion?.let { ScheduleReviewScreen(it, state, viewModel, ::back) }
                    Page.WORK -> WorkScreen(state, viewModel, showNotices, { showNotices = it }, ::back,
                        onNotice = { selectedNoticeId = it; go(Page.NOTICE_DETAIL) },
                        onTask = { selectedTaskId = it; selectedNoticeId = ""; suggestionIndex = -1; taskReturn = Page.WORK.name; go(Page.TASK_DETAIL) },
                        onAddNotice = { selectedNoticeId = ""; sharedDraft = ""; sharedImages = emptyList(); go(Page.NOTICE_EDIT) },
                        onAddTask = { selectedNoticeId = ""; selectedTaskId = ""; suggestionIndex = -1; taskReturn = Page.WORK.name; go(Page.TASK_EDIT) })
                    Page.NOTICE_DETAIL -> state.notices.firstOrNull { it.id == selectedNoticeId }?.let { notice ->
                        NoticeDetailScreen(notice, state, ::back, onEdit = { go(Page.NOTICE_EDIT) },
                            onNewTask = { selectedTaskId = ""; suggestionIndex = -1; go(Page.TASK_EDIT) },
                            onTask = { selectedTaskId = it; suggestionIndex = -1; taskReturn = Page.NOTICE_DETAIL.name; go(Page.TASK_DETAIL) },
                            onImage = { imageId = it; imageReturn = Page.NOTICE_DETAIL.name; go(Page.IMAGE) },
                            onRecognize = { viewModel.recognizeNotice(notice) { go(Page.NOTICE_REVIEW) } })
                    }
                    Page.NOTICE_EDIT -> NoticeEditorScreen(state, viewModel, state.notices.firstOrNull { it.id == selectedNoticeId }, sharedDraft,
                        sharedImages, { sharedImages = emptyList() }, ::back)
                    Page.TASK_DETAIL -> state.tasks.firstOrNull { it.id == selectedTaskId }?.let { task ->
                        TaskDetailScreen(task, state, viewModel, ::back, onEdit = { selectedNoticeId = task.noticeId.orEmpty(); suggestionIndex = -1; go(Page.TASK_EDIT) },
                            onNotice = { selectedNoticeId = it; showNotices = true; go(Page.NOTICE_DETAIL) })
                    }
                    Page.TASK_EDIT -> TaskEditorScreen(state, viewModel, state.tasks.firstOrNull { it.id == selectedTaskId },
                        selectedNoticeId.takeIf(String::isNotEmpty), state.noticeSuggestion?.items?.getOrNull(suggestionIndex), ::back,
                        onSaved = {
                            if (suggestionIndex >= 0) { viewModel.consumeNoticeSuggestion(suggestionIndex); go(Page.NOTICE_REVIEW) } else back()
                        }, defaultDate = if (selectedTaskId.isEmpty() && selectedNoticeId.isEmpty() && suggestionIndex < 0 && taskReturn == Page.TODAY.name) java.time.LocalDate.now(app.manman.teacher.domain.AppTime.zone) else null)
                    Page.IMAGE -> ImageScreen(state, imageId, ::back)
                    Page.NOTICE_REVIEW -> state.noticeSuggestion?.let { suggestion ->
                        NoticeReviewScreen(suggestion, ::back) { index -> selectedTaskId = ""; suggestionIndex = index; go(Page.TASK_EDIT) }
                    }
                }
            }
        }
    }
    if (confirmShare && shared != null) AlertDialog(
        onDismissRequest = { confirmShare = false; consumeSharedContent() },
        title = { Text("收到分享的内容") },
        text = { Text("打开后，当前页面未保存的内容可能丢失。") },
        confirmButton = { TextButton(onClick = {
            sharedDraft = shared.text.orEmpty()
            sharedImages = shared.images
            selectedNoticeId = ""
            showNotices = true
            pageName = Page.NOTICE_EDIT.name
            confirmShare = false
            consumeSharedContent()
        }) { Text("打开内容") } },
        dismissButton = { TextButton(onClick = { confirmShare = false; consumeSharedContent() }) { Text("取消") } },
    )
}
