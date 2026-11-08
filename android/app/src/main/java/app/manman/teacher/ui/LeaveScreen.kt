package app.manman.teacher.ui

import android.app.DatePickerDialog
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.manman.teacher.MainUiState
import app.manman.teacher.MainViewModel
import app.manman.teacher.domain.AppTime
import app.manman.teacher.domain.Leave
import app.manman.teacher.domain.LeavePeriod
import app.manman.teacher.domain.Student
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

@Composable
fun LeaveScreen(state: MainUiState, viewModel: MainViewModel, existing: Leave?, back: () -> Unit, onAddStudents: () -> Unit) {
    val context = LocalContext.current
    val students = state.students.filter(Student::active)
    var studentId by rememberSaveable(existing?.id) { mutableStateOf(existing?.studentId.orEmpty()) }
    var startText by rememberSaveable(existing?.id) { mutableStateOf((existing?.start ?: LocalDate.now(AppTime.zone)).toString()) }
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
                Block(onClick = { chooseDate(end) { endText = it.toString() } }) {
                    Text("结束日期", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(end.toString() + "  ›")
                }
                Text("请假时段", style = MaterialTheme.typography.titleMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(LeavePeriod.ALL_DAY to "全天", LeavePeriod.MORNING to "上午", LeavePeriod.AFTERNOON to "下午").forEach { (period, label) ->
                        FilterChip(selected = periodName == period.name, onClick = { periodName = period.name }, label = { Text(label) }, border = null,
                            colors = FilterChipDefaults.filterChipColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                                selectedContainerColor = MaterialTheme.colorScheme.primaryContainer))
                    }
                }
                Text("全天或上午请假会加入当日不在校用餐名单，下午请假不计入。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        MainButton("保存请假", {
            val leave = Leave(existing?.id ?: UUID.randomUUID().toString(), studentId, start, end,
                LeavePeriod.valueOf(periodName), existing?.recordedAt ?: Instant.now(), existing?.cancelled ?: false)
            viewModel.saveLeave(leave, back)
        }, enabled = selectedStudent != null && !state.busy, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp))
        Spacer(Modifier.height(16.dp))
    }
    if (chooseStudent) StudentPicker(students, onDismiss = { chooseStudent = false }) { student ->
        studentId = student.id
        chooseStudent = false
    }
}
