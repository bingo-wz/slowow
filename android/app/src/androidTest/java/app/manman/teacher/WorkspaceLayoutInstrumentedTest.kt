package app.manman.teacher

import android.content.ClipboardManager
import android.content.Context
import android.graphics.Rect
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import app.manman.teacher.auth.AuthSession
import app.manman.teacher.auth.SecureSessionStore
import app.manman.teacher.data.ClassRepository
import app.manman.teacher.data.MarkingRecord
import app.manman.teacher.data.MarkingStudent
import app.manman.teacher.data.WorkTask
import app.manman.teacher.domain.AppTime
import app.manman.teacher.domain.Leave
import app.manman.teacher.domain.LeavePeriod
import app.manman.teacher.domain.Student
import app.manman.teacher.fonts.AppTextSize
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class WorkspaceLayoutInstrumentedTest {
    @Test fun homeAndRecordsRemainUsableAtNormalAndLargeSystemTextSizes() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("designFixture") == "1")
        val app = ApplicationProvider.getApplicationContext<ManmanApplication>()
        val account = "design-fixture-${UUID.randomUUID()}"
        val repository = ClassRepository(app.database)
        val today = LocalDate.now(AppTime.zone)
        val now = Instant.now()
        val students = listOf(
            Student("fictional-a", "测试甲", normallyEatsAtSchool = false),
            Student("fictional-b", "测试乙", position = 1),
            Student("fictional-c", "测试丙", position = 2),
        )
        students.forEach { repository.saveStudent(account, it) }
        repository.saveLeave(account, Leave("fictional-leave", "fictional-b", today, today, LeavePeriod.MORNING, now))
        repository.saveMarking(account, MarkingRecord("fictional-mark", "课外阅读记录", "有", "没有", now,
            listOf(MarkingStudent("fictional-a", "测试甲", true), MarkingStudent("fictional-b", "测试乙", false))))
        listOf("准备明天的语文作业", "整理班级通知", "查看课程安排", "和同事确认活动", "收集课堂练习", "准备教学材料").forEachIndexed { index, title ->
            repository.saveTask(account, WorkTask("fictional-task-$index", title, null, null, null, index == 1, now, today, index.toLong()))
        }
        SecureSessionStore(app).save(AuthSession(account, "test-token", "test-refresh", System.currentTimeMillis() + 3_600_000))
        val oldSize = app.textSizes.state.value
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val oldScale = device.executeShellCommand("settings get system font_scale").trim()
        app.textSizes.select(AppTextSize.SYSTEM)
        try {
            listOf("1.0", "2.0").forEach { scale ->
                device.executeShellCommand("settings put system font_scale $scale")
                ActivityScenario.launch(MainActivity::class.java).use {
                    assertTrue(device.wait(Until.hasObject(By.text("今日待办")), 20_000))
                    assertTrue(device.wait(Until.hasObject(By.text("准备明天的语文作业")), 10_000))
                    val menu = device.findObject(By.desc("本周菜单")).visibleBounds
                    val notice = device.findObject(By.desc("学校通知")).visibleBounds
                    val settings = device.findObject(By.desc("设置")).visibleBounds
                    val all = device.findObject(By.desc("全部待办")).visibleBounds
                    val add = device.findObject(By.desc("新建待办")).visibleBounds
                    assertFalse(Rect.intersects(menu, notice))
                    assertFalse(Rect.intersects(notice, settings))
                    assertFalse(Rect.intersects(all, add))
                    assertTrue(device.takeScreenshot(File(app.cacheDir, "workspace-home-$scale.png")))
                    device.findObject(By.text("记录")).click()
                    assertTrue(device.wait(Until.hasObject(By.text("不在校用餐名单")), 10_000))
                    assertTrue(device.takeScreenshot(File(app.cacheDir, "workspace-records-$scale.png")))
                    device.findObject(By.desc("复制今日不在校用餐名单")).click()
                    val clipboard = app.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    assertEquals("测试甲、测试乙", clipboard.primaryClip!!.getItemAt(0).coerceToText(app).toString())
                }
            }
        } finally {
            app.textSizes.select(oldSize)
            if (oldScale == "null" || oldScale.isBlank()) device.executeShellCommand("settings delete system font_scale")
            else device.executeShellCommand("settings put system font_scale $oldScale")
        }
    }
}
