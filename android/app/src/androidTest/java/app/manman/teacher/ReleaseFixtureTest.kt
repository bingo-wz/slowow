package app.manman.teacher

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.manman.teacher.auth.AuthSession
import app.manman.teacher.auth.SecureSessionStore
import app.manman.teacher.data.ClassRepository
import app.manman.teacher.data.WorkTask
import app.manman.teacher.domain.LunchRules
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.Instant
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ReleaseFixtureTest {
    @Test fun prepareEmptyTeacherAccount() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("teacherFixture") == "1")
        val app = ApplicationProvider.getApplicationContext<ManmanApplication>()
        val account = "teacher-fixture-${UUID.randomUUID()}"
        SecureSessionStore(app).save(AuthSession(account, "test-token", "test-refresh", System.currentTimeMillis() + 3_600_000))
    }

    @Test fun prepareFictionalAccount() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("releaseFixture") == "1")
        val app = ApplicationProvider.getApplicationContext<ManmanApplication>()
        val account = "release-fixture-${UUID.randomUUID()}"
        SecureSessionStore(app).save(AuthSession(account, "test-token", "test-refresh", System.currentTimeMillis() + 3_600_000))
        val repository = ClassRepository(app.database)
        repository.addStudents(account, (1..52).map { "学生%02d".format(it) }, 0)
        repository.saveMenu(account, LocalDate.now(LunchRules.defaultZone), "虚构菜品")
        val now = Instant.now()
        repository.saveTask(account, WorkTask("release-reminder-${UUID.randomUUID()}", "虚构提醒", null, null, now.plusSeconds(600), false, now))
    }
}
