package app.manman.teacher

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.manman.teacher.auth.AuthSession
import app.manman.teacher.auth.SessionProvider
import app.manman.teacher.data.ClassRepository
import app.manman.teacher.data.ManmanDatabase
import app.manman.teacher.data.RecordCodec
import app.manman.teacher.data.RecordKind
import app.manman.teacher.data.RemoteRecord
import app.manman.teacher.data.RoutineRule
import app.manman.teacher.data.RoutineTime
import app.manman.teacher.data.RoutineVersion
import app.manman.teacher.data.WorkTask
import app.manman.teacher.domain.AppTime
import app.manman.teacher.domain.TaskOrdering
import app.manman.teacher.sync.SyncEngine
import app.manman.teacher.sync.SyncResponse
import app.manman.teacher.sync.SyncResult
import app.manman.teacher.sync.SyncTransport
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.time.LocalTime
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class RoutineSyncInstrumentedTest {
    private class Session(private val owner: String) : SessionProvider {
        override val session: StateFlow<AuthSession?> = MutableStateFlow(AuthSession(owner, "fake", "fake", Long.MAX_VALUE))
        override suspend fun restore() = Unit
        override suspend fun currentAccessToken(accountId: String): String { require(accountId == owner); return "fake" }
    }

    private class Server : SyncTransport {
        val records = linkedMapOf<String, JSONObject>()
        private val operations = mutableMapOf<String, JSONObject>()
        private var sequence = 0L

        fun seed(kind: String, id: String, payload: String): RemoteRecord {
            sequence++
            val json = JSONObject().put("kind", kind).put("id", id).put("payload", JSONObject(payload))
                .put("deleted", false).put("version", 1).put("sequence", sequence).put("updatedAt", 1)
            records["$kind:$id"] = json
            return RemoteRecord(kind, id, payload, false, 1, sequence, 1)
        }

        override suspend fun request(method: String, path: String, token: String, body: JSONObject?): SyncResponse {
            if (method == "POST") {
                val request = requireNotNull(body)
                val operationId = request.getString("operationId")
                operations[operationId]?.let { return SyncResponse(200, JSONObject(it.toString())) }
                val key = request.getString("kind") + ":" + request.getString("id")
                val old = records[key]
                if (old != null && request.getLong("baseVersion") != old.getLong("version")) {
                    return SyncResponse(409, JSONObject().put("current", JSONObject(old.toString())))
                }
                sequence++
                val next = JSONObject().put("kind", request.getString("kind")).put("id", request.getString("id"))
                    .put("payload", request.getJSONObject("payload")).put("deleted", request.getBoolean("deleted"))
                    .put("version", (old?.getLong("version") ?: 0L) + 1).put("sequence", sequence).put("updatedAt", 1)
                records[key] = next
                operations[operationId] = JSONObject(next.toString())
                return SyncResponse(200, JSONObject(next.toString()))
            }
            val after = path.substringAfter("after=").substringBefore('&').toLong()
            val next = records.values.filter { it.getLong("sequence") > after }.sortedBy { it.getLong("sequence") }
            val array = JSONArray().apply { next.forEach { put(JSONObject(it.toString())) } }
            return SyncResponse(200, JSONObject().put("records", array).put("nextCursor", next.lastOrNull()?.getLong("sequence") ?: after))
        }
    }

    @Test fun sameOccurrencesCreatedOfflineOnTwoDevicesDoNotDuplicateOrConflict() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<ManmanApplication>()
        val first = Room.inMemoryDatabaseBuilder(app, ManmanDatabase::class.java).build()
        val second = Room.inMemoryDatabaseBuilder(app, ManmanDatabase::class.java).build()
        try {
            val owner = "sync-routine-${UUID.randomUUID()}"
            val now = Instant.now()
            val day = now.atZone(AppTime.zone).toLocalDate()
            val times = mapOf(day.plusDays(1).dayOfWeek.value to RoutineTime(LocalTime.of(8, 0), LocalTime.of(8, 40)),
                day.plusDays(2).dayOfWeek.value to RoutineTime(LocalTime.of(9, 0), LocalTime.of(9, 45)))
            val rule = RoutineRule("same-rule", now, listOf(RoutineVersion(day, "固定安排", (1..7).toSet(), weekdayTimes = times)))
            val server = Server()
            val remote = server.seed(RecordKind.SETTINGS, RecordCodec.routineRecordId(rule.id), RecordCodec.routine(rule))
            first.records().applyRemote(owner, listOf(remote), remote.sequence)
            second.records().applyRemote(owner, listOf(remote), remote.sequence)
            ClassRepository(first).refreshAgenda(owner, now.plusSeconds(1))
            ClassRepository(second).refreshAgenda(owner, now.plusSeconds(1))
            assertEquals(SyncResult.SUCCESS, SyncEngine(server, first.records(), Session(owner)).sync(owner))
            assertEquals(SyncResult.SUCCESS, SyncEngine(server, second.records(), Session(owner)).sync(owner))
            val firstTasks = first.records().snapshot(owner).filter { it.kind == RecordKind.TASK && !it.deleted }
            val secondRecords = second.records().snapshot(owner)
            val secondTasks = secondRecords.filter { it.kind == RecordKind.TASK && !it.deleted }
            assertEquals(8, firstTasks.size)
            assertEquals(firstTasks.map { it.id }.toSet(), secondTasks.map { it.id }.toSet())
            val tomorrow = secondTasks.map(RecordCodec::task).single { it.dueDate == day.plusDays(1) }
            assertEquals(day.plusDays(1).atTime(8, 0).atZone(AppTime.zone).toInstant(), tomorrow.dueAt)
            assertTrue(secondRecords.none { it.conflictVersion != null })
            assertFalse(secondRecords.any { it.dirty })
        } finally { first.close(); second.close() }
    }

    @Test fun manualOrderOfTimedTasksSynchronizesToAnotherDevice() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<ManmanApplication>()
        val first = Room.inMemoryDatabaseBuilder(app, ManmanDatabase::class.java).build()
        val second = Room.inMemoryDatabaseBuilder(app, ManmanDatabase::class.java).build()
        try {
            val owner = "sync-order-${UUID.randomUUID()}"
            val now = Instant.now()
            val day = now.atZone(AppTime.zone).toLocalDate().plusDays(1)
            val early = day.atTime(8, 0).atZone(AppTime.zone).toInstant()
            val late = day.atTime(9, 0).atZone(AppTime.zone).toInstant()
            val repository = ClassRepository(first)
            repository.saveTask(owner, WorkTask("early", "较早安排", null, early, null, false, now, day, endAt = early.plusSeconds(1800)))
            repository.saveTask(owner, WorkTask("late", "较晚安排", null, late, null, false, now, day, endAt = late.plusSeconds(1800)))
            val server = Server()
            val firstSync = SyncEngine(server, first.records(), Session(owner))
            val secondSync = SyncEngine(server, second.records(), Session(owner))
            assertEquals(SyncResult.SUCCESS, firstSync.sync(owner))
            assertEquals(SyncResult.SUCCESS, secondSync.sync(owner))
            repository.reorderTasks(owner, day, listOf("late", "early"))
            assertEquals(SyncResult.SUCCESS, firstSync.sync(owner))
            assertEquals(SyncResult.SUCCESS, secondSync.sync(owner))
            val tasks = second.records().snapshot(owner).filter { it.kind == RecordKind.TASK && !it.deleted }.map(RecordCodec::task)
            assertEquals(listOf("late", "early"), TaskOrdering.sorted(tasks).map { it.id })
            assertTrue(second.records().snapshot(owner).none { it.conflictVersion != null })
        } finally { first.close(); second.close() }
    }
}
