package app.manman.teacher.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import app.manman.teacher.MainUiState
import app.manman.teacher.MainViewModel
import app.manman.teacher.domain.*
import app.manman.teacher.reminders.ForestReminderWorker

@Composable
internal fun ClassroomForestScreen(state: MainUiState, model: MainViewModel, back: () -> Unit, onGame: () -> Unit) {
    val today = rememberToday()
    var period by rememberSaveable { mutableStateOf(ForestPeriod.WEEK) }
    var filter by rememberSaveable { mutableStateOf<ForestGroup?>(null) }
    var podium by rememberSaveable { mutableStateOf(false) }
    var search by rememberSaveable { mutableStateOf("") }
    var animalFilter by rememberSaveable { mutableStateOf<ForestAnimal?>(null) }
    var chooseAnimal by rememberSaveable { mutableStateOf(false) }
    var onlyUnlit by rememberSaveable { mutableStateOf(false) }
    var editing by rememberSaveable { mutableStateOf<String?>(null) }
    val students = remember(state.students) { state.students.filter(Student::active) }
    val todayIds = remember(today, state.forestInteractions) {
        state.forestInteractions.filter { it.day == today }.map(ForestInteraction::studentId).toSet()
    }
    val counts = remember(today, period, state.forestInteractions) {
        val start = period.start(today)
        state.forestInteractions.filter { it.day in start..today }.groupingBy(ForestInteraction::studentId).eachCount()
    }
    val matched = remember(students, state.forestProfiles, filter, animalFilter) {
        students.filter { student ->
            val profile = state.forestProfiles[student.id]
            (filter == null || (profile?.group ?: ForestGroup.CLASS) == filter) &&
                (animalFilter == null || profile?.animal == animalFilter)
        }
    }
    val shown = matched.filter { (!onlyUnlit || it.id !in counts) && it.name.contains(search.trim()) }
    val view = LocalView.current
    DisposableEffect(view, podium) {
        val previous = view.keepScreenOn
        if (podium) view.keepScreenOn = true
        onDispose { view.keepScreenOn = previous }
    }
    val due = remember(today, students, state.forestProfiles, state.forestInteractions) {
        ForestAttention.due(today, students, state.forestProfiles, state.forestInteractions)
    }
    val context = LocalContext.current
    val account = state.accountId
    var remind by remember(account) { mutableStateOf(account?.let { ForestReminderWorker.enabled(context, it) } == true) }
    fun setReminder(enabled: Boolean) {
        val owner = account ?: return
        ForestReminderWorker.setEnabled(context, owner, enabled)
        remind = enabled
    }
    val requestNotification = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) setReminder(true)
        else model.reportMessage("通知权限未开启，仍可在课堂森林查看关照提示。")
    }
    Column(Modifier.fillMaxSize()) {
        PageHeader("课堂森林", back = back, action = { ActionIcon("game", "地鼠乐园", onGame) })
        LazyVerticalGrid(GridCells.Adaptive(144.dp), Modifier.weight(1f),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Block {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ForestPeriod.entries.forEach { option ->
                            FilterChip(period == option, feedbackClick { period = option }, label = { Text(option.label) })
                        }
                    }
                    Text("${period.label}已点亮 ${matched.count { it.id in counts }} / ${matched.size} 人",
                        style = MaterialTheme.typography.titleMedium)
                    Text("点头像记录今天的提问或互动，再点一次可撤销。", style = MaterialTheme.typography.bodySmall)
                    FeedbackTextButton(onClick = { podium = !podium }) { Text(if (podium) "关闭屏幕常亮" else "讲台模式 · 屏幕常亮") }
                    QuietTextField(search, { search = it.take(50) }, "查找学生")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(filter == null, feedbackClick { filter = null }, label = { Text("全班") })
                        ForestGroup.entries.forEach { option ->
                            FilterChip(filter == option, feedbackClick { filter = option }, label = { Text(option.label) })
                        }
                        FilterChip(onlyUnlit, feedbackClick { onlyUnlit = !onlyUnlit }, label = { Text("只看未点亮") })
                        FeedbackTextButton(onClick = { chooseAnimal = true }) {
                            Text(animalFilter?.let { "${it.icon} ${it.label}" } ?: "按动物头像筛选")
                        }
                    }
                }
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                Block {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("关照提醒", style = MaterialTheme.typography.titleMedium)
                            Text("未互动满 7 天时，可在工作日收到提醒。", style = MaterialTheme.typography.bodySmall)
                        }
                        Switch(remind, feedbackToggle { value ->
                            if (!value) setReminder(false)
                            else if (NotificationManagerCompat.from(context).areNotificationsEnabled()) setReminder(true)
                            else if (Build.VERSION.SDK_INT >= 33) requestNotification.launch(Manifest.permission.POST_NOTIFICATIONS)
                            else model.reportMessage("请在系统设置中开启慢慢的通知。")
                        }, enabled = account != null, modifier = Modifier.semantics { contentDescription = "开启课堂关照通知" })
                    }
                    if (due.isNotEmpty()) {
                        val student = due.first()
                        val suggestion = when (state.forestProfiles[student.id]?.support) {
                            ForestSupport.WIND -> "让 TA 选一道愿意分享的题。"
                            ForestSupport.FIRE -> "先肯定一次具体的努力，再邀请 TA 分享想法。"
                            ForestSupport.EARTH -> "请 TA 完成一个步骤明确的小任务。"
                            ForestSupport.WATER -> "课后留一点时间，听 TA 说说今天的感受。"
                            else -> "邀请 TA 回答一个问题，或帮忙分发材料。"
                        }
                        Text("今天可以多关照 ${student.name}。$suggestion", style = MaterialTheme.typography.bodyMedium)
                        if (due.size > 1) Text("还有 ${due.size - 1} 位同学可以安排一次互动。", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            if (shown.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
                Text(if (students.isEmpty()) "请先在班级名单添加学生。" else "这个筛选下暂无学生。", Modifier.padding(8.dp))
            }
            items(shown, key = Student::id) { student ->
                val profile = state.forestProfiles[student.id]
                val lit = student.id in todayIds
                val total = counts[student.id] ?: 0
                Column(Modifier.fillMaxWidth().background(
                    if (total > 0) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer,
                    MaterialTheme.shapes.medium).padding(8.dp)) {
                    Column(Modifier.fillMaxWidth()
                        .semantics { stateDescription = if (lit) "今天已点亮" else "今天未点亮" }
                        .feedbackClickable { model.markForestInteraction(student.id, !lit) }.padding(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        val icon = profile?.animal?.icon ?: "🌱"
                        Text(if (lit) "$icon ✦" else icon, style = MaterialTheme.typography.headlineSmall)
                        Text(student.name, style = MaterialTheme.typography.bodyLarge)
                        Text(if (total == 0) "${period.label}未点亮" else "${period.label}互动 $total 天",
                            style = MaterialTheme.typography.bodySmall)
                    }
                    ActionIcon("edit", "编辑${student.name}的课堂档案", { editing = student.id })
                }
            }
        }
    }
    if (chooseAnimal) AlertDialog(onDismissRequest = { chooseAnimal = false }, title = { Text("按动物头像筛选") },
        text = {
            FlowRow(Modifier.heightIn(max = 340.dp).verticalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ForestAnimal.entries.forEach { option ->
                    FilterChip(animalFilter == option, feedbackClick { animalFilter = option; chooseAnimal = false },
                        label = { Text("${option.icon} ${option.label}") })
                }
            }
        },
        confirmButton = {
            FeedbackTextButton(onClick = { animalFilter = null; chooseAnimal = false }) { Text("全部头像") }
        })
    students.firstOrNull { it.id == editing }?.let { student ->
        ForestProfileDialog(student, state.forestProfiles[student.id], state.busy, { editing = null }) { profile ->
            model.saveForestProfile(profile) { editing = null }
        }
    }
}

@Composable
private fun ForestProfileDialog(student: Student, original: ForestProfile?, busy: Boolean,
    close: () -> Unit, onSave: (ForestProfile) -> Unit) {
    var animal by rememberSaveable(student.id) { mutableStateOf(original?.animal) }
    var group by rememberSaveable(student.id) { mutableStateOf(original?.group ?: ForestGroup.CLASS) }
    var support by rememberSaveable(student.id) { mutableStateOf(original?.support ?: ForestSupport.NONE) }
    var note by rememberSaveable(student.id) { mutableStateOf(original?.note.orEmpty()) }
    AlertDialog(onDismissRequest = close, title = { Text("${student.name} · 课堂档案") },
        text = {
            Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("动物头像", style = MaterialTheme.typography.titleMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ForestAnimal.entries.forEach { option ->
                        FilterChip(animal == option, feedbackClick { animal = option.takeUnless { it == animal } },
                            label = { Text("${option.icon} ${option.label}") })
                    }
                }
                Text("课堂分组", style = MaterialTheme.typography.titleMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ForestGroup.entries.forEach { option ->
                        FilterChip(group == option, feedbackClick { group = option }, label = { Text(option.label) })
                    }
                }
                Text("适合的互动方式", style = MaterialTheme.typography.titleMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ForestSupport.entries.forEach { option ->
                        FilterChip(support == option, feedbackClick { support = option }, label = { Text(option.label) })
                    }
                }
                QuietTextField(note, { note = it.take(200) }, "互动备注（选填）", singleLine = false)
            }
        },
        confirmButton = { FeedbackTextButton(onClick = { onSave(ForestProfile(student.id, animal, group, support, note)) },
            enabled = !busy) { Text("保存档案") } },
        dismissButton = { FeedbackTextButton(onClick = close) { Text("取消") } })
}
