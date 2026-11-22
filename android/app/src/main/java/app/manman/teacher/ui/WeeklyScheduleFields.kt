package app.manman.teacher.ui

import android.app.TimePickerDialog
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.manman.teacher.data.RoutineTime
import app.manman.teacher.data.RoutineVersion
import java.time.LocalDate
import java.time.LocalTime

internal val weeklyDayNames = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")

internal fun routineTimeLabel(time: RoutineTime): String = if (time.start == null || time.end == null) "不设时间段"
    else "${time.start}—${time.end}"

internal fun encodeRoutineTime(time: RoutineTime): String = "${time.start ?: ""}|${time.end ?: ""}"

internal fun routineTimeAt(values: List<String>, day: Int): RoutineTime {
    val parts = values[day - 1].split('|', limit = 2)
    return RoutineTime(parts[0].takeIf(String::isNotEmpty)?.let(LocalTime::parse),
        parts.getOrElse(1) { "" }.takeIf(String::isNotEmpty)?.let(LocalTime::parse))
}

internal fun routineVersion(effectiveFrom: LocalDate, title: String, weekdays: List<Int>, dayTimes: List<String>,
    enabled: Boolean = true, showMealList: Boolean = false): RoutineVersion {
    val times = weekdays.associateWith { routineTimeAt(dayTimes, it) }
    val common = times.values.distinct().singleOrNull()
    return RoutineVersion(effectiveFrom, title.trim(), weekdays.toSet(), common?.start, common?.end,
        enabled, showMealList, if (common == null) times else emptyMap())
}

@Composable
internal fun WeeklyScheduleFields(weekdays: List<Int>, dayTimes: List<String>, error: String?,
    onWeekdaysChange: (ArrayList<Int>) -> Unit, onTimesChange: (ArrayList<String>) -> Unit, onErrorChange: (String?) -> Unit) {
    val context = LocalContext.current
    fun setTime(days: Set<Int>, time: RoutineTime) {
        onTimesChange(ArrayList(dayTimes).apply { days.forEach { set(it - 1, encodeRoutineTime(time)) } })
        onErrorChange(null)
    }
    fun chooseRange(days: Set<Int>) {
        val previous = routineTimeAt(dayTimes, days.min())
        val initialStart = previous.start ?: LocalTime.of(8, 0)
        TimePickerDialog(context, { _, hour, minute ->
            val selectedStart = LocalTime.of(hour, minute)
            val initialEnd = previous.end?.takeIf { it > selectedStart }
                ?: if (selectedStart <= LocalTime.of(23, 19)) selectedStart.plusMinutes(40) else LocalTime.of(23, 59)
            TimePickerDialog(context, { _, endHour, endMinute ->
                val selectedEnd = LocalTime.of(endHour, endMinute)
                if (selectedEnd <= selectedStart) onErrorChange("结束时间应晚于开始时间。")
                else setTime(days, RoutineTime(selectedStart, selectedEnd))
            }, initialEnd.hour, initialEnd.minute, true).show()
        }, initialStart.hour, initialStart.minute, true).show()
    }
    Block {
        Text("每周哪几天", style = MaterialTheme.typography.titleMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            weeklyDayNames.forEachIndexed { index, name ->
                val day = index + 1
                FilterChip(selected = day in weekdays, onClick = feedbackClick {
                    onWeekdaysChange(ArrayList(if (day in weekdays) weekdays - day else weekdays + day))
                    onErrorChange(null)
                }, label = { Text(name) })
            }
        }
        if (weekdays.isEmpty()) Text("请选择至少一天。", style = MaterialTheme.typography.bodySmall)
    }
    Block {
        Text("每周时间", style = MaterialTheme.typography.titleMedium)
        Text("可只按星期重复，也可为每个星期设置开始和结束时间。",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (weekdays.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FeedbackTextButton(onClick = { chooseRange(weekdays.toSet()) }) { Text("统一设置时间段") }
                if (weekdays.any { routineTimeAt(dayTimes, it).start != null }) {
                    FeedbackTextButton(onClick = { setTime(weekdays.toSet(), RoutineTime()) }) { Text("清除全部时间段") }
                }
            }
            weekdays.sorted().forEach { day ->
                val time = routineTimeAt(dayTimes, day)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(weeklyDayNames[day - 1], style = MaterialTheme.typography.bodyLarge)
                        Text(routineTimeLabel(time), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    ActionIcon("clock", "设置${weeklyDayNames[day - 1]}时间段", { chooseRange(setOf(day)) })
                    if (time.start != null) ActionIcon("close", "清除${weeklyDayNames[day - 1]}时间段", { setTime(setOf(day), RoutineTime()) })
                }
            }
            Text("有时间段的事项，结束后下次打开应用会自动完成。", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        error?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}
