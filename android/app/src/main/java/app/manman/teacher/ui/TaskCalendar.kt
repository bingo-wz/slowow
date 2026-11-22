package app.manman.teacher.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.manman.teacher.data.WorkTask
import app.manman.teacher.domain.TaskOrdering
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter

internal data class CalendarCount(val pending: Int, val completed: Int) {
    fun badge() = CalendarBadge(pending + completed, "${pending}项待处理，${completed}项已完成", pending > 0)
}

internal data class CalendarBadge(val count: Int, val description: String, val emphasized: Boolean = true)

@Composable
internal fun rememberTaskCounts(tasks: List<WorkTask>): Map<LocalDate, CalendarCount> = remember(tasks) {
    tasks.groupBy(TaskOrdering::date).mapNotNull { (day, items) ->
        day?.let { it to CalendarCount(items.count { item -> !item.done }, items.count(WorkTask::done)) }
    }.toMap()
}

@Composable
internal fun rememberWeekDates(day: LocalDate): List<LocalDate> {
    val start = day.minusDays(day.dayOfWeek.value - 1L)
    return remember(start) { (0L..6L).map(start::plusDays) }
}

@Composable
internal fun WeekCalendar(dates: List<LocalDate>, today: LocalDate, selected: LocalDate,
    badges: Map<LocalDate, CalendarBadge>, onSelectDay: (LocalDate) -> Unit) {
    val start = dates.first()
    val end = dates.last()
    val range = remember(start, end, today.year) {
        val shortFormat = DateTimeFormatter.ofPattern("M月d日")
        val yearFormat = DateTimeFormatter.ofPattern("yyyy年M月d日")
        val startLabel = start.format(if (start.year == today.year) shortFormat else yearFormat)
        val endLabel = when {
            end.year != start.year -> end.format(yearFormat)
            end.month != start.month -> end.format(shortFormat)
            else -> "${end.dayOfMonth}日"
        }
        "$startLabel – $endLabel"
    }
    Column(Modifier.fillMaxWidth()
        .background(MaterialTheme.colorScheme.surfaceContainer, MaterialTheme.shapes.medium)
        .padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            ActionIcon("back", "上一周", { onSelectDay(selected.minusWeeks(1)) })
            Text(range, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
            ActionIcon("next", "下一周", { onSelectDay(selected.plusWeeks(1)) })
        }
        CalendarWeekdays()
        Row(Modifier.fillMaxWidth()) {
            dates.forEach { date ->
                CalendarDate(date, today, selected, badges[date], Modifier.weight(1f)) {
                    onSelectDay(date)
                }
            }
        }
        if (selected != today) {
            FeedbackTextButton(onClick = { onSelectDay(today) }, modifier = Modifier.align(Alignment.End)) {
                Text("回到今天")
            }
        }
    }
}

@Composable
private fun CalendarWeekdays() {
    Row(Modifier.fillMaxWidth()) {
        weeklyDayNames.forEach { name ->
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                Text(name.last().toString(), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
internal fun MonthCalendar(month: YearMonth, today: LocalDate,
    badges: Map<LocalDate, CalendarBadge>, onMonth: (YearMonth) -> Unit, onSelectDay: (LocalDate) -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
            itemVerticalAlignment = Alignment.CenterVertically) {
            ActionIcon("back", "上个月", { onMonth(month.minusMonths(1)) })
            Text("${month.year}年${month.monthValue}月", style = MaterialTheme.typography.titleMedium)
            ActionIcon("next", "下个月", { onMonth(month.plusMonths(1)) })
            FeedbackTextButton(onClick = { onMonth(YearMonth.from(today)); onSelectDay(today) }) { Text("回到今天") }
        }
        CalendarWeekdays()
        val start = month.atDay(1)
        val offset = start.dayOfWeek.value - 1
        val rows = (offset + month.lengthOfMonth() + 6) / 7
        repeat(rows) { week ->
            Row(Modifier.fillMaxWidth()) {
                repeat(7) { column ->
                    val number = week * 7 + column - offset + 1
                    if (number !in 1..month.lengthOfMonth()) Spacer(Modifier.weight(1f))
                    else {
                        val day = month.atDay(number)
                        CalendarDate(day, today, null, badges[day], Modifier.weight(1f)) {
                            onSelectDay(day)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CalendarDate(day: LocalDate, today: LocalDate, selectedDay: LocalDate?, badge: CalendarBadge?,
    modifier: Modifier, onClick: () -> Unit) {
    val picked = day == selectedDay
    val colors = MaterialTheme.colorScheme
    val label = "${day.year}年${day.monthValue}月${day.dayOfMonth}日 ${weeklyDayNames[day.dayOfWeek.value - 1]}"
    val summary = badge?.let { "，${it.description}" }.orEmpty()
    Column(modifier.heightIn(min = 48.dp)
        .background(if (picked) colors.primaryContainer else if (day == today) colors.surfaceContainerHigh else colors.surfaceContainerLow,
            MaterialTheme.shapes.small)
        .feedbackClickable(onClick = onClick)
        .semantics { contentDescription = label + (if (day == today) "，今天" else "") + summary; selected = picked }
        .padding(vertical = 4.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center) {
        Text(day.dayOfMonth.toString(), style = MaterialTheme.typography.bodyMedium, maxLines = 1)
        val labelText = when {
            badge == null || badge.count == 0 -> " "
            badge.count > 99 -> "99+"
            else -> badge.count.toString()
        }
        Text(labelText, style = MaterialTheme.typography.labelSmall, maxLines = 1,
            color = if (badge?.emphasized == true) colors.primary else colors.onSurfaceVariant)
    }
}
