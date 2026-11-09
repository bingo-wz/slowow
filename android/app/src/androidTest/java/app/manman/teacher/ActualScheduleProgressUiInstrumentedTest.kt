package app.manman.teacher

import android.graphics.Bitmap
import android.os.SystemClock
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
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
                assertTrue(device.wait(Until.hasObject(By.text("识别课程表和作息表")), 10_000))
                lateinit var state: MutableStateFlow<MainUiState>
                val started = SystemClock.elapsedRealtime()
                val initial = screenshot()
                scenario.onActivity { activity ->
                    val model = ViewModelProvider(activity, MainViewModel.factory(app))[MainViewModel::class.java]
                    val field = MainViewModel::class.java.getDeclaredField("mutableState")
                    field.isAccessible = true
                    @Suppress("UNCHECKED_CAST")
                    state = field.get(model) as MutableStateFlow<MainUiState>
                    state.update { it.copy(busy = true, scheduleProgress = ScheduleProgressState(ScheduleProgressUpdate(SchedulePhase.UPLOADING, 37, 100), started)) }
                }
                val uploading = awaitVisualChange(initial, 0.415f, 0.46f, 120)
                initial.recycle()
                state.update { it.copy(scheduleProgress = ScheduleProgressState(ScheduleProgressUpdate(SchedulePhase.RECEIVING), started)) }
                val receiving = awaitVisualChange(uploading, 0.415f, 0.46f, 120)
                uploading.recycle()
                val ticking = awaitVisualChange(receiving, 0.52f, 0.56f, 20)
                receiving.recycle()
                ticking.recycle()
            }
        } finally {
            app.textSizes.select(oldAppSize)
            try { setFontScale(device, app, oldScale) }
            finally { config.waitForIdleTimeout = oldIdleTimeout }
        }
    }

    private fun screenshot(): Bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        ?: throw AssertionError("无法截取测试画面")

    private fun awaitVisualChange(previous: Bitmap, top: Float, bottom: Float, threshold: Int): Bitmap {
        repeat(30) {
            SystemClock.sleep(250)
            val current = screenshot()
            var changed = 0
            for (y in (current.height * top).toInt() until (current.height * bottom).toInt() step 2) {
                for (x in (current.width * 0.08f).toInt() until (current.width * 0.90f).toInt() step 2) {
                    if (current.getPixel(x, y) != previous.getPixel(x, y)) changed++
                }
            }
            if (changed >= threshold) return current
            current.recycle()
        }
        throw AssertionError("识别状态或已用时间未在画面中更新")
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
