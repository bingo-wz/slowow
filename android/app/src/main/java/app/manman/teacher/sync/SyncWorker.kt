package app.manman.teacher.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.manman.teacher.ManmanApplication
import java.util.concurrent.TimeUnit

class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val accountId = inputData.getString("accountId") ?: return Result.failure()
        val application = applicationContext as ManmanApplication
        val result = application.syncEngine.sync(accountId)
        if (result != SyncResult.RETRY) application.reminders.reschedule()
        return when (result) {
            SyncResult.SUCCESS, SyncResult.SIGNED_OUT -> Result.success()
            SyncResult.RETRY -> Result.retry()
        }
    }

    companion object {
        fun enqueue(context: Context, accountId: String) {
            val request = OneTimeWorkRequestBuilder<SyncWorker>()
                .setInputData(workDataOf("accountId" to accountId))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork("manman-sync-$accountId", ExistingWorkPolicy.APPEND_OR_REPLACE, request)
        }
    }
}
