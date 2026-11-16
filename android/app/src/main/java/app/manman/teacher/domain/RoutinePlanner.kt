package app.manman.teacher.domain

import app.manman.teacher.data.RoutineRule
import app.manman.teacher.data.RoutineVersion
import app.manman.teacher.data.WorkTask
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

data class RoutineOccurrence(
    val id: String,
    val ruleId: String,
    val date: LocalDate,
    val version: RoutineVersion,
    val start: Instant?,
    val end: Instant?,
    val createdAt: Instant,
)

object RoutinePlanner {
    fun occurrenceId(ruleId: String, day: LocalDate): String =
        UUID.nameUUIDFromBytes("routine:$ruleId:$day".toByteArray(Charsets.UTF_8)).toString()

    fun occurrences(rule: RoutineRule, from: LocalDate, through: LocalDate): List<RoutineOccurrence> {
        if (through < from) return emptyList()
        val versions = rule.versions.sortedBy(RoutineVersion::effectiveFrom)
        val first = maxOf(from, versions.first().effectiveFrom)
        if (first > through) return emptyList()
        val result = mutableListOf<RoutineOccurrence>()
        var day = first
        while (!day.isAfter(through)) {
            val version = versions.lastOrNull { !it.effectiveFrom.isAfter(day) }
            if (version != null && version.enabled && day.dayOfWeek.value in version.weekdays) {
                val start = version.start?.let { day.atTime(it).atZone(AppTime.zone).toInstant() }
                val end = version.end?.let { day.atTime(it).atZone(AppTime.zone).toInstant() }
                result.add(RoutineOccurrence(occurrenceId(rule.id, day), rule.id, day, version, start, end, rule.createdAt))
            }
            day = day.plusDays(1)
        }
        return result
    }

    fun shouldAutoComplete(task: WorkTask, now: Instant): Boolean {
        val start = task.dueAt ?: return false
        val end = task.endAt ?: return false
        return !task.done && !task.timingNeedsReview && !task.autoCompletionSuppressed && end > start && !end.isAfter(now)
    }
}
