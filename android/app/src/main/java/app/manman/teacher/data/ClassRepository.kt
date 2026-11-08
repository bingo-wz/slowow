package app.manman.teacher.data

import androidx.room.withTransaction
import app.manman.teacher.domain.Leave
import app.manman.teacher.domain.Student
import app.manman.teacher.domain.TaskOrdering
import kotlinx.coroutines.flow.Flow
import org.json.JSONObject
import java.time.LocalDate
import java.util.UUID

class ClassRepository(private val database: ManmanDatabase) {
    private val dao = database.records()
    fun observe(accountId: String): Flow<List<LocalRecord>> = dao.observeRecords(accountId)
    suspend fun pendingCount(accountId: String): Int = dao.pendingCount(accountId)

    suspend fun addStudents(accountId: String, names: List<String>, startPosition: Int) {
        val cleaned = names.map(String::trim).filter(String::isNotEmpty)
        require(cleaned.isNotEmpty()) { "请先输入学生姓名。" }
        require(cleaned.all { it.length <= 50 }) { "姓名不能超过 50 个字。" }
        database.withTransaction {
            cleaned.forEachIndexed { index, name ->
                val student = Student(UUID.randomUUID().toString(), name, position = startPosition + index)
                dao.saveLocal(accountId, RecordKind.STUDENT, student.id, RecordCodec.student(student))
            }
        }
    }

    suspend fun saveStudent(accountId: String, student: Student) {
        require(student.name.isNotBlank() && student.name.length <= 50) { "请输入不超过 50 个字的姓名。" }
        dao.saveLocal(accountId, RecordKind.STUDENT, student.id, RecordCodec.student(student.copy(name = student.name.trim())))
    }

    suspend fun saveLeave(accountId: String, leave: Leave) {
        require(!leave.end.isBefore(leave.start)) { "结束日期不能早于开始日期。" }
        require(leave.end.toEpochDay() - leave.start.toEpochDay() <= 366) { "一次请假最多记录 367 天。" }
        dao.saveLocal(accountId, RecordKind.LEAVE, leave.id, RecordCodec.leave(leave))
    }

    suspend fun saveMarking(accountId: String, record: MarkingRecord) {
        require(record.title.isNotBlank() && record.firstLabel.isNotBlank() && record.secondLabel.isNotBlank()) {
            "请填写事项名称和两种状态。"
        }
        dao.saveLocal(accountId, RecordKind.MARKING, record.id, RecordCodec.marking(record))
    }

    suspend fun deleteMarking(accountId: String, record: MarkingRecord) {
        dao.saveLocal(accountId, RecordKind.MARKING, record.id, RecordCodec.marking(record), deleted = true)
    }

    suspend fun saveMenuWeek(accountId: String, start: LocalDate, image: AttachmentMeta) {
        require(start.dayOfWeek.value == 1) { "一周应从周一开始。" }
        val id = stableId("menu-week:$start")
        database.withTransaction {
            dao.saveLocal(accountId, RecordKind.ATTACHMENT, image.id, RecordCodec.attachment(image))
            dao.saveLocal(accountId, RecordKind.MENU_WEEK, id, RecordCodec.menuWeek(MenuWeek(id, start, image.id)))
        }
    }

    suspend fun saveTimeSlot(accountId: String, slot: TimeSlot) {
        require(slot.title.isNotBlank() && slot.title.length <= 30) { "请填写不超过 30 个字的节次名称。" }
        require(slot.start < slot.end) { "结束时间应晚于开始时间。" }
        dao.saveLocal(accountId, RecordKind.TIME_SLOT, slot.id, RecordCodec.timeSlot(slot.copy(title = slot.title.trim())))
    }

    suspend fun saveLesson(accountId: String, lesson: Lesson) {
        require(lesson.weekday in 1..7 && lesson.slotId.isNotBlank()) { "请选择星期和节次。" }
        require(lesson.subject.isNotBlank() && lesson.subject.length <= 50) { "请填写不超过 50 个字的课程名称。" }
        dao.saveLocal(accountId, RecordKind.LESSON, lesson.id, RecordCodec.lesson(lesson.copy(subject = lesson.subject.trim())))
    }

    suspend fun deleteLesson(accountId: String, lesson: Lesson) {
        dao.saveLocal(accountId, RecordKind.LESSON, lesson.id, RecordCodec.lesson(lesson), deleted = true)
    }

    suspend fun saveNotice(accountId: String, notice: Notice, images: List<AttachmentMeta> = emptyList()) {
        require(notice.title.isNotBlank() && notice.title.length <= 80) { "请填写不超过 80 个字的通知标题。" }
        require((notice.content.isNotBlank() || notice.attachmentIds.isNotEmpty()) && notice.content.length <= 10000) { "请填写通知内容或添加图片，文字不超过 10000 个字。" }
        require(notice.attachmentIds.size <= 5) { "一条通知最多添加 5 张图片。" }
        database.withTransaction {
            images.forEach { image -> dao.saveLocal(accountId, RecordKind.ATTACHMENT, image.id, RecordCodec.attachment(image)) }
            dao.saveLocal(accountId, RecordKind.NOTICE, notice.id, RecordCodec.notice(notice.copy(title = notice.title.trim(), content = notice.content.trim())))
        }
    }

    suspend fun saveTask(accountId: String, task: WorkTask) {
        require(task.title.isNotBlank() && task.title.length <= 100) { "请填写不超过 100 个字的待办。" }
        dao.saveLocal(accountId, RecordKind.TASK, task.id, RecordCodec.task(task.copy(title = task.title.trim())))
    }

    suspend fun deleteTask(accountId: String, task: WorkTask) {
        dao.saveLocal(accountId, RecordKind.TASK, task.id, RecordCodec.task(task), deleted = true)
    }

    suspend fun setTaskDone(accountId: String, id: String, done: Boolean) {
        database.withTransaction {
            val record = requireNotNull(dao.find(accountId, RecordKind.TASK, id)) { "待办不存在。" }
            require(!record.deleted && record.conflictVersion == null) { "待办已删除或存在同步冲突。" }
            val task = RecordCodec.task(record)
            if (task.done != done) dao.saveLocal(accountId, RecordKind.TASK, id, RecordCodec.task(task.copy(done = done)))
        }
    }

    suspend fun reorderTasks(accountId: String, day: LocalDate?, orderedIds: List<String>) {
        require(orderedIds.isNotEmpty() && orderedIds.distinct().size == orderedIds.size) { "待办顺序无效。" }
        database.withTransaction {
            val records = dao.snapshot(accountId).filter { it.kind == RecordKind.TASK && !it.deleted }
            val current = records.map { it to RecordCodec.task(it) }.filter { TaskOrdering.date(it.second) == day }
            require(current.none { it.first.conflictVersion != null }) { "请先处理待办的同步冲突。" }
            val byId = current.associateBy { it.second.id }
            require(orderedIds.all(byId::containsKey)) { "待办已发生变化，请重新排序。" }
            val remaining = TaskOrdering.sorted(current.map { it.second }).map { it.id }.filterNot(orderedIds::contains)
            (orderedIds + remaining).forEachIndexed { index, id ->
                val task = byId.getValue(id).second
                if (task.sortOrder != index.toLong()) {
                    dao.saveLocal(accountId, RecordKind.TASK, id, RecordCodec.task(task.copy(sortOrder = index.toLong())))
                }
            }
        }
    }

    suspend fun saveLessonReminder(accountId: String, minutes: Int?) {
        require(minutes == null || minutes in 0..60) { "提醒时间设置无效。" }
        database.withTransaction {
            val current = dao.find(accountId, RecordKind.SETTINGS, "reminders")
            val payload = current?.let { JSONObject(it.payload) } ?: JSONObject()
            dao.saveLocal(accountId, RecordKind.SETTINGS, "reminders", payload.put("lessonMinutes", minutes ?: -1).toString())
        }
    }

    suspend fun resolveConflict(accountId: String, record: LocalRecord, keepLocal: Boolean) {
        dao.resolveConflict(accountId, record.kind, record.id, keepLocal)
    }

    private fun stableId(value: String): String = UUID.nameUUIDFromBytes(value.toByteArray(Charsets.UTF_8)).toString()
}
