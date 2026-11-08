package app.manman.teacher.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.manman.teacher.MainUiState
import app.manman.teacher.domain.LunchRules
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.time.Instant

@Composable
fun TodayScreen(state: MainUiState, onLunch: () -> Unit, onLeave: () -> Unit, onMark: () -> Unit, onClass: () -> Unit,
    onSettings: () -> Unit, onMenu: () -> Unit, onSchedule: () -> Unit, onWork: () -> Unit, onTask: (String) -> Unit) {
    val today = LocalDate.now(LunchRules.defaultZone)
    val overrides = state.overrides.filter { it.day == today }.associate { it.studentId to it.value }
    val lunch = LunchRules.calculate(today, state.students, state.leaves, overrides, state.cutoff)
    val review = state.reviews.firstOrNull { it.day == today }
    val status = when {
        lunch.students.isEmpty() -> "等待名单"
        review == null -> "待核验"
        LunchRules.changedSince(review.students, lunch.students) -> "名单有变化"
        else -> "已核验"
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        PageHeader(
            title = "今天",
            subtitle = today.format(DateTimeFormatter.ofPattern("M月d日 EEEE", Locale.SIMPLIFIED_CHINESE)),
            action = { TextButton(onClick = onSettings) { Text("设置") } },
        )
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (state.pendingCount > 0) {
                Surface(onClick = onSettings, color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.small) {
                    Text("${state.pendingCount} 项待同步 · 查看状态  ›", Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSecondaryContainer)
                }
            }
            if (state.conflicts.isNotEmpty()) {
                Text("${state.conflicts.size} 项记录需要处理同步冲突", Modifier.clickable(onClick = onSettings), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }
            SectionHeading("快捷操作")
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                QuickAction("记录请假", Modifier.weight(1f).fillMaxHeight(), onLeave)
                QuickAction("班级标记", Modifier.weight(1f).fillMaxHeight(), onMark)
                QuickAction("学生名单", Modifier.weight(1f).fillMaxHeight(), onClass)
            }
            Block(onClick = onLunch, color = MaterialTheme.colorScheme.primaryContainer) {
                DashboardHeading("午餐核验", "查看名单  ›")
                if (lunch.students.isEmpty()) {
                    Text("先添加学生名单，再核验用餐人数。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
                } else {
                    Text("${lunch.eating.size} 人", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    Text("预计在校用餐", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    Text("不在校用餐 ${lunch.excluded.size} 人 · $status", color = MaterialTheme.colorScheme.onPrimaryContainer, style = MaterialTheme.typography.labelMedium)
                }
                if (lunch.students.isEmpty()) Text(status, color = MaterialTheme.colorScheme.onPrimaryContainer, style = MaterialTheme.typography.labelMedium)
            }
            Block(onClick = onMenu) {
                DashboardHeading("本周菜单", "查看  ›")
            }
            val slots = state.timeSlots.associateBy { it.id }
            val lessons = state.lessons.filter { it.weekday == today.dayOfWeek.value && slots.containsKey(it.slotId) }
                .sortedBy { slots.getValue(it.slotId).start }
            Block(onClick = onSchedule) {
                DashboardHeading("今天的课程", "课程与作息")
                if (lessons.isEmpty()) Text("今天暂无课程", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                else lessons.forEach { lesson ->
                    val slot = slots.getValue(lesson.slotId)
                    Text("${slot.start.format(DateTimeFormatter.ofPattern("HH:mm"))}  ${lesson.subject} · ${slot.title}")
                }
            }
            val pending = state.tasks.filterNot { it.done }.sortedBy { it.dueAt ?: it.dueDate?.atStartOfDay(LunchRules.defaultZone)?.toInstant() ?: Instant.MAX }.take(3)
            Block(onClick = onWork) {
                DashboardHeading("待办", if (pending.isEmpty()) "添加通知或待办" else "查看全部")
                if (pending.isEmpty()) Text("还没有待办", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            pending.forEach { task ->
                Block(onClick = { onTask(task.id) }) {
                    Text(task.title)
                    Text(task.dueAt?.atZone(LunchRules.defaultZone)?.format(DateTimeFormatter.ofPattern("M月d日 HH:mm"))
                        ?: task.dueDate?.toString()?.plus("（仅日期）") ?: "未设置时间",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun QuickAction(text: String, modifier: Modifier, onClick: () -> Unit) {
    Surface(onClick = onClick, modifier = modifier, shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceContainer) {
        Box(Modifier.heightIn(min = 48.dp).padding(horizontal = 8.dp, vertical = 8.dp), contentAlignment = Alignment.Center) {
            Text(text, textAlign = TextAlign.Center, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun DashboardHeading(title: String, action: String) {
    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
        itemVerticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(action, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
    }
}
