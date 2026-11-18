package org.encryptedspaces.android

import android.content.Intent
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DownloadProgressTest {
    @Test fun stageChecklistShowsIndependentProgressCompletionFailuresAndRetries() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = instrumentation.startActivitySync(
            Intent(instrumentation.targetContext, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ) as MainActivity
        try {
            instrumentation.runOnMainSync {
                fun field(name: String) = MainActivity::class.java.getDeclaredField(name)
                    .apply { isAccessible = true }
                val busy = field("busy")
                val bar = field("progress").get(activity) as ProgressBar
                val label = field("downloadStatus").get(activity) as TextView
                val update = MainActivity::class.java.getDeclaredMethod(
                    "updateReadProgress", String::class.java, java.lang.Long.TYPE,
                    java.lang.Long.TYPE, java.lang.Boolean.TYPE
                ).apply { isAccessible = true }
                val controls = MainActivity::class.java.getDeclaredMethod("updateControls")
                    .apply { isAccessible = true }
                busy.setBoolean(activity, true)
                controls.invoke(activity)
                fun report(stage: String, processed: Long = 0, total: Long = -1,
                    complete: Boolean = false) {
                    update.invoke(activity, stage, processed, total, complete)
                }
                report("preparing")
                assertTrue(bar.isIndeterminate)
                assertEquals(View.VISIBLE, label.visibility)
                assertEquals(6, label.text.lines().size)
                assertTrue(label.text.contains("[ ] Verifying: Pending"))
                report("preparing", complete = true)
                report("downloading", 2048)
                assertTrue(bar.isIndeterminate)
                assertTrue(label.text.contains("received"))
                assertFalse(label.text.contains("%"))
                for ((received, expected) in listOf(0L to 0, 2500L to 25, 9999L to 99)) {
                    report("downloading", received, 10_000)
                    assertFalse(bar.isIndeterminate)
                    assertEquals(100, bar.max)
                    assertEquals(expected, bar.progress)
                    assertTrue(label.text.contains("$expected%"))
                }
                report("downloading", 10_000, 10_000, true)
                assertEquals(100, bar.progress)
                assertTrue(label.text.contains("[x] Downloading: Complete (100%)"))
                report("verifying", 2500, 10_000)
                assertEquals(25, bar.progress)
                assertTrue(label.text.contains("[ ] Verifying: 25%"))
                report("verifying", 10_000, 10_000)
                assertTrue(label.text.contains("[ ] Verifying: 100%"))
                assertFalse(label.text.contains("[x] Verifying"))
                report("verifying", 10_000, 10_000, true)
                report("retrieving_key")
                assertTrue(bar.isIndeterminate)
                report("retrieving_key", complete = true)
                report("decrypting", 5000, 10_000)
                assertEquals(50, bar.progress)
                assertTrue(label.text.contains("[ ] Decrypting: 50%"))
                report("decrypting", 10_000, 10_000, true)
                report("preview")
                assertTrue(bar.isIndeterminate)
                report("preview", complete = true)
                busy.setBoolean(activity, false)
                controls.invoke(activity)
                assertEquals(View.INVISIBLE, bar.visibility)
                assertEquals(View.VISIBLE, label.visibility)
                assertTrue(label.text.lines().all { it.startsWith("[x]") })
                // A retry resets completed and pending stages to the new attempt.
                busy.setBoolean(activity, true)
                controls.invoke(activity)
                report("preparing")
                assertFalse(label.text.contains("[x]"))
                assertTrue(bar.isIndeterminate)
                report("preparing", complete = true)
                report("downloading", 500, 1000, true)
                report("verifying", 1000, 1000)
                MainActivity::class.java.getDeclaredMethod("showError", Exception::class.java)
                    .apply { isAccessible = true }.invoke(activity, IllegalStateException("Bad hash"))
                assertTrue(label.text.contains("[ ] Verifying: Failed"))
                assertTrue(label.text.contains("[x] Downloading"))
                assertTrue(label.text.contains("[ ] Decrypting: Pending"))
            }
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
        }
    }
}
