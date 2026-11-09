package app.manman.teacher

import android.graphics.Rect
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import app.manman.teacher.fonts.AppTextSize
import app.manman.teacher.fonts.AppTextSizes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class TextSizeWorkflowInstrumentedTest {
    @Test fun appFontSizeChangesIndependentlyFromSystemAndPersists() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("textSizeFixture") == "1")
        val app = ApplicationProvider.getApplicationContext<ManmanApplication>()
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val oldScale = device.executeShellCommand("settings get system font_scale").trim()
        val oldAppSize = app.textSizes.state.value
        app.textSizes.select(AppTextSize.SYSTEM)
        device.executeShellCommand("settings put system font_scale 2.0")
        try {
            ActivityScenario.launch(MainActivity::class.java).use {
                assertTrue(device.wait(Until.hasObject(By.text("今日待办")), 20_000))
                val large = byText(device, "今日待办").visibleBounds.height()
                val date = device.wait(Until.findObject(By.textContains("月")), 10_000).visibleBounds
                val menu = byDescription(device, "本周菜单").visibleBounds
                val notice = byDescription(device, "学校通知").visibleBounds
                val settings = byDescription(device, "设置").visibleBounds
                assertFalse(Rect.intersects(date, menu))
                assertFalse(Rect.intersects(menu, notice))
                assertFalse(Rect.intersects(notice, settings))
                assertTrue(menu.centerX() < notice.centerX() && notice.centerX() < settings.centerX())
                assertFalse(device.hasObject(By.textContains("· 今天")))
                assertTrue(device.takeScreenshot(File(app.cacheDir, "today-sprout-large.png")))
                byDescription(device, "设置").click()
                assertTrue(device.wait(Until.hasObject(By.text("班级与学生")), 10_000))
                scrollTo(device, "字体大小").click()
                val dialogShown = device.wait(Until.hasObject(By.textContains("只调整慢慢的文字大小")), 10_000)
                if (!dialogShown) {
                    device.dumpWindowHierarchy(File(app.cacheDir, "text-size-dialog-failed.xml"))
                    device.takeScreenshot(File(app.cacheDir, "text-size-dialog-failed.png"))
                }
                assertTrue("未打开字体大小设置", dialogShown)
                repeat(5) {
                    if (!device.hasObject(By.text("标准"))) {
                        device.swipe(device.displayWidth / 2, device.displayHeight * 3 / 5,
                            device.displayWidth / 2, device.displayHeight * 2 / 5, 20)
                    }
                }
                byText(device, "标准").click()
                byText(device, "完成").click()
                assertEquals(AppTextSize.STANDARD, app.textSizes.state.value)
                assertEquals(AppTextSize.STANDARD, AppTextSizes(app).state.value)
                assertEquals("2.0", device.executeShellCommand("settings get system font_scale").trim())
                device.pressBack()
                val standard = byText(device, "今日待办").visibleBounds.height()
                assertTrue("应用字号应独立变化", standard < large)
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
        device.wait(Until.findObject(By.desc(label)), 10_000) ?: throw AssertionError("未找到图标：$label")

    private fun scrollTo(device: UiDevice, label: String): UiObject2 {
        repeat(6) {
            device.waitForIdle()
            device.wait(Until.findObject(By.text(label)), 1_000)?.let {
                val bounds = it.visibleBounds
                if (bounds.top > device.displayHeight / 6 && bounds.bottom < device.displayHeight * 5 / 6) return it
            }
            device.swipe(device.displayWidth / 2, device.displayHeight * 3 / 4,
                device.displayWidth / 2, device.displayHeight / 3, 60)
            SystemClock.sleep(700)
        }
        val cache = ApplicationProvider.getApplicationContext<ManmanApplication>().cacheDir
        device.dumpWindowHierarchy(File(cache, "text-size-test-failed.xml"))
        device.takeScreenshot(File(cache, "text-size-test-failed.png"))
        throw AssertionError("未找到文字：$label")
    }
}
