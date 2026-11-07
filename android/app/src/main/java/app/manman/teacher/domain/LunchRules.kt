package app.manman.teacher.domain

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

data class Student(
    val id: String,
    val name: String,
    val normallyEatsAtSchool: Boolean = true,
    val active: Boolean = true,
    val position: Int = 0,
    val schoolLunchDays: Set<Int>? = null,
    val sortInitial: String? = null,
) {
    fun eatsAtSchoolOn(day: LocalDate): Boolean = schoolLunchDays?.contains(day.dayOfWeek.value) ?: normallyEatsAtSchool

    fun regularSchoolLunchDays(): Set<Int> = schoolLunchDays ?: if (normallyEatsAtSchool) (1..7).toSet() else emptySet()
}

enum class LeavePeriod { ALL_DAY, MORNING, AFTERNOON }

data class Leave(
    val id: String,
    val studentId: String,
    val start: LocalDate,
    val end: LocalDate,
    val period: LeavePeriod,
    val recordedAt: Instant,
    val cancelled: Boolean = false,
)

enum class MealOverride { EAT, DO_NOT_EAT }
enum class ExclusionReason { USUALLY_ELSEWHERE, LEAVE, MANUAL }

data class LunchStudent(
    val studentId: String,
    val name: String,
    val eats: Boolean,
    val reason: ExclusionReason? = null,
)

data class LunchResult(val students: List<LunchStudent>) {
    val eating: List<LunchStudent> get() = students.filter { it.eats }
    val excluded: List<LunchStudent> get() = students.filterNot { it.eats }
}

object LunchRules {
    val defaultZone: ZoneId = ZoneId.of("Asia/Shanghai")
    val defaultCutoff: LocalTime = LocalTime.of(12, 30)

    fun calculate(
        day: LocalDate,
        students: List<Student>,
        leaves: List<Leave>,
        overrides: Map<String, MealOverride> = emptyMap(),
        cutoff: LocalTime = defaultCutoff,
        zone: ZoneId = defaultZone,
    ): LunchResult {
        val deadline = day.atTime(cutoff).atZone(zone).toInstant()
        val absentIds = leaves.asSequence()
            .filter { !it.cancelled && it.period != LeavePeriod.AFTERNOON }
            .filter { !day.isBefore(it.start) && !day.isAfter(it.end) }
            .filter { it.recordedAt.isBefore(deadline) }
            .map { it.studentId }
            .toSet()

        return LunchResult(students.asSequence()
            .filter { it.active }
            .sortedWith(compareBy(Student::position, Student::id))
            .map { student ->
                when {
                    overrides[student.id] == MealOverride.EAT ->
                        LunchStudent(student.id, student.name, eats = true)
                    overrides[student.id] == MealOverride.DO_NOT_EAT ->
                        LunchStudent(student.id, student.name, eats = false, reason = ExclusionReason.MANUAL)
                    !student.eatsAtSchoolOn(day) ->
                        LunchStudent(student.id, student.name, eats = false, reason = ExclusionReason.USUALLY_ELSEWHERE)
                    student.id in absentIds ->
                        LunchStudent(student.id, student.name, eats = false, reason = ExclusionReason.LEAVE)
                    else -> LunchStudent(student.id, student.name, eats = true)
                }
            }.toList())
    }

    fun changedSince(previous: List<LunchStudent>, current: List<LunchStudent>): Boolean =
        previous.associate { it.studentId to it.eats } != current.associate { it.studentId to it.eats }
}
