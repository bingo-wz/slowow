package app.manman.teacher

import android.app.Application
import androidx.room.Room
import app.manman.teacher.auth.AuthRepository
import app.manman.teacher.auth.CloudBaseAuthClient
import app.manman.teacher.auth.CloudBaseTransport
import app.manman.teacher.auth.SecureSessionStore
import app.manman.teacher.data.ManmanDatabase
import app.manman.teacher.data.AttachmentFiles
import app.manman.teacher.data.RecordCodec
import app.manman.teacher.sync.SyncEngine
import app.manman.teacher.sync.HttpsSyncTransport
import app.manman.teacher.reminders.ReminderScheduler
import app.manman.teacher.recognition.RecognitionClient
import app.manman.teacher.fonts.AppFonts

class ManmanApplication : Application() {
    val database: ManmanDatabase by lazy {
        Room.databaseBuilder(this, ManmanDatabase::class.java, "manman.db").build()
    }
    val auth: AuthRepository by lazy {
        val store = SecureSessionStore(this)
        AuthRepository(CloudBaseAuthClient(CloudBaseTransport(
            BuildConfig.AUTH_BASE_URL, store.deviceId,
        )), store)
    }
    val attachments: AttachmentFiles by lazy { AttachmentFiles(this, BuildConfig.SYNC_BASE_URL) }
    val syncEngine: SyncEngine by lazy {
        SyncEngine(HttpsSyncTransport(BuildConfig.SYNC_BASE_URL), database.records(), auth) { account, record, token ->
            attachments.upload(account, RecordCodec.attachment(record), token)
        }
    }
    val reminders: ReminderScheduler by lazy { ReminderScheduler(this) }
    val recognition: RecognitionClient by lazy { RecognitionClient(BuildConfig.SYNC_BASE_URL, auth) }
    val fonts: AppFonts by lazy { AppFonts(this, BuildConfig.SYNC_BASE_URL) }
}
