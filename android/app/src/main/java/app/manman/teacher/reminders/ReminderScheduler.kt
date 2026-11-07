package app.manman.teacher.reminders

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import app.manman.teacher.MainActivity
import app.manman.teacher.ManmanApplication
import app.manman.teacher.R
import app.manman.teacher.data.Lesson
import app.manman.teacher.data.LocalRecord
import app.manman.teacher.data.RecordCodec
import app.manman.teacher.data.RecordKind
import app.manman.teacher.data.TimeSlot
import app.manman.teacher.data.WorkTask
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.time.Instant
import java.time.Duration
import java.time.LocalTime
import java.security.MessageDigest

private data class ReminderData(val ownerKey: String, val tasks: List<WorkTask>, val lessons: List<Lesson>, val slots: List<TimeSlot>,
    val minutes: Int?, val lunchTime: LocalTime?)

class ReminderScheduler(private val application: ManmanApplication) {
    private val alarm = application.getSystemService(AlarmManager::class.java)
    private val mutex = Mutex()
    private val preferences = application.getSharedPreferences("reminder_plan", Context.MODE_PRIVATE)

    suspend fun reschedule() = mutex.withLock {
        val data = load()
        val now = Instant.now()
        val expected = preferences.getLong("next_at", 0)
        if (expected > 0 && data != null && preferences.getString("owner", null) == data.ownerKey &&
            expected <= now.toEpochMilli() && now.toEpochMilli() - expected <= Duration.ofHours(2).toMillis() &&
            ReminderPlan.hasDue(data.tasks, data.lessons, data.slots, data.minutes, Instant.ofEpochMilli(expected).minusMillis(1000), now, data.lunchTime)) {
            preferences.edit().remove("next_at").remove("owner").apply()
            notifyUser()
        }
        schedule(data, now)
    }

    suspend fun deliver(expectedAt: Long) = mutex.withLock {
        val data = load()
        val now = Instant.now()
        val matches = expectedAt > 0 && preferences.getLong("next_at", 0) == expectedAt &&
            data != null && preferences.getString("owner", null) == data.ownerKey
        if (matches && now.toEpochMilli() - expectedAt <= Duration.ofHours(2).toMillis() &&
            ReminderPlan.hasDue(data!!.tasks, data.lessons, data.slots, data.minutes, Instant.ofEpochMilli(expectedAt).minusMillis(1000), now, data.lunchTime)) {
            preferences.edit().remove("next_at").remove("owner").apply()
            notifyUser()
        }
        if (matches) schedule(data, now)
    }

    private suspend fun load(): ReminderData? {
        application.auth.restore()
        val account = application.auth.session.value?.accountId ?: return null
        val records = application.database.records().snapshot(account).filterNot(LocalRecord::deleted)
        fun <T> decode(kind: String, parser: (LocalRecord) -> T) = records.filter { it.kind == kind }.mapNotNull {
            try { parser(it) } catch (_: Exception) { null }
        }
        val settings = records.firstOrNull { it.kind == RecordKind.SETTINGS && it.id == "reminders" }?.let {
            try { JSONObject(it.payload) } catch (_: Exception) { null }
        }
        val minutes = settings?.optInt("lessonMinutes", -1)?.takeIf { it in 0..60 }
        val lunch = if (settings?.optBoolean("lunchEnabled", false) == true) {
            records.firstOrNull { it.kind == RecordKind.SETTINGS && it.id == "main" }?.let {
                try { LocalTime.parse(JSONObject(it.payload).getString("lunchCutoff")) } catch (_: Exception) { null }
            } ?: app.manman.teacher.domain.LunchRules.defaultCutoff
        } else null
        val ownerKey = MessageDigest.getInstance("SHA-256").digest(account.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        return ReminderData(ownerKey, decode(RecordKind.TASK, RecordCodec::task), decode(RecordKind.LESSON, RecordCodec::lesson),
            decode(RecordKind.TIME_SLOT, RecordCodec::timeSlot), minutes, lunch)
    }

    private fun schedule(data: ReminderData?, now: Instant) {
        val pending = pendingIntent(0)
        alarm.cancel(pending)
        preferences.edit().remove("next_at").remove("owner").apply()
        val next = data?.let { ReminderPlan.next(it.tasks, it.lessons, it.slots, it.minutes, now, it.lunchTime) } ?: return
        preferences.edit().putLong("next_at", next.toEpochMilli()).putString("owner", data.ownerKey).apply()
        val operation = pendingIntent(next.toEpochMilli())
        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarm.canScheduleExactAlarms()) {
                alarm.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next.toEpochMilli(), operation)
            } else {
                alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next.toEpochMilli(), operation)
            }
        } catch (_: SecurityException) {
            alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next.toEpochMilli(), operation)
        }
    }

    private fun pendingIntent(expectedAt: Long): PendingIntent = PendingIntent.getBroadcast(application, 1001,
        Intent(application, ReminderReceiver::class.java).setAction(ACTION_REMINDER).putExtra(EXTRA_EXPECTED_AT, expectedAt),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    private fun notifyUser() {
        val manager = application.getSystemService(NotificationManager::class.java)
        if (!manager.areNotificationsEnabled()) return
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(application, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "课程与待办", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "提醒课程和需要处理的工作"
            lockscreenVisibility = Notification.VISIBILITY_PRIVATE
        })
        val open = PendingIntent.getActivity(application, 0, Intent(application, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val publicNotification = Notification.Builder(application, CHANNEL).setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("慢慢提醒").setContentText("你有一项工作需要查看").build()
        val notification = Notification.Builder(application, CHANNEL).setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("慢慢提醒").setContentText("课程、待办或午餐核验时间到了，打开慢慢查看。")
            .setContentIntent(open).setAutoCancel(true).setVisibility(Notification.VISIBILITY_PRIVATE)
            .setPublicVersion(publicNotification).build()
        manager.notify(1001, notification)
    }

    companion object {
        private const val CHANNEL = "teacher_reminders"
        const val ACTION_REMINDER = "app.manman.teacher.REMINDER"
        const val EXTRA_EXPECTED_AT = "expected_at"
    }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val scheduler = (context.applicationContext as ManmanApplication).reminders
                if (intent.action == ReminderScheduler.ACTION_REMINDER) {
                    scheduler.deliver(intent.getLongExtra(ReminderScheduler.EXTRA_EXPECTED_AT, 0))
                } else {
                    scheduler.reschedule()
                }
            } finally {
                pending.finish()
            }
        }
    }
}
