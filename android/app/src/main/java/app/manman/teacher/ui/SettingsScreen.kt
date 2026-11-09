package app.manman.teacher.ui

import android.Manifest
import android.app.AlarmManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import app.manman.teacher.MainUiState
import app.manman.teacher.MainViewModel
import app.manman.teacher.data.LocalRecord
import app.manman.teacher.data.RecordKind
import app.manman.teacher.sync.SyncPhase
import app.manman.teacher.ManmanApplication
import org.json.JSONObject

@Composable
fun SettingsScreen(state: MainUiState, viewModel: MainViewModel, back: () -> Unit, onClass: () -> Unit, onSchedule: () -> Unit) {
    var confirmSignOut by remember { mutableStateOf(false) }
    var selectedConflict by remember { mutableStateOf<LocalRecord?>(null) }
    var bindRecognition by remember { mutableStateOf(false) }
    var pairingCode by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize()) {
        PageHeader("设置", back = back)
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            SectionHeading("班级")
            Block(onClick = onClass) {
                Text("班级与学生", style = MaterialTheme.typography.bodyLarge)
                Text("管理学生名单和固定用餐安排  ›", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Block(onClick = onSchedule) {
                Text("课程与作息", style = MaterialTheme.typography.bodyLarge)
                Text("管理课程和作息时间  ›", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            SectionHeading("提醒")
            ReminderSettings(state, viewModel)
            SectionHeading("显示")
            FontSetting((LocalContext.current.applicationContext as ManmanApplication).fonts)
            TextSizeSetting((LocalContext.current.applicationContext as ManmanApplication).textSizes)
            SectionHeading("识别服务")
            Block(onClick = { bindRecognition = true }) {
                Text("绑定识别账号", style = MaterialTheme.typography.bodyLarge)
                Text("输入设置 MiMo 密钥时显示的授权码。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            SectionHeading("数据与账号")
            Block {
                Text("同步状态", style = MaterialTheme.typography.bodyLarge)
                Text(if (state.pendingCount > 0) "${state.pendingCount} 项更改保存在本机，等待同步。" else "没有待同步的更改。",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                val status = when (state.syncPhase) {
                    SyncPhase.SYNCING -> "正在同步。"
                    SyncPhase.WAITING -> "同步暂未完成，请检查连接或稍后重试，本机更改仍然保留。"
                    SyncPhase.SIGN_IN_REQUIRED -> "登录状态需要验证，本机更改仍然保留。请使用同一手机号重新登录后继续同步。"
                    SyncPhase.IDLE -> "联网后会自动同步。"
                }
                Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                FeedbackTextButton(onClick = viewModel::syncNow) { Text("立即同步") }
            }
            if (state.conflicts.isNotEmpty()) {
                SectionHeading("需要核对")
                state.conflicts.forEach { record ->
                    Block(onClick = { selectedConflict = record }) {
                        Text("${kindLabel(record.kind)}在其他设备上有改动")
                        Text("本机与云端内容都已保留，点此核对。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            if (state.unreadableCount > 0) {
                Block { Text("有 ${state.unreadableCount} 条记录暂时无法读取，请保留应用数据并联系开发者。") }
            }
            FeedbackTextButton(onClick = { confirmSignOut = true }) { Text("退出登录") }
        }
    }
    if (bindRecognition) AlertDialog(
        onDismissRequest = feedbackClick { bindRecognition = false; pairingCode = ""; viewModel.clearMessage() },
        title = { Text("绑定识别账号") },
        text = {
            Column {
                Text("输入电脑上显示的 8 位授权码。最多可绑定两个账号，共用识别额度，各自的班级数据仍然分开保存。", style = MaterialTheme.typography.bodySmall)
                TextField(value = pairingCode, onValueChange = { pairingCode = it.filter(Char::isDigit).take(8) }, modifier = Modifier.feedbackOnFocus(),
                    label = { Text("授权码") }, singleLine = true, visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword))
                state.error?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = { FeedbackTextButton(onClick = { viewModel.bindRecognition(pairingCode) { bindRecognition = false; pairingCode = "" } },
            enabled = pairingCode.length == 8 && !state.busy) { Text("绑定") } },
        dismissButton = { FeedbackTextButton(onClick = { bindRecognition = false; pairingCode = ""; viewModel.clearMessage() }) { Text("取消") } },
    )
    if (confirmSignOut) {
        AlertDialog(
            onDismissRequest = feedbackClick { confirmSignOut = false },
            title = { Text(if (state.pendingCount > 0) "还有未同步的更改" else "退出登录？") },
            text = { Text(if (state.pendingCount > 0) "${state.pendingCount} 项更改会保留在这部手机上。请重新登录同一账号继续同步，在同步完成前不要清除应用数据。" else "退出后可以使用手机号再次登录。") },
            confirmButton = { FeedbackTextButton(onClick = { confirmSignOut = false; viewModel.signOut() }) { Text("退出登录") } },
            dismissButton = { FeedbackTextButton(onClick = { confirmSignOut = false }) { Text("取消") } },
        )
    }
    selectedConflict?.let { record ->
        val names = state.students.associate { it.id to it.name }
        val slots = state.timeSlots.associate { it.id to it.title }
        val notices = state.notices.associate { it.id to it.title }
        val localDescription = describeRecord(record.kind, record.payload, record.deleted, names, slots, notices)
        val remoteDescription = describeRecord(record.kind, record.conflictPayload.orEmpty(), record.conflictDeleted, names, slots, notices)
        val imageConflict = try {
            val local = JSONObject(record.payload)
            val remote = JSONObject(record.conflictPayload.orEmpty())
            when (record.kind) {
                RecordKind.ATTACHMENT -> true
                RecordKind.MENU_WEEK -> local.optString("attachmentId") != remote.optString("attachmentId")
                RecordKind.NOTICE -> (local.optJSONArray("attachmentIds")?.toString() ?: "[]") != (remote.optJSONArray("attachmentIds")?.toString() ?: "[]")
                else -> false
            }
        } catch (_: Exception) { true }
        val canCompare = localDescription != null && remoteDescription != null && !imageConflict
        AlertDialog(
            onDismissRequest = feedbackClick { selectedConflict = null },
            title = { Text("核对${kindLabel(record.kind)}") },
            text = {
                Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("本机内容", style = MaterialTheme.typography.titleMedium)
                    Text(localDescription ?: "内容暂时无法显示，请保留本机数据。")
                    Text("云端内容", style = MaterialTheme.typography.titleMedium)
                    Text(remoteDescription ?: "内容暂时无法显示，请保留本机数据。")
                    Text(if (imageConflict) "图片版本不同，当前暂不能安全比较。请保留本机数据并联系开发者。" else "选择要保留的版本后，记录会再次同步。", style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = { FeedbackTextButton(onClick = { viewModel.resolveConflict(record, true); selectedConflict = null }, enabled = canCompare) { Text("保留本机") } },
            dismissButton = { FeedbackTextButton(onClick = { viewModel.resolveConflict(record, false); selectedConflict = null }, enabled = canCompare) { Text("采用云端") } },
        )
    }
}

@Composable
private fun ReminderSettings(state: MainUiState, viewModel: MainViewModel) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var revision by remember { mutableStateOf(0) }
    var chooseLessonReminder by remember { mutableStateOf(false) }
    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        revision++
        viewModel.refreshReminders()
    }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) { revision++; viewModel.refreshReminders() }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val notificationsEnabled = remember(revision) { NotificationManagerCompat.from(context).areNotificationsEnabled() }
    val exactEnabled = remember(revision) {
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
    }
    Block(onClick = { chooseLessonReminder = true }) {
        Text("课程提醒", style = MaterialTheme.typography.bodyLarge)
        Text(state.lessonReminderMinutes?.let { if (it == 0) "上课时提醒" else "提前 $it 分钟提醒" } ?: "已关闭",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Block {
        Text(if (notificationsEnabled) "通知已开启" else "通知未开启", style = MaterialTheme.typography.bodyLarge)
        Text(if (exactEnabled) "准时提醒可用。" else "准时提醒未开启，系统可能延迟通知。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (!notificationsEnabled) FeedbackTextButton(onClick = {
            if (Build.VERSION.SDK_INT >= 33) notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            else context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
        }) { Text("开启通知") }
        if (!notificationsEnabled) FeedbackTextButton(onClick = {
            context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
        }) { Text("前往系统通知设置") }
        if (!exactEnabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) FeedbackTextButton(onClick = {
            context.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}")))
        }) { Text("设置准时提醒") }
        Text("小米手机还可能需要允许后台运行；请在真机上验证提醒。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (chooseLessonReminder) AlertDialog(
        onDismissRequest = feedbackClick { chooseLessonReminder = false }, title = { Text("课程提醒") },
        text = {
            Column {
                listOf(null to "关闭", 0 to "上课时", 5 to "提前 5 分钟", 10 to "提前 10 分钟", 15 to "提前 15 分钟").forEach { (minutes, label) ->
                    FeedbackTextButton(onClick = { chooseLessonReminder = false; viewModel.saveLessonReminder(minutes) }) { Text(label) }
                }
            }
        },
        confirmButton = { FeedbackTextButton(onClick = { chooseLessonReminder = false }) { Text("取消") } },
    )
}

private fun kindLabel(kind: String): String = when (kind) {
    RecordKind.STUDENT -> "学生信息"
    RecordKind.LEAVE -> "请假记录"
    RecordKind.MEAL_OVERRIDE, RecordKind.LUNCH_REVIEW -> "旧版用餐记录"
    RecordKind.MARKING -> "班级标记"
    RecordKind.MENU -> "菜单"
    RecordKind.MENU_WEEK -> "周菜单原图"
    RecordKind.ATTACHMENT -> "图片"
    RecordKind.TIME_SLOT -> "作息时间"
    RecordKind.LESSON -> "课程"
    RecordKind.NOTICE -> "学校通知"
    RecordKind.TASK -> "待办"
    RecordKind.SETTINGS -> "班级设置"
    else -> "记录"
}

private fun describeRecord(kind: String, payload: String, deleted: Boolean, names: Map<String, String>, slots: Map<String, String>, notices: Map<String, String>): String? {
    if (deleted) return "已删除"
    return try {
        val json = JSONObject(payload)
        when (kind) {
            RecordKind.STUDENT -> {
                val days = json.optJSONArray("schoolLunchDays")?.let { array -> (0 until array.length()).map(array::getInt).toSet() }
                val lunch = when {
                    days == null -> if (json.optBoolean("normallyEatsAtSchool", true)) "通常在校用餐" else "固定不在校用餐"
                    days.isEmpty() -> "固定不在校用餐"
                    days.size == 7 -> "通常在校用餐"
                    else -> "固定不在校用餐：" + (1..7).filterNot(days::contains).joinToString("、") { day -> listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")[day - 1] }
                }
                val initial = json.optString("sortInitial", "").takeIf(String::isNotBlank)?.let { "\n指定首字母：$it" }.orEmpty()
                "姓名：${json.getString("name")}\n$lunch$initial\n${if (json.optBoolean("active", true)) "在当前班级" else "已移出当前班级"}"
            }
            RecordKind.LEAVE -> {
                val period = when (json.getString("period")) { "ALL_DAY" -> "全天"; "MORNING" -> "上午"; "AFTERNOON" -> "下午"; else -> return null }
                "学生：${names[json.getString("studentId")] ?: "名单中的学生"}\n日期：${json.getString("start")} 至 ${json.getString("end")} · $period\n录入：${json.getString("recordedAt")}\n状态：${if (json.optBoolean("cancelled")) "已撤销" else "请假"}"
            }
            RecordKind.MEAL_OVERRIDE -> "学生：${names[json.getString("studentId")] ?: "名单中的学生"}\n日期：${json.getString("day")}\n${if (json.getString("value") == "EAT") "在校用餐" else "不在校用餐"}"
            RecordKind.LUNCH_REVIEW -> {
                val students = json.getJSONArray("students")
                val excluded = (0 until students.length()).map { students.getJSONObject(it) }.filterNot { it.getBoolean("eats") }.map { it.getString("name") }
                "日期：${json.getString("day")}\n预计用餐：${students.length() - excluded.size} 人\n不在校用餐：${excluded.joinToString("、").ifEmpty { "无" }}"
            }
            RecordKind.MARKING -> {
                val students = json.getJSONArray("students")
                val items = (0 until students.length()).map { students.getJSONObject(it) }
                val first = items.filter { it.getBoolean("first") }.map { it.getString("name") }
                val second = items.filterNot { it.getBoolean("first") }.map { it.getString("name") }
                "事项：${json.getString("title")}\n${json.getString("firstLabel")}：${first.joinToString("、").ifEmpty { "无" }}\n${json.getString("secondLabel")}：${second.joinToString("、").ifEmpty { "无" }}"
            }
            RecordKind.MENU -> "日期：${json.getString("day")}\n菜单：\n${json.getString("dishes")}"
            RecordKind.MENU_WEEK -> "周开始日期：${json.getString("start")}\n包含周菜单原图"
            RecordKind.ATTACHMENT -> "图片大小：${json.getLong("size")} 字节"
            RecordKind.TIME_SLOT -> "节次：${json.getString("title")}\n时间：${json.getString("start")} - ${json.getString("end")}"
            RecordKind.LESSON -> "星期：${json.getInt("weekday")}\n节次：${slots[json.getString("slotId")] ?: "关联的节次"}\n课程：${json.getString("subject")}"
            RecordKind.NOTICE -> "标题：${json.getString("title")}\n图片：${json.optJSONArray("attachmentIds")?.length() ?: 0} 张\n原文：\n${json.getString("content")}"
            RecordKind.TASK -> {
                fun optional(key: String): String = if (json.isNull(key)) "未设置" else json.getString(key)
                val source = if (json.isNull("noticeId")) "无" else notices[json.getString("noticeId")] ?: "关联的通知"
                "事项：${json.getString("title")}\n时间：${optional("dueAt")}\n日期：${optional("dueDate")}\n提醒：${optional("remindAt")}\n状态：${if (json.getBoolean("done")) "已完成" else "待处理"}\n来源：$source"
            }
            RecordKind.SETTINGS -> if (json.has("lessonMinutes")) {
                val minutes = json.optInt("lessonMinutes", -1)
                val course = if (minutes < 0) "关闭" else "提前 $minutes 分钟"
                "课程提醒：$course"
            } else "旧版设置已停用，原数据仍保留。"
            else -> null
        }
    } catch (_: Exception) {
        null
    }
}
