package org.encryptedspaces.android

import android.content.Intent
import android.util.AtomicFile
import android.view.View
import android.view.ViewGroup
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
}
