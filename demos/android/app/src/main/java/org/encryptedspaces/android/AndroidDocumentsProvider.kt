package org.encryptedspaces.android

import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.database.MatrixCursor
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.os.ProxyFileDescriptorCallback
import android.os.SystemClock
import android.os.storage.StorageManager
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import android.system.ErrnoException
import android.system.OsConstants
import android.util.Base64
import android.util.Log
import android.webkit.MimeTypeMap
import org.json.JSONArray
import org.json.JSONObject
import java.io.FileNotFoundException
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

class AndroidDocumentsProvider : DocumentsProvider() {
    private val app get() = checkNotNull(context)

    override fun onCreate(): Boolean {
        Log.i(TAG, "onCreate provider ready")
        return true
    }

    override fun queryRoots(projection: Array<out String>?): Cursor = checked("queryRoots") { trace ->
        val result = MatrixCursor(projection ?: ROOT_COLUMNS)
        val connection = CloudSession.saved(app)
        Log.i(TAG, "$trace savedLogin=${connection != null}")
        val scope = connection?.let { CloudSession.scope(it) }
        result.newRow().apply {
            putIfPresent(Root.COLUMN_ROOT_ID, scope ?: LOGIN)
            putIfPresent(Root.COLUMN_DOCUMENT_ID, scope?.let { "$it:0" } ?: LOGIN)
            putIfPresent(Root.COLUMN_TITLE, app.getString(R.string.space_brand))
            putIfPresent(Root.COLUMN_SUMMARY, if (connection == null)
                app.getString(R.string.provider_sign_in)
                else connection.getJSONObject("user_info").optString("user_name"))
            putIfPresent(Root.COLUMN_ICON, R.drawable.ic_folder)
            putIfPresent(Root.COLUMN_FLAGS, if (connection == null) {
                if (Build.VERSION.SDK_INT >= 29) Root.FLAG_EMPTY else 0
            }
                else Root.FLAG_SUPPORTS_CREATE or Root.FLAG_SUPPORTS_IS_CHILD)
            putIfPresent(Root.COLUMN_MIME_TYPES, "*/*")
        }
        result.setNotificationUri(app.contentResolver,
            DocumentsContract.buildRootsUri(CloudSession.authority))
        result
    }

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor = checked(
        "queryDocument document=${documentRef(documentId)}"
    ) { trace ->
        MatrixCursor(projection ?: DOCUMENT_COLUMNS).apply {
            if (documentId == LOGIN) {
                addDocument(this, LOGIN, null, trace)
                extras = android.os.Bundle().apply {
                    putString(DocumentsContract.EXTRA_INFO, app.getString(R.string.provider_sign_in))
                }
            } else {
                document(documentId) { _, id ->
                    addDocument(this, documentId, if (id == 0L) null else stat(id), trace)
                }
            }
            setNotificationUri(app.contentResolver,
                DocumentsContract.buildDocumentUri(CloudSession.authority, documentId))
        }
    }

    override fun queryChildDocuments(
        parentDocumentId: String, projection: Array<out String>?, sortOrder: String?
    ): Cursor = checked("queryChildDocuments parent=${documentRef(parentDocumentId)}") { trace ->
        if (parentDocumentId == LOGIN) {
            return@checked MatrixCursor(projection ?: DOCUMENT_COLUMNS).apply {
                extras = android.os.Bundle().apply {
                    putString(DocumentsContract.EXTRA_INFO, app.getString(R.string.provider_sign_in))
                }
            }
        }
        document(parentDocumentId) { scope, id ->
            val entries = request("list").put("parent_id", id).execute() as JSONArray
            MatrixCursor(projection ?: DOCUMENT_COLUMNS).apply {
                for (index in 0 until entries.length()) {
                    val entry = entries.getJSONObject(index)
                    addDocument(this, "$scope:${entry.getLong("id")}", entry, trace)
                }
                setNotificationUri(app.contentResolver,
                    DocumentsContract.buildChildDocumentsUri(CloudSession.authority, parentDocumentId))
            }
        }
    }

    override fun createDocument(parentDocumentId: String, mimeType: String, displayName: String): String =
        checked("createDocument parent=${documentRef(parentDocumentId)} mimeType=${JSONObject.quote(mimeType)}") {
            document(parentDocumentId) { scope, parent ->
                val entry = request("create").put("parent_id", parent)
                    .put("name", displayName).put("mime_type", mimeType).execute() as JSONObject
                changed(scope, entry)
                "$scope:${entry.getLong("id")}"
            }
        }

    override fun renameDocument(documentId: String, displayName: String): String? = checked(
        "renameDocument document=${documentRef(documentId)}"
    ) {
        document(documentId) { scope, id ->
            require(id != 0L) { "Cannot rename the storage root" }
            val entry = request("rename").put("id", id).put("name", displayName).execute() as JSONObject
            changed(scope, entry)
            // Returning an ID tells DocumentsProvider to revoke grants for the old ID.
            null
        }
    }

    override fun deleteDocument(documentId: String) = checked(
        "deleteDocument document=${documentRef(documentId)}"
    ) {
        document(documentId) { scope, id ->
            require(id != 0L) { "Cannot delete the storage root" }
            val entry = stat(id)
            try {
                request("delete").put("id", id).execute()
            } finally {
                // Recursive deletion can partially succeed before a network failure.
                changed(scope, entry)
            }
            Unit
        }
    }

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean = checked(
        "isChildDocument parent=${documentRef(parentDocumentId)} document=${documentRef(documentId)}"
    ) {
        document(parentDocumentId) { scope, parent ->
            val (childScope, child) = parseId(documentId)
            if (scope != childScope || parent == child || child == 0L) return@document false
            val seen = mutableSetOf<Long>()
            var current = child
            while (current != 0L) {
                check(seen.add(current)) { "Cycle in document tree" }
                current = stat(current).getLong("parent_id")
                if (current == parent) return@document true
            }
            false
        }
    }

    override fun getDocumentType(documentId: String): String? =
        checked("getDocumentType document=${documentRef(documentId)}") { trace ->
            super.getDocumentType(documentId).also {
                Log.i(TAG, "$trace mimeType=${JSONObject.quote(it)}")
            }
        }

    override fun openTypedDocument(
        documentId: String, mimeTypeFilter: String, opts: Bundle?, signal: CancellationSignal?
    ): AssetFileDescriptor =
        checked("openTypedDocument document=${documentRef(documentId)} filter=${JSONObject.quote(mimeTypeFilter)}") {
            super.openTypedDocument(documentId, mimeTypeFilter, opts, signal)
        }

    override fun openDocument(
        documentId: String, mode: String, signal: CancellationSignal?
    ): ParcelFileDescriptor = checked(
        "openDocument document=${documentRef(documentId)} mode=${JSONObject.quote(mode)}"
    ) { trace ->
        signal?.throwIfCanceled()
        val flags = ParcelFileDescriptor.parseMode(mode)
        val writable = flags and ParcelFileDescriptor.MODE_WRITE_ONLY != 0
        val truncate = flags and ParcelFileDescriptor.MODE_TRUNCATE != 0
        val append = flags and ParcelFileDescriptor.MODE_APPEND != 0
        document(documentId) { scope, id ->
            require(id != 0L) { "Cannot open a folder" }
            Log.i(TAG, "$trace download start")
            val read = request("read").put("id", id).execute() as JSONObject
            var entry = read.getJSONObject("inode")
            var bytes = Base64.decode(read.getString("base64"), Base64.DEFAULT)
            Log.i(TAG, "$trace download success bytes=${bytes.size} mimeType=${JSONObject.quote(mime(entry))}")
            try {
                signal?.throwIfCanceled()
                if (truncate) {
                    entry = write(id, ByteArray(0), entry.getString("file_hash"))
                    bytes.fill(0)
                    bytes = ByteArray(0)
                    changed(scope, entry)
                }
                val callback = object : ProxyFileDescriptorCallback() {
                    override fun onGetSize() = logged("$trace onGetSize") { bytes.size.toLong() }

                    override fun onRead(offset: Long, size: Int, data: ByteArray): Int = logged(
                        "$trace onRead offset=$offset requestedBytes=$size"
                    ) {
                        synchronized(CloudSession.lock) {
                            try {
                                val connection = CloudSession.saved(app)
                                if (connection == null || CloudSession.scope(connection) != scope) {
                                    bytes.fill(0)
                                    throw ErrnoException("read", OsConstants.EACCES)
                                }
                                if (offset < 0) throw ErrnoException("read", OsConstants.EINVAL)
                                if (offset >= bytes.size) return@synchronized 0
                                val count = minOf(size, bytes.size - offset.toInt())
                                bytes.copyInto(data, 0, offset.toInt(), offset.toInt() + count)
                                count
                            } catch (error: ErrnoException) {
                                throw error
                            } catch (error: Exception) {
                                bytes.fill(0)
                                throw ErrnoException("read", OsConstants.EIO, error)
                            }
                        }
                    }

                    override fun onWrite(offset: Long, size: Int, data: ByteArray): Int = logged(
                        "$trace onWrite offset=$offset requestedBytes=$size append=$append"
                    ) {
                        if (!writable) throw ErrnoException("write", OsConstants.EBADF)
                        val position = if (append) bytes.size.toLong() else offset
                        if (position < 0 || position > MAX_BYTES || size < 0 ||
                            size > MAX_BYTES - position) {
                            throw ErrnoException("write", OsConstants.EFBIG)
                        }
                        if (size == 0) return@logged 0
                        val replacement = bytes.copyOf(maxOf(bytes.size, position.toInt() + size))
                        data.copyInto(replacement, position.toInt(), 0, size)
                        try {
                            // A successful write means cloud persistence, not a deferred close-time save.
                            document(documentId) { _, currentId ->
                                entry = write(currentId, replacement, entry.getString("file_hash"))
                                changed(scope, entry)
                            }
                            bytes.fill(0)
                            bytes = replacement
                            size
                        } catch (error: Exception) {
                            replacement.fill(0)
                            throw ErrnoException("cloud write", OsConstants.EIO, error)
                        }
                    }

                    override fun onFsync() = logged("$trace onFsync") { Unit }

                    override fun onRelease() = logged("$trace onRelease") {
                        bytes.fill(0)
                        bytes = ByteArray(0)
                    }
                }
                Log.i(TAG, "$trace opening proxy descriptor flags=$flags")
                app.getSystemService(StorageManager::class.java)
                    .openProxyFileDescriptor(flags, callback, descriptorHandler)
            } catch (error: Exception) {
                bytes.fill(0)
                throw error
            }
        }
    }

    private fun write(id: Long, bytes: ByteArray, hash: String): JSONObject =
        request("write").put("id", id).put("expected_hash", hash)
            .put("base64", Base64.encodeToString(bytes, Base64.NO_WRAP)).execute() as JSONObject

    private fun stat(id: Long) = request("stat").put("id", id).execute() as JSONObject
    private fun request(command: String) = JSONObject().put("command", command)
    private fun JSONObject.execute() = CloudSession.request(app, this)

    private fun <T> document(documentId: String, action: (String, Long) -> T): T {
        val (scope, id) = parseId(documentId)
        Log.i(TAG, "connection document=${documentRef(documentId)} checking saved login / restoring if needed")
        return CloudSession.withConnection(app, scope) {
            Log.i(TAG, "connection document=${documentRef(documentId)} ready")
            action(scope, id)
        }
    }

    private fun parseId(documentId: String): Pair<String, Long> {
        val parts = documentId.split(':')
        require(parts.size == 2 && parts[0].isNotEmpty()) { app.getString(R.string.provider_sign_in) }
        val id = parts[1].toLongOrNull()
        require(id != null && id >= 0 && id.toString() == parts[1]) { "Invalid document id" }
        return parts[0] to id
    }

    private fun addDocument(cursor: MatrixCursor, documentId: String, entry: JSONObject?, trace: String) {
        val folder = entry == null || entry.getInt("type") == 2
        val size = entry?.getLong("size") ?: 0L
        val flags = if (entry == null) {
            if (documentId == LOGIN) 0 else Document.FLAG_DIR_SUPPORTS_CREATE
        } else {
            Document.FLAG_SUPPORTS_DELETE or Document.FLAG_SUPPORTS_RENAME or
                if (folder) Document.FLAG_DIR_SUPPORTS_CREATE
                else if (size in 0..MAX_BYTES.toLong()) Document.FLAG_SUPPORTS_WRITE else 0
        }
        val mimeType = if (folder) Document.MIME_TYPE_DIR else mime(entry!!)
        Log.i(TAG, "$trace metadata document=${documentRef(documentId)} " +
            "mimeType=${JSONObject.quote(mimeType)} size=$size flags=$flags")
        cursor.newRow().apply {
            putIfPresent(Document.COLUMN_DOCUMENT_ID, documentId)
            putIfPresent(Document.COLUMN_DISPLAY_NAME, entry?.getString("name")
                ?: app.getString(R.string.space_brand))
            putIfPresent(Document.COLUMN_MIME_TYPE, mimeType)
            putIfPresent(Document.COLUMN_FLAGS, flags)
            putIfPresent(Document.COLUMN_SIZE, if (folder) null else size)
            putIfPresent(Document.COLUMN_LAST_MODIFIED, entry?.getLong("mtime")?.times(1000))
        }
    }

    private fun mime(entry: JSONObject): String {
        val supplied = entry.optString("mime_type").substringBefore(';').trim()
        return supplied.takeIf { it.isNotEmpty() && it != "application/octet-stream" }
            ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(
                entry.getString("name").substringAfterLast('.', "").lowercase(Locale.ROOT))
            ?: "application/octet-stream"
    }

    private fun MatrixCursor.RowBuilder.putIfPresent(column: String, value: Any?) {
        // RowBuilder.add(name, value) ignores columns not included in the projection.
        add(column, value)
    }

    private fun changed(scope: String, entry: JSONObject) {
        app.contentResolver.notifyChange(DocumentsContract.buildDocumentUri(
            CloudSession.authority, "$scope:${entry.getLong("id")}"), null)
        app.contentResolver.notifyChange(DocumentsContract.buildChildDocumentsUri(
            CloudSession.authority, "$scope:${entry.getLong("parent_id")}"), null)
    }

    private fun documentRef(documentId: String): String =
        if (documentId == LOGIN) LOGIN
        else documentId.substringAfterLast(':', "").toLongOrNull()?.let { "inode:$it" } ?: "invalid"

    private fun <T> checked(operation: String, action: (String) -> T): T = try {
        val trace = "request=${nextRequest.incrementAndGet()} $operation callerUid=${Binder.getCallingUid()}"
        logged(trace) { action(trace) }
    } catch (error: android.os.OperationCanceledException) {
        throw error
    } catch (error: Exception) {
        throw FileNotFoundException(error.message ?: error.javaClass.simpleName).apply { initCause(error) }
    }

    private fun <T> logged(operation: String, action: () -> T): T {
        val started = SystemClock.elapsedRealtime()
        Log.i(TAG, "$operation start")
        return try {
            action().also { result ->
                val detail = when (result) {
                    is Cursor -> " rows=${result.count}"
                    is ParcelFileDescriptor -> " fd=${result.fd}"
                    is Int, is Long, is Boolean -> " result=$result"
                    else -> ""
                }
                Log.i(TAG, "$operation success elapsedMs=${SystemClock.elapsedRealtime() - started}$detail")
            }
        } catch (error: android.os.OperationCanceledException) {
            Log.i(TAG, "$operation cancelled elapsedMs=${SystemClock.elapsedRealtime() - started}")
            throw error
        } catch (error: Exception) {
            logException(TAG, "$operation failed elapsedMs=${SystemClock.elapsedRealtime() - started}", error)
            throw error
        }
    }

    companion object {
        private const val TAG = "SpacesDocuments"
        private val nextRequest = AtomicLong()
        private const val LOGIN = "login"
        private const val MAX_BYTES = 32 * 1024 * 1024
        private val ROOT_COLUMNS = arrayOf(Root.COLUMN_ROOT_ID, Root.COLUMN_DOCUMENT_ID,
            Root.COLUMN_TITLE, Root.COLUMN_SUMMARY, Root.COLUMN_ICON, Root.COLUMN_FLAGS,
            Root.COLUMN_MIME_TYPES)
        private val DOCUMENT_COLUMNS = arrayOf(Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE, Document.COLUMN_FLAGS,
            Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED)
        private val descriptorHandler by lazy {
            Handler(HandlerThread("CloudDocuments").apply { start() }.looper)
        }
    }
}
