package app.manman.teacher.domain

import app.manman.teacher.data.WorkTask
import java.time.Instant
import java.time.LocalDate

object TaskOrdering {
    fun date(task: WorkTask): LocalDate? = task.dueAt?.atZone(AppTime.zone)?.toLocalDate() ?: task.dueDate

    fun sorted(tasks: List<WorkTask>): List<WorkTask> = tasks.sortedWith(
        compareBy<WorkTask> { it.dueAt ?: Instant.MAX }
            .thenBy { it.sortOrder ?: Long.MAX_VALUE }
            .thenBy { it.createdAt }
            .thenBy { it.id },
    )
}
