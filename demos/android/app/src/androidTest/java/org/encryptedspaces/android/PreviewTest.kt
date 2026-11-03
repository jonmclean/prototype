package org.encryptedspaces.android

import android.content.Intent
import android.view.WindowManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONObject

@RunWith(AndroidJUnit4::class)
class PreviewTest {
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
