package app.manman.teacher

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.manman.teacher.auth.AuthSession
import app.manman.teacher.auth.SecureSessionStore
import app.manman.teacher.data.ClassRepository
import app.manman.teacher.data.RecordCodec
import app.manman.teacher.data.RecordKind
import app.manman.teacher.data.RoutineRule
import app.manman.teacher.data.RoutineTime
import app.manman.teacher.data.RoutineVersion
import app.manman.teacher.domain.AppTime
import app.manman.teacher.domain.RoutinePlanner
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONObject
import java.time.Instant
import java.time.LocalTime
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class VersionFixtureInstrumentedTest {
    @Test fun prepareWeekdayScheduleForOlderClientCheck() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("versionFixture") == "1")
        val app = ApplicationProvider.getApplicationContext<ManmanApplication>()
        val owner = "version-fixture-${UUID.randomUUID()}"
        val now = Instant.now()
        val today = now.atZone(AppTime.zone).toLocalDate()
        val tomorrow = today.plusDays(1)
        val dayAfter = today.plusDays(2)
        val times = mapOf(tomorrow.dayOfWeek.value to RoutineTime(LocalTime.of(8, 15), LocalTime.of(8, 45)),
            dayAfter.dayOfWeek.value to RoutineTime(LocalTime.of(9, 5), LocalTime.of(9, 50)))
        val rule = RoutineRule("compatibility-check", now,
            listOf(RoutineVersion(today, "虚构固定安排", times.keys, weekdayTimes = times)))
        SecureSessionStore(app).save(AuthSession(owner, "test-token", "test-refresh", System.currentTimeMillis() + 3_600_000))
        ClassRepository(app.database).saveRoutine(owner, rule, now)
        val ruleRecord = app.database.records().find(owner, RecordKind.SETTINGS, RecordCodec.routineRecordId(rule.id))!!
        val taskId = RoutinePlanner.occurrenceId(rule.id, tomorrow)
        val task = app.database.records().find(owner, RecordKind.TASK, taskId)!!
        val metadata = app.database.records().find(owner, RecordKind.SETTINGS, RecordCodec.taskMetadataId(taskId))!!
        assertEquals("routine_v2", JSONObject(ruleRecord.payload).getString("type"))
        assertNotNull(task.payload)
        app.getSharedPreferences("version-check-fixture", 0).edit().putString("owner", owner)
            .putString("ruleId", ruleRecord.id).putString("rule", ruleRecord.payload)
            .putString("taskId", taskId).putString("task", task.payload)
            .putString("metadataId", metadata.id).putString("metadata", metadata.payload).commit()
        Unit
    }
}
