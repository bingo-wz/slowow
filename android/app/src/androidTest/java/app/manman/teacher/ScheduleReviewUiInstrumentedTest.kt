package app.manman.teacher

import androidx.activity.compose.setContent
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
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.LocalTime

@RunWith(AndroidJUnit4::class)
class ScheduleReviewUiInstrumentedTest {
    @Test fun largeFontStillAllowsReviewAndEditing() {
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val oldScale = device.executeShellCommand("settings get system font_scale").trim()
        device.executeShellCommand("settings put system font_scale 2.0")
        val app = ApplicationProvider.getApplicationContext<ManmanApplication>()
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    val model = MainViewModel(app)
                    val result = ScheduleSuggestion(listOf(ScheduleSlotSuggestion(1, "第一节", LocalTime.of(9, 5), LocalTime.of(9, 45))),
                        listOf(ScheduleLessonSuggestion(1, "第一节", "虚构语文")), listOf("请核对时间"))
                    activity.setContent { ManmanTheme { ScheduleReviewScreen(result, MainUiState(accountId = "test"), model) {} } }
                }
                assertTrue(device.wait(Until.hasObject(By.text("合并并保存")), 15_000))
                assertTrue(device.wait(Until.hasObject(By.text("周一 · 第一节")), 5_000))
                device.findObject(By.text("周一 · 第一节")).click()
                assertTrue(device.wait(Until.hasObject(By.text("修改作息")), 5_000))
                assertTrue(device.wait(Until.hasObject(By.text("完成")), 5_000))
                device.takeScreenshot(File(app.cacheDir, "schedule-large-font.png"))
                device.findObject(By.text("完成")).click()
                assertTrue(device.wait(Until.hasObject(By.text("合并并保存")), 5_000))
            }
        } finally {
            if (oldScale == "null" || oldScale.isBlank()) device.executeShellCommand("settings delete system font_scale")
            else device.executeShellCommand("settings put system font_scale $oldScale")
        }
    }
}
