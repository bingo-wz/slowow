package app.manman.teacher

import android.graphics.Rect
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import app.manman.teacher.data.RecordCodec
import app.manman.teacher.data.RecordKind
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class LargeFontLayoutInstrumentedTest {
    @Test fun markingFieldsAndStudentsRemainUsable() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<ManmanApplication>()
        assumeTrue(InstrumentationRegistry.getArguments().getString("releaseFixture") == "1")
        app.auth.restore()
        val account = requireNotNull(app.auth.session.value?.accountId)
        require(account.startsWith("release-fixture-"))
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            val homeReady = device.wait(Until.hasObject(By.text("班级")), 20_000)
            if (!homeReady) device.dumpWindowHierarchy(File("/sdcard/Download/manman-layout-test.xml"))
            assertTrue(homeReady)
            device.findObject(By.text("班级")).click()
            assertTrue(device.wait(Until.hasObject(By.text("班级标记")), 10_000))
            device.findObject(By.text("班级标记")).click()
            assertTrue(device.wait(Until.hasObject(By.text("事项名称")), 10_000))
            val fields = device.findObjects(By.clazz("android.widget.EditText"))
            assertEquals(3, fields.size)
            for (i in fields.indices) for (j in i + 1 until fields.size) {
                assertFalse("输入框不能重叠", Rect.intersects(fields[i].visibleBounds, fields[j].visibleBounds))
            }
            assertTrue(device.wait(Until.hasObject(By.descContains("学生01，有")), 10_000))
            device.waitForIdle()
            val student = device.findObject(By.descContains("学生01，有"))
            val center = student.visibleCenter
            device.executeShellCommand("input tap ${center.x} ${center.y}")
            device.waitForIdle()
            device.findObjects(By.clazz("android.widget.EditText"))[0].text = "测试事项"
            val save = device.wait(Until.findObject(By.text("保存记录")), 10_000)
            assertTrue(save != null)
            save.click()
            assertTrue(device.wait(Until.hasObject(By.text("学生名单")), 10_000))
            val record = app.database.records().pending(account, 100).first { it.kind == RecordKind.MARKING }
            assertFalse("应保存被点击学生的状态", RecordCodec.marking(record).students.first { it.name == "学生01" }.first)
        } finally {
            scenario.close()
        }
    }
}
