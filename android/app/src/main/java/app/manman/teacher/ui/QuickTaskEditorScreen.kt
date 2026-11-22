package app.manman.teacher.ui

import android.app.DatePickerDialog
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.manman.teacher.MainUiState
import app.manman.teacher.MainViewModel
import app.manman.teacher.data.RoutineRule
import app.manman.teacher.data.WorkTask
import app.manman.teacher.domain.AppTime
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.UUID

@Composable
internal fun QuickTaskEditorScreen(state: MainUiState, viewModel: MainViewModel, back: () -> Unit, onSaved: () -> Unit, defaultDate: LocalDate? = null) {
    val context = LocalContext.current
    val today = rememberToday()
    var title by rememberSaveable { mutableStateOf("") }
    var date by rememberSaveable { mutableStateOf((defaultDate ?: today).toString()) }
    var dateChosen by rememberSaveable { mutableStateOf(defaultDate != null) }
    var lessonPeriod by rememberSaveable { mutableStateOf<Int?>(null) }
    var repeatWeekly by rememberSaveable { mutableStateOf(false) }
    var weekdays by rememberSaveable { mutableStateOf(arrayListOf<Int>()) }
    var dayTimes by rememberSaveable { mutableStateOf(ArrayList(List(7) { "|" })) }
    var showMealList by rememberSaveable { mutableStateOf(false) }
    var timeError by rememberSaveable { mutableStateOf<String?>(null) }
    var confirmBack by remember { mutableStateOf(false) }
    LaunchedEffect(today) { if (!dateChosen) date = today.toString() }
    val selected = LocalDate.parse(date)
    val dirty = lessonPeriod != null || title.isNotBlank() || selected != today || repeatWeekly || showMealList
    fun leave() { if (dirty) confirmBack = true else back() }
    BackHandler { leave() }
    fun selectDate(day: LocalDate) { date = day.toString(); dateChosen = true }
    Column(Modifier.fillMaxSize()) {
        PageHeader("新建待办", back = ::leave)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            QuietTextField(title, { title = it.take(100) }, "待办事项")
            Block {
                Text("日期", style = MaterialTheme.typography.titleMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    FilterChip(selected = selected == today, onClick = feedbackClick { selectDate(today) }, label = { Text("今天") })
                    FilterChip(selected = selected == today.plusDays(1), onClick = feedbackClick { selectDate(today.plusDays(1)) },
                        label = { Text("明天") })
                    FilterChip(selected = selected != today && selected != today.plusDays(1), onClick = feedbackClick {
                        DatePickerDialog(context, { _, year, month, day -> selectDate(LocalDate.of(year, month + 1, day)) },
                            selected.year, selected.monthValue - 1, selected.dayOfMonth).show()
                    }, label = {
                        Text(if (selected == today || selected == today.plusDays(1)) "选择日期"
                            else "选择日期 · ${selected.format(DateTimeFormatter.ofPattern("M月d日"))}")
                    })
                }
                if (!repeatWeekly) LessonPeriodPicker(lessonPeriod) { lessonPeriod = it }
                if (repeatWeekly) Text("重复安排从 ${selected.format(DateTimeFormatter.ofPattern("yyyy年M月d日"))} 起生效。",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Block {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("重复设置", style = MaterialTheme.typography.titleMedium)
                        Text(if (repeatWeekly) "每周重复" else "不重复", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = repeatWeekly, onCheckedChange = feedbackToggle { repeatWeekly = it; timeError = null },
                        modifier = Modifier.semantics { contentDescription = "设置每周重复" })
                }
            }
            if (repeatWeekly) {
                WeeklyScheduleFields(weekdays, dayTimes, timeError, { weekdays = it }, { dayTimes = it }, { timeError = it })
                Block {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = showMealList, onCheckedChange = feedbackToggle { showMealList = it },
                            modifier = Modifier.semantics { contentDescription = "关联不在校用餐名单" })
                        Text("关联不在校用餐名单", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
        MainButton("保存待办", {
            val now = Instant.now()
            if (repeatWeekly) {
                val version = routineVersion(selected, title, weekdays, dayTimes, showMealList = showMealList)
                viewModel.saveRoutine(RoutineRule(UUID.randomUUID().toString(), now, listOf(version)), onDone = onSaved)
            } else {
                viewModel.saveTask(WorkTask(UUID.randomUUID().toString(), title, null, null, null, false, now, selected, lessonPeriod = lessonPeriod), onDone = onSaved)
            }
        }, enabled = title.isNotBlank() && !state.busy && (!repeatWeekly || (weekdays.isNotEmpty() && timeError == null)),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp))
        Spacer(Modifier.height(16.dp))
    }
    if (confirmBack) AlertDialog(onDismissRequest = feedbackClick { confirmBack = false },
        title = { Text("待办还没有保存") }, text = { Text("离开后，未保存的内容不会保留。") },
        confirmButton = { FeedbackTextButton(onClick = { confirmBack = false; back() }) { Text("放弃修改") } },
        dismissButton = { FeedbackTextButton(onClick = { confirmBack = false }) { Text("继续编辑") } })
}

@Composable
internal fun LessonPeriodPicker(period: Int?, onSelect: (Int?) -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    FeedbackTextButton(onClick = { open = true }) {
        Text(period?.let { "第 $it 节课" } ?: "标记课节（选填）")
    }
    if (open) AlertDialog(onDismissRequest = { open = false }, title = { Text("标记课节") },
        text = {
            FlowRow(Modifier.heightIn(max = 340.dp).verticalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                (1..12).forEach { number ->
                    FilterChip(period == number, feedbackClick { onSelect(number); open = false },
                        label = { Text("第 $number 节") })
                }
            }
        },
        confirmButton = {
            FeedbackTextButton(onClick = { onSelect(null); open = false }) { Text("不标记") }
        },
        dismissButton = { FeedbackTextButton(onClick = { open = false }) { Text("取消") } })
}
