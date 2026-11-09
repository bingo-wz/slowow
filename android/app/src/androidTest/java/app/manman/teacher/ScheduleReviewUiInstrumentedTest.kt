package app.manman.teacher

import androidx.activity.compose.setContent
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import android.os.SystemClock
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.ui.Modifier
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import app.manman.teacher.recognition.ScheduleLessonSuggestion
import app.manman.teacher.recognition.ScheduleSlotSuggestion
import app.manman.teacher.recognition.ScheduleSuggestion
import app.manman.teacher.ui.ManmanTheme
import app.manman.teacher.ui.ScheduleReviewScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.LocalTime

@RunWith(AndroidJUnit4::class)
class ScheduleReviewUiInstrumentedTest {
    @Test fun largeFontReviewCanEditAndKeepWeekdayTimes() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("scheduleFixture") == "1")
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val oldScale = device.executeShellCommand("settings get system font_scale").trim()
        val app = ApplicationProvider.getApplicationContext<ManmanApplication>()
        applyFontScale(device, app, "2.0")
        val original = File(app.cacheDir, "fictional-review-source.png")
        Bitmap.createBitmap(40, 50, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.WHITE)
            original.outputStream().use { compress(Bitmap.CompressFormat.PNG, 100, it) }
            recycle()
        }
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                lateinit var model: MainViewModel
                scenario.onActivity { activity ->
                    model = MainViewModel(app)
                    model.updateScheduleSuggestion(ScheduleSuggestion(listOf(
                        ScheduleSlotSuggestion(1, "第一节", LocalTime.of(9, 5), LocalTime.of(9, 45)),
                        ScheduleSlotSuggestion(2, "第一节", LocalTime.of(8, 35), LocalTime.of(9, 15)),
                    ), listOf(ScheduleLessonSuggestion(1, "第一节", "虚构语文"),
                        ScheduleLessonSuggestion(2, "第一节", "虚构数学")), listOf("请核对时间")))
                    activity.setContent {
                        val state by model.state.collectAsStateWithLifecycle()
                        state.scheduleSuggestion?.let { suggestion ->
                            ManmanTheme {
                                Scaffold { padding -> Box(Modifier.padding(padding)) {
                                    ScheduleReviewScreen(suggestion, state.copy(scheduleSourceUris = listOf(Uri.fromFile(original).toString())), model) {}
                                } }
                            }
                        }
                    }
                }
                assertTrue(device.wait(Until.hasObject(By.text("保存识别结果")), 15_000))
                scrollTo(device, "原图 1")
                device.findObject(By.text("原图 1")).click()
                assertTrue(device.wait(Until.hasObject(By.desc("课程或作息原图")), 10_000))
                device.pressBack()
                assertTrue(device.wait(Until.hasObject(By.text("保存识别结果")), 5_000))
                scrollTo(device, "虚构语文")
                device.findObject(By.text("虚构语文")).click()
                assertTrue(device.wait(Until.hasObject(By.text("修改课程")), 5_000))
                val edits = device.findObjects(By.clazz("android.widget.EditText"))
                assertEquals(2, edits.size)
                edits[1].text = "虚构音乐"
                device.findObject(By.text("完成")).click()
                assertTrue(device.wait(Until.hasObject(By.text("虚构音乐")), 5_000))
                assertEquals("虚构音乐", model.state.value.scheduleSuggestion!!.lessons.first().subject)
                assertEquals(LocalTime.of(9, 5), model.state.value.scheduleSuggestion!!.slots.first().start)
                assertEquals(LocalTime.of(8, 35), model.state.value.scheduleSuggestion!!.slots.last().start)
                assertTrue(device.takeScreenshot(File(app.cacheDir, "schedule-review-large.png")))
            }
        } finally {
            original.delete()
            applyFontScale(device, app, oldScale)
        }
    }

    private fun scrollTo(device: UiDevice, text: String) {
        repeat(7) {
            if (device.wait(Until.hasObject(By.text(text)), 1000)) return
            device.swipe(device.displayWidth / 2, device.displayHeight * 3 / 4, device.displayWidth / 2, device.displayHeight / 3, 20)
        }
        throw AssertionError("未找到：$text")
    }

    private fun applyFontScale(device: UiDevice, app: ManmanApplication, value: String) {
        if (value == "null" || value.isBlank()) device.executeShellCommand("settings delete system font_scale")
        else device.executeShellCommand("settings put system font_scale $value")
        val expected = value.toFloatOrNull() ?: 1f
        repeat(40) {
            if (kotlin.math.abs(app.resources.configuration.fontScale - expected) < 0.01f) {
                device.waitForIdle()
                return
            }
            SystemClock.sleep(100)
        }
        throw AssertionError("系统字号未切换到测试值")
    }
}
