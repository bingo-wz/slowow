package app.manman.teacher.ui

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.manman.teacher.MainUiState
import app.manman.teacher.MainViewModel
import app.manman.teacher.data.LunchReview
import app.manman.teacher.domain.ExclusionReason
import app.manman.teacher.domain.Leave
import app.manman.teacher.domain.LeavePeriod
import app.manman.teacher.domain.LunchRules
import app.manman.teacher.domain.LunchStudent
import app.manman.teacher.domain.MealOverride
import app.manman.teacher.domain.Student
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.UUID

@Composable
fun LunchScreen(state: MainUiState, viewModel: MainViewModel, back: () -> Unit, onAddStudents: () -> Unit) {
    val day = LocalDate.now(LunchRules.defaultZone)
    val overrides = state.overrides.filter { it.day == day }.associate { it.studentId to it.value }
    val lunch = LunchRules.calculate(day, state.students, state.leaves, overrides, state.cutoff)
    val review = state.reviews.firstOrNull { it.day == day }
    val changed = review != null && LunchRules.changedSince(review.students, lunch.students)
    var selected by remember { mutableStateOf<LunchStudent?>(null) }
    Column(Modifier.fillMaxSize()) {
        PageHeader("午餐核验", "${day.monthValue} 月 ${day.dayOfMonth} 日 · 截止 ${state.cutoff}", back = back)
        if (lunch.students.isEmpty()) {
            Column(Modifier.padding(horizontal = 16.dp)) { EmptyHint("还没有学生名单", "添加学生后即可核验午餐人数。", "添加学生", onAddStudents) }
        } else {
            LazyColumn(Modifier.weight(1f), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    Block {
                        Text("预计在校用餐", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("${lunch.eating.size} 人", style = MaterialTheme.typography.headlineSmall)
                        Text("班级 ${lunch.students.size} 人 · 不在校用餐 ${lunch.excluded.size} 人", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        val status = when { changed -> "核验后名单有变化，请重新确认"; review != null -> "本次名单已核验"; else -> "请核对名单后确认" }
                        SmallBadge(status)
                    }
                    Spacer(Modifier.height(16.dp))
                    Text("点选学生可调整今天的用餐情况", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    SectionHeading("不在校用餐 · ${lunch.excluded.size} 人")
                    if (lunch.excluded.isEmpty()) Text("暂无", Modifier.padding(8.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                items(lunch.excluded, key = { "excluded-${it.studentId}" }) { person ->
                    LunchPersonRow(person, overrides[person.studentId]) { selected = person }
                }
                item { Spacer(Modifier.height(8.dp)); SectionHeading("在校用餐 · ${lunch.eating.size} 人") }
                items(lunch.eating, key = { "eating-${it.studentId}" }) { person ->
                    LunchPersonRow(person, overrides[person.studentId]) { selected = person }
                }
                item { Spacer(Modifier.height(16.dp)) }
            }
            MainButton(if (changed) "重新确认人数" else "确认核验", { viewModel.confirmLunch(day, lunch.students) },
                enabled = !state.busy, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp))
            Spacer(Modifier.height(16.dp))
        }
    }
    selected?.let { person ->
        AlertDialog(
            onDismissRequest = { selected = null },
            title = { Text("${person.name}今天的午餐") },
            text = {
                Column {
                    Text("仅调整今天的用餐情况，不会改变请假记录。", style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { viewModel.setMealOverride(day, person.studentId, MealOverride.EAT); selected = null }) { Text("今天在校吃") }
                    TextButton(onClick = { viewModel.setMealOverride(day, person.studentId, MealOverride.DO_NOT_EAT); selected = null }) { Text("今天不在校吃") }
                    TextButton(onClick = { viewModel.setMealOverride(day, person.studentId, null); selected = null }) { Text("按请假和日常习惯计算") }
                }
            },
            confirmButton = { TextButton(onClick = { selected = null }) { Text("关闭") } },
        )
    }
}

@Composable
private fun LunchPersonRow(person: LunchStudent, override: MealOverride?, onClick: () -> Unit) {
    val reason = when {
        override == MealOverride.EAT -> "今天手动调整为在校用餐"
        person.reason == ExclusionReason.MANUAL -> "今天手动调整为不在校用餐"
        person.reason == ExclusionReason.USUALLY_ELSEWHERE -> "固定不在校用餐"
        person.reason == ExclusionReason.LEAVE -> "请假"
        else -> "在校用餐"
    }
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(person.name)
            Text(reason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text("调整  ›", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
fun LeaveScreen(state: MainUiState, viewModel: MainViewModel, existing: Leave?, back: () -> Unit, onAddStudents: () -> Unit) {
    val context = LocalContext.current
    val students = state.students.filter(Student::active)
    var studentId by rememberSaveable(existing?.id) { mutableStateOf(existing?.studentId.orEmpty()) }
    var startText by rememberSaveable(existing?.id) { mutableStateOf((existing?.start ?: LocalDate.now(LunchRules.defaultZone)).toString()) }
    var endText by rememberSaveable(existing?.id) { mutableStateOf(existing?.end?.toString() ?: startText) }
    var periodName by rememberSaveable(existing?.id) { mutableStateOf(existing?.period?.name ?: LeavePeriod.ALL_DAY.name) }
    var chooseStudent by remember { mutableStateOf(false) }
    val start = LocalDate.parse(startText)
    val end = LocalDate.parse(endText)
    val selectedStudent = students.firstOrNull { it.id == studentId }
    fun chooseDate(date: LocalDate, onSelected: (LocalDate) -> Unit) {
        DatePickerDialog(context, { _, year, month, day -> onSelected(LocalDate.of(year, month + 1, day)) }, date.year, date.monthValue - 1, date.dayOfMonth).show()
    }
    Column(Modifier.fillMaxSize()) {
        PageHeader(if (existing == null) "记录请假" else "修改请假", back = back)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (students.isEmpty()) {
                EmptyHint("先添加学生", "有名单后就可以记录请假。", "添加学生", onAddStudents)
            } else {
                Block(onClick = { chooseStudent = true }) {
                    Text("学生", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(selectedStudent?.name ?: "选择学生  ›")
                }
                Block(onClick = { chooseDate(start) { selected -> startText = selected.toString(); if (end.isBefore(selected)) endText = selected.toString() } }) {
                    Text("开始日期", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(start.toString() + "  ›")
                }
                Block(onClick = { chooseDate(end) { selected -> endText = selected.toString() } }) {
                    Text("结束日期", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(end.toString() + "  ›")
                }
                Text("请假时段", style = MaterialTheme.typography.titleMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(LeavePeriod.ALL_DAY to "全天", LeavePeriod.MORNING to "上午", LeavePeriod.AFTERNOON to "下午").forEach { (period, label) ->
                        FilterChip(
                            selected = periodName == period.name,
                            onClick = { periodName = period.name },
                            label = { Text(label) },
                            border = null,
                            colors = FilterChipDefaults.filterChipColors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                                selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                            ),
                        )
                    }
                }
                Text("午餐按录入时间计算。${state.cutoff} 后新记录的当天请假不会自动扣减；如需调整，可在午餐核验中修改。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        MainButton("保存请假", {
            val leave = Leave(existing?.id ?: UUID.randomUUID().toString(), studentId, start, end,
                LeavePeriod.valueOf(periodName), existing?.recordedAt ?: Instant.now(), existing?.cancelled ?: false)
            viewModel.saveLeave(leave, back)
        }, enabled = selectedStudent != null && !state.busy, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp))
        Spacer(Modifier.height(16.dp))
    }
    if (chooseStudent) {
        StudentPicker(students, onDismiss = { chooseStudent = false }) { student ->
            studentId = student.id
            chooseStudent = false
        }
    }
}

@Composable
fun LunchReviewDetailScreen(review: LunchReview, back: () -> Unit) {
    val excluded = review.students.filterNot(LunchStudent::eats)
    val eating = review.students.filter(LunchStudent::eats)
    Column(Modifier.fillMaxSize()) {
        PageHeader("午餐核验记录", "${review.day} · 已保存的名单", back = back)
        LazyColumn(Modifier.weight(1f), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                Block {
                    Text("在校用餐 ${eating.size} 人", style = MaterialTheme.typography.titleMedium)
                    Text("不在校用餐 ${excluded.size} 人 · 核验于 ${review.confirmedAt.atZone(LunchRules.defaultZone).format(DateTimeFormatter.ofPattern("M月d日 HH:mm"))}",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.height(16.dp))
                SectionHeading("不在校用餐 · ${excluded.size} 人")
            }
            if (excluded.isEmpty()) item { Text("暂无", Modifier.padding(8.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            items(excluded, key = { "out-${it.studentId}" }) { person -> Text(person.name, Modifier.padding(8.dp)) }
            item { Spacer(Modifier.height(8.dp)); SectionHeading("在校用餐 · ${eating.size} 人") }
            items(eating, key = { "in-${it.studentId}" }) { person -> Text(person.name, Modifier.padding(8.dp)) }
            item { Spacer(Modifier.height(16.dp)) }
        }
    }
}

@Composable
fun CutoffSetting(state: MainUiState, viewModel: MainViewModel) {
    val context = LocalContext.current
    Block(onClick = {
        TimePickerDialog(context, { _, hour, minute -> viewModel.saveCutoff(LocalTime.of(hour, minute)) {} },
            state.cutoff.hour, state.cutoff.minute, true).show()
    }) {
        Text("午餐核验截止时间", style = MaterialTheme.typography.bodyLarge)
        Text("${state.cutoff.format(DateTimeFormatter.ofPattern("HH:mm"))}  ›", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
