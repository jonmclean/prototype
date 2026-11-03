package org.encryptedspaces.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONObject

@RunWith(AndroidJUnit4::class)
class BridgeTest {
    @Test fun nativeLibraryLoadsAndReportsDisconnectedState() {
        NativeBridge.request("""{"command":"disconnect"}""")
        val response = JSONObject(NativeBridge.request(
            """{"command":"list","parent_id":0}"""))
        assertFalse(response.getBoolean("ok"))
        assertTrue(response.getString("error").contains("Connect to a space first"))
    }

    @Test fun malformedRequestReturnsAnErrorAcrossJni() {
        val response = JSONObject(NativeBridge.request(
            "not JSON"))
        assertFalse(response.getBoolean("ok"))
        assertTrue(response.getString("error").isNotBlank())
    }

    @Test fun disconnectIsIdempotent() {
        repeat(2) {
            val response = JSONObject(NativeBridge.request(
                """{"command":"disconnect"}"""))
            assertTrue(response.getBoolean("ok"))
        }
    }
}
