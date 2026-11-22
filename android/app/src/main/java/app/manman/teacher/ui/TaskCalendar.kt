package app.manman.teacher.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.unit.dp
import app.manman.teacher.data.WorkTask
import app.manman.teacher.domain.TaskOrdering
import java.time.LocalDate
import java.time.YearMonth

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
internal fun RollingTaskCalendar(today: LocalDate, selected: LocalDate, counts: Map<LocalDate, CalendarCount>,
    onSelectDay: (LocalDate) -> Unit) {
    val dates = remember(today) { (-7L..7L).map(today::plusDays) }
    val scroll = rememberLazyListState((dates.indexOf(selected) - 2).coerceAtLeast(0))
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val width = ((maxWidth - 32.dp) / 5).coerceAtLeast(48.dp)
        LazyRow(state = scroll, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(dates, key = { it.toEpochDay() }) { day ->
                CalendarDate(day, today, selected, counts[day]?.badge(), Modifier.width(width), showWeekday = true) { onSelectDay(day) }
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
        Row(Modifier.fillMaxWidth()) {
            weeklyDayNames.forEach { name ->
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    Text(name.last().toString(), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
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
    modifier: Modifier, showWeekday: Boolean = false, onClick: () -> Unit) {
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
        if (showWeekday) Text(if (day == today) "今天" else weeklyDayNames[day.dayOfWeek.value - 1],
            style = MaterialTheme.typography.labelSmall, maxLines = 1)
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
