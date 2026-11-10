package org.encryptedspaces.android

import android.content.Intent
import android.app.AlertDialog
import android.graphics.Typeface
import android.graphics.Bitmap
import android.text.Spanned
import io.noties.markwon.core.spans.StrongEmphasisSpan
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ListView
import android.widget.ImageView
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONObject
import java.io.ByteArrayOutputStream

@RunWith(AndroidJUnit4::class)
class PreviewTest {
    private fun textViews(view: View): List<TextView> = when (view) {
        is TextView -> listOf(view)
        is ViewGroup -> (0 until view.childCount).flatMap { textViews(view.getChildAt(it)) }
        else -> emptyList()
    }

    @Test fun markdownWithGenericMimeRendersAndCanShowSourceWithoutSavingFiles() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val activity = instrumentation.startActivitySync(
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ) as MainActivity
        val before = context.dataDir.walkTopDown().filter { it.isFile }
            .map { it.relativeTo(context.dataDir).path }.toSet()
        val source = "# Notes\n\n**Important**\n\n- One\n- Two\n\n```\nhello()\n```\n\n" +
            "[Link](https://example.test/)\n\n![Remote](https://example.test/image.png)"
        val bytes = source.toByteArray()
        try {
            instrumentation.runOnMainSync {
                MainActivity::class.java.getDeclaredMethod(
                    "preview", JSONObject::class.java, ByteArray::class.java
                ).apply { isAccessible = true }.invoke(activity,
                    JSONObject().put("name", "README.MD")
                        .put("mime_type", "application/octet-stream"), bytes)
                val dialog = MainActivity::class.java.getDeclaredField("previewDialog")
                    .apply { isAccessible = true }.get(activity) as AlertDialog
                assertTrue(dialog.window!!.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
                assertEquals(WindowManager.LayoutParams.MATCH_PARENT, dialog.window!!.attributes.height)
                val reader = textViews(dialog.window!!.decorView)
                    .single { it.text.contains("Important") }
                assertFalse(reader.text.toString().contains("**Important**"))
                assertTrue(reader.text.toString().contains("hello()"))
                val rendered = reader.text as Spanned
                assertTrue(rendered.getSpans(0, rendered.length, StrongEmphasisSpan::class.java)
                    .isNotEmpty())
                assertFalse(reader.linksClickable)
                assertNull(reader.movementMethod)
                assertFalse(reader.isTextSelectable)
                val toggle = dialog.getButton(AlertDialog.BUTTON_NEUTRAL)
                toggle.performClick()
                assertEquals(source, reader.text.toString())
                assertEquals(activity.getString(R.string.view_rendered), toggle.text.toString())
                toggle.performClick()
                assertFalse(reader.text.toString().contains("**Important**"))
                assertFalse(reader.linksClickable)
                assertNull(reader.movementMethod)
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            }
            instrumentation.waitForIdleSync()
            assertTrue(bytes.all { it == 0.toByte() })
            val after = context.dataDir.walkTopDown().filter { it.isFile }
                .map { it.relativeTo(context.dataDir).path }.toSet()
            assertEquals(before, after)
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
        }
    }

    @Test fun textFilenameAndMimeFallbacksUseBoundedPlainTextReader() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = instrumentation.startActivitySync(
            Intent(instrumentation.targetContext, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ) as MainActivity
        try {
            val preview = MainActivity::class.java.getDeclaredMethod(
                "preview", JSONObject::class.java, ByteArray::class.java
            ).apply { isAccessible = true }
            for ((name, mime) in listOf(
                "notes.TXT" to "application/octet-stream",
                "notes.text" to "",
                "unknown" to "TEXT/PLAIN; charset=utf-8",
                "data" to "application/json"
            )) {
                val content = "**Plain text**\n" + "x".repeat(256 * 1024) + "NOT_IN_PREVIEW"
                val bytes = content.toByteArray()
                instrumentation.runOnMainSync {
                    preview.invoke(activity, JSONObject().put("name", name).put("mime_type", mime), bytes)
                    val dialog = MainActivity::class.java.getDeclaredField("previewDialog")
                        .apply { isAccessible = true }.get(activity) as AlertDialog
                    val reader = textViews(dialog.window!!.decorView)
                        .single { it.text.contains("**Plain text**") }
                    assertTrue(reader.text.startsWith(activity.getString(R.string.text_truncated)))
                    assertFalse(reader.text.contains("NOT_IN_PREVIEW"))
                    assertEquals(Typeface.MONOSPACE, reader.typeface)
                    assertFalse(reader.isTextSelectable)
                    dialog.dismiss()
                }
                instrumentation.waitForIdleSync()
                assertTrue(bytes.all { it == 0.toByte() })
            }
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
        }
    }

    @Test fun explorerRowsShowFileTypesSizesAndBreadcrumbs() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = instrumentation.startActivitySync(
            Intent(instrumentation.targetContext, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ) as MainActivity
        try {
            instrumentation.runOnMainSync {
                val entries = listOf(
                    JSONObject().put("name", "Documents").put("type", 2),
                    JSONObject().put("name", "README.md").put("type", 1)
                        .put("size", 2048).put("mime_type", "application/octet-stream"),
                    JSONObject().put("name", "notes.txt").put("type", 1)
                        .put("size", 12).put("mime_type", "application/octet-stream")
                )
                MainActivity::class.java.getDeclaredField("entries")
                    .apply { isAccessible = true }.set(activity, entries)
                MainActivity::class.java.getDeclaredMethod("renderEntries")
                    .apply { isAccessible = true }.invoke(activity)
                val list = MainActivity::class.java.getDeclaredField("list")
                    .apply { isAccessible = true }.get(activity) as ListView
                assertEquals(3, list.adapter.count)
                for (index in entries.indices) {
                    val row = list.adapter.getView(index, null, list)
                    assertEquals(entries[index].getString("name"),
                        row.findViewById<TextView>(R.id.file_name).text.toString())
                    val details = row.findViewById<TextView>(R.id.file_details).text.toString()
                    assertTrue(details.contains(activity.getString(
                        when (index) {
                            0 -> R.string.folder_type
                            1 -> R.string.markdown_type
                            else -> R.string.text_type
                        }
                    )))
                    if (index > 0) assertTrue(details.contains("B"))
                }
                val breadcrumb = MainActivity::class.java.getDeclaredField("breadcrumb")
                    .apply { isAccessible = true }.get(activity) as View
                assertEquals(activity.getString(R.string.all_files),
                    textViews(breadcrumb).single().text.toString())
                MainActivity::class.java.getDeclaredField("connected")
                    .apply { isAccessible = true }.set(activity, true)
                MainActivity::class.java.getDeclaredField("folders")
                    .apply { isAccessible = true }.set(activity,
                        mutableListOf(0L to "/", 1L to "Documents", 2L to "Notes"))
                MainActivity::class.java.getDeclaredMethod("renderEntries")
                    .apply { isAccessible = true }.invoke(activity)
                val labels = textViews(breadcrumb).filter { it.text != " / " }
                assertEquals(listOf(activity.getString(R.string.all_files), "Documents", "Notes"),
                    labels.map { it.text.toString() })
                assertTrue(labels[0].isEnabled)
                assertTrue(labels[1].isEnabled)
                assertFalse(labels[2].isEnabled)
                MainActivity::class.java.getDeclaredField("busy")
                    .apply { isAccessible = true }.set(activity, true)
                MainActivity::class.java.getDeclaredMethod("updateControls")
                    .apply { isAccessible = true }.invoke(activity)
                assertTrue(labels.none { it.isEnabled })
            }
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
        }
    }

    @Test fun imageAndBinaryPreviewsStillWorkAndReplacingAPreviewClearsItsBuffer() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = instrumentation.startActivitySync(
            Intent(instrumentation.targetContext, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ) as MainActivity
        val imageBytes = ByteArrayOutputStream().use { output ->
            val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
            bitmap.recycle()
            output.toByteArray()
        }
        val binaryBytes = byteArrayOf(1, 2, 3)
        val dialogField = MainActivity::class.java.getDeclaredField("previewDialog")
            .apply { isAccessible = true }
        try {
            instrumentation.runOnMainSync {
                val preview = MainActivity::class.java.getDeclaredMethod(
                    "preview", JSONObject::class.java, ByteArray::class.java
                ).apply { isAccessible = true }
                preview.invoke(activity, JSONObject().put("name", "image.png")
                    .put("mime_type", "image/png"), imageBytes)
                val image = dialogField.get(activity) as AlertDialog
                fun containsImage(view: View): Boolean =
                    view is ImageView || view is ViewGroup &&
                        (0 until view.childCount).any { containsImage(view.getChildAt(it)) }
                assertTrue(containsImage(image.window!!.decorView))
                preview.invoke(activity, JSONObject().put("name", "data.bin")
                    .put("mime_type", "application/octet-stream"), binaryBytes)
            }
            instrumentation.waitForIdleSync()
            assertTrue(imageBytes.all { it == 0.toByte() })
            instrumentation.runOnMainSync {
                val binary = dialogField.get(activity) as AlertDialog
                assertTrue(binary.isShowing)
                assertTrue(textViews(binary.window!!.decorView).any {
                    it.text.toString() == activity.getString(R.string.binary_preview, binaryBytes.size)
                })
                binary.dismiss()
            }
            instrumentation.waitForIdleSync()
            assertTrue(binaryBytes.all { it == 0.toByte() })
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
        }
    }

    @Test fun textPreviewDoesNotWriteLocalFilesAndBlocksScreenshots() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val activity = instrumentation.startActivitySync(
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ) as MainActivity
        val before = context.dataDir.walkTopDown().filter { it.isFile }
            .map { it.relativeTo(context.dataDir).path }.toSet()
        val bytes = "Memory-only preview regression test".toByteArray()
        val preview = MainActivity::class.java.getDeclaredMethod(
            "preview", JSONObject::class.java, ByteArray::class.java
        ).apply { isAccessible = true }
        instrumentation.runOnMainSync {
            assertTrue(activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
            preview.invoke(activity, JSONObject().put("name", "preview.txt")
                .put("mime_type", "text/plain"), bytes)
        }
        instrumentation.waitForIdleSync()
        val after = context.dataDir.walkTopDown().filter { it.isFile }
            .map { it.relativeTo(context.dataDir).path }.toSet()
        assertEquals(before, after)
        instrumentation.runOnMainSync { activity.finish() }
    }
}
