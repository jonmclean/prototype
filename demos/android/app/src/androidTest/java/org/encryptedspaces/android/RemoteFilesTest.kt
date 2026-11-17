package org.encryptedspaces.android

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.system.Os
import android.system.ErrnoException
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
            synchronized(CloudSession.lock) {
                // Leave Kotlin's initialized flag stale while losing the native connection.
                for (command in listOf("list", "stat", "read")) {
                    request(JSONObject().put("command", "disconnect"))
                    val recovered = CloudSession.request(context, JSONObject()
                        .put("command", command).put("parent_id", folder)
                        .put("id", nested.getJSONObject(0).getLong("id")))
                    if (command == "read") {
                        assertEquals("Android in-memory read: hello encrypted spaces!\n",
                            Base64.decode((recovered as JSONObject).getString("base64"), Base64.DEFAULT)
                                .toString(Charsets.UTF_8))
                    } else if (command == "list") {
                        assertEquals(1, (recovered as JSONArray).length())
                    } else {
                        assertEquals(nested.getJSONObject(0).getLong("id"),
                            (recovered as JSONObject).getLong("id"))
                    }
                }
            }
            if (arguments.getString("remoteTrustCert") == null) {
                // Model a fresh process: no native client, but the app-private login remains.
                synchronized(CloudSession.lock) {
                    request(JSONObject().put("command", "disconnect"))
                    CloudSession::class.java.getDeclaredField("connected").apply {
                        isAccessible = true
                    }.setBoolean(null, false)
                }
            }
            checkDocumentsProvider(context)
        } finally {
            request(JSONObject().put("command", "disconnect"))
            instrumentation.runOnMainSync { activity.finish() }
        }
    }

    private fun checkDocumentsProvider(context: Context) {
        val resolver = context.contentResolver
        val before = context.dataDir.walkTopDown().filter { it.isFile }
            .map { it.relativeTo(context.dataDir).path }.toSet()
        val authority = "${context.packageName}.documents"
        val rootId = resolver.query(DocumentsContract.buildRootsUri(authority),
            arrayOf(Root.COLUMN_DOCUMENT_ID), null, null, null)!!.use {
            assertTrue(it.moveToFirst())
            it.getString(0)
        }
        val root = DocumentsContract.buildDocumentUri(authority, rootId)
        val folder = checkNotNull(DocumentsContract.createDocument(
            resolver, root, Document.MIME_TYPE_DIR, "Provider edits"))
        var forgotten = false
        var folderDeleted = false
        try {
            val file = checkNotNull(DocumentsContract.createDocument(
                resolver, folder, "text/plain", "draft.txt"))
            assertTrue(DocumentsContract.isChildDocument(resolver, root, file))
            assertTrue(DocumentsContract.isChildDocument(resolver, folder, file))
            assertFalse(DocumentsContract.isChildDocument(resolver, file, folder))
            val original = "Cloud document text".toByteArray()
            resolver.openFileDescriptor(file, "rwt")!!.use { descriptor ->
                assertEquals(original.size, Os.pwrite(descriptor.fileDescriptor,
                    original, 0, original.size, 0))
                val edit = "edit".toByteArray()
                assertEquals(edit.size, Os.pwrite(descriptor.fileDescriptor, edit, 0, edit.size, 6))
                Os.fsync(descriptor.fileDescriptor)
                val contents = ByteArray(original.size)
                assertEquals(contents.size, Os.pread(descriptor.fileDescriptor,
                    contents, 0, contents.size, 0))
                assertEquals("Cloud editment text", contents.toString(Charsets.UTF_8))
            }
            resolver.openInputStream(file)!!.use {
                assertEquals("Cloud editment text", it.readBytes().toString(Charsets.UTF_8))
            }
            resolver.acquireContentProviderClient(authority)!!.use { client ->
                val provider = client.localContentProvider as AndroidDocumentsProvider
                assertNull("A stable-ID rename must not revoke existing URI grants",
                    provider.renameDocument(DocumentsContract.getDocumentId(file), "intermediate.txt"))
                try {
                    provider.queryDocument("different-member:1", null).close()
                    fail("Provider accepted a document from another account")
                } catch (_: java.io.FileNotFoundException) {
                }
            }
            val renamed = checkNotNull(DocumentsContract.renameDocument(resolver, file, "saved.txt"))
            assertEquals(file, renamed)
            resolver.query(file, arrayOf(Document.COLUMN_SIZE, Document.COLUMN_DISPLAY_NAME),
                null, null, null)!!.use {
                assertTrue(it.moveToFirst())
                assertEquals(original.size.toLong(), it.getLong(0))
                assertEquals("saved.txt", it.getString(1))
            }
            resolver.openFileDescriptor(file, "wa")!!.use { descriptor ->
                val suffix = "!".toByteArray()
                assertEquals(1, Os.write(descriptor.fileDescriptor, suffix, 0, 1))
            }
            resolver.openInputStream(file)!!.use {
                assertEquals("Cloud editment text!", it.readBytes().toString(Charsets.UTF_8))
            }
            resolver.openFileDescriptor(file, "rw")!!.use { first ->
                resolver.openFileDescriptor(file, "rw")!!.use { stale ->
                    val edit = "X".toByteArray()
                    assertEquals(1, Os.pwrite(first.fileDescriptor, edit, 0, 1, 0))
                    try {
                        Os.pwrite(stale.fileDescriptor, edit, 0, 1, 0)
                        fail("Stale descriptor overwrote a newer file")
                    } catch (_: ErrnoException) {
                        // Failed cloud writes must be returned to the calling editor.
                    }
                    try {
                        Os.pwrite(first.fileDescriptor, edit, 0, 1, 32L * 1024 * 1024)
                        fail("Write exceeded the 32 MiB limit")
                    } catch (_: ErrnoException) {
                    }
                }
            }
            resolver.openFileDescriptor(file, "wt")!!.close()
            resolver.openInputStream(file)!!.use { assertEquals(0, it.readBytes().size) }
            assertTrue(DocumentsContract.deleteDocument(resolver, folder))
            folderDeleted = true
            resolver.query(DocumentsContract.buildChildDocumentsUri(authority, rootId),
                arrayOf(Document.COLUMN_DISPLAY_NAME), null, null, null)!!.use {
                while (it.moveToNext()) assertNotEquals("Provider edits", it.getString(0))
            }
            // Forget must invalidate already-open descriptors as well as future URI access.
            val temporary = checkNotNull(DocumentsContract.createDocument(
                resolver, root, "text/plain", "Forget descriptor.txt"))
            resolver.openFileDescriptor(temporary, "rw")!!.use { descriptor ->
                val content = "private".toByteArray()
                Os.pwrite(descriptor.fileDescriptor, content, 0, content.size, 0)
                val after = context.dataDir.walkTopDown().filter { it.isFile }
                    .map { it.relativeTo(context.dataDir).path }.toSet()
                assertEquals("Provider created a local plaintext staging/cache file", before, after)
                assertFalse(CloudSession.file(context).openRead().use {
                    it.reader().readText().contains("Cloud document text")
                })
                assertTrue(DocumentsContract.deleteDocument(resolver, temporary))
                CloudSession.request(context, JSONObject().put("command", "disconnect"))
                forgotten = true
                try {
                    Os.pread(descriptor.fileDescriptor, ByteArray(content.size), 0, content.size, 0)
                    fail("Forgotten account remained readable")
                } catch (_: ErrnoException) {
                }
            }
        } finally {
            if (!forgotten && !folderDeleted) {
                // Cleanup is intentionally visible if the underlying operation fails.
                DocumentsContract.deleteDocument(resolver, folder)
            }
        }
    }
}
