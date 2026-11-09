package app.manman.teacher

import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import android.os.SystemClock
import app.manman.teacher.data.ClassRepository
import app.manman.teacher.data.MarkingRecord
import app.manman.teacher.data.MarkingStudent
import app.manman.teacher.data.RecordKind
import app.manman.teacher.domain.AppTime
import app.manman.teacher.domain.Leave
import app.manman.teacher.domain.LeavePeriod
import app.manman.teacher.domain.Student
import app.manman.teacher.fonts.AppTextSize
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter

@RunWith(AndroidJUnit4::class)
class LeaveDateGroupingInstrumentedTest {
    @Test fun dateGroupsRemainUsableWithLargeSystemText() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("teacherFixture") == "1")
        val app = ApplicationProvider.getApplicationContext<ManmanApplication>()
        app.auth.restore()
        val account = requireNotNull(app.auth.session.value?.accountId)
        require(account.startsWith("teacher-fixture-"))
        val repository = ClassRepository(app.database)
        val today = LocalDate.now(AppTime.zone)
        repository.saveStudent(account, Student("large-group-student", "虚构大字学生"))
        repository.saveLeave(account, Leave("large-group-leave", "large-group-student", today, today, LeavePeriod.ALL_DAY, Instant.now()))
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val oldScale = device.executeShellCommand("settings get system font_scale").trim()
        val oldAppSize = app.textSizes.state.value
        app.textSizes.select(AppTextSize.SYSTEM)
        device.executeShellCommand("settings put system font_scale 2.0")
        repeat(40) {
            if (kotlin.math.abs(app.resources.configuration.fontScale - 2f) < 0.01f) return@repeat
            SystemClock.sleep(100)
        }
        val date = "请假日期 " + today.format(DateTimeFormatter.ofPattern("yyyy年M月d日"))
        try {
            ActivityScenario.launch(MainActivity::class.java).use {
                text(device, "记录").click()
                val heading = scrollToDescription(device, date)
                assertTrue(heading.visibleBounds.height() > 0)
                heading.click()
                assertTrue(device.wait(Until.gone(By.text("虚构大字学生")), 5_000))
                scrollToDescription(device, date).click()
                scrollTo(device, "虚构大字学生")
                assertTrue(device.hasObject(By.text("虚构大字学生")))
            }
        } finally {
            cleanup(app, account, listOf(RecordKind.STUDENT to "large-group-student", RecordKind.LEAVE to "large-group-leave"))
            app.textSizes.select(oldAppSize)
            if (oldScale == "null" || oldScale.isBlank()) device.executeShellCommand("settings delete system font_scale")
            else device.executeShellCommand("settings put system font_scale $oldScale")
        }
    }

    @Test fun groupsByStartDateAndKeepsCrossDayLeaveAsOneRecord() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("teacherFixture") == "1")
        val app = ApplicationProvider.getApplicationContext<ManmanApplication>()
        app.auth.restore()
        val account = requireNotNull(app.auth.session.value?.accountId)
        require(account.startsWith("teacher-fixture-"))
        val repository = ClassRepository(app.database)
        val today = LocalDate.now(AppTime.zone)
        val future = today.plusDays(2)
        val past = today.minusDays(2)
        val names = listOf("虚构今天甲", "虚构今天乙", "虚构未来", "虚构过去")
        names.forEachIndexed { index, name -> repository.saveStudent(account, Student("group-$index", name, position = index)) }
        val now = Instant.now()
        repository.saveLeave(account, Leave("today-1", "group-0", today, today, LeavePeriod.MORNING, now))
        repository.saveLeave(account, Leave("today-2", "group-1", today, today.plusDays(1), LeavePeriod.ALL_DAY, now.plusSeconds(1), cancelled = true))
        repository.saveLeave(account, Leave("future", "group-2", future, future.plusDays(2), LeavePeriod.ALL_DAY, now))
        repository.saveLeave(account, Leave("past", "group-3", past, past, LeavePeriod.AFTERNOON, now))
        repository.saveMarking(account, MarkingRecord("marking", "虚构班级事项", "有", "没有", now,
            listOf(MarkingStudent("group-0", "虚构今天甲", true))))
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val oldScale = device.executeShellCommand("settings get system font_scale").trim()
        val oldAppSize = app.textSizes.state.value
        app.textSizes.select(AppTextSize.STANDARD)
        device.executeShellCommand("settings put system font_scale 1.0")
        fun dateLabel(day: LocalDate) = "请假日期 " + day.format(DateTimeFormatter.ofPattern("yyyy年M月d日"))
        try {
            ActivityScenario.launch(MainActivity::class.java).use {
                text(device, "记录").click()
                assertTrue(device.wait(Until.hasObject(By.text("虚构今天甲")), 10_000))
                assertTrue(device.hasObject(By.text("虚构今天乙")))
                assertFalse(device.hasObject(By.text("虚构未来")))
                assertFalse(device.hasObject(By.text("虚构过去")))
                val end = future.plusDays(2).format(DateTimeFormatter.ofPattern(if (future.year == future.plusDays(2).year) "M月d日" else "yyyy年M月d日"))
                description(device, dateLabel(future)).click()
                assertTrue(device.wait(Until.hasObject(By.text("虚构未来")), 5_000))
                assertTrue(device.hasObject(By.text("至 $end · 全天")))
                description(device, dateLabel(future)).click()
                assertTrue(device.wait(Until.gone(By.text("虚构未来")), 5_000))
                description(device, dateLabel(today)).click()
                assertTrue(device.wait(Until.gone(By.text("虚构今天甲")), 5_000))
                description(device, dateLabel(today)).click()
                assertTrue(device.wait(Until.hasObject(By.text("虚构今天甲")), 5_000))
                scrollTo(device, "虚构班级事项")
                assertTrue(device.hasObject(By.text("虚构班级事项")))
            }
        } finally {
            cleanup(app, account, (0..3).map { RecordKind.STUDENT to "group-$it" } +
                listOf("today-1", "today-2", "future", "past").map { RecordKind.LEAVE to it } +
                listOf(RecordKind.MARKING to "marking"))
            app.textSizes.select(oldAppSize)
            if (oldScale == "null" || oldScale.isBlank()) device.executeShellCommand("settings delete system font_scale")
            else device.executeShellCommand("settings put system font_scale $oldScale")
        }
    }

    private fun text(device: UiDevice, label: String): UiObject2 =
        device.wait(Until.findObject(By.text(label)), 10_000) ?: throw AssertionError("未找到文字：$label")

    private fun description(device: UiDevice, label: String): UiObject2 =
        device.wait(Until.findObject(By.desc(label)), 10_000) ?: throw AssertionError("未找到日期：$label")

    private fun scrollTo(device: UiDevice, label: String) {
        repeat(6) {
            if (device.wait(Until.hasObject(By.text(label)), 1_000)) return
            device.swipe(device.displayWidth / 2, device.displayHeight * 3 / 4, device.displayWidth / 2, device.displayHeight / 3, 20)
        }
        throw AssertionError("未找到文字：$label")
    }

    private fun scrollToDescription(device: UiDevice, label: String): UiObject2 {
        repeat(6) {
            device.wait(Until.findObject(By.desc(label)), 1_000)?.let { return it }
            device.swipe(device.displayWidth / 2, device.displayHeight * 3 / 4, device.displayWidth / 2, device.displayHeight / 3, 20)
        }
        throw AssertionError("未找到日期：$label")
    }

    private suspend fun cleanup(app: ManmanApplication, account: String, entries: List<Pair<String, String>>) {
        val dao = app.database.records()
        entries.forEach { (kind, id) ->
            dao.find(account, kind, id)?.let { record ->
                if (!record.deleted) dao.saveLocal(account, kind, id, record.payload, deleted = true)
            }
        }
    }
}
