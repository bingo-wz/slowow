package app.manman.teacher

import android.os.SystemClock
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.StaleObjectException
import androidx.test.uiautomator.Configurator
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import app.manman.teacher.recognition.SchedulePhase
import app.manman.teacher.recognition.ScheduleProgressUpdate
import app.manman.teacher.fonts.AppTextSize
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ActualScheduleProgressUiInstrumentedTest {
    @Test fun actualAppContinuouslyUpdatesProgress() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("teacherFixture") == "1")
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val config = Configurator.getInstance()
        val oldIdleTimeout = config.waitForIdleTimeout
        config.waitForIdleTimeout = 1_000
        val app = ApplicationProvider.getApplicationContext<ManmanApplication>()
        val oldAppSize = app.textSizes.state.value
        app.textSizes.select(AppTextSize.SYSTEM)
        val oldScale = device.executeShellCommand("settings get system font_scale").trim()
        setFontScale(device, app, "2.0")
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                assertTrue(device.wait(Until.hasObject(By.desc("设置")), 15_000))
                device.findObject(By.desc("设置")).click()
                assertTrue(device.wait(Until.hasObject(By.text("课程与作息")), 10_000))
                device.findObject(By.text("课程与作息")).click()
                assertTrue(device.wait(Until.hasObject(By.desc("识别课程表和作息表")), 10_000))
                lateinit var state: MutableStateFlow<MainUiState>
                val started = SystemClock.elapsedRealtime()
                scenario.onActivity { activity ->
                    val model = ViewModelProvider(activity, MainViewModel.factory(app))[MainViewModel::class.java]
                    val field = MainViewModel::class.java.getDeclaredField("mutableState")
                    field.isAccessible = true
                    @Suppress("UNCHECKED_CAST")
                    state = field.get(model) as MutableStateFlow<MainUiState>
                    state.update { it.copy(busy = true, scheduleProgress = ScheduleProgressState(ScheduleProgressUpdate(SchedulePhase.UPLOADING, 37, 100), started)) }
                }
                val uploading = device.wait(Until.findObject(By.text("正在发送图片 37%")), 10_000)
                assertTrue("上传进度应显示在屏幕上", uploading != null && uploading.visibleBounds.height() > 0)
                state.update { it.copy(scheduleProgress = ScheduleProgressState(ScheduleProgressUpdate(SchedulePhase.RECEIVING), started)) }
                val receiving = device.wait(Until.findObject(By.text("AI 正在返回识别内容，完成后即可核对")), 10_000)
                if (receiving == null || receiving.visibleBounds.height() == 0) {
                    device.dumpWindowHierarchy(File(app.cacheDir, "schedule-progress-failed.xml"))
                    device.takeScreenshot(File(app.cacheDir, "schedule-progress-failed.png"))
                }
                assertTrue("识别状态应显示在屏幕上", receiving != null && receiving.visibleBounds.height() > 0)
                val elapsed = device.wait(Until.findObject(By.textContains("已用 ")), 10_000)
                assertTrue(elapsed != null && elapsed.visibleBounds.height() > 0)
                val first = elapsed.text
                var updated = false
                repeat(20) {
                    SystemClock.sleep(250)
                    try {
                        val current = device.findObject(By.textContains("已用 "))
                        if (current != null && current.text != first) updated = true
                    } catch (_: StaleObjectException) { }
                }
                if (!updated) {
                    device.dumpWindowHierarchy(File(app.cacheDir, "schedule-elapsed-failed.xml"))
                    device.takeScreenshot(File(app.cacheDir, "schedule-elapsed-failed.png"))
                    File(app.cacheDir, "schedule-elapsed-first.txt").writeText(first)
                }
                assertTrue("识别等待期间用时应持续更新", updated)
            }
        } finally {
            app.textSizes.select(oldAppSize)
            try { setFontScale(device, app, oldScale) }
            finally { config.waitForIdleTimeout = oldIdleTimeout }
        }
    }

    private fun setFontScale(device: UiDevice, app: ManmanApplication, value: String) {
        if (value == "null" || value.isBlank()) device.executeShellCommand("settings delete system font_scale")
        else device.executeShellCommand("settings put system font_scale $value")
        val expected = value.toFloatOrNull() ?: 1f
        repeat(40) {
            if (kotlin.math.abs(app.resources.configuration.fontScale - expected) < 0.01f) return
            SystemClock.sleep(100)
        }
        throw AssertionError("系统字号未切换到测试值")
    }
}
