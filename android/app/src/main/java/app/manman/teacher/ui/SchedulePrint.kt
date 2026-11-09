package app.manman.teacher.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.PrintManager
import android.print.pdf.PrintedPdfDocument
import app.manman.teacher.data.ScheduleKind
import app.manman.teacher.data.ScheduleProfile
import app.manman.teacher.domain.TeacherTimetable
import app.manman.teacher.domain.TimetableItem
import java.io.FileOutputStream
import java.io.OutputStream
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.math.ceil

fun printTeacherSchedule(context: Context, week: LocalDate, items: List<TimetableItem>, teacher: String?, profile: ScheduleProfile) {
    val manager = context.getSystemService(Context.PRINT_SERVICE) as PrintManager
    val attributes = PrintAttributes.Builder().setMediaSize(PrintAttributes.MediaSize.ISO_A4.asLandscape())
        .setMinMargins(PrintAttributes.Margins(400, 400, 400, 400)).build()
    manager.print("慢慢 · 教师课程时间表", SchedulePrintAdapter(context, week, items, teacher, profile), attributes)
}

internal class SchedulePrintAdapter(
    private val context: Context,
    private val week: LocalDate,
    private val items: List<TimetableItem>,
    private val teacher: String?,
    private val profile: ScheduleProfile,
) : PrintDocumentAdapter() {
    private var attributes: PrintAttributes? = null
    private var pageCount = 1
    private var rowsPerPage = 1
    private val includeWeekend = items.any { it.date.dayOfWeek.value >= 6 &&
        (it.lesson != null || it.slot.weekdays.any { day -> day >= 6 }) }
    private val days = if (includeWeekend) 7 else 5
    private val selected = items.filter { java.time.temporal.ChronoUnit.DAYS.between(week, it.date) in 0 until days.toLong() }
    private val boundaries = selected.flatMap { listOf(it.start, it.end) }.distinct().sorted()
    private val rowHeight = 38f

    override fun onLayout(oldAttributes: PrintAttributes?, newAttributes: PrintAttributes,
        cancellationSignal: CancellationSignal, callback: LayoutResultCallback, extras: android.os.Bundle?) {
        if (cancellationSignal.isCanceled) { callback.onLayoutCancelled(); return }
        prepare(newAttributes)
        callback.onLayoutFinished(PrintDocumentInfo.Builder("教师课程时间表.pdf")
            .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT).setPageCount(pageCount).build(), true)
    }

    internal fun prepare(newAttributes: PrintAttributes): Int {
        attributes = newAttributes
        val document = PrintedPdfDocument(context, newAttributes)
        try {
            val page = document.startPage(0)
            val available = (page.info.contentRect.height() - 94f).coerceAtLeast(rowHeight)
            rowsPerPage = (available / rowHeight).toInt().coerceAtLeast(1)
            pageCount = ceil((boundaries.size - 1).coerceAtLeast(1).toDouble() / rowsPerPage).toInt().coerceAtLeast(1)
            document.finishPage(page)
        } finally { document.close() }
        return pageCount
    }

    override fun onWrite(pages: Array<out PageRange>, destination: ParcelFileDescriptor,
        cancellationSignal: CancellationSignal, callback: WriteResultCallback) {
        if (attributes == null) { callback.onWriteFailed("打印设置不可用"); return }
        try {
            val completed = FileOutputStream(destination.fileDescriptor).use { render(it, cancellationSignal) }
            if (completed) callback.onWriteFinished(arrayOf(PageRange.ALL_PAGES)) else callback.onWriteCancelled()
        } catch (_: Exception) {
            callback.onWriteFailed("生成课表失败，请重试。")
        } finally {
            runCatching { destination.close() }
        }
    }

    internal fun render(output: OutputStream, cancellationSignal: CancellationSignal): Boolean {
        val document = PrintedPdfDocument(context, requireNotNull(attributes))
        try {
            for (pageIndex in 0 until pageCount) {
                if (cancellationSignal.isCanceled) return false
                val page = document.startPage(pageIndex)
                drawPage(page, pageIndex)
                document.finishPage(page)
            }
            if (cancellationSignal.isCanceled) return false
            document.writeTo(output)
            return true
        } finally { document.close() }
    }

    private fun drawPage(page: PdfDocument.Page, pageIndex: Int) {
        val canvas = page.canvas
        val area = page.info.contentRect
        val x = area.left.toFloat()
        val y = area.top.toFloat()
        val width = area.width().toFloat()
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        fun text(value: String, px: Float, py: Float, size: Float, color: Int = Color.rgb(29, 44, 66), bold: Boolean = false) {
            paint.color = color
            paint.textSize = size
            paint.typeface = if (bold) android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.BOLD) else android.graphics.Typeface.DEFAULT
            canvas.drawText(value, px, py, paint)
        }
        val weekNo = TeacherTimetable.schoolWeek(week, profile.termStart)?.let { "第 $it 周 · " }.orEmpty()
        text("${weekNo}教师课程时间表", x, y + 18f, 16f, bold = true)
        val subtitle = (listOf(profile.school, profile.term, profile.className, teacher ?: profile.myName)
            .filter(String::isNotBlank) + "${week} — ${week.plusDays(6)}").joinToString("  ·  ")
        text(fit(subtitle, width - 20f, paint, 9f), x, y + 35f, 9f, Color.rgb(86, 100, 119))
        text("授课  /  值守  /  空堂  /  待确认     第 ${pageIndex + 1} / $pageCount 页", x, y + 50f, 9f, Color.rgb(86, 100, 119))
        val axis = 48f
        val cellWidth = (width - axis) / days
        val top = y + 62f
        paint.color = Color.rgb(239, 244, 251)
        canvas.drawRect(x, top, x + width, top + 22f, paint)
        text("时间", x + 4f, top + 15f, 9f, bold = true)
        repeat(days) { index ->
            val date = week.plusDays(index.toLong())
            text("${scheduleDayNames[index]} ${date.format(DateTimeFormatter.ofPattern("M/d"))}",
                x + axis + index * cellWidth + 4f, top + 15f, 9f, bold = true)
        }
        val firstRow = pageIndex * rowsPerPage
        val lastRow = minOf(firstRow + rowsPerPage, (boundaries.size - 1).coerceAtLeast(0))
        val gridTop = top + 22f
        if (boundaries.size < 2) text("没有可打印的作息", x + axis, gridTop + 20f, 10f)
        for (row in firstRow until lastRow) {
            val rowY = gridTop + (row - firstRow) * rowHeight
            paint.color = Color.rgb(225, 232, 240)
            paint.strokeWidth = 0.5f
            canvas.drawLine(x, rowY, x + width, rowY, paint)
            text(boundaries[row].toString(), x + 3f, rowY + 13f, 8f, Color.rgb(83, 99, 116))
        }
        selected.forEach { item ->
            if (item.coveredBy != null) return@forEach
            val first = boundaries.indexOf(item.start)
            val end = boundaries.indexOf(item.end)
            val visibleStart = maxOf(first, firstRow)
            val visibleEnd = minOf(end, lastRow)
            if (first < 0 || end < 0 || visibleStart >= visibleEnd) return@forEach
            val day = java.time.temporal.ChronoUnit.DAYS.between(week, item.date).toInt()
            val left = x + axis + day * cellWidth + 2f
            val upper = gridTop + (visibleStart - firstRow) * rowHeight + 2f
            val height = (visibleEnd - visibleStart) * rowHeight - 4f
            paint.color = when (item.kind) {
                ScheduleKind.TEACHING -> Color.rgb(234, 241, 255)
                ScheduleKind.DUTY -> Color.rgb(255, 244, 227)
                ScheduleKind.FREE -> Color.rgb(232, 246, 239)
                null -> Color.rgb(240, 242, 244)
            }
            canvas.drawRoundRect(left, upper, left + cellWidth - 4f, upper + height, 4f, 4f, paint)
            val label = (if (first < firstRow) "续 · " else "") + (item.lesson?.subject ?: "${item.slot.title} · 未安排")
            val color = if (teacher != null && item.lesson != null && item.lesson.teachers.isNotBlank() &&
                !TeacherTimetable.matchesTeacher(item.lesson, teacher)) Color.rgb(120, 128, 138) else Color.rgb(31, 51, 83)
            text(fit(label, cellWidth - 12f, paint, 8.5f), left + 4f, upper + 10f, 8.5f, color, bold = true)
            text("${item.start}–${item.end}", left + 4f, upper + 20f, 8f, color)
            if (height >= 32f) {
                val info = listOf(item.lesson?.className?.ifBlank { profile.className }.orEmpty(), item.lesson?.location.orEmpty(),
                    item.lesson?.role.orEmpty())
                    .filter(String::isNotBlank).joinToString(" · ")
                if (info.isNotBlank()) text(fit(info, cellWidth - 12f, paint, 8f), left + 4f, upper + 30f, 8f, color)
            }
        }
    }

    private fun fit(value: String, width: Float, paint: Paint, size: Float): String {
        paint.textSize = size
        if (paint.measureText(value) <= width) return value
        val count = paint.breakText(value, true, (width - paint.measureText("…")).coerceAtLeast(0f), null)
        return value.take(count) + "…"
    }
}
