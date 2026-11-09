package app.manman.teacher.data

import androidx.room.withTransaction
import app.manman.teacher.domain.Leave
import app.manman.teacher.domain.Student
import app.manman.teacher.domain.TaskOrdering
import app.manman.teacher.recognition.ScheduleSuggestion
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

    suspend fun deleteCancelledLeave(accountId: String, id: String) {
        database.withTransaction {
            val current = requireNotNull(dao.find(accountId, RecordKind.LEAVE, id)) { "请假记录不存在。" }
            require(!current.deleted && current.conflictVersion == null) { "请假已删除或存在同步冲突。" }
            require(RecordCodec.leave(current).cancelled) { "只有已撤销的请假可以删除。" }
            dao.saveLocal(accountId, RecordKind.LEAVE, id, current.payload, deleted = true)
        }
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
        require(slot.weekdays.all { it in 1..7 }) { "作息的星期无效。" }
        database.withTransaction {
            val records = dao.snapshot(accountId).filter { !it.deleted }
            val current = records.firstOrNull { it.kind == RecordKind.TIME_SLOT && it.id == slot.id }?.let(RecordCodec::timeSlot)
            val slots = records.filter { it.kind == RecordKind.TIME_SLOT && it.id != slot.id }.map(RecordCodec::timeSlot)
            val lessons = records.filter { it.kind == RecordKind.LESSON }.map(RecordCodec::lesson)
            require(lessons.none { (it.slotId == slot.id || it.endSlotId == slot.id) && slot.weekdays.isNotEmpty() && it.weekday !in slot.weekdays }) {
                "已有课程使用该星期的节次，请先调整课程。"
            }
            if (current == null || current.start != slot.start || current.end != slot.end || current.weekdays != slot.weekdays) {
                val overlap = slots.firstOrNull { other ->
                    (slot.weekdays.isEmpty() || other.weekdays.isEmpty() || slot.weekdays.any { it in other.weekdays }) &&
                        slot.start < other.end && other.start < slot.end
                }
                require(overlap == null) { "作息时间与「${overlap?.title}」重叠，请先调整。" }
            }
            val affected = lessons.filter { it.slotId == slot.id || it.endSlotId == slot.id }.map(Lesson::id).toSet()
            validateSpannedLessons(lessons, slots + slot, affected)
            dao.saveLocal(accountId, RecordKind.TIME_SLOT, slot.id, RecordCodec.timeSlot(slot.copy(title = slot.title.trim())))
        }
    }

    suspend fun saveLesson(accountId: String, lesson: Lesson) {
        require(lesson.weekday in 1..7 && lesson.slotId.isNotBlank()) { "请选择星期和节次。" }
        require(lesson.subject.isNotBlank() && lesson.subject.length <= 50) { "请填写不超过 50 个字的课程名称。" }
        require(listOf(lesson.teachers, lesson.className, lesson.location, lesson.role).all { it.length <= 80 } &&
            lesson.note.length <= 500 && lesson.preparation.length <= 500) { "课程附加信息过长，请精简后保存。" }
        require(lesson.reminderMinutes == null || lesson.reminderMinutes in -1..60) { "提醒时间无效。" }
        database.withTransaction {
            val records = dao.snapshot(accountId).filter { !it.deleted }
            val slots = records.filter { it.kind == RecordKind.TIME_SLOT }.map(RecordCodec::timeSlot)
            val start = slots.firstOrNull { it.id == lesson.slotId && (it.weekdays.isEmpty() || lesson.weekday in it.weekdays) }
            require(start != null) { "所选星期没有这个节次，请先调整作息。" }
            val lessons = records.filter { it.kind == RecordKind.LESSON && it.id != lesson.id }.map(RecordCodec::lesson) + lesson
            require(lessons.count { it.weekday == lesson.weekday && it.slotId == lesson.slotId } == 1) { "这个节次已有课程，请先编辑原课程。" }
            validateSpannedLessons(lessons, slots, setOf(lesson.id))
            dao.saveLocal(accountId, RecordKind.LESSON, lesson.id, RecordCodec.lesson(lesson.copy(subject = lesson.subject.trim())))
        }
    }

    private fun validateSpannedLessons(lessons: List<Lesson>, slots: List<TimeSlot>, affected: Set<String>) {
        val byId = slots.associateBy(TimeSlot::id)
        fun end(lesson: Lesson): java.time.LocalTime? {
            val start = byId[lesson.slotId] ?: return null
            val last = if (lesson.endSlotId == null) start else byId[lesson.endSlotId]
            if (last == null || (last.weekdays.isNotEmpty() && lesson.weekday !in last.weekdays) || last.end < start.end) {
                require(lesson.id !in affected) { "跨节结束节次无效，请重新选择。" }
                return null
            }
            return last.end
        }
        lessons.filter { it.endSlotId != null }.forEach { lesson ->
            val start = byId[lesson.slotId] ?: return@forEach
            val finish = end(lesson) ?: return@forEach
            val overlapping = lessons.firstOrNull { other ->
                if (other.id == lesson.id || other.weekday != lesson.weekday || (lesson.id !in affected && other.id !in affected)) false
                else {
                    val otherStart = byId[other.slotId]
                    val otherEnd = end(other)
                    otherStart != null && otherEnd != null && start.start < otherEnd && otherStart.start < finish
                }
            }
            require(overlapping == null) { "跨节事项与「${overlapping?.subject}」重叠，请先调整。" }
        }
    }

    suspend fun saveScheduleProfile(accountId: String, profile: ScheduleProfile) {
        require(listOf(profile.school, profile.term, profile.className, profile.myName).all { it.length <= 80 } && profile.notes.length <= 1000) {
            "课表信息过长，请精简后保存。"
        }
        dao.saveLocal(accountId, RecordKind.SETTINGS, "schedule_profile", RecordCodec.scheduleProfile(profile))
    }

    suspend fun deleteLesson(accountId: String, lesson: Lesson) {
        dao.saveLocal(accountId, RecordKind.LESSON, lesson.id, RecordCodec.lesson(lesson), deleted = true)
    }

    suspend fun saveRecognizedSchedule(accountId: String, suggestion: ScheduleSuggestion, replace: Boolean) {
        database.withTransaction {
            val existing = dao.snapshot(accountId).filter { !it.deleted && it.kind in setOf(RecordKind.TIME_SLOT, RecordKind.LESSON) }
            require(existing.none { it.conflictVersion != null }) { "请先处理课程与作息的同步冲突。" }
            val oldSlots = existing.filter { it.kind == RecordKind.TIME_SLOT }.map(RecordCodec::timeSlot)
            val oldLessons = existing.filter { it.kind == RecordKind.LESSON }.map(RecordCodec::lesson)
            val slots = if (replace) mutableListOf() else oldSlots.toMutableList()
            val lessons = if (replace) mutableListOf() else oldLessons.toMutableList()
            val created = mutableListOf<TimeSlot>()
            val modified = mutableListOf<Lesson>()
            val seenSlots = mutableSetOf<Pair<Int, String>>()
            val seenLessons = mutableSetOf<Pair<Int, String>>()
            require(suggestion.slots.isNotEmpty() || suggestion.lessons.isNotEmpty()) { "没有可保存的课程或作息。" }
            suggestion.slots.forEach { item ->
                val title = item.title.trim()
                val start = item.start
                val end = item.end
                require(item.weekday in 1..7 && title.isNotEmpty() && title.length <= 30 && start != null && end != null && start < end) {
                    "请补全每个作息的星期、名称和正确的开始结束时间。"
                }
                require(seenSlots.add(item.weekday to title)) { "星期${item.weekday}的「$title」重复，请先修改。" }
                val matches = slots.filter { it.title == title && (it.weekdays.isEmpty() || item.weekday in it.weekdays) }
                require(matches.size <= 1) { "星期${item.weekday}已有多个「$title」，请先调整。" }
                val current = matches.firstOrNull()
                require(current == null || (current.start == start && current.end == end)) { "星期${item.weekday}的「$title」时间不同，请先调整，或选择替换全部。" }
                if (current == null) {
                    val slot = TimeSlot(UUID.randomUUID().toString(), title, start, end, setOf(item.weekday))
                    slots.add(slot)
                    created.add(slot)
                }
            }
            created.forEach { candidate ->
                val day = candidate.weekdays.single()
                val overlap = slots.firstOrNull { other ->
                    other.id != candidate.id && (other.weekdays.isEmpty() || day in other.weekdays) &&
                        candidate.start < other.end && other.start < candidate.end
                }
                require(overlap == null) { "星期${day}的「${candidate.title}」与「${overlap?.title}」时间重叠，请先核对。" }
            }
            suggestion.lessons.forEach { item ->
                val title = item.slot.trim()
                val subject = item.subject.trim()
                require(item.weekday in 1..7 && title.isNotEmpty() && subject.isNotEmpty() && subject.length <= 50) { "请补全每项课程。" }
                require(seenLessons.add(item.weekday to title)) { "星期${item.weekday}的「$title」有重复课程，请先修改。" }
                val matches = slots.filter { it.title == title && (it.weekdays.isEmpty() || item.weekday in it.weekdays) }
                require(matches.size == 1) { "星期${item.weekday}的「$title」找不到唯一作息，请导入作息表或补充时间。" }
                val slot = matches.single()
                val currentLessons = lessons.filter { it.weekday == item.weekday && it.slotId == slot.id }
                require(currentLessons.size <= 1) { "星期${item.weekday}的「$title」已有多个课程，请先处理。" }
                val current = currentLessons.firstOrNull()
                val lesson = current?.copy(subject = subject) ?: Lesson(UUID.randomUUID().toString(), item.weekday, slot.id, subject)
                lessons.removeAll { it.id == lesson.id }
                lessons.add(lesson)
                modified.add(lesson)
            }
            validateSpannedLessons(lessons, slots, modified.map(Lesson::id).toSet())
            if (replace) existing.forEach { dao.saveLocal(accountId, it.kind, it.id, it.payload, deleted = true) }
            created.forEach { dao.saveLocal(accountId, RecordKind.TIME_SLOT, it.id, RecordCodec.timeSlot(it)) }
            modified.forEach { dao.saveLocal(accountId, RecordKind.LESSON, it.id, RecordCodec.lesson(it)) }
        }
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
