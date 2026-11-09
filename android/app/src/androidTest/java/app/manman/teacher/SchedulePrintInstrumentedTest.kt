package app.manman.teacher

import android.graphics.pdf.PdfRenderer
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PrintAttributes
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.manman.teacher.data.Lesson
import app.manman.teacher.data.ScheduleKind
import app.manman.teacher.data.ScheduleProfile
import app.manman.teacher.data.TimeSlot
import app.manman.teacher.domain.TeacherTimetable
import app.manman.teacher.ui.SchedulePrintAdapter
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.LocalDate
import java.time.LocalTime

@RunWith(AndroidJUnit4::class)
class SchedulePrintInstrumentedTest {
    @Test fun longWeekProducesReadableMultipagePdf() {
        val app = ApplicationProvider.getApplicationContext<ManmanApplication>()
        val week = LocalDate.of(2026, 11, 9)
        val slots = (0 until 20).map { index ->
            val start = LocalTime.of(7, 0).plusMinutes(index * 30L)
            TimeSlot("slot-$index", "测试时段$index", start, start.plusMinutes(25), setOf(1))
        }
        val lessons = slots.mapIndexed { index, slot -> Lesson("lesson-$index", 1, slot.id, "虚构事项$index", ScheduleKind.TEACHING,
            className = if (index == 0) "虚构班级" else "", location = if (index == 0) "虚构教室" else "") }
        val items = TeacherTimetable.build(week, slots, lessons)
        val adapter = SchedulePrintAdapter(app, week, items, null, ScheduleProfile())
        val attrs = PrintAttributes.Builder().setMediaSize(PrintAttributes.MediaSize.ISO_A4.asLandscape())
            .setResolution(PrintAttributes.Resolution("test", "test", 300, 300))
            .setMinMargins(PrintAttributes.Margins(400, 400, 400, 400)).build()
        val pages = adapter.prepare(attrs)
        assertTrue("长课表应分页", pages > 1)
        val file = File(app.cacheDir, "fictional-schedule-print.pdf")
        val written = file.outputStream().use { adapter.render(it, CancellationSignal()) }
        assertTrue("课表 PDF 应成功写入", written && file.length() > 0)
        PdfRenderer(ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)).use { pdf ->
            assertTrue(pdf.pageCount == pages)
            pdf.openPage(0).use { page -> assertTrue(page.width > page.height) }
        }
        if (InstrumentationRegistry.getArguments().getString("printFixture") != "1") file.delete()
    }
}
