package app.manman.teacher.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow
import java.util.UUID

@Entity(
    tableName = "records",
    primaryKeys = ["accountId", "kind", "id"],
    indices = [Index(value = ["accountId", "dirty"])],
)
data class LocalRecord(
    val accountId: String,
    val kind: String,
    val id: String,
    val payload: String,
    val deleted: Boolean = false,
    val serverVersion: Long = 0,
    val operationId: String = "",
    val dirty: Boolean = false,
    val updatedAt: Long,
    val conflictPayload: String? = null,
    val conflictDeleted: Boolean = false,
    val conflictVersion: Long? = null,
)

@Entity(tableName = "sync_cursors", primaryKeys = ["accountId"])
data class SyncCursor(val accountId: String, val sequence: Long = 0)

data class RemoteRecord(
    val kind: String,
    val id: String,
    val payload: String,
    val deleted: Boolean,
    val version: Long,
    val sequence: Long,
    val updatedAt: Long,
)

@Dao
abstract class RecordDao {
    @Query("SELECT * FROM records WHERE accountId = :accountId ORDER BY updatedAt DESC")
    abstract fun observeRecords(accountId: String): Flow<List<LocalRecord>>

    @Query("SELECT * FROM records WHERE accountId = :accountId")
    abstract suspend fun snapshot(accountId: String): List<LocalRecord>

    @Query("SELECT * FROM records WHERE accountId = :accountId AND kind = :kind AND id = :id LIMIT 1")
    abstract suspend fun find(accountId: String, kind: String, id: String): LocalRecord?

    @Query("SELECT * FROM records WHERE accountId = :accountId AND dirty = 1 AND conflictVersion IS NULL ORDER BY CASE WHEN kind = 'attachment' THEN 0 ELSE 1 END, updatedAt ASC LIMIT :limit")
    abstract suspend fun pending(accountId: String, limit: Int = 50): List<LocalRecord>

    @Query("SELECT COUNT(*) FROM records WHERE accountId = :accountId AND dirty = 1")
    abstract suspend fun pendingCount(accountId: String): Int

    @Query("SELECT * FROM sync_cursors WHERE accountId = :accountId LIMIT 1")
    abstract suspend fun cursor(accountId: String): SyncCursor?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    protected abstract suspend fun put(record: LocalRecord)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    protected abstract suspend fun putCursor(cursor: SyncCursor)

    @Transaction
    open suspend fun saveLocal(
        accountId: String,
        kind: String,
        id: String,
        payload: String,
        deleted: Boolean = false,
        now: Long = System.currentTimeMillis(),
    ) {
        val previous = find(accountId, kind, id)
        put(LocalRecord(
            accountId = accountId,
            kind = kind,
            id = id,
            payload = payload,
            deleted = deleted,
            serverVersion = previous?.serverVersion ?: 0,
            operationId = UUID.randomUUID().toString(),
            dirty = true,
            updatedAt = now,
            conflictPayload = previous?.conflictPayload,
            conflictDeleted = previous?.conflictDeleted ?: false,
            conflictVersion = previous?.conflictVersion,
        ))
    }

    @Transaction
    open suspend fun acknowledge(accountId: String, sent: LocalRecord, version: Long) {
        val current = find(accountId, sent.kind, sent.id) ?: return
        val unchanged = current.operationId == sent.operationId
        put(current.copy(serverVersion = version, dirty = !unchanged || current.conflictVersion != null))
    }

    @Transaction
    open suspend fun saveConflict(accountId: String, kind: String, id: String, remote: RemoteRecord) {
        val current = find(accountId, kind, id) ?: return
        put(current.copy(
            conflictPayload = remote.payload,
            conflictDeleted = remote.deleted,
            conflictVersion = remote.version,
        ))
    }

    @Transaction
    open suspend fun applyRemote(accountId: String, records: List<RemoteRecord>, sequence: Long) {
        records.forEach { remote ->
            val current = find(accountId, remote.kind, remote.id)
            when {
                current == null || !current.dirty -> put(LocalRecord(
                    accountId = accountId,
                    kind = remote.kind,
                    id = remote.id,
                    payload = remote.payload,
                    deleted = remote.deleted,
                    serverVersion = remote.version,
                    updatedAt = remote.updatedAt,
                ))
                remote.version > current.serverVersion -> put(current.copy(
                    conflictPayload = remote.payload,
                    conflictDeleted = remote.deleted,
                    conflictVersion = remote.version,
                ))
            }
        }
        putCursor(SyncCursor(accountId, sequence))
    }

    @Transaction
    open suspend fun resolveConflict(accountId: String, kind: String, id: String, keepLocal: Boolean) {
        val current = find(accountId, kind, id) ?: return
        val remoteVersion = current.conflictVersion ?: return
        val remotePayload = current.conflictPayload ?: return
        put(if (keepLocal) {
            current.copy(
                serverVersion = remoteVersion,
                operationId = UUID.randomUUID().toString(),
                dirty = true,
                conflictPayload = null,
                conflictDeleted = false,
                conflictVersion = null,
            )
        } else {
            current.copy(
                payload = remotePayload,
                deleted = current.conflictDeleted,
                serverVersion = remoteVersion,
                dirty = false,
                operationId = "",
                conflictPayload = null,
                conflictDeleted = false,
                conflictVersion = null,
            )
        })
    }
}

@Database(entities = [LocalRecord::class, SyncCursor::class], version = 1, exportSchema = true)
abstract class ManmanDatabase : RoomDatabase() {
    abstract fun records(): RecordDao
}
