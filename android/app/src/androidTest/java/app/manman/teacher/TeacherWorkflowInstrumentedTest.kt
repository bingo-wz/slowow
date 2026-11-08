package app.manman.teacher

import androidx.test.core.app.ActivityScenario
import android.content.Intent
import android.content.ContentValues
import android.app.NotificationManager
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.StaleObjectException
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import app.manman.teacher.auth.SecureSessionStore
import app.manman.teacher.data.ClassRepository
import app.manman.teacher.data.Lesson
import app.manman.teacher.data.TimeSlot
import app.manman.teacher.data.WorkTask
import app.manman.teacher.domain.Leave
import app.manman.teacher.domain.LeavePeriod
import app.manman.teacher.domain.AppTime
import app.manman.teacher.domain.Student
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.LocalDate
import java.time.LocalTime
import java.time.Instant
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class TeacherWorkflowInstrumentedTest {
    @Test fun classAbsenceAndMarkingCanBeUsedOnDevice() = runBlocking {
        val application = ApplicationProvider.getApplicationContext<ManmanApplication>()
        assumeTrue(InstrumentationRegistry.getArguments().getString("teacherFixture") == "1")
        application.auth.restore()
        val account = requireNotNull(application.auth.session.value?.accountId)
        require(account.startsWith("teacher-fixture-"))
        assertEquals(account, application.auth.session.value?.accountId)
        val repository = ClassRepository(application.database)
        val students = (1..52).map { index -> Student("student-$index", "学生%02d".format(index), normallyEatsAtSchool = index > 3, position = index) }
        students.forEach { repository.saveStudent(account, it) }
        val today = LocalDate.now(AppTime.zone)
        val slot = TimeSlot("slot-1", "第一节", LocalTime.of(8, 0), LocalTime.of(8, 40))
        repository.saveTimeSlot(account, slot)
        repository.saveLesson(account, Lesson("lesson-1", today.dayOfWeek.value, slot.id, "语文"))
        listOf(4, 5).forEach { index ->
            repository.saveLeave(account, Leave("leave-$index", "student-$index", today, today, LeavePeriod.ALL_DAY,
                today.atTime(9, 0).atZone(AppTime.zone).toInstant()))
        }
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val oldScale = device.executeShellCommand("settings get system font_scale").trim()
        device.executeShellCommand("settings put system font_scale 1.0")
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            val homeVisible = device.wait(Until.hasObject(By.desc("本周菜单")), 20_000)
            if (!homeVisible) {
                device.takeScreenshot(File(application.cacheDir, "manman-diagnostic.png"))
                device.dumpWindowHierarchy(File(application.cacheDir, "manman-diagnostic.xml"))
            }
            assertTrue(homeVisible)
            assertTrue(device.wait(Until.hasObject(By.text("今日待办")), 10_000))
            assertTrue(device.takeScreenshot(File(application.cacheDir, "manman-home.png")))
            device.executeShellCommand("screencap -p /sdcard/Download/manman-home-current.png")
            tapText(device, "记录")
            tapText(device, "不在校用餐名单")
            assertTrue(device.wait(Until.hasObject(By.text("共 5 人")), 10_000))
            assertNotNull(device.findObject(By.text("学生04")))
            assertTrue(device.takeScreenshot(File(application.cacheDir, "manman-lunch.png")))
            device.pressBack()
            tapText(device, "今天")
            tapDescription(device, "设置")
            tapText(device, "班级与学生")
            assertTrue(device.wait(Until.hasObject(By.text("班级标记")), 10_000))
            tapText(device, "班级标记")
            assertTrue(device.wait(Until.hasObject(By.text("保存记录")), 10_000))
            assertTrue(device.takeScreenshot(File(application.cacheDir, "manman-marking.png")))
            device.executeShellCommand("screencap -p /sdcard/Download/manman-marking-current.png")
            device.pressBack()
            device.pressBack()
            tapText(device, "课程与作息")
            assertTrue(device.wait(Until.hasObject(By.text("第一节")), 10_000))
            assertTrue(device.wait(Until.hasObject(By.text("语文")), 10_000))
            assertTrue(!device.hasObject(By.textContains("导入课程")))
            tapDescription(device, "添加节次")
            assertTrue(device.wait(Until.hasObject(By.clazz("android.widget.EditText")), 10_000))
            device.findObject(By.clazz("android.widget.EditText")).text = "活动"
            tapText(device, "保存")
            assertTrue(device.wait(Until.hasObject(By.text("活动")), 10_000))
            repeat(3) {
                if (!device.hasObject(By.desc("添加活动课程"))) {
                    device.swipe(device.displayWidth / 2, device.displayHeight * 3 / 4,
                        device.displayWidth / 2, device.displayHeight / 3, 20)
                }
            }
            tapDescription(device, "添加活动课程")
            assertTrue(device.wait(Until.hasObject(By.clazz("android.widget.EditText")), 10_000))
            device.findObject(By.clazz("android.widget.EditText")).text = "班会"
            tapText(device, "保存")
            assertTrue(device.wait(Until.hasObject(By.text("班会")), 10_000))
            device.pressBack()
            device.pressBack()
            scenario.onActivity { activity ->
                activity.startActivity(Intent(activity, MainActivity::class.java).setAction(Intent.ACTION_SEND)
                    .setType("text/plain").putExtra(Intent.EXTRA_TEXT, "明天下午开班会。")
                    .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP))
            }
            assertTrue(device.wait(Until.hasObject(By.text("添加通知")), 10_000))
            val fields = device.findObjects(By.clazz("android.widget.EditText"))
            assertTrue(fields.size >= 2)
            fields[0].text = "班会安排通知"
            assertEquals("明天下午开班会。", fields[1].text)
            tapText(device, "保存通知")
            assertTrue(device.wait(Until.hasObject(By.text("班会安排通知")), 10_000))
            tapText(device, "班会安排通知")
            assertTrue(device.wait(Until.hasObject(By.text("明天下午开班会。")), 10_000))
            tapText(device, "识别通知中的待办")
            assertTrue(device.wait(Until.hasObject(By.text("发送这条通知进行识别？")), 10_000))
            tapText(device, "取消")
            device.pressBack()
            val bitmap = Bitmap.createBitmap(24, 24, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.GREEN) }
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "manman-test-${UUID.randomUUID()}.png")
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            }
            val source = application.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)!!
            application.contentResolver.openOutputStream(source)!!.use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            scenario.onActivity { activity ->
                activity.startActivity(Intent(activity, MainActivity::class.java).setAction(Intent.ACTION_SEND).setType("image/png")
                    .putExtra(Intent.EXTRA_STREAM, source).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_GRANT_READ_URI_PERMISSION))
            }
            assertTrue(device.wait(Until.hasObject(By.text("添加通知")), 10_000))
            assertTrue(device.wait(Until.hasObject(By.text("图片 1")), 10_000))
            device.findObjects(By.clazz("android.widget.EditText"))[0].text = "图片通知"
            tapText(device, "保存通知")
            assertTrue(device.wait(Until.hasObject(By.text("图片通知")), 10_000))
            tapText(device, "图片通知")
            assertTrue(device.wait(Until.hasObject(By.text("查看图片 1  ›")), 10_000))
            tapText(device, "查看图片 1  ›")
            assertTrue(device.wait(Until.hasObject(By.text("查看原图")), 10_000))
            application.contentResolver.delete(source, null, null)
            bitmap.recycle()

            device.executeShellCommand("pm grant app.manman.teacher android.permission.POST_NOTIFICATIONS")
            val now = Instant.now()
            val task = WorkTask("reminder-${UUID.randomUUID()}", "测试提醒", null, null, now.plusMillis(1500), false, now)
            repository.saveTask(account, task)
            application.reminders.reschedule()
            delay(1800)
            application.reminders.deliver(task.remindAt!!.toEpochMilli())
            val notifications = application.getSystemService(NotificationManager::class.java)
            assertTrue(notifications.activeNotifications.any { it.id == 1001 })
            notifications.cancel(1001)
            repository.saveTask(account, task.copy(done = true))
            application.reminders.deliver(task.remindAt.toEpochMilli())
            assertTrue(notifications.activeNotifications.none { it.id == 1001 })
        } finally {
            scenario.close()
            SecureSessionStore(application).clear()
            if (oldScale == "null" || oldScale.isBlank()) device.executeShellCommand("settings delete system font_scale")
            else device.executeShellCommand("settings put system font_scale $oldScale")
        }
        Unit
    }

    private fun tapText(device: UiDevice, text: String) {
        repeat(3) {
            try {
                val element = device.wait(Until.findObject(By.text(text)), 5_000)
                if (element != null) {
                    element.click()
                    return
                }
            } catch (_: StaleObjectException) {
                device.waitForIdle()
            }
        }
        val cache = ApplicationProvider.getApplicationContext<ManmanApplication>().cacheDir
        device.dumpWindowHierarchy(File(cache, "manman-tap-failed.xml"))
        device.takeScreenshot(File(cache, "manman-tap-failed.png"))
        throw AssertionError("无法点击界面文字：$text")
    }

    private fun tapDescription(device: UiDevice, label: String) {
        val element = device.wait(Until.findObject(By.desc(label)), 10_000)
            ?: throw AssertionError("无法找到图标：$label")
        element.click()
    }
}
