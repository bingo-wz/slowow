package app.manman.teacher

import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.manman.teacher.auth.AuthSession
import app.manman.teacher.auth.SessionProvider
import app.manman.teacher.recognition.SchedulePhase
import app.manman.teacher.recognition.ScheduleProgressUpdate
import app.manman.teacher.recognition.ScheduleRecognition
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalTime
import java.util.Collections
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class ScheduleRecognitionInstrumentedTest {
    private val session = object : SessionProvider {
        override val session = MutableStateFlow<AuthSession?>(null)
        override suspend fun restore() = Unit
        override suspend fun currentAccessToken(accountId: String) = "fictional-token"
    }

    @Test fun twoImagesAreUploadedTogetherAndActualPhasesAreReported() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<ManmanApplication>()
        val images = makeImages(app)
        val response = """
            data: {"type":"phase","phase":"received"}

            data: {"type":"heartbeat"}

            data: {"type":"phase","phase":"generating"}

            data: {"type":"result","result":{"slots":[{"weekday":1,"title":"第一节","start":"09:05","end":"09:45"}],"lessons":[{"weekday":1,"slot":"第一节","subject":"虚构语文"}],"warnings":[]}}

        """.trimIndent()
        val connection = FakeConnection(response.byteInputStream())
        val updates = Collections.synchronizedList(mutableListOf<ScheduleProgressUpdate>())
        try {
            val client = ScheduleRecognition("https://example.test", session) { connection }
            val result = client.recognize(app, "fictional", images.map(Uri::fromFile)) { updates.add(it) }
            val request = JSONObject(connection.sent.toString(Charsets.UTF_8.name()))
            assertTrue(request.getBoolean("confirmed"))
            assertEquals(2, request.getJSONArray("files").length())
            assertEquals("image/png", request.getJSONArray("files").getJSONObject(0).getString("kind"))
            assertEquals(2, updates.count { it.phase == SchedulePhase.PREPARING })
            assertTrue(updates.any { it.phase == SchedulePhase.UPLOADING && it.current == 100 })
            assertTrue(updates.any { it.phase == SchedulePhase.WAITING })
            assertTrue(updates.any { it.phase == SchedulePhase.RECEIVING })
            assertEquals(LocalTime.of(9, 5), result.slots.single().start)
            assertEquals("虚构语文", result.lessons.single().subject)
        } finally { images.forEach(File::delete) }
    }

    @Test fun stoppingRecognitionDisconnectsTheNetworkRequest() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<ManmanApplication>()
        val images = makeImages(app)
        val disconnected = CountDownLatch(1)
        val ready = CompletableDeferred<Unit>()
        val blocked = object : InputStream() {
            override fun read(): Int { disconnected.await(5, TimeUnit.SECONDS); return -1 }
        }
        val connection = FakeConnection(blocked, disconnected)
        try {
            val client = ScheduleRecognition("https://example.test", session) { connection }
            val job = async { client.recognize(app, "fictional", images.map(Uri::fromFile)) {
                if (it.phase == SchedulePhase.WAITING) ready.complete(Unit)
            } }
            withTimeout(10_000) { ready.await() }
            job.cancelAndJoin()
            assertTrue(disconnected.await(5, TimeUnit.SECONDS))
        } finally { images.forEach(File::delete) }
    }

    @Test fun connectionRetriesOnlyBeforeImageBytesAreSent() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<ManmanApplication>()
        val images = makeImages(app)
        val response = """data: {"type":"result","result":{"slots":[{"weekday":1,"title":"第一节","start":"09:05","end":"09:45"}],"lessons":[],"warnings":[]}}

        """.trimIndent()
        val updates = Collections.synchronizedList(mutableListOf<ScheduleProgressUpdate>())
        var connections = 0
        try {
            val client = ScheduleRecognition("https://example.test", session) {
                connections++
                if (connections == 1) FakeConnection(response.byteInputStream(), failBeforeUpload = true)
                else FakeConnection(response.byteInputStream())
            }
            client.recognize(app, "fictional", images.map(Uri::fromFile)) { updates.add(it) }
            assertEquals(2, connections)
            assertTrue(updates.any { it.phase == SchedulePhase.RECONNECTING && it.current == 1 })

            connections = 0
            val partial = ScheduleRecognition("https://example.test", session) {
                connections++
                FakeConnection(ByteArrayInputStream(byteArrayOf()), failWhileWriting = true)
            }
            try {
                partial.recognize(app, "fictional", images.map(Uri::fromFile)) { }
                fail("图片开始发送后不能自动重试")
            } catch (_: IllegalArgumentException) { }
            assertEquals(1, connections)
        } finally { images.forEach(File::delete) }
    }

    private fun makeImages(app: ManmanApplication): List<File> = (1..2).map { index ->
        File(app.cacheDir, "fictional-schedule-$index-${UUID.randomUUID()}.png").also { file ->
            val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(if (index == 1) Color.WHITE else Color.GREEN)
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }

    private class FakeConnection(private val response: InputStream, private val closed: CountDownLatch? = null,
        private val failBeforeUpload: Boolean = false, private val failWhileWriting: Boolean = false) : HttpURLConnection(URL("https://example.test")) {
        val sent = ByteArrayOutputStream()
        override fun getOutputStream(): OutputStream {
            if (failBeforeUpload) throw IOException("虚构连接失败")
            if (failWhileWriting) return object : OutputStream() {
                override fun write(value: Int) { throw IOException("虚构上传中断") }
                override fun write(bytes: ByteArray, offset: Int, length: Int) {
                    sent.write(bytes, offset, minOf(length, 8))
                    throw IOException("虚构上传中断")
                }
            }
            return sent
        }
        override fun getInputStream() = response
        override fun getResponseCode() = 200
        override fun getContentType() = "text/event-stream; charset=utf-8"
        override fun connect() = Unit
        override fun disconnect() { closed?.countDown() }
        override fun usingProxy() = false
    }
}
