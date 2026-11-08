package app.manman.teacher.domain

import app.manman.teacher.data.WorkTask
import java.time.Instant
import java.time.LocalDate

object TaskOrdering {
    fun date(task: WorkTask): LocalDate? = task.dueAt?.atZone(LunchRules.defaultZone)?.toLocalDate() ?: task.dueDate

    fun sorted(tasks: List<WorkTask>): List<WorkTask> = tasks.sortedWith(
        compareBy<WorkTask> { it.sortOrder ?: Long.MAX_VALUE }
            .thenBy { it.dueAt ?: Instant.MAX }
            .thenBy { it.createdAt }
            .thenBy { it.id },
    )
}
