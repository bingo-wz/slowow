package app.manman.teacher.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.manman.teacher.MainUiState
import app.manman.teacher.data.Lesson
import app.manman.teacher.data.ScheduleKind
import app.manman.teacher.data.ScheduleProfile
import app.manman.teacher.domain.AppTime
import app.manman.teacher.domain.TeacherTimetable
import app.manman.teacher.domain.TimetableItem
import app.manman.teacher.domain.TaskOrdering
import kotlinx.coroutines.delay
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter

private data class ScheduleColors(
    val blue: Color, val blueSoft: Color, val blueText: Color,
    val amber: Color, val amberSoft: Color, val mint: Color, val mintSoft: Color,
    val now: Color, val today: Color,
)

@Composable
private fun scheduleColors(): ScheduleColors = if (isSystemInDarkTheme()) ScheduleColors(
    Color(0xFFAFC9FF), Color(0xFF21395D), Color(0xFFD9E7FF), Color(0xFFF0CB8B), Color(0xFF4B3B21),
    Color(0xFF9FD8BB), Color(0xFF213D32), Color(0xFFFFAAA2), Color(0xFF172D46),
) else ScheduleColors(
    Color(0xFF3B6FD4), Color(0xFFEAF1FF), Color(0xFF254F9D), Color(0xFF946019), Color(0xFFFFF4E3),
    Color(0xFF397A60), Color(0xFFE8F6EF), Color(0xFFBE3F36), Color(0xFFF1F6FF),
)

@Composable
private fun rememberScheduleNow(): LocalDateTime {
    var now by remember { mutableStateOf(LocalDateTime.now(AppTime.zone)) }
    LaunchedEffect(Unit) {
        while (true) {
            now = LocalDateTime.now(AppTime.zone)
            delay(30_000)
        }
    }
    return now
}

@Composable
fun TeacherSchedulePage(
    state: MainUiState,
    images: List<String>,
    onBack: () -> Unit,
    onImport: () -> Unit,
    onAddImages: () -> Unit,
    onRecognize: () -> Unit,
    onStopRecognition: () -> Unit,
    onReview: () -> Unit,
    onAddSlot: () -> Unit,
    onEditSlot: (TimetableItem) -> Unit,
    onEditLesson: (TimetableItem) -> Unit,
    onEditProfile: () -> Unit,
    onPrint: (LocalDate, List<TimetableItem>, String?, ScheduleProfile) -> Unit,
) {
    val now = rememberScheduleNow()
    var weekText by rememberSaveable { mutableStateOf(TeacherTimetable.weekStart(now.toLocalDate()).toString()) }
    val week = LocalDate.parse(weekText)
    var dayView by rememberSaveable { mutableStateOf(false) }
    var dayIndex by rememberSaveable { mutableStateOf((now.dayOfWeek.value - 1).coerceIn(0, 6)) }
    var teacher by rememberSaveable { mutableStateOf("") }
    var chooseTeacher by remember { mutableStateOf(false) }
    var showWeekend by rememberSaveable { mutableStateOf(false) }
    var details by remember { mutableStateOf<TimetableItem?>(null) }
    val filter = teacher.takeIf(String::isNotBlank)
    val allItems = remember(week, state.timeSlots, state.lessons) { TeacherTimetable.build(week, state.timeSlots, state.lessons) }
    val currentWeek = TeacherTimetable.weekStart(now.toLocalDate()) == week
    val day = week.plusDays(dayIndex.toLong())
    val days = if (showWeekend || dayIndex >= 5 || state.lessons.any { it.weekday >= 6 } ||
        state.timeSlots.any { it.weekdays.any { weekday -> weekday >= 6 } }) 7 else 5
    val teachers = remember(state.lessons, state.scheduleProfile.myName) {
        (TeacherTimetable.teacherNames(state.lessons) + state.scheduleProfile.myName).filter(String::isNotBlank).distinct().sorted()
    }
    val colors = scheduleColors()
    val summary = remember(allItems, now.toLocalDate(), filter) { TeacherTimetable.summary(allItems, now.toLocalDate(), filter) }
    val titleParts = listOf(state.scheduleProfile.school, state.scheduleProfile.term, state.scheduleProfile.className,
        state.scheduleProfile.myName).filter(String::isNotBlank)
    val weekNumber = TeacherTimetable.schoolWeek(week, state.scheduleProfile.termStart)
    val weekLabel = weekNumber?.let { "第 ${it} 周" } ?: if (currentWeek) "本周" else "所选周"
    val range = "${week.format(DateTimeFormatter.ofPattern("M月d日"))}—${week.plusDays(6).format(DateTimeFormatter.ofPattern("M月d日"))}"
    Column(Modifier.fillMaxSize()) {
        PageHeader("教师课程时间表", titleParts.joinToString(" · ").ifBlank { "课程、值守与作息" }, back = onBack,
            action = { ActionIcon("edit", "编辑课表信息", onEditProfile) })
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.large) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    key(week, weekLabel) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            ActionIcon("back", "上一周", { weekText = week.minusWeeks(1).toString() })
                            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(weekLabel, style = MaterialTheme.typography.titleMedium)
                                Text(range, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            ActionIcon("forward", "下一周", { weekText = week.plusWeeks(1).toString() })
                        }
                    }
                    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        FilterChip(selected = !dayView, onClick = feedbackClick { dayView = false }, label = { Text("周视图") })
                        FilterChip(selected = dayView, onClick = feedbackClick { dayView = true }, label = { Text("今日视图") })
                        FilterChip(selected = filter != null, onClick = feedbackClick { chooseTeacher = true },
                            label = { Text(filter ?: "全部教师") })
                        if (!currentWeek) FilterChip(selected = false, onClick = feedbackClick { weekText = TeacherTimetable.weekStart(now.toLocalDate()).toString() },
                            label = { Text("回到本周") })
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        FeedbackTextButton(onClick = onImport, enabled = !state.busy,
                            modifier = Modifier.weight(1f).semantics { contentDescription = "识别课程表和作息表" }) {
                            Text(if (LocalDensity.current.fontScale >= 1.3f) "图片识别" else "识别课程表和作息表")
                        }
                        ActionIcon("plus", "添加节次", onAddSlot, enabled = !state.busy)
                        ActionIcon("print", "打印或保存课表 PDF", { onPrint(week, allItems, filter, state.scheduleProfile) }, enabled = allItems.isNotEmpty())
                    }
                    if (state.scheduleProgress != null) {
                        ScheduleProgress(state.scheduleProgress)
                        FeedbackTextButton(onClick = onStopRecognition) { Text("停止识别") }
                    } else {
                        if (state.scheduleSuggestion != null) FeedbackTextButton(onClick = onReview) { Text("继续核对识别结果") }
                        if (images.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("已选择 ${images.size} 张图片", style = MaterialTheme.typography.bodySmall)
                            if (images.size < 2) FeedbackTextButton(onClick = onAddImages) { Text("再添加一张") }
                            FeedbackTextButton(onClick = onRecognize, enabled = !state.busy) { Text("开始识别") }
                        }
                    }
                }
            }
            if (filter != null) Text("仅统计已标注该教师的事项；未填写教师的安排仍会保留。", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            ScheduleNowPanel(allItems, now, currentWeek, filter, colors)
            ScheduleSummary(summary.weeklyLessons, summary.todayLessons, summary.todayDuty, summary.freeMinutes,
                summary.unclassified, currentWeek, colors)
            Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.large) {
                Column(Modifier.fillMaxWidth().padding(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(if (dayView) "${scheduleDayNames[dayIndex]}时间线" else "$weekLabel · 我的课表",
                            Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                        if (!showWeekend && days == 5) FeedbackTextButton(onClick = { showWeekend = true }) { Text("周末") }
                    }
                    ScheduleLegend(colors)
                    key(dayView, state.timeSlots, state.lessons) {
                        if (state.timeSlots.isEmpty()) {
                            Text("还没有作息时间，可手动添加或识别图片。", Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        } else if (dayView) {
                            val scroll = rememberScrollState()
                            Row(Modifier.horizontalScroll(scroll).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                repeat(days) { index ->
                                    FilterChip(selected = dayIndex == index, onClick = feedbackClick { dayIndex = index },
                                        label = { Text(scheduleDayNames[index]) })
                                }
                            }
                            ScheduleDayList(allItems.filter { it.date == day }, now, filter, state.scheduleProfile, colors, { details = it })
                        } else {
                            ScheduleWeekGrid(week, allItems, days, now, filter, state.scheduleProfile, colors, { details = it })
                            Text("左右滑动查看星期，点击事项查看详情。", Modifier.padding(horizontal = 16.dp),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            if (!dayView && currentWeek) {
                Text("今日时间线", style = MaterialTheme.typography.titleMedium)
                ScheduleDayList(allItems.filter { it.date == now.toLocalDate() }, now, filter, state.scheduleProfile, colors, { details = it })
            }
            val todayTasks = state.tasks.filter { !it.done && TaskOrdering.date(it) == now.toLocalDate() }
            if (todayTasks.isNotEmpty() || state.scheduleProfile.notes.isNotBlank()) {
                Block {
                    Text("待办与提醒", style = MaterialTheme.typography.titleMedium)
                    todayTasks.forEach { Text("• ${it.title}", style = MaterialTheme.typography.bodyMedium) }
                    if (state.scheduleProfile.notes.isNotBlank()) Text(state.scheduleProfile.notes, style = MaterialTheme.typography.bodySmall)
                }
            }
            ScheduleWorkload(allItems.mapNotNull { it.lesson }.filter { it.kind == ScheduleKind.TEACHING && TeacherTimetable.matchesTeacher(it, filter) }, colors)
            if (summary.unclassified > 0) Text("${summary.unclassified} 项尚未分类，不计入授课、值守或空堂统计。", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
        }
    }
    if (chooseTeacher) AlertDialog(onDismissRequest = feedbackClick { chooseTeacher = false }, title = { Text("选择教师") },
        text = { Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
            FeedbackTextButton(onClick = { teacher = ""; chooseTeacher = false }, modifier = Modifier.fillMaxWidth()) { Text("全部教师") }
            teachers.forEach { name ->
                FeedbackTextButton(onClick = { teacher = name; chooseTeacher = false }, modifier = Modifier.fillMaxWidth()) { Text(name) }
            }
            if (teachers.isEmpty()) Text("还没有填写教师，可在事项详情中补充。", style = MaterialTheme.typography.bodySmall)
        } }, confirmButton = { FeedbackTextButton(onClick = { chooseTeacher = false }) { Text("关闭") } })
    details?.let { selected ->
        ScheduleItemDetails(selected, state.scheduleProfile, onDismiss = { details = null },
            onEditSlot = { details = null; onEditSlot(selected) }, onEditLesson = { details = null; onEditLesson(selected) })
    }
}

@Composable
private fun ScheduleNowPanel(items: List<TimetableItem>, now: LocalDateTime, currentWeek: Boolean, teacher: String?, colors: ScheduleColors) {
    val (current, next) = if (currentWeek) TeacherTimetable.currentAndNext(items, now, teacher) else null to null
    Surface(color = colors.blueSoft, shape = MaterialTheme.shapes.large) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(if (!currentWeek) "所选周的安排" else if (teacher == null) "此刻 · 当天安排" else "此刻 · $teacher 的安排",
                color = colors.blueText, style = MaterialTheme.typography.titleMedium)
            Text(when {
                !currentWeek -> "课程按每周重复显示"
                current != null -> current.lesson?.subject.orEmpty()
                next != null -> "当前没有已填写的事项"
                teacher != null -> "今天没有该教师的已标注事项"
                else -> "今天没有更多已填写的事项"
            }, style = MaterialTheme.typography.bodyLarge)
            if (current != null) {
                val seconds = java.time.Duration.between(now.toLocalTime(), current.end).seconds.coerceAtLeast(0)
                val remaining = (seconds + 59) / 60
                Text("${current.start}—${current.end} · 剩余约 $remaining 分钟", style = MaterialTheme.typography.bodySmall)
                if (current.kind == null) Text("事项类型待确认", style = MaterialTheme.typography.bodySmall)
                current.lesson?.location?.takeIf(String::isNotBlank)?.let { Text("地点：$it", style = MaterialTheme.typography.bodySmall) }
            }
            if (next != null) Text("下一项  ${next.start}  ${next.lesson?.subject}", style = MaterialTheme.typography.bodySmall, color = colors.blueText)
        }
    }
}

@Composable
private fun ScheduleSummary(lessons: Int, today: Int, duty: Int, freeMinutes: Long, unknown: Int, currentWeek: Boolean, colors: ScheduleColors) {
    val values = listOf("本周授课" to "$lessons 节", "今日授课" to if (currentWeek) "$today 节" else "—",
        "今日到岗" to if (currentWeek) "$duty 项" else "—", "本周空堂" to if (freeMinutes == 0L) "0 分钟" else "${freeMinutes / 60}小时${freeMinutes % 60}分")
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        values.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { (label, value) ->
                    Surface(modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.medium) {
                        Column(Modifier.padding(16.dp)) {
                            Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(value, style = MaterialTheme.typography.titleMedium, color = colors.blueText)
                        }
                    }
                }
            }
        }
        if (unknown > 0) Text("仅统计已分类事项", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ScheduleLegend(colors: ScheduleColors) {
    FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp), itemVerticalAlignment = Alignment.CenterVertically) {
        listOf("授课" to colors.blue, "值守" to colors.amber, "空堂" to colors.mint,
            "待确认" to MaterialTheme.colorScheme.onSurfaceVariant, "当前" to colors.now).forEach { (label, color) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).background(color, MaterialTheme.shapes.small))
                Text(" $label", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

private data class TimeBand(val start: LocalTime, val end: LocalTime, val top: Dp, val height: Dp)

@Composable
private fun ScheduleWeekGrid(week: LocalDate, items: List<TimetableItem>, days: Int, now: LocalDateTime, teacher: String?,
    profile: ScheduleProfile, colors: ScheduleColors, onOpen: (TimetableItem) -> Unit) {
    val selectedItems = items.filter { chronoDay(it.date, week) in 0 until days }
    val points = remember(selectedItems) { selectedItems.flatMap { listOf(it.start, it.end) }.distinct().sorted() }
    if (points.size < 2) {
        Text("本周还没有作息安排。", Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall)
        return
    }
    val density = LocalDensity.current
    val titleLine = with(density) { MaterialTheme.typography.labelLarge.lineHeight.toDp() }
    val smallLine = with(density) { MaterialTheme.typography.labelSmall.lineHeight.toDp() }
    val smallFont = with(density) { MaterialTheme.typography.labelSmall.fontSize.toDp() }
    val minimumHeight = titleLine * 2 + smallLine * 3 + 32.dp
    val roleHeight = minimumHeight + smallLine
    val dayWidth = maxOf(160.dp, smallFont * 8 + 32.dp)
    val bands = remember(points, minimumHeight, roleHeight, selectedItems) {
        val lengths = points.zipWithNext().map { (start, end) ->
            maxOf(12.dp, (java.time.Duration.between(start, end).toMinutes() * 1.4f).dp)
        }.toMutableList()
        selectedItems.sortedBy(TimetableItem::minutes).forEach { item ->
            val first = points.indexOf(item.start)
            val last = points.indexOf(item.end)
            if (first >= 0 && last > first) {
                val height = (first until last).fold(0.dp) { acc, index -> acc + lengths[index] }
                val required = if (item.lesson?.role?.isNotBlank() == true) roleHeight else minimumHeight
                if (height < required) {
                    val longest = (first until last).maxBy { lengths[it] }
                    lengths[longest] += required - height
                }
            }
        }
        var offset = 0.dp
        points.zipWithNext().mapIndexed { index, (start, end) ->
            TimeBand(start, end, offset, lengths[index]).also { offset += lengths[index] }
        }
    }
    val totalHeight = bands.last().let { it.top + it.height }
    val scroll = rememberScrollState()
    LaunchedEffect(week, days, dayWidth, now.toLocalDate()) {
        val todayIndex = chronoDay(now.toLocalDate(), week)
        if (todayIndex in 0 until days) {
            withFrameNanos { }
            val target = with(density) { dayWidth.roundToPx() } * todayIndex
            scroll.scrollTo(target.coerceAtMost(scroll.maxValue))
        }
    }
    val axisWidth = maxOf(72.dp, smallFont * 4 + 16.dp)
    val labelSpacing = smallLine + 4.dp
    val startTimes = selectedItems.map(TimetableItem::start).toSet()
    val axisLabels = remember(bands, startTimes, labelSpacing) {
        var lastY = -labelSpacing
        bands.filter { band ->
            if (band.start in startTimes && band.top - lastY >= labelSpacing) {
                lastY = band.top
                true
            } else false
        }
    }
    Column {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("时间", Modifier.width(axisWidth).padding(start = 16.dp), style = MaterialTheme.typography.labelSmall)
            Row(Modifier.weight(1f).horizontalScroll(scroll)) {
                repeat(days) { index ->
                    val date = week.plusDays(index.toLong())
                    Column(Modifier.width(dayWidth).background(if (date == now.toLocalDate()) colors.today else Color.Transparent)
                        .padding(horizontal = 8.dp, vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(scheduleDayNames[index], style = MaterialTheme.typography.labelLarge,
                            color = if (date == now.toLocalDate()) colors.blue else MaterialTheme.colorScheme.onSurface)
                        Text(date.format(DateTimeFormatter.ofPattern("M/d")), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth()) {
            Box(Modifier.width(axisWidth).height(totalHeight)) {
                axisLabels.forEach { band ->
                    Text(band.start.format(DateTimeFormatter.ofPattern("HH:mm")),
                        Modifier.offset(y = band.top).padding(start = 8.dp, top = 4.dp),
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Box(Modifier.weight(1f).horizontalScroll(scroll)) {
                Box(Modifier.width(dayWidth * days).height(totalHeight)) {
                    repeat(days) { index ->
                        Box(Modifier.offset(x = dayWidth * index).width(dayWidth).height(totalHeight)
                            .background(if (week.plusDays(index.toLong()) == now.toLocalDate()) colors.today else Color.Transparent))
                    }
                    Canvas(Modifier.matchParentSize()) {
                        val line = colors.blue.copy(alpha = 0.12f)
                        bands.forEach { band -> drawLine(line, Offset(0f, band.top.toPx()), Offset(size.width, band.top.toPx()), 1.dp.toPx()) }
                    }
                    selectedItems.filter { it.coveredBy == null }.forEach { item ->
                        val startBand = bands.firstOrNull { it.start == item.start }
                        val endBand = bands.lastOrNull { it.end == item.end }
                        if (startBand != null && endBand != null) {
                            val column = chronoDay(item.date, week)
                            val itemHeight = endBand.top + endBand.height - startBand.top
                            ScheduleGridItem(item, profile, now, teacher, colors,
                                Modifier.offset(x = dayWidth * column, y = startBand.top).width(dayWidth).height(itemHeight).padding(4.dp)) { onOpen(item) }
                        }
                    }
                    val todayIndex = chronoDay(now.toLocalDate(), week)
                    val nowTime = now.toLocalTime()
                    val band = bands.firstOrNull { nowTime >= it.start && nowTime < it.end }
                    if (todayIndex in 0 until days && band != null) {
                        val active = selectedItems.firstOrNull {
                            it.coveredBy == null && it.date == now.toLocalDate() && nowTime >= it.start && nowTime < it.end
                        }
                        val firstBand = active?.let { item -> bands.firstOrNull { it.start == item.start } }
                        val lastBand = active?.let { item -> bands.lastOrNull { it.end == item.end } }
                        val from = active?.start ?: band.start
                        val until = active?.end ?: band.end
                        val top = firstBand?.top ?: band.top
                        val bottom = lastBand?.let { it.top + it.height } ?: (band.top + band.height)
                        val elapsed = java.time.Duration.between(from, nowTime).toMillis().toFloat()
                        val duration = java.time.Duration.between(from, until).toMillis().coerceAtLeast(1).toFloat()
                        val y = top + (bottom - top) * (elapsed / duration).coerceIn(0f, 1f)
                        Canvas(Modifier.offset(x = dayWidth * todayIndex, y = y).width(dayWidth).height(2.dp)) {
                            drawLine(colors.now, Offset.Zero, Offset(size.width, 0f), 2.dp.toPx())
                        }
                    }
                }
            }
        }
    }
}

private fun chronoDay(day: LocalDate, week: LocalDate): Int = java.time.temporal.ChronoUnit.DAYS.between(week, day).toInt()

@Composable
private fun ScheduleGridItem(item: TimetableItem, profile: ScheduleProfile, now: LocalDateTime, teacher: String?, colors: ScheduleColors,
    modifier: Modifier = Modifier, onClick: () -> Unit) {
    val lesson = item.lesson
    val background = when (lesson?.kind) {
        ScheduleKind.TEACHING -> colors.blueSoft
        ScheduleKind.DUTY -> colors.amberSoft
        ScheduleKind.FREE -> colors.mintSoft
        null -> MaterialTheme.colorScheme.surfaceContainer
    }
    val ink = when (lesson?.kind) {
        ScheduleKind.TEACHING -> colors.blueText
        ScheduleKind.DUTY -> colors.amber
        ScheduleKind.FREE -> colors.mint
        null -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val isCurrent = item.date == now.toLocalDate() && now.toLocalTime() >= item.start && now.toLocalTime() < item.end
    val past = item.date.isBefore(now.toLocalDate()) || (item.date == now.toLocalDate() && now.toLocalTime() >= item.end)
    val otherTeacher = teacher != null && lesson != null && lesson.teachers.isNotBlank() && !TeacherTimetable.matchesTeacher(lesson, teacher)
    val border = if (isCurrent) colors.now else ink.copy(alpha = if (lesson?.kind == ScheduleKind.TEACHING) 0.35f else 0.6f)
    val dashed = lesson?.kind == ScheduleKind.DUTY || lesson?.kind == ScheduleKind.FREE
    Column(modifier.semantics { stateDescription = if (isCurrent) "进行中" else if (past) "已结束" else "待进行" }
        .clip(MaterialTheme.shapes.small).background(background).drawBehind {
        val effect = if (dashed && !isCurrent) PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx())) else null
        drawRoundRect(border, size = Size(size.width, size.height), cornerRadius = androidx.compose.ui.geometry.CornerRadius(8.dp.toPx()),
            style = Stroke(width = (if (isCurrent) 2.dp else 1.dp).toPx(), pathEffect = effect))
    }.feedbackClickable(onClick = onClick).alpha(if (past || otherTeacher) 0.62f else 1f).padding(8.dp),
        verticalArrangement = Arrangement.Center) {
        Text(lesson?.subject ?: item.slot.title, style = MaterialTheme.typography.labelLarge, color = ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text("${item.start}—${item.end}", style = MaterialTheme.typography.labelSmall, color = ink, maxLines = 1)
        val context = listOf(if (lesson == null) "未安排" else item.slot.title,
            lesson?.className?.ifBlank { profile.className } ?: "", lesson?.location.orEmpty())
            .filter(String::isNotBlank).joinToString(" · ")
        if (context.isNotBlank()) Text(context, style = MaterialTheme.typography.bodySmall, color = ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (lesson?.role?.isNotBlank() == true) Text(lesson.role, style = MaterialTheme.typography.labelSmall, color = ink, maxLines = 1)
    }
}

@Composable
private fun ScheduleDayList(items: List<TimetableItem>, now: LocalDateTime, teacher: String?, profile: ScheduleProfile, colors: ScheduleColors,
    onOpen: (TimetableItem) -> Unit) {
    val visible = items.filter { it.coveredBy == null }
    if (visible.isEmpty()) {
        Text("这一天还没有作息安排。", Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    key(visible, profile.className) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            visible.forEach { item ->
                val current = item.date == now.toLocalDate() && now.toLocalTime() >= item.start && now.toLocalTime() < item.end
                val past = item.date.isBefore(now.toLocalDate()) || (item.date == now.toLocalDate() && now.toLocalTime() >= item.end)
                Surface(color = when (item.kind) {
                    ScheduleKind.TEACHING -> colors.blueSoft
                    ScheduleKind.DUTY -> colors.amberSoft
                    ScheduleKind.FREE -> colors.mintSoft
                    null -> MaterialTheme.colorScheme.surfaceContainer
                }, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth().feedbackClickable { onOpen(item) }) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(item.lesson?.subject ?: "${item.slot.title} · 未安排", style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.alpha(if (past || (teacher != null && item.lesson != null && item.lesson.teachers.isNotBlank() &&
                                    !TeacherTimetable.matchesTeacher(item.lesson, teacher))) 0.62f else 1f))
                            Text("${item.start}—${item.end}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            item.lesson?.let { lesson ->
                                val context = listOf(lesson.className.ifBlank { profile.className }, lesson.location, lesson.role)
                                    .filter(String::isNotBlank).joinToString(" · ")
                                if (context.isNotBlank()) Text(context, style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        Text(if (current) "进行中" else if (past) "已结束" else "待进行", style = MaterialTheme.typography.labelSmall,
                            color = if (current) colors.now else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun ScheduleWorkload(lessons: List<Lesson>, colors: ScheduleColors) {
    if (lessons.isEmpty()) return
    val groups = lessons.groupingBy(Lesson::subject).eachCount().entries.sortedByDescending { it.value }
    val largest = groups.first().value
    Block {
        Text("本周课时构成", style = MaterialTheme.typography.titleMedium)
        groups.forEach { (subject, count) ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(subject, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                Box(Modifier.width(96.dp).height(8.dp).background(colors.blueSoft, MaterialTheme.shapes.small)) {
                    Box(Modifier.fillMaxWidth(count.toFloat() / largest).height(8.dp).background(colors.blue, MaterialTheme.shapes.small))
                }
                Text("  $count 节", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun ScheduleItemDetails(item: TimetableItem, profile: ScheduleProfile, onDismiss: () -> Unit,
    onEditSlot: () -> Unit, onEditLesson: () -> Unit) {
    val lesson = item.lesson
    val type = when (lesson?.kind) {
        ScheduleKind.TEACHING -> "授课"
        ScheduleKind.DUTY -> "值守 / 班级活动"
        ScheduleKind.FREE -> "空堂"
        null -> if (lesson == null) "未安排" else "待确认"
    }
    AlertDialog(onDismissRequest = feedbackClick(onDismiss), title = { Text(lesson?.subject ?: item.slot.title) },
        text = { Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("${item.date} · ${item.start}—${item.end}", style = MaterialTheme.typography.bodyLarge)
            Text("节次：${item.slot.title}", style = MaterialTheme.typography.bodyMedium)
            Text("类型：$type", style = MaterialTheme.typography.bodyMedium)
            if (item.spanIncomplete) Text("结束节次已失效，请重新选择。", style = MaterialTheme.typography.bodySmall)
            lesson?.let {
                listOf("教师" to it.teachers, "班级" to it.className.ifBlank { profile.className }, "地点" to it.location,
                    "角色" to it.role, "课前准备" to it.preparation, "备注" to it.note).forEach { (label, value) ->
                    if (value.isNotBlank()) Text("$label：$value", style = MaterialTheme.typography.bodyMedium)
                }
                val reminder = when (it.reminderMinutes) {
                    null -> "跟随课程提醒设置"
                    -1 -> "关闭"
                    0 -> "开始时"
                    else -> "提前 ${it.reminderMinutes} 分钟"
                }
                Text("提醒：$reminder", style = MaterialTheme.typography.bodySmall)
            }
        } },
        confirmButton = { FeedbackTextButton(onClick = onEditLesson) { Text(if (lesson == null) "添加事项" else "编辑事项") } },
        dismissButton = { Row {
            FeedbackTextButton(onClick = onEditSlot) { Text("调整作息") }
            FeedbackTextButton(onClick = onDismiss) { Text("关闭") }
        } })
}
