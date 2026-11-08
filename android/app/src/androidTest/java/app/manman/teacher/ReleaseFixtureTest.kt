package app.manman.teacher

import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.manman.teacher.auth.AuthSession
import app.manman.teacher.auth.SecureSessionStore
import app.manman.teacher.data.ClassRepository
import app.manman.teacher.data.Lesson
import app.manman.teacher.data.RecordKind
import app.manman.teacher.data.TimeSlot
import app.manman.teacher.data.WorkTask
import app.manman.teacher.domain.LunchRules
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.LocalTime
import java.time.Instant
import java.time.DayOfWeek
import java.time.temporal.TemporalAdjusters
import java.io.File
import java.util.UUID
import org.json.JSONObject

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
        val legacyMenu = JSONObject().put("day", LocalDate.now(LunchRules.defaultZone).toString()).put("dishes", "虚构菜品")
        app.database.records().saveLocal(account, RecordKind.MENU, "legacy-menu", legacyMenu.toString())
        val imageFile = File(app.cacheDir, "fictional-weekly-menu.png")
        val bitmap = Bitmap.createBitmap(24, 32, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.GREEN) }
        imageFile.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val image = app.attachments.stage(Uri.fromFile(imageFile))
        app.attachments.adopt(account, image)
        val start = LocalDate.now(LunchRules.defaultZone).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        repository.saveMenuWeek(account, start, image)
        imageFile.delete()
        bitmap.recycle()
        val slot = TimeSlot("legacy-slot", "第一节", LocalTime.of(9, 5), LocalTime.of(9, 45), setOf(1))
        repository.saveTimeSlot(account, slot)
        repository.saveLesson(account, Lesson("legacy-lesson", 1, slot.id, "虚构语文"))
        val now = Instant.now()
        repository.saveTask(account, WorkTask("release-reminder-${UUID.randomUUID()}", "虚构提醒", null, null, now.plusSeconds(600), false, now))
    }
}
