package org.encryptedspaces.android

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.text.format.Formatter
import android.util.AtomicFile
import android.util.Base64
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.Gravity
import android.view.WindowManager
import android.widget.*
import io.noties.markwon.Markwon
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.ByteArrayOutputStream
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.util.concurrent.Executors
import java.util.Locale

class MainActivity : Activity() {
    private lateinit var address: EditText
    private lateinit var status: TextView
    private lateinit var breadcrumb: LinearLayout
    private lateinit var connectionPanel: LinearLayout
    private lateinit var emptyState: TextView
    private lateinit var progress: ProgressBar
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
    private val snapshotFile get() = AtomicFile(File(noBackupFilesDir, "invited-connection.json"))
    private val markdown by lazy { Markwon.create(this) }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val inset = dp(12)
            setPadding(inset, inset, inset, inset)
        }
        val titleRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        root.addView(titleRow)
        titleRow.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(this@MainActivity).apply {
                setText(R.string.files_title)
                textSize = 28f
                setTypeface(typeface, Typeface.BOLD)
            })
            addView(TextView(this@MainActivity).apply {
                setText(R.string.space_brand)
                textSize = 12f
            })
        }, LinearLayout.LayoutParams(0, -2, 1f))
        titleRow.addView(Button(this).apply {
            setText(R.string.connection)
            isAllCaps = false
            setOnClickListener {
                connectionPanel.visibility =
                    if (connectionPanel.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            }
        })
        connectionPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), dp(8), dp(4), dp(8))
        }
        root.addView(connectionPanel)
        connectionPanel.addView(TextView(this).apply {
            setText(R.string.warning)
            textSize = 12f
        })
        address = EditText(this).apply {
            setHint(R.string.endpoint_hint)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_URI
            isSingleLine = true
        }
        controls.add(address)
        connectionPanel.addView(address)
        fun row(parent: LinearLayout = connectionPanel): LinearLayout =
            LinearLayout(this).also { parent.addView(it) }
        fun button(row: LinearLayout, text: Int, action: () -> Unit): Button =
            Button(this).apply {
                setText(text)
                isAllCaps = false
                setOnClickListener { action() }
                controls.add(this)
                row.addView(this, LinearLayout.LayoutParams(0, -2, 1f))
            }
        val connectionRow = row()
        button(connectionRow, R.string.import_invitation) {
            if (address.text.toString().isBlank()) {
                showError(IllegalArgumentException(getString(R.string.endpoint_required)))
            } else {
                openDocument(IMPORT_INVITATION)
            }
        }
        reconnect = button(connectionRow, R.string.restore) {
            val endpoint = address.text.toString().trim()
            networkOperation {
                val connection = JSONObject(snapshotFile.openRead().use { it.reader().readText() })
                restore(connection, endpoint)
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
                    connectionPanel.visibility = View.VISIBLE
                    renderEntries()
                    status.setText(R.string.disconnected)
                }
            }
        }
        val navigationRow = row(root)
        up = button(navigationRow, R.string.up) {
            networkOperation { loadFolder(folders.dropLast(1)) }
        }
        refresh = button(navigationRow, R.string.refresh) {
            networkOperation { loadFolder(folders.toList()) }
        }
        breadcrumb = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        root.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(breadcrumb)
        })
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true
            visibility = View.INVISIBLE
        }
        root.addView(progress, LinearLayout.LayoutParams(-1, dp(4)))
        status = TextView(this).also {
            it.setText(R.string.disconnected)
            it.textSize = 12f
            it.setPadding(dp(4), dp(8), dp(4), dp(8))
        }
        list = ListView(this).apply {
            setBackgroundColor(android.graphics.Color.WHITE)
            dividerHeight = dp(1)
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
        val files = FrameLayout(this)
        files.addView(list, FrameLayout.LayoutParams(-1, -1))
        emptyState = TextView(this).apply {
            gravity = Gravity.CENTER
            textSize = 16f
            setPadding(dp(24), dp(32), dp(24), dp(32))
        }
        files.addView(emptyState, FrameLayout.LayoutParams(-1, -1))
        list.emptyView = emptyState
        root.addView(files, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(status)
        setContentView(FrameLayout(this).apply {
            fitsSystemWindows = true
            addView(root, FrameLayout.LayoutParams(-1, -1))
        })
        if (Build.VERSION.SDK_INT >= 33) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(
                android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT
            ) { navigateBack() }
        }
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

    @Deprecated("Used on Android versions before the back-invoked API")
    @SuppressLint("GestureBackNavigation") // API 33+ uses the platform callback registered in onCreate.
    override fun onBackPressed() {
        navigateBack()
    }

    private fun navigateBack() {
        if (connected && folders.size > 1) {
            val parent = folders.dropLast(1)
            networkOperation { loadFolder(parent) }
        } else {
            finish()
        }
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
            IMPORT_INVITATION -> {
                val endpoint = address.text.toString().trim()
                networkOperation {
                    val invitation = JSONObject(
                        readDocument(uri, 1024 * 1024).toString(Charsets.UTF_8)
                    )
                    establishConnection(JSONObject()
                        .put("command", "join")
                        .put("invitation", invitation), endpoint)
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

    private fun restore(connection: JSONObject, overrideAddress: String) {
        val endpoint = overrideAddress.ifEmpty {
            connection.getJSONObject("user_info").getString("ws_address")
        }
        establishConnection(JSONObject()
            .put("command", "restore")
            .put("connection", connection), endpoint)
    }

    private fun establishConnection(request: JSONObject, endpoint: String) {
        require(endpoint.isNotBlank()) { getString(R.string.endpoint_required) }
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
        val snapshot = native(request
            .put("address", endpoint)
            .put("certificates", certificates)) as JSONObject
        saveSnapshot(snapshot)
        runOnUiThread {
            address.setText(endpoint)
            connected = true
            connectionPanel.visibility = View.GONE
        }
        loadFolder(listOf(0L to "/"))
    }

    private fun loadFolder(path: List<Pair<Long, String>>) {
        val result = native(JSONObject()
            .put("command", "list")
            .put("parent_id", path.last().first)) as JSONArray
        saveSnapshot(native(JSONObject().put("command", "snapshot")) as JSONObject)
        val loaded = (0 until result.length()).map { result.getJSONObject(it) }
            .sortedWith(compareBy<JSONObject> { it.getInt("type") != 2 }
                .thenBy { it.getString("name").lowercase(Locale.ROOT) }
                .thenBy { it.getString("name") })
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
        val mime = mimeType(entry)
        val isMarkdown = isMarkdown(entry)
        var textView: TextView? = null
        var source: String? = null
        var sourceVisible = false
        var releaseImage: (() -> Unit)? = null
        val view: View = when {
            mime.startsWith("image/") && !isText(entry) -> {
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
            isText(entry) -> {
                val limit = 256 * 1024
                val text = String(bytes, 0, minOf(bytes.size, limit), Charsets.UTF_8)
                ScrollView(this).apply {
                    addView(TextView(this@MainActivity).apply {
                        setPadding(dp(20), dp(20), dp(20), dp(20))
                        textSize = 16f
                        val content = if (bytes.size > limit)
                            getString(R.string.text_truncated) + "\n\n" + text else text
                        if (isMarkdown) {
                            source = content
                            markdown.setMarkdown(this, content)
                        } else {
                            typeface = Typeface.MONOSPACE
                            this.text = content
                        }
                        textView = this
                        // Disable text selection: copying would persist content to the clipboard.
                        setTextIsSelectable(false)
                        // Previews must not navigate to external URLs or fetch remote content.
                        movementMethod = null
                        linksClickable = false
                    })
                }
            }
            else -> TextView(this).apply {
                text = getString(R.string.binary_preview, bytes.size)
                setPadding(16, 16, 16, 16)
            }
        }
        val builder = AlertDialog.Builder(this)
            .setTitle(entry.getString("name"))
            .setView(view)
            .setPositiveButton(R.string.close_preview, null)
        if (isMarkdown) builder.setNeutralButton(R.string.view_source, null)
        builder.create().apply {
            previewDialog = this
            setOnDismissListener {
                bytes.fill(0)
                textView?.text = ""
                source = null
                releaseImage?.invoke()
                if (previewDialog === this) previewDialog = null
            }
            window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            show()
            window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            window?.setLayout(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT
            )
            if (isMarkdown) getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                val reader = checkNotNull(textView)
                val content = checkNotNull(source)
                sourceVisible = !sourceVisible
                if (sourceVisible) {
                    reader.typeface = Typeface.MONOSPACE
                    reader.text = content
                } else {
                    reader.typeface = Typeface.DEFAULT
                    markdown.setMarkdown(reader, content)
                }
                reader.movementMethod = null
                reader.linksClickable = false
                getButton(AlertDialog.BUTTON_NEUTRAL).setText(
                    if (sourceVisible) R.string.view_rendered else R.string.view_source
                )
            }
        }
    }

    private fun mimeType(entry: JSONObject) =
        entry.optString("mime_type").substringBefore(';').trim().lowercase(Locale.ROOT)

    private fun extension(entry: JSONObject) =
        entry.getString("name").substringAfterLast('.', "").lowercase(Locale.ROOT)

    private fun isMarkdown(entry: JSONObject) =
        mimeType(entry) in setOf("text/markdown", "text/x-markdown") ||
            extension(entry) in setOf("md", "markdown", "mdown")

    private fun isText(entry: JSONObject): Boolean {
        val mime = mimeType(entry)
        return isMarkdown(entry) || mime.startsWith("text/") ||
            mime in setOf("application/json", "application/xml") ||
            extension(entry) in setOf("txt", "text", "log", "csv", "json", "xml")
    }

    private fun fileType(entry: JSONObject): Int = when {
        entry.getInt("type") == 2 -> R.string.folder_type
        isMarkdown(entry) -> R.string.markdown_type
        isText(entry) -> R.string.text_type
        mimeType(entry).startsWith("image/") -> R.string.image_type
        else -> R.string.file_type
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
        Log.e("SpacesAndroid", "Android operation failed:")
        // Log's Throwable overload omits UnknownHostException and can truncate long traces.
        error.stackTraceToString().lineSequence().forEach { line ->
            var start = 0
            do {
                var end = minOf(start + 1000, line.length)
                if (end < line.length && line[end - 1].isHighSurrogate()) end--
                Log.e("SpacesAndroid", line.substring(start, end))
                start = end
            } while (start < line.length)
        }
        status.text = getString(R.string.error, error.message ?: error.javaClass.simpleName)
    }

    private fun renderEntries() {
        breadcrumb.removeAllViews()
        folders.forEachIndexed { index, (_, name) ->
            if (index > 0) breadcrumb.addView(TextView(this).apply { text = " / " })
            breadcrumb.addView(TextView(this).apply {
                text = if (index == 0) getString(R.string.all_files) else name
                textSize = 14f
                setPadding(dp(8), dp(12), dp(8), dp(12))
                setTypeface(typeface, if (index == folders.lastIndex) Typeface.BOLD else Typeface.NORMAL)
                isEnabled = connected && index != folders.lastIndex
                setOnClickListener {
                    val path = folders.take(index + 1)
                    networkOperation { loadFolder(path) }
                }
            })
        }
        emptyState.setText(if (connected) R.string.empty else R.string.browse_hint)
        list.adapter = object : ArrayAdapter<JSONObject>(this, R.layout.file_row, entries) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val row = convertView ?: layoutInflater.inflate(R.layout.file_row, parent, false)
                val entry = getItem(position)!!
                val folder = entry.getInt("type") == 2
                row.findViewById<TextView>(R.id.file_name).text = entry.getString("name")
                row.findViewById<TextView>(R.id.file_details).text =
                    if (folder) getString(R.string.folder_type)
                    else getString(R.string.file_details, getString(fileType(entry)),
                        Formatter.formatShortFileSize(this@MainActivity, entry.getLong("size")))
                row.findViewById<ImageView>(R.id.file_icon).setImageResource(
                    if (folder) R.drawable.ic_folder else R.drawable.ic_document
                )
                row.findViewById<ImageView>(R.id.folder_arrow).visibility =
                    if (folder) View.VISIBLE else View.INVISIBLE
                return row
            }
        }
    }

    private fun updateControls() {
        val available = !busy && pendingNetworkAction == null
        progress.visibility = if (busy) View.VISIBLE else View.INVISIBLE
        controls.forEach { it.isEnabled = available }
        for (index in 0 until breadcrumb.childCount) {
            breadcrumb.getChildAt(index).isEnabled =
                available && connected && index < breadcrumb.childCount - 1
        }
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
        private const val IMPORT_INVITATION = 1
        private const val IMPORT_CERTIFICATE = 2
        private const val LOCAL_NETWORK_PERMISSION = 3
    }
}
