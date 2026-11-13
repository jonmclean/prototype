package org.encryptedspaces.android

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class NetworkPermissionTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    private fun withActivity(test: (MainActivity) -> Unit) {
        val context = instrumentation.targetContext
        val activity = instrumentation.startActivitySync(
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ) as MainActivity
        try {
            test(activity)
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
            instrumentation.waitForIdleSync()
        }
    }

    private fun pendingAction(activity: MainActivity, action: () -> Unit) {
        MainActivity::class.java.getDeclaredField("pendingNetworkAction").apply {
            isAccessible = true
        }.set(activity, action)
    }

    private fun permissionDialog(activity: MainActivity): AlertDialog =
        MainActivity::class.java.getDeclaredField("networkPermissionDialog").apply {
            isAccessible = true
        }.get(activity) as AlertDialog

    @Test fun grantedPermissionRunsPendingOperation() = withActivity { activity ->
        val completed = CountDownLatch(1)
        instrumentation.runOnMainSync {
            pendingAction(activity) { completed.countDown() }
            activity.onRequestPermissionsResult(
                3, arrayOf("android.permission.ACCESS_LOCAL_NETWORK"),
                intArrayOf(PackageManager.PERMISSION_GRANTED)
            )
        }
        assertTrue(completed.await(5, TimeUnit.SECONDS))
    }

    @Test fun deniedPermissionDoesNotRunUntilInternetOnlyIsChosen() = withActivity { activity ->
        val completed = CountDownLatch(1)
        instrumentation.runOnMainSync {
            pendingAction(activity) { completed.countDown() }
            activity.onRequestPermissionsResult(
                3, arrayOf("android.permission.ACCESS_LOCAL_NETWORK"),
                intArrayOf(PackageManager.PERMISSION_DENIED)
            )
            val dialog = permissionDialog(activity)
            assertTrue(dialog.isShowing)
            assertEquals(1L, completed.count)
            assertEquals(
                activity.getString(R.string.local_network_denied),
                dialog.findViewById<TextView>(android.R.id.message).text.toString()
            )
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        }
        assertTrue(completed.await(5, TimeUnit.SECONDS))
    }

    @Test fun cancelledPermissionRequestCanBeDismissedWithoutRunningOperation() = withActivity { activity ->
        var executed = false
        instrumentation.runOnMainSync {
            pendingAction(activity) { executed = true }
            activity.onRequestPermissionsResult(3, emptyArray(), intArrayOf())
            permissionDialog(activity).getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        }
        instrumentation.waitForIdleSync()
        assertFalse(executed)
        val pending = MainActivity::class.java.getDeclaredField("pendingNetworkAction").apply {
            isAccessible = true
        }
        assertNull(pending.get(activity))
    }

    @Test fun networkOperationUsesGrantedPermissionOrLegacyAndroid() = withActivity { activity ->
        if (Build.VERSION.SDK_INT >= 37) {
            instrumentation.uiAutomation.grantRuntimePermission(
                activity.packageName, Manifest.permission.ACCESS_LOCAL_NETWORK
            )
        }
        val completed = CountDownLatch(1)
        val operation = MainActivity::class.java.getDeclaredMethod(
            "networkOperation", Function0::class.java
        ).apply { isAccessible = true }
        instrumentation.runOnMainSync {
            operation.invoke(activity, { completed.countDown() })
        }
        assertTrue(completed.await(5, TimeUnit.SECONDS))
    }
}
