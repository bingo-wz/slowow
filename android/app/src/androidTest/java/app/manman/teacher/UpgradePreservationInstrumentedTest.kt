package app.manman.teacher

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.manman.teacher.data.RecordCodec
import app.manman.teacher.data.RecordKind
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class UpgradePreservationInstrumentedTest {
    @Test fun oldRecordsAndLoginSurviveUpgrade() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("upgradeFixture") == "1")
        val app = ApplicationProvider.getApplicationContext<ManmanApplication>()
        app.auth.restore()
        val account = requireNotNull(app.auth.session.value?.accountId)
        require(account.startsWith("release-fixture-"))
        val records = app.database.records().snapshot(account)
        val students = records.filter { it.kind == RecordKind.STUDENT && !it.deleted }.map(RecordCodec::student)
        assertEquals(52, students.size)
        assertTrue(students.all { it.normallyEatsAtSchool })
        assertTrue(students.all { it.schoolLunchDays == null })
        assertNull(students.first().sortInitial)
        assertTrue(records.count { it.dirty } >= 52)
    }
}
