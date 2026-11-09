package app.manman.teacher

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.manman.teacher.sync.HttpsSyncTransport
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONObject

@RunWith(AndroidJUnit4::class)
class ServerHttpsInstrumentedTest {
    @Test fun androidTrustsServerCertificateAndRejectsAnInvalidSession() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("testServer") == "1")
        val response = HttpsSyncTransport(BuildConfig.SYNC_BASE_URL).request("GET", "/v1/records?after=0", "invalid-test-token", null)
        assertEquals(401, response.code)
    }

    @Test fun scheduleEndpointIsReachableAndRequiresLogin() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("testServer") == "1")
        val response = HttpsSyncTransport(BuildConfig.SYNC_BASE_URL).request("POST", "/v1/recognize/schedule", "invalid-test-token", JSONObject())
        assertEquals(401, response.code)
    }
}
