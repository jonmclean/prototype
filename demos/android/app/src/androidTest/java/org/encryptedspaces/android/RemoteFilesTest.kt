package org.encryptedspaces.android

import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeNotNull
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.KeyStore
import java.security.cert.CertificateFactory

@RunWith(AndroidJUnit4::class)
class RemoteFilesTest {
    @Test fun remoteListingAndInMemoryReadDoNotCreateLocalFiles() {
        val arguments = InstrumentationRegistry.getArguments()
        val snapshotPath = arguments.getString("remoteSnapshot")
        // Report skipped unless the host supplied a real fixture deployment.
        assumeNotNull(snapshotPath)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val connection = JSONObject(File(checkNotNull(snapshotPath)).readText())
        val certificates = JSONArray()
        val store = KeyStore.getInstance("AndroidCAStore").apply { load(null) }
        for (alias in store.aliases()) {
            certificates.put(Base64.encodeToString(store.getCertificate(alias).encoded, Base64.NO_WRAP))
        }
        arguments.getString("remoteTrustCert")?.let { path ->
            File(path).inputStream().use { stream ->
                CertificateFactory.getInstance("X.509").generateCertificates(stream).forEach {
                    certificates.put(Base64.encodeToString(it.encoded, Base64.NO_WRAP))
                }
            }
        }
        fun request(json: JSONObject): Any {
            val result = JSONObject(NativeBridge.request(json.toString()))
            assertTrue(result.optString("error"), result.getBoolean("ok"))
            return result.get("data")
        }
        val before = context.dataDir.walkTopDown().filter { it.isFile }
            .map { it.relativeTo(context.dataDir).path }.toSet()
        try {
            request(JSONObject().put("command", "connect").put("connection", connection)
                .put("address", arguments.getString("remoteAddress", "ws://10.0.2.2:18082/ws"))
                .put("certificates", certificates))
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
            val after = context.dataDir.walkTopDown().filter { it.isFile }
                .map { it.relativeTo(context.dataDir).path }.toSet()
            assertEquals(before, after)
        } finally {
            request(JSONObject().put("command", "disconnect"))
        }
    }
}
