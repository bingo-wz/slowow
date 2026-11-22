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
import java.time.YearMonth
import java.time.format.DateTimeFormatter

@RunWith(AndroidJUnit4::class)
class RecordsCalendarInstrumentedTest {
    @Test fun calendarRemainsUsableWithLargeSystemText() = runBlocking {
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
        try {
            ActivityScenario.launch(MainActivity::class.java).use {
                text(device, "记录").click()
                val date = dateCell(device, today)
                assertTrue(date.visibleBounds.height() > 0)
                assertFalse(device.hasObject(By.text("虚构大字学生")))
                date.click()
                scrollTo(device, "虚构大字学生")
                device.pressBack()
                assertTrue(dateCell(device, today).visibleBounds.height() > 0)
            }
        } finally {
            cleanup(app, account, listOf(RecordKind.STUDENT to "large-group-student", RecordKind.LEAVE to "large-group-leave"))
            app.textSizes.select(oldAppSize)
            if (oldScale == "null" || oldScale.isBlank()) device.executeShellCommand("settings delete system font_scale")
            else device.executeShellCommand("settings put system font_scale $oldScale")
        }
    }

    @Test fun datesShowTheirLeaveAndMarkingRecords() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("teacherFixture") == "1")
        val app = ApplicationProvider.getApplicationContext<ManmanApplication>()
        app.auth.restore()
        val account = requireNotNull(app.auth.session.value?.accountId)
        require(account.startsWith("teacher-fixture-"))
        val repository = ClassRepository(app.database)
        val today = LocalDate.now(AppTime.zone)
        val future = YearMonth.from(today).plusMonths(1).atDay(5)
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
        try {
            ActivityScenario.launch(MainActivity::class.java).use {
                text(device, "记录").click()
                val date = dateCell(device, today)
                assertTrue(date.contentDescription.contains("2条请假，1条班级标记"))
                assertFalse(device.hasObject(By.text("虚构今天甲")))
                date.click()
                scrollTo(device, "虚构今天甲")
                scrollTo(device, "虚构今天乙")
                scrollTo(device, "虚构班级事项")
                assertFalse(device.hasObject(By.text("虚构未来")))
                assertFalse(device.hasObject(By.text("虚构过去")))

                device.pressBack()
                dateCell(device, today)
                if (today.month == today.plusDays(1).month) {
                    dateCell(device, today.plusDays(1)).click()
                    assertTrue(device.wait(Until.hasObject(By.text("当天没有请假记录。")), 5_000))
                    device.pressBack()
                }
                device.wait(Until.findObject(By.desc("下个月")), 5_000).click()
                val continuing = dateCell(device, future.plusDays(1))
                assertTrue(continuing.contentDescription.contains("1条请假，0条班级标记"))
                continuing.click()
                scrollTo(device, "虚构未来")
                val dateFormat = DateTimeFormatter.ofPattern("M月d日")
                assertTrue(device.hasObject(By.text("${future.format(dateFormat)}至${future.plusDays(2).format(dateFormat)} · 全天")))

                device.pressBack()
                val nextMonth = dateCell(device, future)
                assertTrue(nextMonth.contentDescription.contains("1条请假，0条班级标记"))
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

    private fun scrollTo(device: UiDevice, label: String) {
        repeat(6) {
            if (device.wait(Until.hasObject(By.text(label)), 1_000)) return
            device.swipe(device.displayWidth / 2, device.displayHeight * 3 / 4, device.displayWidth / 2, device.displayHeight / 3, 20)
        }
        throw AssertionError("未找到文字：$label")
    }

    private fun dateCell(device: UiDevice, day: LocalDate): UiObject2 {
        val label = day.format(DateTimeFormatter.ofPattern("yyyy年M月d日")) + " "
        repeat(6) {
            device.wait(Until.findObject(By.descStartsWith(label)), 1_000)?.let { return it }
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
