package org.encryptedspaces.android

import android.os.CancellationSignal
import android.os.OperationCanceledException
import android.os.ParcelFileDescriptor
import android.os.Process
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Root
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.FileNotFoundException

@RunWith(AndroidJUnit4::class)
class DocumentsProviderTest {
    @Test fun logsQueriesOpenFailuresAndCancellationWithoutAccountScopes() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val privateScope = "private-account-${System.nanoTime()}"
        val inode = -(System.nanoTime() % 1_000_000_000 + 1)
        val documentId = "$privateScope:$inode"
        context.contentResolver.acquireContentProviderClient("${context.packageName}.documents")!!.use { client ->
            val provider = client.localContentProvider as AndroidDocumentsProvider
            provider.queryDocument("login", null).use { assertEquals(1, it.count) }
            assertEquals(DocumentsContract.Document.MIME_TYPE_DIR, provider.getDocumentType("login"))
            try {
                provider.openDocument(documentId, "r", null).close()
                fail("Invalid document ID was accepted")
            } catch (_: FileNotFoundException) {
            }
            try {
                provider.openTypedDocument(documentId, "*/*", null, null).close()
                fail("Invalid typed document ID was accepted")
            } catch (_: FileNotFoundException) {
            }
            try {
                provider.openDocument(documentId, "r", CancellationSignal().apply { cancel() }).close()
                fail("Cancelled open was accepted")
            } catch (_: OperationCanceledException) {
            }
        }
        val output = instrumentation.uiAutomation.executeShellCommand(
            "logcat -d -t 2000 --pid=${Process.myPid()} -s SpacesDocuments:I '*:S'"
        )
        val logs = ParcelFileDescriptor.AutoCloseInputStream(output).bufferedReader().use { it.readText() }
        val open = """request=\d+ openDocument document=inode:$inode mode="r" callerUid=\d+"""
        assertTrue(logs, Regex("$open start").containsMatchIn(logs))
        assertTrue(logs, Regex("$open failed elapsedMs=\\d+").containsMatchIn(logs))
        assertTrue(logs, Regex("$open cancelled elapsedMs=\\d+").containsMatchIn(logs))
        assertTrue(logs, logs.contains("java.lang.IllegalArgumentException: Invalid document id"))
        assertTrue(logs, logs.contains("openTypedDocument document=inode:$inode"))
        assertTrue(logs, Regex("queryDocument document=login callerUid=\\d+ success elapsedMs=\\d+ rows=1")
            .containsMatchIn(logs))
        assertTrue(logs, logs.contains("metadata document=login mimeType=" +
            JSONObject.quote(DocumentsContract.Document.MIME_TYPE_DIR)))
        assertFalse("Account scope leaked into provider logs", logs.contains(privateScope))
    }

    @Test fun signedOutProviderDirectsUsersToTheExistingApplication() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        CloudSession.request(context, JSONObject().put("command", "disconnect"))
        val resolver = context.contentResolver
        val authority = "${context.packageName}.documents"
        val login = resolver.query(DocumentsContract.buildRootsUri(authority),
            arrayOf(Root.COLUMN_DOCUMENT_ID, Root.COLUMN_SUMMARY), null, null, null)!!.use {
            assertEquals(1, it.count)
            assertTrue(it.moveToFirst())
            assertEquals(context.getString(R.string.provider_sign_in), it.getString(1))
            it.getString(0)
        }
        resolver.query(DocumentsContract.buildChildDocumentsUri(authority, login),
            null, null, null, null)!!.use {
            assertEquals(0, it.count)
            assertEquals(context.getString(R.string.provider_sign_in),
                it.extras.getString(DocumentsContract.EXTRA_INFO))
        }
        resolver.acquireContentProviderClient(authority)!!.use { client ->
            val provider = client.localContentProvider as AndroidDocumentsProvider
            for (id in listOf("another-account:1", "invalid", "scope:-1", "scope:01")) {
                try {
                    provider.queryDocument(id, null).close()
                    fail("Provider accepted a document without a signed-in account: $id")
                } catch (_: FileNotFoundException) {
                }
            }
        }
    }
}
