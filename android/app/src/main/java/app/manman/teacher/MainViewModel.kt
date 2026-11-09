package app.manman.teacher

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import android.net.Uri
import android.os.SystemClock
import app.manman.teacher.auth.AuthException
import app.manman.teacher.auth.VerificationRequest
import app.manman.teacher.data.ClassRepository
import app.manman.teacher.data.LocalRecord
import app.manman.teacher.data.MarkingRecord
import app.manman.teacher.data.MenuWeek
import app.manman.teacher.data.AttachmentMeta
import app.manman.teacher.data.TimeSlot
import app.manman.teacher.data.Lesson
import app.manman.teacher.data.ScheduleProfile
import app.manman.teacher.data.Notice
import app.manman.teacher.data.WorkTask
import app.manman.teacher.data.RecordCodec
import app.manman.teacher.data.RecordKind
import app.manman.teacher.domain.Leave
import app.manman.teacher.domain.Student
import app.manman.teacher.sync.SyncPhase
import app.manman.teacher.sync.SyncResult
import app.manman.teacher.sync.SyncWorker
import app.manman.teacher.recognition.NoticeSuggestion
import app.manman.teacher.recognition.ScheduleSuggestion
import app.manman.teacher.recognition.ScheduleProgressUpdate
import app.manman.teacher.recognition.SchedulePhase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.time.LocalDate

data class ScheduleProgressState(val update: ScheduleProgressUpdate, val startedAt: Long)

data class MainUiState(
    val restoring: Boolean = true,
    val accountId: String? = null,
    val verification: VerificationRequest? = null,
    val busy: Boolean = false,
    val error: String? = null,
    val message: String? = null,
    val students: List<Student> = emptyList(),
    val leaves: List<Leave> = emptyList(),
    val markings: List<MarkingRecord> = emptyList(),
    val menuWeeks: List<MenuWeek> = emptyList(),
    val attachments: List<AttachmentMeta> = emptyList(),
    val imageDrafts: Map<String, List<AttachmentMeta>> = emptyMap(),
    val timeSlots: List<TimeSlot> = emptyList(),
    val lessons: List<Lesson> = emptyList(),
    val scheduleProfile: ScheduleProfile = ScheduleProfile(),
    val notices: List<Notice> = emptyList(),
    val tasks: List<WorkTask> = emptyList(),
    val noticeSuggestion: NoticeSuggestion? = null,
    val scheduleSuggestion: ScheduleSuggestion? = null,
    val scheduleSourceUris: List<String> = emptyList(),
    val scheduleProgress: ScheduleProgressState? = null,
    val lessonReminderMinutes: Int? = null,
    val pendingCount: Int = 0,
    val conflicts: List<LocalRecord> = emptyList(),
    val unreadableCount: Int = 0,
    val syncPhase: SyncPhase = SyncPhase.IDLE,
)

class MainViewModel(private val application: ManmanApplication) : ViewModel() {
    private val auth = application.auth
    private val repository = ClassRepository(application.database)
    private val mutableState = MutableStateFlow(MainUiState())
    private var manualSync: Job? = null
    private var reminderRefresh: Job? = null
    private var scheduleJob: Job? = null
    val state: StateFlow<MainUiState> = mutableState.asStateFlow()

    init {
        viewModelScope.launch {
            auth.restore()
            mutableState.update {
                it.copy(restoring = false, error = if (auth.sessionReadError) "无法读取本机登录凭证，请重新登录。" else null)
            }
            auth.session.map { it?.accountId }.distinctUntilChanged().collectLatest { accountId ->
                if (accountId == null) {
                    mutableState.update { MainUiState(restoring = false, error = it.error, message = it.message) }
                    refreshReminders()
                } else {
                    mutableState.update { it.copy(accountId = accountId, verification = null, error = null) }
                    SyncWorker.enqueue(application, accountId)
                    var reminderRecords: List<Triple<String, String, String>>? = null
                    repository.observe(accountId).collect { records ->
                        updateRecords(records)
                        val relevant = records.filter { !it.deleted && it.kind in setOf(RecordKind.TASK, RecordKind.LESSON, RecordKind.TIME_SLOT, RecordKind.SETTINGS) }
                            .map { Triple(it.kind, it.id, it.payload) }.sortedBy { it.first + it.second }
                        if (relevant != reminderRecords) {
                            reminderRecords = relevant
                            refreshReminders()
                        }
                    }
                }
            }
        }
        viewModelScope.launch {
            application.syncEngine.phase.collectLatest { phase -> mutableState.update { it.copy(syncPhase = phase) } }
        }
    }

    fun clearMessage() = mutableState.update { it.copy(error = null, message = null) }
    fun reportMessage(message: String) = mutableState.update { it.copy(error = message) }
    fun resetVerification() = mutableState.update { it.copy(verification = null, error = null) }

    fun requestCode(phone: String) = perform {
        val request = auth.requestCode(phone)
        mutableState.update { it.copy(verification = request, message = "验证码已发送，请查看短信。") }
    }

    fun verifyCode(code: String) = perform {
        val request = state.value.verification ?: throw IllegalArgumentException("请先获取验证码。")
        auth.signIn(request, code)
    }

    fun signOut() = perform {
        val success = auth.signOut()
        mutableState.update { it.copy(message = if (success) "已退出登录。" else "已退出本机登录，云端会话将在到期后失效。") }
    }

    fun addStudents(names: List<String>, onDone: () -> Unit) = perform {
        val account = requireAccount()
        val position = (state.value.students.maxOfOrNull(Student::position) ?: -1) + 1
        repository.addStudents(account, names, position)
        onDone()
        mutableState.update { it.copy(message = "学生名单已保存。") }
    }

    fun saveStudent(student: Student, onDone: () -> Unit = {}) = perform {
        repository.saveStudent(requireAccount(), student)
        onDone()
        mutableState.update { it.copy(message = "学生信息已保存。") }
    }

    fun saveLeave(leave: Leave, onDone: () -> Unit = {}) = perform {
        repository.saveLeave(requireAccount(), leave)
        onDone()
        mutableState.update { it.copy(message = if (leave.cancelled) "请假已撤销。" else "请假已记录。") }
    }

    fun deleteCancelledLeave(id: String, onDone: () -> Unit) = perform {
        repository.deleteCancelledLeave(requireAccount(), id)
        onDone()
        mutableState.update { it.copy(message = "请假记录已删除。") }
    }

    fun saveMarking(record: MarkingRecord, onDone: () -> Unit) = perform {
        repository.saveMarking(requireAccount(), record)
        onDone()
        mutableState.update { it.copy(message = "班级标记已保存。") }
    }

    fun deleteMarking(record: MarkingRecord, onDone: () -> Unit) = perform {
        repository.deleteMarking(requireAccount(), record)
        onDone()
        mutableState.update { it.copy(message = "班级标记已删除。") }
    }

    fun bindRecognition(code: String, onDone: () -> Unit) = perform {
        application.recognition.bind(requireAccount(), code)
        onDone()
        mutableState.update { it.copy(message = "识别服务已绑定当前账号。") }
    }

    fun recognizeNotice(notice: Notice, onDone: () -> Unit) = perform {
        val result = application.recognition.notice(requireAccount(), notice.id, notice.content, notice.attachmentIds)
        mutableState.update { it.copy(noticeSuggestion = result) }
        onDone()
    }

    fun consumeNoticeSuggestion(index: Int) = mutableState.update { current ->
        val suggestion = current.noticeSuggestion ?: return@update current
        current.copy(noticeSuggestion = suggestion.copy(items = suggestion.items.mapIndexed { itemIndex, item ->
            if (itemIndex == index) item.copy(consumed = true) else item
        }))
    }

    fun clearNoticeSuggestion() = mutableState.update { it.copy(noticeSuggestion = null) }

    fun recognizeSchedule(uris: List<Uri>, onDone: () -> Unit) {
        if (state.value.busy) return
        val startedAt = SystemClock.elapsedRealtime()
        mutableState.update { it.copy(busy = true, error = null, message = null, scheduleSuggestion = null, scheduleSourceUris = uris.map(Uri::toString),
            scheduleProgress = ScheduleProgressState(ScheduleProgressUpdate(SchedulePhase.PREPARING), startedAt)) }
        scheduleJob = viewModelScope.launch {
            try {
                val account = requireAccount()
                val suggestion = application.scheduleRecognition.recognize(application, account, uris) { progress ->
                    mutableState.update { current ->
                        if (current.accountId == account) current.copy(scheduleProgress = ScheduleProgressState(progress, startedAt)) else current
                    }
                }
                require(state.value.accountId == account) { "账号已变化，请重新识别。" }
                mutableState.update { it.copy(scheduleSuggestion = suggestion) }
                onDone()
            } catch (error: CancellationException) {
                mutableState.update { it.copy(message = "已停止本次识别。") }
                throw error
            } catch (error: AuthException) {
                mutableState.update { it.copy(error = error.message ?: "请重新登录后识别。") }
            } catch (error: IllegalArgumentException) {
                mutableState.update { it.copy(error = error.message ?: "请检查选择的图片。") }
            } catch (_: Exception) {
                mutableState.update { it.copy(error = "识别暂时未完成，请稍后重试。") }
            } finally {
                mutableState.update { it.copy(busy = false, scheduleProgress = null) }
            }
        }
    }

    fun cancelScheduleRecognition() { scheduleJob?.cancel() }

    fun clearScheduleSuggestion() = mutableState.update { it.copy(scheduleSuggestion = null, scheduleSourceUris = emptyList()) }

    fun updateScheduleSuggestion(suggestion: ScheduleSuggestion) = mutableState.update {
        it.copy(scheduleSuggestion = suggestion)
    }

    fun saveRecognizedSchedule(suggestion: ScheduleSuggestion, replace: Boolean, onDone: () -> Unit) = perform {
        repository.saveRecognizedSchedule(requireAccount(), suggestion, replace)
        mutableState.update { it.copy(scheduleSuggestion = null, scheduleSourceUris = emptyList(), message = "课程与作息已保存。") }
        onDone()
    }

    fun stageImages(draftId: String, uris: List<Uri>) = perform {
        val existing = state.value.imageDrafts[draftId].orEmpty()
        require(existing.size + uris.size <= 5) { "最多选择 5 张图片。" }
        val staged = mutableListOf<AttachmentMeta>()
        try {
            uris.forEach { staged.add(application.attachments.stage(it)) }
        } catch (error: Exception) {
            staged.forEach { application.attachments.discard(it) }
            throw error
        }
        mutableState.update { it.copy(imageDrafts = it.imageDrafts + (draftId to (existing + staged))) }
    }

    fun discardImage(draftId: String, image: AttachmentMeta) {
        mutableState.update { it.copy(imageDrafts = it.imageDrafts + (draftId to it.imageDrafts[draftId].orEmpty().filterNot { item -> item.id == image.id })) }
        viewModelScope.launch { application.attachments.discard(image) }
    }

    fun discardDraft(draftId: String) {
        val images = state.value.imageDrafts[draftId].orEmpty()
        mutableState.update { it.copy(imageDrafts = it.imageDrafts - draftId) }
        viewModelScope.launch { images.forEach { image -> application.attachments.discard(image) } }
    }

    fun saveMenuImage(start: LocalDate, uri: Uri) = perform {
        val account = requireAccount()
        val image = application.attachments.stage(uri)
        try {
            application.attachments.adopt(account, image)
            repository.saveMenuWeek(account, start, image)
        } finally {
            application.attachments.discard(image)
        }
        mutableState.update { it.copy(message = "本周菜单已保存。") }
    }

    fun saveTimeSlot(slot: TimeSlot, onDone: () -> Unit) = perform {
        repository.saveTimeSlot(requireAccount(), slot)
        onDone()
        mutableState.update { it.copy(message = "作息时间已保存。") }
    }

    fun saveLesson(lesson: Lesson, onDone: () -> Unit) = perform {
        repository.saveLesson(requireAccount(), lesson)
        onDone()
        mutableState.update { it.copy(message = "课程已保存。") }
    }

    fun saveScheduleProfile(profile: ScheduleProfile, onDone: () -> Unit) = perform {
        repository.saveScheduleProfile(requireAccount(), profile)
        onDone()
        mutableState.update { it.copy(message = "课表信息已保存。") }
    }

    fun deleteLesson(lesson: Lesson, onDone: () -> Unit) = perform {
        repository.deleteLesson(requireAccount(), lesson)
        onDone()
        mutableState.update { it.copy(message = "课程已移除。") }
    }

    fun saveNotice(notice: Notice, draftId: String, onDone: () -> Unit) = perform {
        val account = requireAccount()
        val images = state.value.imageDrafts[draftId].orEmpty()
        images.forEach { application.attachments.adopt(account, it) }
        repository.saveNotice(account, notice, images)
        discardDraft(draftId)
        onDone()
        mutableState.update { it.copy(message = "通知已保存。") }
    }

    fun saveTask(task: WorkTask, onDone: () -> Unit = {}) = perform {
        repository.saveTask(requireAccount(), task)
        onDone()
        mutableState.update { it.copy(message = if (task.done) "待办已完成。" else "待办已保存。") }
    }

    fun reorderTasks(day: LocalDate?, ids: List<String>) = viewModelScope.launch {
        try {
            val account = requireAccount()
            repository.reorderTasks(account, day, ids)
            SyncWorker.enqueue(application, account)
        } catch (error: CancellationException) {
            throw error
        } catch (error: IllegalArgumentException) {
            mutableState.update { it.copy(error = error.message ?: "待办顺序未保存。") }
        } catch (_: Exception) {
            mutableState.update { it.copy(error = "待办顺序未保存，请稍后重试。") }
        }
    }

    fun setTaskDone(id: String, done: Boolean) = viewModelScope.launch {
        try {
            val account = requireAccount()
            repository.setTaskDone(account, id, done)
            SyncWorker.enqueue(application, account)
        } catch (error: CancellationException) {
            throw error
        } catch (error: IllegalArgumentException) {
            mutableState.update { it.copy(error = error.message ?: "待办状态未保存。") }
        } catch (_: Exception) {
            mutableState.update { it.copy(error = "待办状态未保存，请稍后重试。") }
        }
    }

    fun deleteTask(task: WorkTask, onDone: () -> Unit) = perform {
        repository.deleteTask(requireAccount(), task)
        onDone()
        mutableState.update { it.copy(message = "待办已删除。") }
    }

    fun saveLessonReminder(minutes: Int?) = perform {
        repository.saveLessonReminder(requireAccount(), minutes)
        mutableState.update { it.copy(message = "课程提醒设置已更新。") }
    }

    fun resolveConflict(record: LocalRecord, keepLocal: Boolean) = perform {
        repository.resolveConflict(requireAccount(), record, keepLocal)
    }

    fun syncNow() {
        val accountId = state.value.accountId ?: return
        if (manualSync?.isActive == true) return
        manualSync = viewModelScope.launch {
            val result = application.syncEngine.sync(accountId)
            when (result) {
                SyncResult.SUCCESS -> mutableState.update { it.copy(message = "同步已完成。") }
                SyncResult.RETRY -> {
                    SyncWorker.enqueue(application, accountId)
                    mutableState.update { it.copy(message = "同步未完成，更改仍保存在本机，请查看同步状态。") }
                }
                SyncResult.SIGNED_OUT -> mutableState.update {
                    it.copy(message = "登录状态需要验证，本机更改仍然保留。")
                }
            }
        }
    }

    fun refreshReminders() {
        reminderRefresh?.cancel()
        reminderRefresh = viewModelScope.launch {
            try {
                application.reminders.reschedule()
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                mutableState.update { it.copy(error = "提醒暂时无法更新，请稍后重试。") }
            }
        }
    }

    private fun requireAccount(): String = state.value.accountId ?: throw IllegalStateException("请先登录。")

    private fun perform(block: suspend () -> Unit) {
        if (state.value.busy) return
        mutableState.update { it.copy(busy = true, error = null, message = null) }
        viewModelScope.launch {
            try {
                block()
                auth.session.value?.accountId?.let { accountId ->
                    if (repository.pendingCount(accountId) > 0) SyncWorker.enqueue(application, accountId)
                }
            } catch (error: AuthException) {
                mutableState.update { it.copy(error = error.message ?: "操作未完成。") }
            } catch (error: IllegalArgumentException) {
                mutableState.update { it.copy(error = error.message ?: "请检查填写内容。") }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                mutableState.update { it.copy(error = "操作暂未完成，请检查网络或稍后重试。") }
            } finally {
                mutableState.update { it.copy(busy = false) }
            }
        }
    }

    private fun updateRecords(records: List<LocalRecord>) {
        var unreadable = 0
        val visible = records.filterNot(LocalRecord::deleted)
        fun <T> decode(kind: String, parser: (LocalRecord) -> T): List<T> = visible
            .filter { it.kind == kind }
            .mapNotNull { record ->
                try { parser(record) } catch (_: Exception) { unreadable++; null }
            }
        val students = decode(RecordKind.STUDENT, RecordCodec::student).sortedBy(Student::position)
        val leaves = decode(RecordKind.LEAVE, RecordCodec::leave)
        val markings = decode(RecordKind.MARKING, RecordCodec::marking).sortedByDescending(MarkingRecord::createdAt)
        val menuWeeks = decode(RecordKind.MENU_WEEK, RecordCodec::menuWeek)
        val attachments = decode(RecordKind.ATTACHMENT, RecordCodec::attachment)
        val timeSlots = decode(RecordKind.TIME_SLOT, RecordCodec::timeSlot).sortedBy(TimeSlot::start)
        val lessons = decode(RecordKind.LESSON, RecordCodec::lesson)
        val notices = decode(RecordKind.NOTICE, RecordCodec::notice).sortedByDescending(Notice::createdAt)
        val tasks = decode(RecordKind.TASK, RecordCodec::task).sortedByDescending(WorkTask::createdAt)
        val reminderSettings = visible.firstOrNull { it.kind == RecordKind.SETTINGS && it.id == "reminders" }?.let {
            try { JSONObject(it.payload) } catch (_: Exception) { unreadable++; null }
        }
        val lessonReminder = reminderSettings?.optInt("lessonMinutes", -1)?.takeIf { it in 0..60 }
        val scheduleProfile = visible.firstOrNull { it.kind == RecordKind.SETTINGS && it.id == "schedule_profile" }?.let {
            try { RecordCodec.scheduleProfile(it) } catch (_: Exception) { unreadable++; null }
        } ?: ScheduleProfile()
        mutableState.update { it.copy(
            students = students, leaves = leaves, markings = markings,
            menuWeeks = menuWeeks, attachments = attachments,
            timeSlots = timeSlots, lessons = lessons, scheduleProfile = scheduleProfile, notices = notices, tasks = tasks,
            lessonReminderMinutes = lessonReminder,
            pendingCount = records.count(LocalRecord::dirty),
            conflicts = records.filter { record -> record.conflictVersion != null }, unreadableCount = unreadable,
        ) }
    }

    companion object {
        fun factory(application: ManmanApplication): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = MainViewModel(application) as T
        }
    }
}
