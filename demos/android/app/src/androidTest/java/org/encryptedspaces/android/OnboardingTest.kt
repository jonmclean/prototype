package org.encryptedspaces.android

import android.content.Intent
import android.app.Activity
import android.app.AlertDialog
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.AtomicFile
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OnboardingTest {
    private fun userNameDialog(activity: MainActivity): AlertDialog =
        MainActivity::class.java.getDeclaredField("userNameDialog")
            .apply { isAccessible = true }.get(activity) as AlertDialog

    private fun nameInput(view: View): EditText? = when (view) {
        is EditText -> view
        is ViewGroup -> (0 until view.childCount).firstNotNullOfOrNull {
            nameInput(view.getChildAt(it))
        }
        else -> null
    }

    private fun buttons(view: View): List<Button> = when (view) {
        is Button -> listOf(view)
        is ViewGroup -> (0 until view.childCount).flatMap { buttons(view.getChildAt(it)) }
        else -> emptyList()
    }

    @Test fun onboardingOnlyImportsInvitationsAndRequiresAnEndpoint() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val activity = instrumentation.startActivitySync(
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ) as MainActivity
        try {
            instrumentation.runOnMainSync {
                val buttons = buttons(activity.findViewById(android.R.id.content))
                assertFalse(buttons.any { it.text.toString() == "Import snapshot" })
                val importButton = buttons.single { it.text == activity.getString(R.string.import_invitation) }
                val address = MainActivity::class.java.getDeclaredField("address").apply {
                    isAccessible = true
                }.get(activity) as EditText
                address.setText("")
                importButton.performClick()
                val status = MainActivity::class.java.getDeclaredField("status").apply {
                    isAccessible = true
                }.get(activity) as TextView
                assertEquals(
                    activity.getString(R.string.error, activity.getString(R.string.endpoint_required)),
                    status.text.toString()
                )
                val saved = MainActivity::class.java.getDeclaredMethod("getSnapshotFile").apply {
                    isAccessible = true
                }.invoke(activity) as AtomicFile
                assertEquals("invited-connection.json", saved.baseFile.name)
                assertEquals(activity.noBackupFilesDir, saved.baseFile.parentFile)
            }
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
        }

    }

    @Test fun namePromptDefaultsToPhoneNameAndRequiresANonblankName() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = instrumentation.startActivitySync(
            Intent(instrumentation.targetContext, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ) as MainActivity
        var accepted: String? = null
        try {
            instrumentation.runOnMainSync {
                val action: (String) -> Unit = { accepted = it }
                MainActivity::class.java.getDeclaredMethod("promptUserName", Function1::class.java)
                    .apply { isAccessible = true }.invoke(activity, action)
                val dialog = userNameDialog(activity)
                assertTrue(dialog.isShowing)
                assertTrue(dialog.window!!.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
                val input = checkNotNull(nameInput(dialog.window!!.decorView))
                val expected = Settings.Global.getString(activity.contentResolver, "device_name")
                    ?.trim()?.takeIf { it.isNotEmpty() } ?: Build.MODEL
                assertEquals(expected, input.text.toString())
                input.setText("   ")
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
                assertTrue(dialog.isShowing)
                assertNull(accepted)
                assertEquals(activity.getString(R.string.user_name_required), input.error.toString())
                input.setText("  My phone  ")
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
                assertEquals("My phone", accepted)
                assertFalse(dialog.isShowing)
            }
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
        }
    }

    @Test fun invitationAndReconnectAskForANameAndCancellationDoesNotStartWork() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = instrumentation.startActivitySync(
            Intent(instrumentation.targetContext, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ) as MainActivity
        try {
            for (join in listOf(true, false)) {
                instrumentation.runOnMainSync {
                    if (join) {
                        MainActivity::class.java.getDeclaredMethod(
                            "onActivityResult", Integer.TYPE, Integer.TYPE, Intent::class.java
                        ).apply { isAccessible = true }.invoke(activity, 1, Activity.RESULT_OK,
                            Intent().setData(Uri.parse("content://test/unused-invitation")))
                    } else {
                        val reconnect = MainActivity::class.java.getDeclaredField("reconnect")
                            .apply { isAccessible = true }.get(activity) as Button
                        reconnect.performClick()
                    }
                    val dialog = userNameDialog(activity)
                    assertTrue(dialog.isShowing)
                    dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
                }
                instrumentation.waitForIdleSync()
                instrumentation.runOnMainSync {
                    assertFalse(MainActivity::class.java.getDeclaredField("busy")
                        .apply { isAccessible = true }.get(activity) as Boolean)
                    assertNull(MainActivity::class.java.getDeclaredField("pendingNetworkAction")
                        .apply { isAccessible = true }.get(activity))
                    assertNull(MainActivity::class.java.getDeclaredField("userNameDialog")
                        .apply { isAccessible = true }.get(activity))
                }
            }
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
        }
    }
}
