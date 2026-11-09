package app.manman.teacher.ui

import android.app.DatePickerDialog
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.manman.teacher.data.Lesson
import app.manman.teacher.data.ScheduleKind
import app.manman.teacher.data.ScheduleProfile
import app.manman.teacher.data.TimeSlot
import app.manman.teacher.domain.AppTime
import java.time.LocalDate
import java.util.UUID

@Composable
fun ScheduleProfileDialog(profile: ScheduleProfile, busy: Boolean, onDismiss: () -> Unit, onSave: (ScheduleProfile) -> Unit) {
    var school by rememberSaveable { mutableStateOf(profile.school) }
    var term by rememberSaveable { mutableStateOf(profile.term) }
    var className by rememberSaveable { mutableStateOf(profile.className) }
    var myName by rememberSaveable { mutableStateOf(profile.myName) }
    var termStart by rememberSaveable { mutableStateOf(profile.termStart?.toString().orEmpty()) }
    var notes by rememberSaveable { mutableStateOf(profile.notes) }
    val context = LocalContext.current
    val height = (LocalConfiguration.current.screenHeightDp * 0.65f).dp
    AlertDialog(onDismissRequest = feedbackClick(onDismiss), title = { Text("课表信息") },
        text = {
            Column(Modifier.heightIn(max = height).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("以下信息均可留空，填写后会随账号同步。", style = MaterialTheme.typography.bodySmall)
                QuietTextField(school, { school = it.take(80) }, "学校")
                QuietTextField(term, { term = it.take(80) }, "学期")
                QuietTextField(className, { className = it.take(80) }, "默认班级")
                QuietTextField(myName, { myName = it.take(80) }, "我的姓名")
                FeedbackTextButton(onClick = {
                    val day = termStart.takeIf(String::isNotBlank)?.let(LocalDate::parse) ?: LocalDate.now(AppTime.zone)
                    DatePickerDialog(context, { _, year, month, date -> termStart = LocalDate.of(year, month + 1, date).toString() },
                        day.year, day.monthValue - 1, day.dayOfMonth).show()
                }) { Text("学期第一周日期：${termStart.ifBlank { "未设置" }}") }
                if (termStart.isNotBlank()) FeedbackTextButton(onClick = { termStart = "" }) { Text("清除开学日期") }
                Text("按所选日期所在的周一计算周次。", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                QuietTextField(notes, { notes = it.take(1000) }, "学校提醒或备注", singleLine = false)
            }
        },
        confirmButton = { FeedbackTextButton(onClick = {
            onSave(ScheduleProfile(school.trim(), term.trim(), className.trim(), myName.trim(),
                termStart.takeIf(String::isNotBlank)?.let(LocalDate::parse), notes.trim()))
        }, enabled = !busy) { Text("保存") } },
        dismissButton = { FeedbackTextButton(onClick = onDismiss) { Text("取消") } })
}

@Composable
fun ScheduleLessonDialog(
    slot: TimeSlot, weekday: Int, existing: Lesson?, allSlots: List<TimeSlot>, busy: Boolean,
    onDismiss: () -> Unit, onDelete: (() -> Unit)?, onSave: (Lesson) -> Unit,
) {
    var subject by rememberSaveable(slot.id, weekday) { mutableStateOf(existing?.subject.orEmpty()) }
    var kind by rememberSaveable(slot.id, weekday) { mutableStateOf(existing?.kind?.name.orEmpty()) }
    var teachers by rememberSaveable(slot.id, weekday) { mutableStateOf(existing?.teachers.orEmpty()) }
    var className by rememberSaveable(slot.id, weekday) { mutableStateOf(existing?.className.orEmpty()) }
    var location by rememberSaveable(slot.id, weekday) { mutableStateOf(existing?.location.orEmpty()) }
    var role by rememberSaveable(slot.id, weekday) { mutableStateOf(existing?.role.orEmpty()) }
    var note by rememberSaveable(slot.id, weekday) { mutableStateOf(existing?.note.orEmpty()) }
    var preparation by rememberSaveable(slot.id, weekday) { mutableStateOf(existing?.preparation.orEmpty()) }
    var endSlot by rememberSaveable(slot.id, weekday) { mutableStateOf(existing?.endSlotId.orEmpty()) }
    var reminder by rememberSaveable(slot.id, weekday) { mutableStateOf(existing?.reminderMinutes?.toString().orEmpty()) }
    var chooseEnd by remember { mutableStateOf(false) }
    val later = allSlots.filter { (it.weekdays.isEmpty() || weekday in it.weekdays) && it.end > slot.end }
        .sortedWith(compareBy(TimeSlot::start, TimeSlot::end))
    val last = later.firstOrNull { it.id == endSlot }
    val height = (LocalConfiguration.current.screenHeightDp * 0.62f).dp
    AlertDialog(onDismissRequest = feedbackClick(onDismiss), title = { Text("${scheduleDayNames[weekday - 1]} · ${slot.title}") },
        text = {
            Column(Modifier.heightIn(max = height).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                QuietTextField(subject, { subject = it.take(50) }, "课程或事项名称")
                Text("事项类型", style = MaterialTheme.typography.titleMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("" to "待确认", ScheduleKind.TEACHING.name to "授课", ScheduleKind.DUTY.name to "值守", ScheduleKind.FREE.name to "空堂").forEach { (value, label) ->
                        FilterChip(selected = kind == value, onClick = feedbackClick { kind = value }, label = { Text(label) })
                    }
                }
                QuietTextField(teachers, { teachers = it.take(80) }, "教师（多人用顿号分隔）")
                QuietTextField(className, { className = it.take(80) }, "班级（留空使用默认班级）")
                QuietTextField(location, { location = it.take(80) }, "教室或地点")
                QuietTextField(role, { role = it.take(80) }, "角色，如主讲、协同")
                FeedbackTextButton(onClick = { chooseEnd = true }) {
                    Text("持续到：${last?.let { "${it.title} ${it.end}" } ?: if (endSlot.isNotEmpty()) "原结束节次已失效" else "本节结束"}")
                }
                Text("提醒时间", style = MaterialTheme.typography.titleMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("" to "跟随设置", "-1" to "关闭", "0" to "开始时", "5" to "提前5分", "10" to "提前10分", "15" to "提前15分").forEach { (value, label) ->
                        FilterChip(selected = reminder == value, onClick = feedbackClick { reminder = value }, label = { Text(label) })
                    }
                }
                QuietTextField(preparation, { preparation = it.take(500) }, "课前准备", singleLine = false)
                QuietTextField(note, { note = it.take(500) }, "备注", singleLine = false)
            }
        },
        confirmButton = { FeedbackTextButton(onClick = {
            val base = existing ?: Lesson(UUID.randomUUID().toString(), weekday, slot.id, subject)
            onSave(base.copy(subject = subject.trim(), kind = ScheduleKind.entries.firstOrNull { it.name == kind },
                teachers = teachers.trim(), className = className.trim(), location = location.trim(), role = role.trim(),
                note = note.trim(), preparation = preparation.trim(), endSlotId = endSlot.takeIf(String::isNotBlank),
                reminderMinutes = reminder.toIntOrNull()))
        }, enabled = subject.isNotBlank() && !busy && (endSlot.isEmpty() || last != null)) { Text("保存") } },
        dismissButton = { Row {
            if (onDelete != null) FeedbackTextButton(onClick = onDelete, enabled = !busy) { Text("移除课程") }
            FeedbackTextButton(onClick = onDismiss) { Text("取消") }
        } })
    if (chooseEnd) AlertDialog(onDismissRequest = feedbackClick { chooseEnd = false }, title = { Text("选择结束节次") },
        text = { Column(Modifier.heightIn(max = height).verticalScroll(rememberScrollState())) {
            FeedbackTextButton(onClick = { endSlot = ""; chooseEnd = false }, modifier = Modifier.fillMaxWidth()) { Text("本节结束（${slot.end}）") }
            later.forEach { choice ->
                FeedbackTextButton(onClick = { endSlot = choice.id; chooseEnd = false }, modifier = Modifier.fillMaxWidth()) {
                    Text("${choice.title} · ${choice.end}")
                }
            }
        } },
        confirmButton = { FeedbackTextButton(onClick = { chooseEnd = false }) { Text("取消") } })
}

val scheduleDayNames = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")
