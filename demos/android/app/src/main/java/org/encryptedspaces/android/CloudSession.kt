package org.encryptedspaces.android

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.util.AtomicFile
import android.util.Base64
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.KeyStore

/** Serializes SDK operations and credential persistence across the activity and provider. */
internal object CloudSession {
    val lock = Any()
    private var connected = false
    private var sessionCertificates: JSONArray? = null
    val authority get() = "${BuildConfig.APPLICATION_ID}.documents"

    fun file(context: Context) = AtomicFile(File(context.noBackupFilesDir, "invited-connection.json"))

    fun saved(context: Context): JSONObject? = synchronized(lock) {
        val file = file(context)
        if (!file.baseFile.exists()) null
        else JSONObject(file.openRead().use { it.reader().readText() })
    }

    fun scope(connection: JSONObject): String {
        val identity = JSONArray()
            .put(connection.getJSONObject("space_snapshot").get("space_id"))
            .put(connection.getJSONObject("user_info").getLong("user_id"))
        return Base64.encodeToString(identity.toString().toByteArray(Charsets.UTF_8),
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    private fun native(request: JSONObject): Any {
        val response = JSONObject(NativeBridge.request(request.toString()))
        if (!response.getBoolean("ok")) {
            val transportFailure = response.optBoolean("transport_failure")
            if (transportFailure) connected = false
            throw NativeRequestException(response.getString("error"), transportFailure)
        }
        return response.get("data")
    }

    internal class NativeRequestException(message: String, val transportFailure: Boolean) :
        IllegalStateException(message)

    internal fun <T> recoverRead(command: String, operation: () -> T, reconnect: () -> Unit): T {
        try {
            return operation()
        } catch (original: NativeRequestException) {
            if (!original.transportFailure || command !in setOf("list", "read", "stat")) throw original
            logException("SpacesDocuments", "$command transport failed; reconnecting once", original)
            try {
                reconnect()
                Log.i("SpacesDocuments", "$command reconnected; retrying read once")
                return operation()
            } catch (error: Exception) {
                if (error !== original) error.addSuppressed(original)
                throw error
            }
        }
    }

    private fun save(context: Context, snapshot: JSONObject) {
        val file = file(context)
        val output = file.startWrite()
        try {
            output.write(snapshot.toString().toByteArray(Charsets.UTF_8))
            file.finishWrite(output)
        } catch (error: Exception) {
            file.failWrite(output)
            throw error
        }
    }

    fun request(context: Context, request: JSONObject): Any = synchronized(lock) {
        val command = request.getString("command")
        if (command == "join" || command == "restore" || command == "disconnect") {
            connected = false
            if (command == "join" || command == "disconnect") {
                sessionCertificates = null
                context.revokeUriPermission(Uri.parse("content://$authority"),
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                file(context).delete()
            }
            notifyRoots(context)
        }
        val result = recoverRead(command, { native(request) }) {
            val connection = saved(context) ?: error(context.getString(R.string.provider_sign_in))
            restoreConnection(context, connection)
        }
        if (command == "join" || command == "restore") {
            sessionCertificates = JSONArray(request.getJSONArray("certificates").toString())
        }
        if (command == "disconnect") {
            file(context).delete()
        } else {
            val snapshot = if (command in setOf("join", "restore", "snapshot", "set_user_name"))
                result as JSONObject else native(JSONObject().put("command", "snapshot")) as JSONObject
            // In particular, save rotated invitation keys before any further operation.
            save(context, snapshot)
            connected = true
        }
        if (command in setOf("join", "restore", "disconnect", "set_user_name")) notifyRoots(context)
        result
    }

    fun <T> withConnection(context: Context, expectedScope: String, action: () -> T): T =
        synchronized(lock) {
            val connection = saved(context) ?: error(context.getString(R.string.provider_sign_in))
            check(scope(connection) == expectedScope) {
                context.getString(R.string.provider_wrong_account)
            }
            if (!connected) {
                restoreConnection(context, connection)
            }
            action()
        }

    private fun restoreConnection(context: Context, connection: JSONObject) {
        val certificates = sessionCertificates ?: run {
            val store = KeyStore.getInstance("AndroidCAStore").apply { load(null) }
            JSONArray().apply {
                for (alias in store.aliases()) {
                    put(Base64.encodeToString(store.getCertificate(alias).encoded, Base64.NO_WRAP))
                }
            }
        }
        request(context, JSONObject().put("command", "restore")
            .put("connection", connection)
            .put("address", connection.getJSONObject("user_info").getString("ws_address"))
            .put("certificates", certificates))
    }

    fun notifyRoots(context: Context) {
        context.contentResolver.notifyChange(DocumentsContract.buildRootsUri(authority), null)
    }
}
