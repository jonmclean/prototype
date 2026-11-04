package org.encryptedspaces.android

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.graphics.BitmapFactory
import android.util.AtomicFile
import android.util.Base64
import android.view.View
import android.view.WindowManager
import android.widget.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.ByteArrayOutputStream
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private lateinit var address: EditText
    private lateinit var status: TextView
    private lateinit var breadcrumb: TextView
    private lateinit var list: ListView
    private lateinit var reconnect: Button
    private lateinit var refresh: Button
    private lateinit var up: Button
    private lateinit var forget: Button
    private val controls = mutableListOf<View>()
    private val worker = Executors.newSingleThreadExecutor()
    private val folders = mutableListOf(0L to "/")
    private var entries = emptyList<JSONObject>()
    private var connected = false
    private var busy = false
    private var extraCertificates = emptyList<String>()
    private var previewDialog: AlertDialog? = null
    private var networkPermissionDialog: AlertDialog? = null
    private var pendingNetworkAction: (() -> Unit)? = null
    private val snapshotFile get() = AtomicFile(File(noBackupFilesDir, "connection.json"))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val inset = (16 * resources.displayMetrics.density).toInt()
            setPadding(inset, inset, inset, inset)
            fitsSystemWindows = true
        }
        root.addView(TextView(this).apply {
            setText(R.string.title)
            textSize = 22f
        })
        root.addView(TextView(this).apply { setText(R.string.warning) })
        address = EditText(this).apply {
            setHint(R.string.endpoint_hint)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_URI
            isSingleLine = true
        }
        controls.add(address)
        root.addView(address)
        fun row(): LinearLayout = LinearLayout(this).also { root.addView(it) }
        fun button(row: LinearLayout, text: Int, action: () -> Unit): Button =
            Button(this).apply {
                setText(text)
                setOnClickListener { action() }
                controls.add(this)
                row.addView(this, LinearLayout.LayoutParams(0, -2, 1f))
            }
        val connectionRow = row()
        button(connectionRow, R.string.import_snapshot) { openDocument(IMPORT_SNAPSHOT) }
        reconnect = button(connectionRow, R.string.restore) {
            val endpoint = address.text.toString().trim()
            networkOperation {
                val connection = JSONObject(snapshotFile.openRead().use { it.reader().readText() })
                connect(connection, endpoint)
            }
        }
        val trustRow = row()
        button(trustRow, R.string.certificate) { openDocument(IMPORT_CERTIFICATE) }
        forget = button(trustRow, R.string.forget) {
            operation {
                native(JSONObject().put("command", "disconnect"))
                snapshotFile.delete()
                extraCertificates = emptyList()
                runOnUiThread {
                    connected = false
                    address.setText("")
                    folders.clear()
                    folders.add(0L to "/")
                    entries = emptyList()
                    renderEntries()
                    status.setText(R.string.disconnected)
                }
            }
        }
        val navigationRow = row()
        up = button(navigationRow, R.string.up) {
            networkOperation { loadFolder(folders.dropLast(1)) }
        }
        refresh = button(navigationRow, R.string.refresh) {
            networkOperation { loadFolder(folders.toList()) }
        }
        breadcrumb = TextView(this).also { root.addView(it) }
        status = TextView(this).also {
            it.setText(R.string.disconnected)
            it.setPadding(0, 8, 0, 8)
            root.addView(it)
        }
        list = ListView(this).apply {
            setOnItemClickListener { _, _, position, _ ->
                val entry = entries[position]
                networkOperation {
                    if (entry.getInt("type") == 2) {
                        loadFolder(folders + (entry.getLong("id") to entry.getString("name")))
                    } else {
                        readFile(entry)
                    }
                }
            }
        }
        controls.add(list)
        root.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        try {
            if (snapshotFile.baseFile.exists()) {
                val saved = JSONObject(snapshotFile.openRead().use { it.reader().readText() })
                address.setText(saved.getJSONObject("user_info").getString("ws_address"))
            }
        } catch (error: Exception) {
            showError(error)
        }
        renderEntries()
        updateControls()
    }

    private fun openDocument(code: Int) {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }, code)
    }

    @Deprecated("Uses platform document picker without an AndroidX dependency")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        when (requestCode) {
            IMPORT_SNAPSHOT -> {
                val endpoint = address.text.toString().trim()
                networkOperation {
                    connect(JSONObject(readDocument(uri, 16 * 1024 * 1024).toString(Charsets.UTF_8)), endpoint)
                }
            }
            IMPORT_CERTIFICATE -> operation {
                val bytes = readDocument(uri, 1024 * 1024)
                val certificates = CertificateFactory.getInstance("X.509")
                    .generateCertificates(bytes.inputStream())
                require(certificates.isNotEmpty()) { "No PEM or DER certificates found" }
                extraCertificates = certificates.map {
                    Base64.encodeToString(it.encoded, Base64.NO_WRAP)
                }
                runOnUiThread { status.setText(R.string.cert_added) }
            }
        }
    }

    private fun readDocument(uri: Uri, limit: Int): ByteArray {
        val input = contentResolver.openInputStream(uri) ?: error("Cannot open selected document")
        return input.use {
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = it.read(buffer)
                if (count < 0) break
                require(output.size() + count <= limit) { "Selected document is too large" }
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
    }

    private fun connect(connection: JSONObject, overrideAddress: String) {
        val endpoint = overrideAddress.ifEmpty {
            connection.getJSONObject("user_info").getString("ws_address")
        }
        runOnUiThread {
            connected = false
            entries = emptyList()
            renderEntries()
        }
        val keyStore = KeyStore.getInstance("AndroidCAStore").apply { load(null) }
        val certificates = JSONArray()
        for (alias in keyStore.aliases()) {
            certificates.put(Base64.encodeToString(keyStore.getCertificate(alias).encoded, Base64.NO_WRAP))
        }
        extraCertificates.forEach { certificates.put(it) }
        val snapshot = native(JSONObject()
            .put("command", "connect")
            .put("address", endpoint)
            .put("connection", connection)
            .put("certificates", certificates)) as JSONObject
        saveSnapshot(snapshot)
        runOnUiThread {
            address.setText(endpoint)
            connected = true
        }
        loadFolder(listOf(0L to "/"))
    }

    private fun loadFolder(path: List<Pair<Long, String>>) {
        val result = native(JSONObject()
            .put("command", "list")
            .put("parent_id", path.last().first)) as JSONArray
        saveSnapshot(native(JSONObject().put("command", "snapshot")) as JSONObject)
        val loaded = (0 until result.length()).map { result.getJSONObject(it) }
        runOnUiThread {
            folders.clear()
            folders.addAll(path)
            entries = loaded
            renderEntries()
            status.text = if (entries.isEmpty()) getString(R.string.empty)
                else resources.getQuantityString(R.plurals.file_count, entries.size, entries.size)
        }
    }

    private fun readFile(entry: JSONObject) {
        val result = native(JSONObject()
            .put("command", "read")
            .put("id", entry.getLong("id"))) as JSONObject
        saveSnapshot(native(JSONObject().put("command", "snapshot")) as JSONObject)
        val bytes = Base64.decode(result.getString("base64"), Base64.DEFAULT)
        runOnUiThread {
            if (isFinishing || isDestroyed) {
                bytes.fill(0)
                return@runOnUiThread
            }
            try {
                preview(entry, bytes)
                status.setText(R.string.preview)
            } catch (error: Exception) {
                bytes.fill(0)
                showError(error)
            }
        }
    }

    private fun preview(entry: JSONObject, bytes: ByteArray) {
        previewDialog?.dismiss()
        val mime = entry.getString("mime_type")
        var releaseImage: (() -> Unit)? = null
        val view: View = when {
            mime.startsWith("image/") -> {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                require(bounds.outWidth > 0 && bounds.outHeight > 0) { getString(R.string.invalid_image) }
                var sample = 1
                while (bounds.outWidth / sample > 2048 || bounds.outHeight / sample > 2048) sample *= 2
                val options = BitmapFactory.Options().apply { inSampleSize = sample }
                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
                    ?: error(getString(R.string.invalid_image))
                releaseImage = { bitmap.recycle() }
                ImageView(this).apply {
                    setImageBitmap(bitmap)
                    adjustViewBounds = true
                }
            }
            mime.startsWith("text/") || mime in setOf("application/json", "application/xml") -> {
                val limit = 256 * 1024
                val text = String(bytes, 0, minOf(bytes.size, limit), Charsets.UTF_8)
                ScrollView(this).apply {
                    addView(TextView(this@MainActivity).apply {
                        setPadding(16, 16, 16, 16)
                        this.text = if (bytes.size > limit)
                            getString(R.string.text_truncated) + "\n\n" + text else text
                        // Disable text selection: copying would persist content to the clipboard.
                        setTextIsSelectable(false)
                    })
                }
            }
            else -> TextView(this).apply {
                text = getString(R.string.binary_preview, bytes.size)
                setPadding(16, 16, 16, 16)
            }
        }
        AlertDialog.Builder(this)
            .setTitle(entry.getString("name"))
            .setView(view)
            .setPositiveButton(android.R.string.ok, null)
            .create().apply {
                previewDialog = this
                setOnDismissListener {
                    bytes.fill(0)
                    releaseImage?.invoke()
                    previewDialog = null
                }
                window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                show()
                window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            }
    }

    private fun native(request: JSONObject): Any {
        val response = JSONObject(NativeBridge.request(request.toString()))
        check(response.getBoolean("ok")) { response.getString("error") }
        return response.get("data")
    }

    private fun saveSnapshot(snapshot: JSONObject) {
        val output = snapshotFile.startWrite()
        try {
            output.write(snapshot.toString().toByteArray(Charsets.UTF_8))
            snapshotFile.finishWrite(output)
        } catch (error: Exception) {
            snapshotFile.failWrite(output)
            throw error
        }
    }

    private fun networkOperation(action: () -> Unit) {
        if (busy || pendingNetworkAction != null) return
        if (Build.VERSION.SDK_INT >= 37 &&
            checkSelfPermission(Manifest.permission.ACCESS_LOCAL_NETWORK) != PackageManager.PERMISSION_GRANTED) {
            pendingNetworkAction = action
            status.setText(R.string.local_network_request)
            updateControls()
            requestPermissions(arrayOf(Manifest.permission.ACCESS_LOCAL_NETWORK), LOCAL_NETWORK_PERMISSION)
        } else {
            operation(action)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != LOCAL_NETWORK_PERMISSION) return
        val action = pendingNetworkAction ?: return
        pendingNetworkAction = null
        updateControls()
        if (isFinishing || isDestroyed) return
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            operation(action)
        } else {
            status.setText(R.string.local_network_denied)
            val dialog = AlertDialog.Builder(this)
                .setTitle(R.string.local_network_title)
                .setMessage(R.string.local_network_denied)
                .setPositiveButton(R.string.internet_only) { _, _ ->
                    pendingNetworkAction = null
                    operation(action)
                }
                .setNegativeButton(android.R.string.cancel, null)
                .create()
            pendingNetworkAction = action
            networkPermissionDialog = dialog
            updateControls()
            dialog.setOnDismissListener {
                pendingNetworkAction = null
                networkPermissionDialog = null
                updateControls()
            }
            dialog.show()
        }
    }

    private fun operation(action: () -> Unit) {
        if (busy) return
        busy = true
        status.setText(R.string.working)
        updateControls()
        worker.execute {
            try {
                action()
            } catch (error: Exception) {
                runOnUiThread { showError(error) }
            } finally {
                runOnUiThread {
                    busy = false
                    updateControls()
                }
            }
        }
    }

    private fun showError(error: Exception) {
        status.text = getString(R.string.error, error.message ?: error.javaClass.simpleName)
    }

    private fun renderEntries() {
        breadcrumb.text = folders.joinToString(" / ") { it.second }
        list.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, entries.map {
            if (it.getInt("type") == 2) getString(R.string.folder, it.getString("name"))
            else getString(R.string.file, it.getString("name"), it.getLong("size"))
        })
    }

    private fun updateControls() {
        val available = !busy && pendingNetworkAction == null
        controls.forEach { it.isEnabled = available }
        reconnect.isEnabled = available && snapshotFile.baseFile.exists()
        forget.isEnabled = available && (connected || snapshotFile.baseFile.exists())
        refresh.isEnabled = available && connected
        up.isEnabled = available && connected && folders.size > 1
    }

    override fun onDestroy() {
        pendingNetworkAction = null
        networkPermissionDialog?.dismiss()
        previewDialog?.dismiss()
        worker.shutdown()
        super.onDestroy()
    }

    companion object {
        private const val IMPORT_SNAPSHOT = 1
        private const val IMPORT_CERTIFICATE = 2
        private const val LOCAL_NETWORK_PERMISSION = 3
    }
}
