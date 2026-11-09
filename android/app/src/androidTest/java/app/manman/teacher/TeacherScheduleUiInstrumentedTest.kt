package app.manman.teacher

import android.os.SystemClock
import android.graphics.Paint
import android.util.TypedValue
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.StaleObjectException
import androidx.test.uiautomator.Until
import app.manman.teacher.auth.AuthSession
import app.manman.teacher.auth.SecureSessionStore
import app.manman.teacher.data.ClassRepository
import app.manman.teacher.data.Lesson
import app.manman.teacher.data.RecordCodec
import app.manman.teacher.data.RecordKind
import app.manman.teacher.data.ScheduleKind
import app.manman.teacher.data.ScheduleProfile
import app.manman.teacher.data.TimeSlot
import app.manman.teacher.domain.AppTime
import app.manman.teacher.fonts.AppTextSize
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class TeacherScheduleUiInstrumentedTest {
    @Test fun weeklyAndDailyViewsRemainUsableAtLargeFontSizes() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("teacherScheduleFixture") == "1")
        val app = ApplicationProvider.getApplicationContext<ManmanApplication>()
        val account = "schedule-ui-fixture-${UUID.randomUUID()}"
        val repository = ClassRepository(app.database)
        val today = LocalDate.now(AppTime.zone)
        val weekday = today.dayOfWeek.value
        val day = setOf(weekday)
        val slots = listOf(
            TimeSlot("fictional-first", "第一节", LocalTime.of(8, 35), LocalTime.of(9, 15), day),
            TimeSlot("fictional-second", "第二节", LocalTime.of(9, 25), LocalTime.of(10, 5), day),
            TimeSlot("fictional-third", "第三节", LocalTime.of(10, 15), LocalTime.of(10, 55), day),
            TimeSlot("fictional-fourth", "第四节", LocalTime.of(11, 5), LocalTime.of(11, 45), day),
        )
        slots.forEach { repository.saveTimeSlot(account, it) }
        val otherDay = if (weekday == 1) 2 else 1
        repository.saveTimeSlot(account, TimeSlot("fictional-other", "第一节", LocalTime.of(9, 5), LocalTime.of(9, 45), setOf(otherDay)))
        repository.saveLesson(account, Lesson("fictional-lesson", weekday, slots[0].id, "虚构语文", ScheduleKind.TEACHING,
            teachers = "测试老师", className = "测试班", location = "测试教室", role = "主讲"))
        repository.saveLesson(account, Lesson("fictional-duty", weekday, slots[1].id, "虚构值守", ScheduleKind.DUTY,
            teachers = "测试老师", endSlotId = slots[2].id))
        repository.saveLesson(account, Lesson("fictional-free", weekday, slots[3].id, "虚构备课", ScheduleKind.FREE,
            teachers = "测试老师"))
        repository.saveScheduleProfile(account, ScheduleProfile("测试学校", "测试学期", "测试班", "测试老师", today.minusWeeks(10), "虚构提醒"))
        SecureSessionStore(app).save(AuthSession(account, "test-token", "test-refresh", System.currentTimeMillis() + 3_600_000))
        val oldAppSize = app.textSizes.state.value
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val oldScale = device.executeShellCommand("settings get system font_scale").trim()
        app.textSizes.select(AppTextSize.SYSTEM)
        try {
            listOf("1.0", "2.0").forEach { scale ->
                device.executeShellCommand("settings put system font_scale $scale")
                ActivityScenario.launch(MainActivity::class.java).use {
                    byDescription(device, "设置").click()
                    byText(device, "课程与作息").click()
                    assertTrue(device.wait(Until.hasObject(By.text("教师课程时间表")), 10_000))
                    assertTrue(device.wait(Until.hasObject(By.text("周视图")), 10_000))
                    assertTrue(device.takeScreenshot(File(app.cacheDir, "teacher-schedule-top-$scale.png")))
                    if (scale == "1.0") {
                        byDescription(device, "下一周").click()
                        if (!device.wait(Until.hasObject(By.text("第 12 周")), 10_000)) {
                            device.dumpWindowHierarchy(File(app.cacheDir, "teacher-schedule-next-week.xml"))
                            device.takeScreenshot(File(app.cacheDir, "teacher-schedule-next-week.png"))
                            throw AssertionError("切换下一周后未显示第 12 周")
                        }
                        byDescription(device, "上一周").click()
                        assertTrue(device.wait(Until.hasObject(By.text("第 11 周")), 10_000))
                        byText(device, "全部教师").click()
                        byText(device, "测试老师").click()
                        byDescription(device, "打印或保存课表 PDF").click()
                        assertTrue("应打开系统打印界面", device.wait(Until.hasObject(By.pkg("com.android.printspooler")), 15_000))
                        device.pressBack()
                        assertTrue(device.wait(Until.hasObject(By.text("教师课程时间表")), 10_000))
                    }
                    scrollTo(device, "第 11 周 · 我的课表")
                    device.swipe(device.displayWidth / 2, device.displayHeight * 3 / 4,
                        device.displayWidth / 2, device.displayHeight / 2, 60)
                    SystemClock.sleep(500)
                    assertTrue(device.takeScreenshot(File(app.cacheDir, "teacher-schedule-week-$scale.png")))
                    if (scale == "2.0") {
                        val range = requireNotNull(device.wait(Until.findObject(By.text("08:35—09:15")), 10_000))
                        val axis = requireNotNull(device.wait(Until.findObject(By.text("08:35")), 10_000))
                        assertTrue("大字号时课程时间应完整可见", range.visibleBounds.width() > 0)
                        assertTrue("大字号时时间轴应显示在一行", axis.visibleBounds.height() > 0)
                        val metrics = app.resources.displayMetrics
                        val paint = Paint().apply { textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 14f, metrics) }
                        assertTrue("课程时间不能被截断", range.visibleBounds.width() >= paint.measureText("08:35—09:15") - 4)
                        val lineHeight = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 20f, metrics)
                        assertTrue("时间轴不能折行", axis.visibleBounds.height() <= lineHeight * 1.2f)
                    }
                    device.pressBack()
                    byText(device, "课程与作息").click()
                    byText(device, "今日视图").click()
                    scrollTo(device, "虚构语文").click()
                    assertTrue(device.wait(Until.hasObject(By.text("编辑事项")), 10_000))
                    assertTrue(device.hasObject(By.text("教师：测试老师")))
                    val expectedLocation = if (scale == "1.0") "测试教室" else "更新后教室"
                    assertTrue(device.hasObject(By.text("地点：$expectedLocation")))
                    byText(device, "编辑事项").click()
                    assertTrue(device.wait(Until.hasObject(By.text("事项类型")), 10_000))
                    if (scale == "1.0") {
                        val location = scrollDialogToField(device, "测试教室")
                        location.text = "更新后教室"
                        byText(device, "保存").click()
                        withTimeout(10_000) {
                            while (RecordCodec.lesson(app.database.records().find(account, RecordKind.LESSON, "fictional-lesson")!!).location != "更新后教室") delay(100)
                        }
                        val saved = RecordCodec.lesson(app.database.records().find(account, RecordKind.LESSON, "fictional-lesson")!!)
                        assertEquals("测试老师", saved.teachers)
                        assertEquals("测试班", saved.className)
                        assertEquals(ScheduleKind.TEACHING, saved.kind)
                    } else device.pressBack()
                    scrollTo(device, "虚构值守")
                    assertTrue(device.takeScreenshot(File(app.cacheDir, "teacher-schedule-day-$scale.png")))
                    scrollTo(device, "虚构值守").click()
                    assertTrue(device.wait(Until.hasObject(By.text("类型：值守 / 班级活动")), 10_000))
                    assertTrue(device.hasObject(By.textContains("09:25—10:55")))
                    device.pressBack()
                }
            }
        } finally {
            app.textSizes.select(oldAppSize)
            if (oldScale == "null" || oldScale.isBlank()) device.executeShellCommand("settings delete system font_scale")
            else device.executeShellCommand("settings put system font_scale $oldScale")
        }
    }

    private fun byText(device: UiDevice, label: String): UiObject2 =
        device.wait(Until.findObject(By.text(label)), 10_000) ?: throw AssertionError("未找到文字：$label")

    private fun byDescription(device: UiDevice, label: String): UiObject2 =
        device.wait(Until.findObject(By.desc(label)), 10_000) ?: throw AssertionError("未找到操作：$label")

    private fun scrollTo(device: UiDevice, label: String): UiObject2 {
        repeat(14) {
            device.waitForIdle()
            try {
                device.wait(Until.findObject(By.text(label)), 800)?.let { found ->
                    if (found.visibleBounds.centerY() < device.displayHeight * 5 / 6) return found
                }
            } catch (_: StaleObjectException) { }
            device.swipe(device.displayWidth / 2, device.displayHeight * 3 / 4,
                device.displayWidth / 2, device.displayHeight / 3, 60)
            SystemClock.sleep(500)
        }
        val cache = ApplicationProvider.getApplicationContext<ManmanApplication>().cacheDir
        device.dumpWindowHierarchy(File(cache, "teacher-schedule-failed.xml"))
        device.takeScreenshot(File(cache, "teacher-schedule-failed.png"))
        throw AssertionError("未找到文字：$label")
    }

    private fun scrollDialogToField(device: UiDevice, value: String): UiObject2 {
        repeat(8) {
            device.waitForIdle()
            device.findObject(By.clazz("android.widget.EditText").text(value))?.let { return it }
            device.swipe(device.displayWidth / 2, device.displayHeight * 3 / 4,
                device.displayWidth / 2, device.displayHeight / 3, 60)
            SystemClock.sleep(400)
        }
        throw AssertionError("未找到输入框：$value")
    }

}
