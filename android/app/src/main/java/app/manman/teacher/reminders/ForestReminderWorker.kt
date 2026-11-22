package app.manman.teacher.reminders

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.manman.teacher.MainActivity
import app.manman.teacher.ManmanApplication
import app.manman.teacher.R
import app.manman.teacher.data.ForestRecords
import app.manman.teacher.data.RecordCodec
import app.manman.teacher.data.RecordKind
import app.manman.teacher.domain.AppTime
import app.manman.teacher.domain.ForestAttention
import java.time.Duration
import java.time.ZonedDateTime
import java.util.UUID
import java.util.concurrent.TimeUnit

class ForestReminderWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as ManmanApplication
        val accountKey = inputData.getString(KEY) ?: return Result.success()
        app.auth.restore()
        val account = app.auth.session.value?.accountId ?: return Result.success()
        if (key(account) != accountKey || !enabled(app, account)) return Result.success()
        val today = ZonedDateTime.now(AppTime.zone).toLocalDate()
        if (today.dayOfWeek.value > 5) return Result.success()
        val preferences = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (preferences.getString("last_$accountKey", null) == today.toString()) return Result.success()
        val records = app.database.records().snapshot(account).filter { !it.deleted && it.conflictVersion == null }
        val students = records.filter { it.kind == RecordKind.STUDENT }.mapNotNull { runCatching { RecordCodec.student(it) }.getOrNull() }
        val profiles = records.filter { it.kind == RecordKind.SETTINGS && it.id.startsWith(ForestRecords.PROFILE) }
            .mapNotNull { runCatching { ForestRecords.profile(it) }.getOrNull() }.associateBy { it.studentId }
        val interactions = records.filter { it.kind == RecordKind.SETTINGS && it.id.startsWith(ForestRecords.INTERACTION) }
            .mapNotNull { runCatching { ForestRecords.interaction(it) }.getOrNull() }
        if (ForestAttention.due(today, students, profiles, interactions).isEmpty()) return Result.success()
        if (app.auth.session.value?.accountId != account || !enabled(app, account)) return Result.success()
        val manager = app.getSystemService(NotificationManager::class.java)
        if (!manager.areNotificationsEnabled()) return Result.success()
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "课堂关照", NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(app, 1002,
            Intent(app, MainActivity::class.java).setAction(OPEN_FOREST)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notice = Notification.Builder(app, CHANNEL).setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("课堂森林").setContentText("今天可以安排一次课堂互动。")
            .setContentIntent(open).setVisibility(Notification.VISIBILITY_PRIVATE).setAutoCancel(true).build()
        try {
            manager.notify(1002, notice)
            preferences.edit().putString("last_$accountKey", today.toString()).apply()
        } catch (_: SecurityException) {
            // 系统权限在检查之后被关闭，本次不发送。
        }
        return Result.success()
    }

    companion object {
        const val OPEN_FOREST = "app.manman.teacher.OPEN_FOREST"
        private const val CHANNEL = "classroom_attention"
        private const val PREFS = "forest_reminders"
        private const val KEY = "owner"
        private fun key(account: String) = UUID.nameUUIDFromBytes(account.toByteArray(Charsets.UTF_8)).toString()

        fun enabled(context: Context, account: String) =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(key(account), false)

        fun setEnabled(context: Context, account: String, enabled: Boolean) {
            val owner = key(account)
            val work = WorkManager.getInstance(context)
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(owner, enabled).apply()
            if (!enabled) {
                work.cancelUniqueWork("forest-$owner")
                context.getSystemService(NotificationManager::class.java).cancel(1002)
                return
            }
            val now = ZonedDateTime.now(AppTime.zone)
            val morning = now.toLocalDate().atTime(7, 30).atZone(AppTime.zone)
            val next = if (morning > now) morning else morning.plusDays(1)
            work.enqueueUniquePeriodicWork("forest-$owner", ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<ForestReminderWorker>(1, TimeUnit.DAYS)
                    .setInitialDelay(Duration.between(now, next).toMillis(), TimeUnit.MILLISECONDS)
                    .setInputData(workDataOf(KEY to owner)).build())
        }
    }
}
