package app.manman.teacher

import android.graphics.Color
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfDocument
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.manman.teacher.data.ClassRepository
import app.manman.teacher.data.RecordCodec
import app.manman.teacher.data.RecordKind
import app.manman.teacher.recognition.ScheduleDocuments
import app.manman.teacher.recognition.ScheduleLessonSuggestion
import app.manman.teacher.recognition.ScheduleSlotSuggestion
import app.manman.teacher.recognition.ScheduleSuggestion
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.Assume.assumeTrue
import org.junit.runner.RunWith
import java.io.File
import java.time.LocalTime
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ScheduleImportInstrumentedTest {
    @Test fun providedDocumentsCanBePreparedOnDevice() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("localDocumentFixture") == "1")
        val application = ApplicationProvider.getApplicationContext<ManmanApplication>()
        val pdf = File(application.cacheDir, "provided-schedule.pdf")
        val image = File(application.cacheDir, "provided-timetable.jpg")
        assertTrue(pdf.isFile && image.isFile)
        val files = ScheduleDocuments.read(application, listOf(Uri.fromFile(pdf), Uri.fromFile(image)), 90)
        assertEquals(2, files.size)
        assertTrue(files.all { it.kind.startsWith("image/") && it.data.isNotEmpty() })
        if (InstrumentationRegistry.getArguments().getString("exportLocalFixture") == "1") {
            File(application.cacheDir, "provided-rendered.jpg").writeBytes(files.first().data)
        }
    }

    @Test fun pdfPageIsRenderedAndTemporaryFileIsRemoved() = runBlocking {
        val application = ApplicationProvider.getApplicationContext<ManmanApplication>()
        val source = File(application.cacheDir, "fictional-${UUID.randomUUID()}.pdf")
        val pdf = PdfDocument()
        val page = pdf.startPage(PdfDocument.PageInfo.Builder(400, 600, 1).create())
        page.canvas.drawColor(Color.WHITE)
        pdf.finishPage(page)
        source.outputStream().use(pdf::writeTo)
        pdf.close()
        val files = ScheduleDocuments.read(application, listOf(Uri.fromFile(source)))
        assertEquals(1, files.size)
        assertEquals("image/jpeg", files.single().kind)
        assertTrue(files.single().data.isNotEmpty())
        val rotated = ScheduleDocuments.read(application, listOf(Uri.fromFile(source)), 90).single().data
        val image = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(rotated, 0, rotated.size, image)
        assertTrue(image.outWidth > image.outHeight)
        assertTrue(application.cacheDir.listFiles().orEmpty().none { it.name.startsWith("schedule-") && it.name.endsWith(".pdf") })
        source.delete()
        Unit
    }

    @Test fun weekdayTimesMergeSafelyAndFailedReplacementKeepsData() = runBlocking {
        val application = ApplicationProvider.getApplicationContext<ManmanApplication>()
        val repository = ClassRepository(application.database)
        val account = "schedule-test-${UUID.randomUUID()}"
        fun time(s: String) = LocalTime.parse(s)
        val suggestion = ScheduleSuggestion(listOf(
            ScheduleSlotSuggestion(1, "第一节", time("09:05"), time("09:45")),
            ScheduleSlotSuggestion(2, "第一节", time("08:35"), time("09:15")),
        ), listOf(
            ScheduleLessonSuggestion(1, "第一节", "语文"),
            ScheduleLessonSuggestion(2, "第一节", "数学"),
        ), emptyList())
        repository.saveRecognizedSchedule(account, suggestion, false)
        val dao = application.database.records()
        fun active(records: List<app.manman.teacher.data.LocalRecord>) = records.filterNot { it.deleted }
        var records = active(dao.snapshot(account))
        val slots = records.filter { it.kind == RecordKind.TIME_SLOT }.map(RecordCodec::timeSlot)
        assertEquals(time("09:05"), slots.single { 1 in it.weekdays }.start)
        assertEquals(time("08:35"), slots.single { 2 in it.weekdays }.start)
        repository.saveRecognizedSchedule(account, ScheduleSuggestion(emptyList(), listOf(ScheduleLessonSuggestion(1, "第一节", "音乐")), emptyList()), false)
        records = active(dao.snapshot(account))
        assertEquals(2, records.count { it.kind == RecordKind.LESSON })
        assertEquals("音乐", records.filter { it.kind == RecordKind.LESSON }.map(RecordCodec::lesson).single { it.weekday == 1 }.subject)
        try {
            repository.saveRecognizedSchedule(account, ScheduleSuggestion(emptyList(), listOf(ScheduleLessonSuggestion(1, "不存在的节次", "语文")), emptyList()), true)
            fail("缺少时间时不能替换")
        } catch (_: IllegalArgumentException) { }
        assertEquals(records.toSet(), active(dao.snapshot(account)).toSet())
        try {
            repository.saveRecognizedSchedule(account, ScheduleSuggestion(listOf(ScheduleSlotSuggestion(1, "第一节", time("08:00"), time("08:40"))), emptyList(), emptyList()), false)
            fail("同名节次时间冲突时不能覆盖")
        } catch (_: IllegalArgumentException) { }
        assertEquals(records.toSet(), active(dao.snapshot(account)).toSet())
    }
}
