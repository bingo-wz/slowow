package app.manman.teacher.data

import app.manman.teacher.domain.Leave
import app.manman.teacher.domain.LeavePeriod
import app.manman.teacher.domain.Student
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

object RecordKind {
    const val STUDENT = "student"
    const val LEAVE = "leave"
    // 保留旧记录类型，以便已有数据继续同步和处理冲突。
    const val MEAL_OVERRIDE = "meal_override"
    const val LUNCH_REVIEW = "lunch_review"
    const val MARKING = "marking"
    // 保留旧版日菜单类型，以兼容已有记录的同步与冲突处理。
    const val MENU = "menu"
    const val MENU_WEEK = "menu_week"
    const val NOTICE = "notice"
    const val TASK = "task"
    const val LESSON = "lesson"
    const val TIME_SLOT = "time_slot"
    const val ATTACHMENT = "attachment"
    const val SETTINGS = "settings"
}

data class MarkingStudent(val studentId: String, val name: String, val first: Boolean)
data class MarkingRecord(
    val id: String,
    val title: String,
    val firstLabel: String,
    val secondLabel: String,
    val createdAt: Instant,
    val students: List<MarkingStudent>,
)
data class MenuWeek(val id: String, val start: LocalDate, val attachmentId: String)
data class AttachmentMeta(val id: String, val mime: String, val size: Long, val sha256: String)
data class TimeSlot(val id: String, val title: String, val start: LocalTime, val end: LocalTime, val weekdays: Set<Int> = emptySet())
enum class ScheduleKind { TEACHING, DUTY, FREE }
data class Lesson(
    val id: String,
    val weekday: Int,
    val slotId: String,
    val subject: String,
    val kind: ScheduleKind? = null,
    val teachers: String = "",
    val className: String = "",
    val location: String = "",
    val role: String = "",
    val note: String = "",
    val preparation: String = "",
    val endSlotId: String? = null,
    val reminderMinutes: Int? = null,
)
data class ScheduleProfile(
    val school: String = "",
    val term: String = "",
    val className: String = "",
    val myName: String = "",
    val termStart: LocalDate? = null,
    val notes: String = "",
)
data class Notice(val id: String, val title: String, val content: String, val createdAt: Instant, val attachmentIds: List<String> = emptyList())
data class WorkTask(
    val id: String,
    val title: String,
    val noticeId: String?,
    val dueAt: Instant?,
    val remindAt: Instant?,
    val done: Boolean,
    val createdAt: Instant,
    val dueDate: LocalDate? = null,
    val sortOrder: Long? = null,
    val endAt: Instant? = null,
    val routineId: String? = null,
    val routineDate: LocalDate? = null,
    val routineException: Boolean = false,
    val removedByRule: Boolean = false,
    val autoCompletionSuppressed: Boolean = false,
    val autoCompleted: Boolean = false,
    val showMealList: Boolean = false,
    val timingNeedsReview: Boolean = false,
    val lessonPeriod: Int? = null,
) : java.io.Serializable

data class RoutineTime(val start: LocalTime? = null, val end: LocalTime? = null) : java.io.Serializable

data class RoutineVersion(
    val effectiveFrom: LocalDate,
    val title: String,
    val weekdays: Set<Int>,
    val start: LocalTime? = null,
    val end: LocalTime? = null,
    val enabled: Boolean = true,
    val showMealList: Boolean = false,
    val weekdayTimes: Map<Int, RoutineTime> = emptyMap(),
) : java.io.Serializable {
    fun timeFor(weekday: Int): RoutineTime = weekdayTimes[weekday] ?: RoutineTime(start, end)
}

data class RoutineRule(val id: String, val createdAt: Instant, val versions: List<RoutineVersion>) : java.io.Serializable {
    val current: RoutineVersion get() = versions.maxBy(RoutineVersion::effectiveFrom)
}

data class TaskMetadata(
    val taskId: String,
    val expectedTitle: String?,
    val expectedDueAt: Instant?,
    val expectedDueDate: LocalDate?,
    val expectedDone: Boolean,
    val endAt: Instant? = null,
    val routineId: String? = null,
    val routineDate: LocalDate? = null,
    val routineException: Boolean = false,
    val removedByRule: Boolean = false,
    val autoCompletionSuppressed: Boolean = false,
    val autoCompleted: Boolean = false,
    val showMealList: Boolean = false,
    val lessonPeriod: Int? = null,
)

object RecordCodec {
    const val ROUTINE_PREFIX = "routine_"
    const val TASK_METADATA_PREFIX = "taskmeta_"

    fun routineRecordId(id: String): String = ROUTINE_PREFIX + id
    fun taskMetadataId(id: String): String = TASK_METADATA_PREFIX +
        UUID.nameUUIDFromBytes("taskmeta:$id".toByteArray(Charsets.UTF_8)).toString()

    fun routine(value: RoutineRule): String = JSONObject().put("type", if (value.versions.any {
        it.weekdayTimes.isNotEmpty()
    }) "routine_v2" else "routine_v1").put("id", value.id)
        .put("createdAt", value.createdAt.toString()).put("versions", JSONArray().apply {
            value.versions.sortedBy(RoutineVersion::effectiveFrom).forEach { version ->
                val entry = JSONObject().put("effectiveFrom", version.effectiveFrom.toString()).put("title", version.title)
                    .put("weekdays", JSONArray(version.weekdays.sorted())).put("start", version.start?.toString() ?: JSONObject.NULL)
                    .put("end", version.end?.toString() ?: JSONObject.NULL).put("enabled", version.enabled)
                    .put("showMealList", version.showMealList)
                if (version.weekdayTimes.isNotEmpty()) entry.put("weekdayTimes", JSONObject().apply {
                    version.weekdayTimes.toSortedMap().forEach { (day, time) ->
                        put(day.toString(), JSONObject().put("start", time.start?.toString() ?: JSONObject.NULL)
                            .put("end", time.end?.toString() ?: JSONObject.NULL))
                    }
                })
                put(entry)
            }
        }).toString()

    fun routine(record: LocalRecord): RoutineRule = JSONObject(record.payload).let { json ->
        val type = json.getString("type")
        require(type == "routine_v1" || type == "routine_v2")
        val versions = json.getJSONArray("versions")
        RoutineRule(json.getString("id"), Instant.parse(json.getString("createdAt")), (0 until versions.length()).map { index ->
            val version = versions.getJSONObject(index)
            val weekdays = version.getJSONArray("weekdays")
            val selectedDays = (0 until weekdays.length()).map(weekdays::getInt).toSet()
            require(selectedDays.isNotEmpty() && selectedDays.all { it in 1..7 } && selectedDays.size == weekdays.length())
            val entries = version.optJSONObject("weekdayTimes")
            require(type == "routine_v2" || entries == null)
            val weekdayTimes = entries?.keys()?.asSequence()?.associate { key ->
                val day = key.toInt()
                require(day in 1..7 && key == day.toString())
                val time = entries.getJSONObject(key)
                val start = if (time.isNull("start")) null else LocalTime.parse(time.getString("start"))
                val end = if (time.isNull("end")) null else LocalTime.parse(time.getString("end"))
                require((start == null && end == null) || (start != null && end != null && end > start))
                day to RoutineTime(start, end)
            }.orEmpty()
            require(weekdayTimes.keys.all { it in selectedDays })
            val start = if (version.isNull("start")) null else LocalTime.parse(version.getString("start"))
            val end = if (version.isNull("end")) null else LocalTime.parse(version.getString("end"))
            require((start == null && end == null) || (start != null && end != null && end > start))
            RoutineVersion(LocalDate.parse(version.getString("effectiveFrom")), version.getString("title"),
                selectedDays, start, end,
                version.optBoolean("enabled", true), version.optBoolean("showMealList", false), weekdayTimes)
        }.also { require(it.isNotEmpty()) })
    }

    fun taskMetadata(value: TaskMetadata): String = JSONObject().put("type", "task_metadata_v1")
        .put("taskId", value.taskId).put("expectedTitle", value.expectedTitle ?: JSONObject.NULL)
        .put("expectedDueAt", value.expectedDueAt?.toString() ?: JSONObject.NULL)
        .put("expectedDueDate", value.expectedDueDate?.toString() ?: JSONObject.NULL).put("expectedDone", value.expectedDone)
        .put("endAt", value.endAt?.toString() ?: JSONObject.NULL).put("routineId", value.routineId ?: JSONObject.NULL)
        .put("routineDate", value.routineDate?.toString() ?: JSONObject.NULL).put("routineException", value.routineException)
        .put("removedByRule", value.removedByRule).put("autoCompletionSuppressed", value.autoCompletionSuppressed)
        .put("autoCompleted", value.autoCompleted).put("showMealList", value.showMealList)
        .apply { value.lessonPeriod?.let { put("lessonPeriod", it) } }.toString()

    fun taskMetadata(record: LocalRecord): TaskMetadata = JSONObject(record.payload).let { json ->
        require(json.getString("type") == "task_metadata_v1")
        fun optional(key: String) = if (json.isNull(key)) null else json.getString(key)
        TaskMetadata(json.getString("taskId"), optional("expectedTitle"), optional("expectedDueAt")?.let(Instant::parse),
            optional("expectedDueDate")?.let(LocalDate::parse), json.optBoolean("expectedDone", false),
            optional("endAt")?.let(Instant::parse), optional("routineId"), optional("routineDate")?.let(LocalDate::parse),
            json.optBoolean("routineException", false), json.optBoolean("removedByRule", false),
            json.optBoolean("autoCompletionSuppressed", false), json.optBoolean("autoCompleted", false),
            json.optBoolean("showMealList", false), json.optInt("lessonPeriod").takeIf { it in 1..12 })
    }

    fun metadataFor(task: WorkTask): TaskMetadata = TaskMetadata(task.id, task.title, task.dueAt, task.dueDate, task.done, task.endAt,
        task.routineId, task.routineDate, task.routineException, task.removedByRule, task.autoCompletionSuppressed,
        task.autoCompleted, task.showMealList, task.lessonPeriod)

    fun withMetadata(task: WorkTask, metadata: TaskMetadata?): WorkTask {
        if (metadata == null || metadata.taskId != task.id) return task
        val timeMatches = metadata.expectedDueAt == task.dueAt && metadata.expectedDueDate == task.dueDate
        return task.copy(endAt = metadata.endAt.takeIf { timeMatches }, routineId = metadata.routineId,
            routineDate = metadata.routineDate, routineException = metadata.routineException ||
                (metadata.routineId != null && (!timeMatches || (metadata.expectedTitle != null && metadata.expectedTitle != task.title))),
            removedByRule = metadata.removedByRule,
            autoCompletionSuppressed = metadata.autoCompletionSuppressed || (metadata.expectedDone && !task.done),
            autoCompleted = task.done && metadata.expectedDone && metadata.autoCompleted,
            showMealList = metadata.showMealList, lessonPeriod = metadata.lessonPeriod, timingNeedsReview = !timeMatches && metadata.endAt != null)
    }
    fun menuWeek(value: MenuWeek): String = JSONObject().put("start", value.start.toString())
        .put("attachmentId", value.attachmentId).toString()

    fun menuWeek(record: LocalRecord): MenuWeek = JSONObject(record.payload).let {
        MenuWeek(record.id, LocalDate.parse(it.getString("start")), it.getString("attachmentId"))
    }

    fun attachment(value: AttachmentMeta): String = JSONObject().put("mime", value.mime)
        .put("size", value.size).put("sha256", value.sha256).toString()

    fun attachment(record: LocalRecord): AttachmentMeta = JSONObject(record.payload).let {
        AttachmentMeta(record.id, it.getString("mime"), it.getLong("size"), it.getString("sha256"))
    }

    fun timeSlot(value: TimeSlot): String = JSONObject()
        .put("title", value.title).put("start", value.start.toString()).put("end", value.end.toString())
        .put("weekdays", JSONArray(value.weekdays.sorted())).toString()

    fun timeSlot(record: LocalRecord): TimeSlot = JSONObject(record.payload).let {
        val weekdays = it.optJSONArray("weekdays") ?: JSONArray()
        TimeSlot(record.id, it.getString("title"), LocalTime.parse(it.getString("start")), LocalTime.parse(it.getString("end")),
            (0 until weekdays.length()).map(weekdays::getInt).filter { day -> day in 1..7 }.toSet())
    }

    fun lesson(value: Lesson): String = JSONObject().put("weekday", value.weekday)
        .put("slotId", value.slotId).put("subject", value.subject)
        .put("kind", value.kind?.name ?: JSONObject.NULL).put("teachers", value.teachers)
        .put("className", value.className).put("location", value.location).put("role", value.role)
        .put("note", value.note).put("preparation", value.preparation)
        .put("endSlotId", value.endSlotId ?: JSONObject.NULL)
        .put("reminderMinutes", value.reminderMinutes ?: JSONObject.NULL).toString()

    fun lesson(record: LocalRecord): Lesson = JSONObject(record.payload).let {
        val kind = ScheduleKind.entries.firstOrNull { kind -> kind.name == it.optString("kind") }
        Lesson(record.id, it.getInt("weekday"), it.getString("slotId"), it.getString("subject"), kind,
            it.optString("teachers", ""), it.optString("className", ""), it.optString("location", ""),
            it.optString("role", ""), it.optString("note", ""), it.optString("preparation", ""),
            if (it.isNull("endSlotId")) null else it.optString("endSlotId").takeIf(String::isNotBlank),
            if (it.isNull("reminderMinutes")) null else it.optInt("reminderMinutes", -2).takeIf { value -> value in -1..60 })
    }

    fun scheduleProfile(value: ScheduleProfile): String = JSONObject().put("school", value.school)
        .put("term", value.term).put("className", value.className).put("myName", value.myName)
        .put("termStart", value.termStart?.toString() ?: JSONObject.NULL).put("notes", value.notes).toString()

    fun scheduleProfile(record: LocalRecord): ScheduleProfile = JSONObject(record.payload).let {
        val termStart = if (it.isNull("termStart")) null else runCatching { LocalDate.parse(it.getString("termStart")) }.getOrNull()
        ScheduleProfile(it.optString("school", ""), it.optString("term", ""), it.optString("className", ""),
            it.optString("myName", ""), termStart, it.optString("notes", ""))
    }

    fun notice(value: Notice): String = JSONObject().put("title", value.title)
        .put("content", value.content).put("createdAt", value.createdAt.toString())
        .put("attachmentIds", JSONArray(value.attachmentIds)).toString()

    fun notice(record: LocalRecord): Notice = JSONObject(record.payload).let {
        val array = it.optJSONArray("attachmentIds") ?: JSONArray()
        Notice(record.id, it.getString("title"), it.getString("content"), Instant.parse(it.getString("createdAt")),
            (0 until array.length()).map(array::getString))
    }

    fun task(value: WorkTask): String = JSONObject().put("title", value.title)
        .put("noticeId", value.noticeId ?: JSONObject.NULL).put("dueAt", value.dueAt?.toString() ?: JSONObject.NULL)
        .put("remindAt", value.remindAt?.toString() ?: JSONObject.NULL).put("done", value.done)
        .put("createdAt", value.createdAt.toString()).put("dueDate", value.dueDate?.toString() ?: JSONObject.NULL)
        .put("sortOrder", value.sortOrder ?: JSONObject.NULL).toString()

    fun task(record: LocalRecord): WorkTask = JSONObject(record.payload).let {
        fun optional(key: String) = if (it.isNull(key)) null else it.getString(key)
        WorkTask(record.id, it.getString("title"), optional("noticeId"), optional("dueAt")?.let(Instant::parse),
            optional("remindAt")?.let(Instant::parse), it.getBoolean("done"), Instant.parse(it.getString("createdAt")),
            optional("dueDate")?.let(LocalDate::parse),
            if (it.isNull("sortOrder")) null else it.getLong("sortOrder"))
    }

    fun student(value: Student): String = JSONObject()
        .put("name", value.name)
        .put("normallyEatsAtSchool", value.normallyEatsAtSchool)
        .put("active", value.active)
        .put("position", value.position)
        .apply {
            value.schoolLunchDays?.let { days ->
                require(days.all { it in 1..7 }) { "用餐星期无效。" }
                put("schoolLunchDays", JSONArray(days.sorted()))
            }
            value.sortInitial?.let { initial ->
                require(initial.matches(Regex("[A-Z]"))) { "拼音首字母无效。" }
                put("sortInitial", initial)
            }
        }.toString()

    fun student(record: LocalRecord): Student = JSONObject(record.payload).let {
        val days = if (!it.has("schoolLunchDays") || it.isNull("schoolLunchDays")) null else {
            val array = it.getJSONArray("schoolLunchDays")
            (0 until array.length()).map(array::getInt).toSet().also { values ->
                require(values.all { day -> day in 1..7 }) { "用餐星期无效。" }
            }
        }
        val initial = it.optString("sortInitial", "").takeIf { value -> value.matches(Regex("[A-Z]")) }
        Student(record.id, it.getString("name"), it.optBoolean("normallyEatsAtSchool", true),
            it.optBoolean("active", true), it.optInt("position", 0), days, initial)
    }

    fun leave(value: Leave): String = JSONObject()
        .put("studentId", value.studentId)
        .put("start", value.start.toString())
        .put("end", value.end.toString())
        .put("period", value.period.name)
        .put("recordedAt", value.recordedAt.toString())
        .put("cancelled", value.cancelled).apply { if (value.reason.isNotBlank()) put("reason", value.reason) }.toString()

    fun leave(record: LocalRecord): Leave = JSONObject(record.payload).let {
        Leave(record.id, it.getString("studentId"), LocalDate.parse(it.getString("start")),
            LocalDate.parse(it.getString("end")), LeavePeriod.valueOf(it.getString("period")),
            Instant.parse(it.getString("recordedAt")), it.optBoolean("cancelled", false), if (it.isNull("reason")) "" else it.optString("reason"))
    }

    fun marking(value: MarkingRecord): String = JSONObject()
        .put("title", value.title).put("firstLabel", value.firstLabel).put("secondLabel", value.secondLabel)
        .put("createdAt", value.createdAt.toString()).put("students", JSONArray().apply {
            value.students.forEach { student ->
                put(JSONObject().put("studentId", student.studentId).put("name", student.name).put("first", student.first))
            }
        }).toString()

    fun marking(record: LocalRecord): MarkingRecord = JSONObject(record.payload).let { root ->
        val array = root.getJSONArray("students")
        MarkingRecord(record.id, root.getString("title"), root.getString("firstLabel"), root.getString("secondLabel"),
            Instant.parse(root.getString("createdAt")), (0 until array.length()).map { index ->
                val item = array.getJSONObject(index)
                MarkingStudent(item.getString("studentId"), item.getString("name"), item.getBoolean("first"))
            })
    }
}
