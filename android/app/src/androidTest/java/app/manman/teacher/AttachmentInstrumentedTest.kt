package app.manman.teacher

import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.manman.teacher.data.ClassRepository
import app.manman.teacher.data.Notice
import app.manman.teacher.data.RecordCodec
import app.manman.teacher.data.RecordKind
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.Instant
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class AttachmentInstrumentedTest {
    @Test fun privateImagesAreCheckedAndQueuedBeforeTheirNotice() = runBlocking {
        val application = ApplicationProvider.getApplicationContext<ManmanApplication>()
        val source = File(application.cacheDir, "image-source-${UUID.randomUUID()}.png")
        val bitmap = Bitmap.createBitmap(24, 24, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.GREEN) }
        source.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val files = application.attachments
        val image = files.stage(Uri.fromFile(source))
        val account = "test-${UUID.randomUUID()}"
        files.adopt(account, image)
        val preview = files.preview(account, image) { throw AssertionError("本机图片不应联网") }
        assertEquals(24, preview.width)
        try {
            files.preview("other-$account", image) { throw IllegalStateException("其他账号没有本机原图") }
            throw AssertionError("不同账号不应读取原图")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message!!.contains("其他账号"))
        }
        val notice = Notice("notice-${UUID.randomUUID()}", "图片通知", "", Instant.now(), listOf(image.id))
        ClassRepository(application.database).saveNotice(account, notice, listOf(image))
        val pending = application.database.records().pending(account)
        assertEquals(RecordKind.ATTACHMENT, pending.first().kind)
        assertEquals(image, RecordCodec.attachment(pending.first()))
        assertEquals(listOf(image.id), RecordCodec.notice(pending.first { it.kind == RecordKind.NOTICE }).attachmentIds)
        source.delete()
        bitmap.recycle()
        preview.recycle()
    }
}
