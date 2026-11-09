package org.encryptedspaces.android

import android.os.ParcelFileDescriptor
import android.os.Process
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
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

    @Test fun nativeFailureLogsItsCauseAndRustBacktrace() {
        val invitation = "invalid_invitation_${System.nanoTime()}"
        val response = JSONObject(NativeBridge.request(
            JSONObject().put("command", "join")
                .put("address", "wss://example.test/ws")
                .put("certificates", org.json.JSONArray())
                .put("invitation", invitation).toString()))
        assertFalse(response.getBoolean("ok"))
        val output = InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand("logcat -d -t 2000 --pid=${Process.myPid()} -s SpacesNative:I '*:S'")
        val logs = ParcelFileDescriptor.AutoCloseInputStream(output).bufferedReader().use {
            it.readText()
        }
        val failure = logs.substringAfterLast("SDK request failed:")
        assertTrue(logs.contains("WebSocket join attempt: endpoint=\"wss://example.test/ws\""))
        assertTrue(logs.contains("host=Some(\"example.test\"), port=Some(443)"))
        assertTrue(failure.contains("Invalid invitation file"))
        assertTrue(failure.contains("Caused by:"))
        assertTrue(failure.contains(invitation))
        assertTrue(failure.contains("invalid type"))
        assertTrue(failure.contains("Stack backtrace:"))
        assertTrue(Regex("0x[0-9a-f]+").containsMatchIn(failure))
        assertTrue(Regex("spaces_android(?:\\[[0-9a-f]+\\])?::parse_invitation")
            .containsMatchIn(failure))
        assertTrue(Regex("native/src/lib\\.rs:\\d+").containsMatchIn(failure))
        assertFalse(failure.contains("Error backtrace unavailable"))
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
