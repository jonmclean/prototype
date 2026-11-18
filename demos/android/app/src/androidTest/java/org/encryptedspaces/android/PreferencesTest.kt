package org.encryptedspaces.android

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Switch
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PreferencesTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    private fun descendants(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap {
            descendants(view.getChildAt(it))
        } else emptyList()

    private fun secure(activity: Activity): Boolean =
        activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0

    private fun preferencesIntent() = Intent(Intent.ACTION_APPLICATION_PREFERENCES)
        .setPackage(context.packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    @Test fun applicationPreferencesExposeDescriptionAndPersistCapturePolicy() {
        val original = AppConfig.blockScreenCapture(context)
        var activity: PreferencesActivity? = null
        try {
            AppConfig.setBlockScreenCapture(context, true)
            val resolved = context.packageManager.resolveActivity(preferencesIntent(), 0)
            assertEquals(PreferencesActivity::class.java.name, resolved!!.activityInfo.name)
            assertTrue(resolved.activityInfo.exported)
            activity = instrumentation.startActivitySync(preferencesIntent()) as PreferencesActivity
            instrumentation.runOnMainSync {
                val screen = checkNotNull(activity)
                assertTrue(secure(screen))
                val views = descendants(screen.window.decorView)
                assertTrue(views.filterIsInstance<TextView>().any {
                    it.text == context.getString(R.string.block_screen_capture_description)
                })
                val toggle = views.filterIsInstance<Switch>().single()
                assertTrue(toggle.isChecked)
                toggle.performClick()
                assertFalse(AppConfig.blockScreenCapture(context))
                assertFalse(secure(screen))
                screen.finish()
            }
            instrumentation.waitForIdleSync()
            activity = instrumentation.startActivitySync(preferencesIntent()) as PreferencesActivity
            instrumentation.runOnMainSync {
                val screen = checkNotNull(activity)
                val toggle = descendants(screen.window.decorView).filterIsInstance<Switch>().single()
                assertFalse(toggle.isChecked)
                assertFalse(secure(screen))
                toggle.performClick()
                assertTrue(AppConfig.blockScreenCapture(context))
                assertTrue(secure(screen))
            }
        } finally {
            AppConfig.setBlockScreenCapture(context, original)
            instrumentation.runOnMainSync { activity?.finish() }
            instrumentation.waitForIdleSync()
        }
    }

    @Test fun returningFromPreferencesUpdatesExistingActivityAndPreviewWindow() {
        val original = AppConfig.blockScreenCapture(context)
        var settings: PreferencesActivity? = null
        AppConfig.setBlockScreenCapture(context, true)
        val activity = instrumentation.startActivitySync(
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ) as MainActivity
        try {
            lateinit var dialog: AlertDialog
            instrumentation.runOnMainSync {
                MainActivity::class.java.getDeclaredMethod(
                    "preview", JSONObject::class.java, ByteArray::class.java
                ).apply { isAccessible = true }.invoke(activity,
                    JSONObject().put("name", "note.txt").put("mime_type", "text/plain"),
                    "Private text".toByteArray())
                dialog = MainActivity::class.java.getDeclaredField("previewDialog")
                    .apply { isAccessible = true }.get(activity) as AlertDialog
                assertTrue(secure(activity))
                assertTrue(dialog.window!!.attributes.flags and
                    WindowManager.LayoutParams.FLAG_SECURE != 0)
            }
            for (enabled in listOf(false, true)) {
                settings = instrumentation.startActivitySync(preferencesIntent()) as PreferencesActivity
                instrumentation.runOnMainSync {
                    descendants(checkNotNull(settings).window.decorView)
                        .filterIsInstance<Switch>().single().performClick()
                    checkNotNull(settings).finish()
                }
                instrumentation.waitForIdleSync()
                instrumentation.runOnMainSync {
                    assertEquals(enabled, secure(activity))
                    assertTrue(dialog.isShowing)
                    assertEquals(enabled, dialog.window!!.attributes.flags and
                        WindowManager.LayoutParams.FLAG_SECURE != 0)
                }
            }
        } finally {
            AppConfig.setBlockScreenCapture(context, original)
            instrumentation.runOnMainSync {
                settings?.finish()
                activity.finish()
            }
            instrumentation.waitForIdleSync()
        }
    }
}
