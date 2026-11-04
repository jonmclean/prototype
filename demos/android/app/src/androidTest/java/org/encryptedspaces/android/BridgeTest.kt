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

    @Test fun desktopSnapshotCannotBeUsedAsAnInvitation() {
        val request = JSONObject().put("command", "join")
            .put("address", "ws://10.0.2.2:18082/ws")
            .put("certificates", org.json.JSONArray())
            .put("invitation", JSONObject()
                .put("user_info", JSONObject())
                .put("space_snapshot", JSONObject()))
        val response = JSONObject(NativeBridge.request(request.toString()))
        assertFalse(response.getBoolean("ok"))
        assertTrue(response.getString("error").contains("Desktop snapshots cannot be imported"))
    }

    @Test fun oldSnapshotImportCommandIsNotAccepted() {
        val response = JSONObject(NativeBridge.request("""{"command":"connect"}"""))
        assertFalse(response.getBoolean("ok"))
    }
}
