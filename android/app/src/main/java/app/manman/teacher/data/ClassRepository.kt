package app.manman.teacher.data

import androidx.room.withTransaction
import app.manman.teacher.domain.Leave
import app.manman.teacher.domain.LunchStudent
import app.manman.teacher.domain.MealOverride
import app.manman.teacher.domain.Student
import app.manman.teacher.recognition.ScheduleSuggestion
import kotlinx.coroutines.flow.Flow
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
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

    suspend fun saveMealOverride(accountId: String, day: LocalDate, studentId: String, value: MealOverride?) {
        val id = UUID.nameUUIDFromBytes("meal:$day:$studentId".toByteArray(Charsets.UTF_8)).toString()
        val record = MealOverrideRecord(id, day, studentId, value ?: MealOverride.EAT)
        dao.saveLocal(accountId, RecordKind.MEAL_OVERRIDE, id, RecordCodec.mealOverride(record), deleted = value == null)
    }

    suspend fun confirmLunch(accountId: String, day: LocalDate, students: List<LunchStudent>) {
        val review = LunchReview(UUID.randomUUID().toString(), day, Instant.now(), students)
        dao.saveLocal(accountId, RecordKind.LUNCH_REVIEW, review.id, RecordCodec.lunchReview(review))
    }

    suspend fun saveMarking(accountId: String, record: MarkingRecord) {
        require(record.title.isNotBlank() && record.firstLabel.isNotBlank() && record.secondLabel.isNotBlank()) {
            "请填写事项名称和两种状态。"
        }
        dao.saveLocal(accountId, RecordKind.MARKING, record.id, RecordCodec.marking(record))
    }

    suspend fun saveMenu(accountId: String, day: LocalDate, dishes: String) {
        val content = dishes.trim()
        require(content.length <= 3000) { "一天的菜单不能超过 3000 个字。" }
        val id = stableId("menu:$day")
        if (content.isNotEmpty() || dao.find(accountId, RecordKind.MENU, id) != null) {
            dao.saveLocal(accountId, RecordKind.MENU, id, RecordCodec.menu(MenuDay(id, day, content)), deleted = content.isEmpty())
        }
    }

    suspend fun saveMenus(accountId: String, start: LocalDate, entries: Map<LocalDate, String>) {
        require(start.dayOfWeek.value == 1 && entries.keys.all { !it.isBefore(start) && it.isBefore(start.plusDays(7)) }) {
            "菜单日期不在所选的一周内。"
        }
        require(entries.isNotEmpty() && entries.values.all { it.isNotBlank() && it.length <= 3000 }) { "请至少填写一天的菜单。" }
        database.withTransaction {
            entries.forEach { (day, dishes) ->
                val id = stableId("menu:$day")
                dao.saveLocal(accountId, RecordKind.MENU, id, RecordCodec.menu(MenuDay(id, day, dishes.trim())))
            }
        }
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
            suggestion.lessons.forEach { item ->
                val title = item.slot.trim()
                val subject = item.subject.trim()
                require(item.weekday in 1..7 && title.isNotEmpty() && subject.isNotEmpty() && subject.length <= 50) { "请补全每项课程。" }
                require(seenLessons.add(item.weekday to title)) { "星期${item.weekday}的「$title」有重复课程，请先修改。" }
                val matches = slots.filter { it.title == title && (it.weekdays.isEmpty() || item.weekday in it.weekdays) }
                require(matches.size == 1) { "星期${item.weekday}的「$title」找不到唯一作息，请导入作息表或补充时间。" }
                val slot = matches.single()
                val current = lessons.firstOrNull { it.weekday == item.weekday && it.slotId == slot.id }
                val lesson = Lesson(current?.id ?: UUID.randomUUID().toString(), item.weekday, slot.id, subject)
                lessons.removeAll { it.id == lesson.id }
                lessons.add(lesson)
                modified.add(lesson)
            }
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

    suspend fun saveCutoff(accountId: String, cutoff: LocalTime) {
        val payload = JSONObject().put("lunchCutoff", cutoff.toString()).toString()
        dao.saveLocal(accountId, RecordKind.SETTINGS, "main", payload)
    }

    suspend fun saveLessonReminder(accountId: String, minutes: Int?) {
        require(minutes == null || minutes in 0..60) { "提醒时间设置无效。" }
        database.withTransaction {
            val current = dao.find(accountId, RecordKind.SETTINGS, "reminders")
            val payload = current?.let { JSONObject(it.payload) } ?: JSONObject()
            dao.saveLocal(accountId, RecordKind.SETTINGS, "reminders", payload.put("lessonMinutes", minutes ?: -1).toString())
        }
    }

    suspend fun saveLunchReminder(accountId: String, enabled: Boolean) {
        database.withTransaction {
            val current = dao.find(accountId, RecordKind.SETTINGS, "reminders")
            val payload = current?.let { JSONObject(it.payload) } ?: JSONObject()
            dao.saveLocal(accountId, RecordKind.SETTINGS, "reminders", payload.put("lunchEnabled", enabled).toString())
        }
    }

    suspend fun resolveConflict(accountId: String, record: LocalRecord, keepLocal: Boolean) {
        dao.resolveConflict(accountId, record.kind, record.id, keepLocal)
    }

    private fun stableId(value: String): String = UUID.nameUUIDFromBytes(value.toByteArray(Charsets.UTF_8)).toString()
}
