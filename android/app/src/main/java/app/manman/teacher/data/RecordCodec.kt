package app.manman.teacher.data

import app.manman.teacher.domain.ExclusionReason
import app.manman.teacher.domain.Leave
import app.manman.teacher.domain.LeavePeriod
import app.manman.teacher.domain.LunchStudent
import app.manman.teacher.domain.MealOverride
import app.manman.teacher.domain.Student
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

object RecordKind {
    const val STUDENT = "student"
    const val LEAVE = "leave"
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

data class MealOverrideRecord(val id: String, val day: LocalDate, val studentId: String, val value: MealOverride)
data class LunchReview(val id: String, val day: LocalDate, val confirmedAt: Instant, val students: List<LunchStudent>)
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
data class Lesson(val id: String, val weekday: Int, val slotId: String, val subject: String)
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
)

object RecordCodec {
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
        .put("slotId", value.slotId).put("subject", value.subject).toString()

    fun lesson(record: LocalRecord): Lesson = JSONObject(record.payload).let {
        Lesson(record.id, it.getInt("weekday"), it.getString("slotId"), it.getString("subject"))
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
        .put("createdAt", value.createdAt.toString()).put("dueDate", value.dueDate?.toString() ?: JSONObject.NULL).toString()

    fun task(record: LocalRecord): WorkTask = JSONObject(record.payload).let {
        fun optional(key: String) = if (it.isNull(key)) null else it.getString(key)
        WorkTask(record.id, it.getString("title"), optional("noticeId"), optional("dueAt")?.let(Instant::parse),
            optional("remindAt")?.let(Instant::parse), it.getBoolean("done"), Instant.parse(it.getString("createdAt")),
            optional("dueDate")?.let(LocalDate::parse))
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
        .put("cancelled", value.cancelled).toString()

    fun leave(record: LocalRecord): Leave = JSONObject(record.payload).let {
        Leave(record.id, it.getString("studentId"), LocalDate.parse(it.getString("start")),
            LocalDate.parse(it.getString("end")), LeavePeriod.valueOf(it.getString("period")),
            Instant.parse(it.getString("recordedAt")), it.optBoolean("cancelled", false))
    }

    fun mealOverride(value: MealOverrideRecord): String = JSONObject()
        .put("day", value.day.toString()).put("studentId", value.studentId).put("value", value.value.name).toString()

    fun mealOverride(record: LocalRecord): MealOverrideRecord = JSONObject(record.payload).let {
        MealOverrideRecord(record.id, LocalDate.parse(it.getString("day")), it.getString("studentId"),
            MealOverride.valueOf(it.getString("value")))
    }

    fun lunchReview(value: LunchReview): String = JSONObject()
        .put("day", value.day.toString()).put("confirmedAt", value.confirmedAt.toString())
        .put("students", JSONArray().apply {
            value.students.forEach { student ->
                put(JSONObject().put("studentId", student.studentId).put("name", student.name)
                    .put("eats", student.eats).put("reason", student.reason?.name ?: JSONObject.NULL))
            }
        }).toString()

    fun lunchReview(record: LocalRecord): LunchReview = JSONObject(record.payload).let { root ->
        val array = root.getJSONArray("students")
        val students = (0 until array.length()).map { index ->
            val item = array.getJSONObject(index)
            val reason = item.optString("reason").takeIf { it.isNotBlank() && it != "null" }
            LunchStudent(item.getString("studentId"), item.getString("name"), item.getBoolean("eats"),
                reason?.let(ExclusionReason::valueOf))
        }
        LunchReview(record.id, LocalDate.parse(root.getString("day")),
            Instant.parse(root.getString("confirmedAt")), students)
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
