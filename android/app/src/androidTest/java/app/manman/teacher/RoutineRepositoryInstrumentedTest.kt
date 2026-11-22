package app.manman.teacher

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.manman.teacher.data.ClassRepository
import app.manman.teacher.data.ManmanDatabase
import app.manman.teacher.data.RecordCodec
import app.manman.teacher.data.RecordKind
import app.manman.teacher.data.RoutineRule
import app.manman.teacher.data.RoutineTime
import app.manman.teacher.data.RoutineVersion
import app.manman.teacher.data.WorkTask
import app.manman.teacher.domain.AppTime
import app.manman.teacher.domain.RoutinePlanner
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class RoutineRepositoryInstrumentedTest {
    @Test fun aFutureRepeatingTaskShowsItsFirstOccurrenceWithoutWaitingForThatWeek() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<ManmanApplication>()
        val database = Room.inMemoryDatabaseBuilder(app, ManmanDatabase::class.java).build()
        try {
            val repository = ClassRepository(database)
            val owner = "future-rule-${UUID.randomUUID()}"
            val now = Instant.now()
            val start = now.atZone(AppTime.zone).toLocalDate().plusDays(45)
            val version = RoutineVersion(start, "未来固定事项", setOf(start.dayOfWeek.value))
            val rule = RoutineRule("future-rule", now, listOf(version))
            repository.saveRoutine(owner, rule, now)
            val first = database.records().find(owner, RecordKind.TASK, RoutinePlanner.occurrenceId(rule.id, start))
            assertNotNull(first)
            assertEquals(start, RecordCodec.task(first!!).dueDate)
            assertNull(RecordCodec.task(first).dueAt)
        } finally { database.close() }
    }

    @Test fun browsingLaterDatesCreatesOnlyThatWindowAndKeepsFutureEditsConsistent() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<ManmanApplication>()
        val database = Room.inMemoryDatabaseBuilder(app, ManmanDatabase::class.java).build()
        try {
            val repository = ClassRepository(database)
            val dao = database.records()
            val owner = "browsed-week-${UUID.randomUUID()}"
            val now = Instant.now()
            val today = now.atZone(AppTime.zone).toLocalDate()
            val from = today.plusWeeks(8)
            val version = RoutineVersion(today, "每周待办", (0L..4L).map { from.plusDays(it).dayOfWeek.value }.toSet())
            val rule = RoutineRule("browsed-week", now, listOf(version))
            repository.saveRoutine(owner, rule, now)
            fun id(date: LocalDate) = RoutinePlanner.occurrenceId(rule.id, date)
            suspend fun taskRecord(date: LocalDate) = dao.find(owner, RecordKind.TASK, id(date))
            suspend fun task(date: LocalDate): WorkTask {
                val raw = requireNotNull(taskRecord(date))
                val metadata = requireNotNull(dao.find(owner, RecordKind.SETTINGS, RecordCodec.taskMetadataId(raw.id)))
                return RecordCodec.withMetadata(RecordCodec.task(raw), RecordCodec.taskMetadata(metadata))
            }
            assertNull(taskRecord(from))
            val window = from..from.plusDays(6)
            assertTrue(repository.refreshAgenda(owner, now, calendarRange = window).changed)
            assertNull(taskRecord(from.minusWeeks(1)))
            assertNull(taskRecord(from.plusDays(5)))
            assertNull(taskRecord(from.plusWeeks(1)))
            assertEquals(from, task(from).routineDate)
            assertFalse(repository.refreshAgenda(owner, now, calendarRange = window).changed)

            val singleEdit = from.plusDays(1)
            val completed = from.plusDays(2)
            val deleted = from.plusDays(3)
            repository.saveTask(owner, task(singleEdit).copy(title = "仅改这一次"), expected = task(singleEdit))
            repository.setTaskDone(owner, id(completed), true, now)
            repository.deleteTask(owner, task(deleted))

            val edited = rule.copy(versions = listOf(version.copy(title = "更新后的待办",
                start = LocalTime.of(8, 0), end = LocalTime.of(8, 30))))
            repository.saveRoutine(owner, edited, now, rule)
            assertEquals("更新后的待办", task(from).title)
            assertEquals(from.atTime(8, 30).atZone(AppTime.zone).toInstant(), task(from).endAt)
            assertEquals("仅改这一次", task(singleEdit).title)
            assertTrue(task(completed).done)
            assertEquals("每周待办", task(completed).title)
            assertTrue(taskRecord(deleted)!!.deleted)

            val paused = edited.copy(versions = listOf(edited.current.copy(enabled = false)))
            repository.saveRoutine(owner, paused, now, edited)
            assertTrue(taskRecord(from)!!.deleted)
            assertFalse(taskRecord(singleEdit)!!.deleted)
            assertFalse(taskRecord(completed)!!.deleted)
            repository.saveRoutine(owner, edited, now, paused)
            assertFalse(taskRecord(from)!!.deleted)
            assertTrue(taskRecord(deleted)!!.deleted)
            assertFalse(repository.refreshAgenda(owner, now, calendarRange = window).changed)
        } finally { database.close() }
    }

    @Test fun weekdayRangesCreateAndUpdateOnlyTheirOwnOccurrences() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<ManmanApplication>()
        val database = Room.inMemoryDatabaseBuilder(app, ManmanDatabase::class.java).build()
        try {
            val repository = ClassRepository(database)
            val dao = database.records()
            val owner = "weekday-ranges-${UUID.randomUUID()}"
            val now = Instant.now()
            val first = now.atZone(AppTime.zone).toLocalDate()
            val days = (0L..2L).map { first.plusDays(it).dayOfWeek.value }
            val times = mapOf(days[0] to RoutineTime(LocalTime.of(8, 15), LocalTime.of(8, 45)),
                days[1] to RoutineTime(LocalTime.of(9, 5), LocalTime.of(9, 50)), days[2] to RoutineTime())
            val version = RoutineVersion(first, "虚构每周事项", days.toSet(), weekdayTimes = times)
            val rule = RoutineRule("weekday-rule", now, listOf(version))
            repository.saveRoutine(owner, rule, now)
            fun id(offset: Long) = RoutinePlanner.occurrenceId(rule.id, first.plusDays(offset))
            suspend fun task(offset: Long): WorkTask {
                val raw = dao.find(owner, RecordKind.TASK, id(offset))!!
                val meta = dao.find(owner, RecordKind.SETTINGS, RecordCodec.taskMetadataId(raw.id))!!
                return RecordCodec.withMetadata(RecordCodec.task(raw), RecordCodec.taskMetadata(meta))
            }
            assertEquals(first.atTime(8, 15).atZone(AppTime.zone).toInstant(), task(0).dueAt)
            assertEquals(first.plusDays(1).atTime(9, 50).atZone(AppTime.zone).toInstant(), task(1).endAt)
            assertNull(task(2).dueAt)
            assertNull(task(2).endAt)

            val revised = version.copy(weekdayTimes = times + (days[1] to RoutineTime(LocalTime.of(10, 0), LocalTime.of(10, 30))))
            repository.saveRoutine(owner, rule.copy(versions = listOf(revised)), now, rule)
            assertEquals(first.atTime(8, 15).atZone(AppTime.zone).toInstant(), task(0).dueAt)
            assertEquals(first.plusDays(1).atTime(10, 30).atZone(AppTime.zone).toInstant(), task(1).endAt)
            assertNull(task(2).dueAt)
            val result = repository.refreshAgenda(owner, first.plusDays(2).atTime(23, 0).atZone(AppTime.zone).toInstant())
            assertEquals(2, result.earlierCompleted)
            assertTrue(task(0).done)
            assertTrue(task(1).done)
            assertFalse(task(2).done)

            val invalid = revised.copy(weekdayTimes = times + (days[0] to RoutineTime(LocalTime.of(8, 0), null)))
            val error = runCatching { repository.saveRoutine(owner, rule.copy(versions = listOf(invalid)), now) }.exceptionOrNull()
            assertTrue(error is IllegalArgumentException)
        } finally { database.close() }
    }

    @Test fun editingMergesIndependentUpdatesAndRejectsConflictingOrDeletedItems() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<ManmanApplication>()
        val database = Room.inMemoryDatabaseBuilder(app, ManmanDatabase::class.java).build()
        try {
            val repository = ClassRepository(database)
            val dao = database.records()
            val owner = "editing-${UUID.randomUUID()}"
            val now = Instant.now()
            val day = now.atZone(AppTime.zone).toLocalDate()
            val original = WorkTask("concurrent-task", "原事项", null, null, null, false, now, day)
            repository.saveTask(owner, original)
            repository.saveTask(owner, original.copy(title = "另一设备修改"))
            repository.setTaskDone(owner, original.id, true, now)
            val edited = original.copy(dueDate = day.plusDays(1))
            repository.saveTask(owner, edited, original)
            val merged = RecordCodec.task(dao.find(owner, RecordKind.TASK, original.id)!!)
            assertEquals("另一设备修改", merged.title)
            assertEquals(day.plusDays(1), merged.dueDate)
            assertTrue(merged.done)

            val conflict = runCatching { repository.saveTask(owner, original.copy(title = "本机修改"), original) }.exceptionOrNull()
            assertTrue(conflict is IllegalArgumentException)
            assertEquals("另一设备修改", RecordCodec.task(dao.find(owner, RecordKind.TASK, original.id)!!).title)
            repository.deleteTask(owner, merged)
            val deleted = runCatching { repository.saveTask(owner, edited, original) }.exceptionOrNull()
            assertTrue(deleted is IllegalArgumentException)
            assertTrue(dao.find(owner, RecordKind.TASK, original.id)!!.deleted)
        } finally { database.close() }
    }

    @Test fun staleRoutineEditorCannotOverwriteOrDeleteNewerVersion() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<ManmanApplication>()
        val database = Room.inMemoryDatabaseBuilder(app, ManmanDatabase::class.java).build()
        try {
            val repository = ClassRepository(database)
            val dao = database.records()
            val owner = "routine-editing-${UUID.randomUUID()}"
            val now = Instant.now()
            val day = now.atZone(AppTime.zone).toLocalDate()
            val initial = RoutineRule("shared-rule", now, listOf(RoutineVersion(day, "原安排", setOf(1))))
            val remote = initial.copy(versions = listOf(initial.current.copy(title = "新安排")))
            repository.saveRoutine(owner, initial, now)
            repository.saveRoutine(owner, remote, now)
            val staleSave = runCatching { repository.saveRoutine(owner, initial, now, initial) }.exceptionOrNull()
            val staleDelete = runCatching { repository.deleteRoutine(owner, initial.id, now, initial) }.exceptionOrNull()
            assertTrue(staleSave is IllegalArgumentException)
            assertTrue(staleDelete is IllegalArgumentException)
            val record = dao.find(owner, RecordKind.SETTINGS, RecordCodec.routineRecordId(initial.id))!!
            assertFalse(record.deleted)
            assertEquals(remote, RecordCodec.routine(record))
        } finally { database.close() }
    }

    @Test fun automaticCompletionIsPerDayAndRespectsManualUndo() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<ManmanApplication>()
        val database = Room.inMemoryDatabaseBuilder(app, ManmanDatabase::class.java).build()
        try {
            val repository = ClassRepository(database)
            val dao = database.records()
            val owner = "routine-${UUID.randomUUID()}"
            val now = Instant.now()
            val first = now.atZone(AppTime.zone).toLocalDate()
            val version = RoutineVersion(first, "晨检", (1..7).toSet(), LocalTime.of(0, 1), LocalTime.of(0, 2))
            val rule = RoutineRule("routine-a", now, listOf(version))
            repository.saveRoutine(owner, rule, now)
            val openedAt = first.plusDays(2).atTime(12, 0).atZone(AppTime.zone).toInstant()
            val result = repository.refreshAgenda(owner, openedAt)
            assertEquals(1, result.todayCompleted)
            assertEquals(2, result.earlierCompleted)
            val todayId = RoutinePlanner.occurrenceId(rule.id, first.plusDays(2))
            val todayRecord = requireNotNull(dao.find(owner, RecordKind.TASK, todayId))
            val todayMeta = requireNotNull(dao.find(owner, RecordKind.SETTINGS, RecordCodec.taskMetadataId(todayId)))
            val completed = RecordCodec.withMetadata(RecordCodec.task(todayRecord), RecordCodec.taskMetadata(todayMeta))
            assertTrue(completed.done)
            assertTrue(completed.autoCompleted)
            assertTrue(repository.setTaskDone(owner, todayId, false, openedAt))
            assertFalse(repository.setTaskDone(owner, todayId, false, openedAt))
            val repeated = repository.refreshAgenda(owner, openedAt.plusSeconds(1))
            assertEquals(0, repeated.todayCompleted)
            val reopened = RecordCodec.withMetadata(RecordCodec.task(dao.find(owner, RecordKind.TASK, todayId)!!),
                RecordCodec.taskMetadata(dao.find(owner, RecordKind.SETTINGS, RecordCodec.taskMetadataId(todayId))!!))
            assertFalse(reopened.done)
            assertTrue(reopened.autoCompletionSuppressed)
            assertNull(dao.find("different-$owner", RecordKind.TASK, todayId))
        } finally { database.close() }
    }

    @Test fun editingPausingAndDeletingPreserveExceptionsAndEarlierDays() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<ManmanApplication>()
        val database = Room.inMemoryDatabaseBuilder(app, ManmanDatabase::class.java).build()
        try {
            val repository = ClassRepository(database)
            val dao = database.records()
            val owner = "routine-${UUID.randomUUID()}"
            val now = Instant.now()
            val first = now.atZone(AppTime.zone).toLocalDate()
            val initial = RoutineVersion(first, "午餐核销", (1..7).toSet(), showMealList = true)
            val rule = RoutineRule("routine-b", now, listOf(initial))
            repository.saveRoutine(owner, rule, now)
            val exceptionId = RoutinePlanner.occurrenceId(rule.id, first.plusDays(1))
            val original = RecordCodec.withMetadata(RecordCodec.task(dao.find(owner, RecordKind.TASK, exceptionId)!!),
                RecordCodec.taskMetadata(dao.find(owner, RecordKind.SETTINGS, RecordCodec.taskMetadataId(exceptionId))!!))
            repository.saveTask(owner, original.copy(title = "单独修改", routineException = true))
            val updated = rule.copy(versions = listOf(initial.copy(title = "新午餐安排")))
            repository.saveRoutine(owner, updated, now)
            assertEquals("单独修改", RecordCodec.task(dao.find(owner, RecordKind.TASK, exceptionId)!!).title)
            val todayId = RoutinePlanner.occurrenceId(rule.id, first)
            assertEquals("新午餐安排", RecordCodec.task(dao.find(owner, RecordKind.TASK, todayId)!!).title)
            val pause = updated.copy(versions = listOf(updated.current.copy(enabled = false)))
            repository.saveRoutine(owner, pause, now)
            assertTrue(dao.find(owner, RecordKind.TASK, todayId)!!.deleted)
            assertFalse(dao.find(owner, RecordKind.TASK, exceptionId)!!.deleted)
            repository.saveRoutine(owner, updated, now)
            assertFalse(dao.find(owner, RecordKind.TASK, todayId)!!.deleted)
            val userTask = RecordCodec.withMetadata(RecordCodec.task(dao.find(owner, RecordKind.TASK, todayId)!!),
                RecordCodec.taskMetadata(dao.find(owner, RecordKind.SETTINGS, RecordCodec.taskMetadataId(todayId))!!))
            repository.deleteTask(owner, userTask)
            repository.refreshAgenda(owner, now.plusSeconds(1))
            assertTrue(dao.find(owner, RecordKind.TASK, todayId)!!.deleted)
            assertNotNull(dao.find(owner, RecordKind.SETTINGS, RecordCodec.routineRecordId(rule.id)))
        } finally { database.close() }
    }

    @Test fun oldClientTimeEditsAreKeptForReviewAndNeverAutomaticallyCompleted() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<ManmanApplication>()
        val database = Room.inMemoryDatabaseBuilder(app, ManmanDatabase::class.java).build()
        try {
            val repository = ClassRepository(database)
            val dao = database.records()
            val owner = "routine-${UUID.randomUUID()}"
            val now = Instant.now()
            val day = now.atZone(AppTime.zone).toLocalDate()
            val task = WorkTask("old-client", "虚构待办", null, now.minusSeconds(7200), null, false, now.minusSeconds(9000), day,
                endAt = now.minusSeconds(3600))
            repository.saveTask(owner, task)
            dao.saveLocal(owner, RecordKind.TASK, task.id, RecordCodec.task(task.copy(dueAt = now.minusSeconds(5400))))
            repository.refreshAgenda(owner, now.plusSeconds(2))
            val raw = dao.find(owner, RecordKind.TASK, task.id)!!
            val metadata = dao.find(owner, RecordKind.SETTINGS, RecordCodec.taskMetadataId(task.id))!!
            val merged = RecordCodec.withMetadata(RecordCodec.task(raw), RecordCodec.taskMetadata(metadata))
            assertFalse(merged.done)
            assertTrue(merged.timingNeedsReview)
            assertNull(merged.endAt)
            repository.setTaskDone(owner, task.id, true, now)
            val preserved = RecordCodec.taskMetadata(dao.find(owner, RecordKind.SETTINGS, metadata.id)!!)
            assertEquals(task.endAt, preserved.endAt)
        } finally { database.close() }
    }
}
