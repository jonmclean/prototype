package org.encryptedspaces.android

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.util.Base64
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.view.View
import android.view.ViewGroup
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeNotNull
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class RemoteFilesTest {
    @Test fun remoteListingAndInMemoryReadDoNotCreateLocalFiles() {
        val arguments = InstrumentationRegistry.getArguments()
        val invitationPath = arguments.getString("remoteInvitation")
        // Report skipped unless the host supplied a real fixture deployment.
        assumeNotNull(invitationPath)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        if (Build.VERSION.SDK_INT >= 37) {
            InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(
                context.packageName, Manifest.permission.ACCESS_LOCAL_NETWORK
            )
        }
        val invitation = JSONObject(File(checkNotNull(invitationPath)).readText())
        val activity = instrumentation.startActivitySync(
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ) as MainActivity
        fun field(name: String): Any = MainActivity::class.java.getDeclaredField(name).apply {
            isAccessible = true
        }.get(activity)!!
        val documentResult = MainActivity::class.java.getDeclaredMethod(
            "onActivityResult", Integer.TYPE, Integer.TYPE, Intent::class.java
        ).apply { isAccessible = true }
        fun importDocument(code: Int, path: String) {
            documentResult.invoke(activity, code, Activity.RESULT_OK,
                Intent().setData(Uri.fromFile(File(path))))
        }
        fun confirmUserName() {
            val dialog = field("userNameDialog") as AlertDialog
            fun input(view: View): EditText? = when (view) {
                is EditText -> view
                is ViewGroup -> (0 until view.childCount).firstNotNullOfOrNull {
                    input(view.getChildAt(it))
                }
                else -> null
            }
            checkNotNull(input(dialog.window!!.decorView)).setText("Android integration test")
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        }
        fun awaitConnection() {
            val deadline = SystemClock.elapsedRealtime() + 65_000
            while (SystemClock.elapsedRealtime() < deadline) {
                var busy = true
                instrumentation.runOnMainSync { busy = field("busy") as Boolean }
                if (!busy) {
                    instrumentation.runOnMainSync {
                        assertTrue((field("status") as TextView).text.toString(), field("connected") as Boolean)
                    }
                    return
                }
                Thread.sleep(50)
            }
            fail("Invitation join/reconnect did not finish")
        }
        fun request(json: JSONObject): Any {
            val result = JSONObject(NativeBridge.request(json.toString()))
            assertTrue(result.optString("error"), result.getBoolean("ok"))
            return result.get("data")
        }
        try {
            val address = arguments.getString("remoteAddress", "ws://10.0.2.2:18082/ws")
            instrumentation.runOnMainSync {
                (field("address") as EditText).setText(address)
                arguments.getString("remoteTrustCert")?.let { path ->
                    importDocument(2, path)
                }
            }
            instrumentation.waitForIdleSync()
            // Certificate import is an asynchronous local operation.
            val trustDeadline = SystemClock.elapsedRealtime() + 10_000
            while (SystemClock.elapsedRealtime() < trustDeadline) {
                var busy = true
                instrumentation.runOnMainSync { busy = field("busy") as Boolean }
                if (!busy) break
                Thread.sleep(50)
            }
            instrumentation.runOnMainSync {
                importDocument(1, checkNotNull(invitationPath))
                confirmUserName()
            }
            awaitConnection()
            val saved = File(context.noBackupFilesDir, "invited-connection.json")
            assertTrue(saved.isFile)
            val connection = JSONObject(saved.readText())
            assertEquals("Android integration test",
                connection.getJSONObject("user_info").getString("user_name"))
            assertEquals(
                invitation.getJSONObject("user").getLong("id"),
                connection.getJSONObject("user_info").getLong("user_id")
            )
            val before = context.dataDir.walkTopDown().filter { it.isFile }
                .map { it.relativeTo(context.dataDir).path }.toSet()
            val root = request(JSONObject().put("command", "list").put("parent_id", 0)) as JSONArray
            assertEquals(2, root.length())
            assertEquals(2, root.getJSONObject(0).getInt("type"))
            val folder = root.getJSONObject(0).getLong("id")
            val nested = request(JSONObject().put("command", "list").put("parent_id", folder)) as JSONArray
            assertEquals(1, nested.length())
            val read = request(JSONObject().put("command", "read")
                .put("id", nested.getJSONObject(0).getLong("id"))) as JSONObject
            val bytes = Base64.decode(read.getString("base64"), Base64.DEFAULT)
            assertEquals("Android in-memory read: hello encrypted spaces!\n", bytes.toString(Charsets.UTF_8))
            bytes.fill(0)
            instrumentation.runOnMainSync {
                (field("reconnect") as Button).performClick()
                confirmUserName()
            }
            awaitConnection()
            val restored = request(JSONObject().put("command", "list").put("parent_id", folder)) as JSONArray
            assertEquals(1, restored.length())
            val after = context.dataDir.walkTopDown().filter { it.isFile }
                .map { it.relativeTo(context.dataDir).path }.toSet()
            assertEquals(before, after)
            assertFalse(saved.readText().contains("Android in-memory read: hello encrypted spaces!"))
        } finally {
            request(JSONObject().put("command", "disconnect"))
            instrumentation.runOnMainSync { activity.finish() }
        }
    }
}
