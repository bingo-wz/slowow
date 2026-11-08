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
import app.manman.teacher.data.MarkingRecord
import app.manman.teacher.data.MarkingStudent
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.LocalTime
import java.time.Instant
import java.time.DayOfWeek
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import java.io.File
import java.util.UUID
import org.json.JSONObject
import org.json.JSONArray

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
        val zone = ZoneId.of("Asia/Shanghai")
        SecureSessionStore(app).save(AuthSession(account, "test-token", "test-refresh", System.currentTimeMillis() + 3_600_000))
        val repository = ClassRepository(app.database)
        repository.addStudents(account, (1..52).map { "学生%02d".format(it) }, 0)
        val legacyMenu = JSONObject().put("day", LocalDate.now(zone).toString()).put("dishes", "虚构菜品")
        app.database.records().saveLocal(account, RecordKind.MENU, "legacy-menu", legacyMenu.toString())
        val imageFile = File(app.cacheDir, "fictional-weekly-menu.png")
        val bitmap = Bitmap.createBitmap(24, 32, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.GREEN) }
        imageFile.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val image = app.attachments.stage(Uri.fromFile(imageFile))
        app.attachments.adopt(account, image)
        val start = LocalDate.now(zone).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        repository.saveMenuWeek(account, start, image)
        imageFile.delete()
        bitmap.recycle()
        val slot = TimeSlot("legacy-slot", "第一节", LocalTime.of(9, 5), LocalTime.of(9, 45), setOf(1))
        repository.saveTimeSlot(account, slot)
        repository.saveLesson(account, Lesson("legacy-lesson", 1, slot.id, "虚构语文"))
        val now = Instant.now()
        val oldTask = JSONObject().put("title", "虚构提醒").put("noticeId", JSONObject.NULL).put("dueAt", JSONObject.NULL)
            .put("remindAt", now.plusSeconds(600).toString()).put("done", false).put("createdAt", now.toString())
            .put("dueDate", LocalDate.now(zone).toString())
        app.database.records().saveLocal(account, RecordKind.TASK, "legacy-task", oldTask.toString())
        repository.saveMarking(account, MarkingRecord("legacy-marking", "虚构事项", "有", "没有", now,
            listOf(MarkingStudent("legacy-student", "虚构学生", false))))
        val oldReview = JSONObject().put("day", LocalDate.now(zone).toString()).put("confirmedAt", now.toString()).put("students", JSONArray())
        app.database.records().saveLocal(account, RecordKind.LUNCH_REVIEW, "legacy-review", oldReview.toString())
        val oldOverride = JSONObject().put("day", LocalDate.now(zone).toString()).put("studentId", "legacy-student").put("value", "DO_NOT_EAT")
        app.database.records().saveLocal(account, RecordKind.MEAL_OVERRIDE, "legacy-override", oldOverride.toString())
        app.database.records().saveLocal(account, RecordKind.SETTINGS, "main", JSONObject().put("lunchCutoff", "12:30").toString())
        app.database.records().saveLocal(account, RecordKind.SETTINGS, "reminders", JSONObject().put("lunchEnabled", true).put("lessonMinutes", -1).toString())
    }
}
