package org.encryptedspaces.android

import android.content.Intent
import android.os.ParcelFileDescriptor
import android.os.Process
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.net.UnknownHostException
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class ExceptionLoggingTest {
    private fun logs(): String {
        val output = InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand("logcat -d -t 2000 --pid=${Process.myPid()} -s SpacesAndroid:E '*:S'")
        return ParcelFileDescriptor.AutoCloseInputStream(output).bufferedReader().use {
            it.readText()
        }
    }

    @Test fun displayedExceptionLogsLongMessagesCausesAndSuppressedExceptions() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = instrumentation.startActivitySync(
            Intent(instrumentation.targetContext, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ) as MainActivity
        val marker = "exception_${System.nanoTime()}"
        val error = IllegalStateException(
            "$marker ${"x".repeat(9999)}\uD83D\uDE80 ${marker}_end",
            UnknownHostException("${marker}_dns")
        ).apply {
            addSuppressed(IllegalArgumentException("${marker}_suppressed"))
        }
        try {
            instrumentation.runOnMainSync {
                MainActivity::class.java.getDeclaredMethod("showError", Exception::class.java)
                    .apply { isAccessible = true }.invoke(activity, error)
                val status = MainActivity::class.java.getDeclaredField("status")
                    .apply { isAccessible = true }.get(activity) as TextView
                assertEquals(activity.getString(R.string.error, error.message), status.text.toString())
            }
            val logs = logs()
            assertTrue(logs.contains("java.lang.IllegalStateException: $marker"))
            assertTrue(logs.contains("\uD83D\uDE80 ${marker}_end"))
            assertTrue(logs.contains("Caused by: java.net.UnknownHostException: ${marker}_dns"))
            assertTrue(logs.contains("Suppressed: java.lang.IllegalArgumentException: ${marker}_suppressed"))
            assertTrue(logs.contains("ExceptionLoggingTest.displayedExceptionLogsLongMessages"))
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
        }
    }

    @Test fun workerExceptionIsLoggedAndStillDisplayed() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = instrumentation.startActivitySync(
            Intent(instrumentation.targetContext, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ) as MainActivity
        val marker = "worker_exception_${System.nanoTime()}"
        try {
            instrumentation.runOnMainSync {
                val action: () -> Unit = { throw IllegalArgumentException(marker) }
                MainActivity::class.java.getDeclaredMethod("operation", Function0::class.java)
                    .apply { isAccessible = true }.invoke(activity, action)
            }
            val worker = MainActivity::class.java.getDeclaredField("worker")
                .apply { isAccessible = true }.get(activity) as ExecutorService
            worker.submit {}.get(10, TimeUnit.SECONDS)
            instrumentation.waitForIdleSync()
            assertTrue(logs().contains("java.lang.IllegalArgumentException: $marker"))
            instrumentation.runOnMainSync {
                val status = MainActivity::class.java.getDeclaredField("status")
                    .apply { isAccessible = true }.get(activity) as TextView
                assertEquals(activity.getString(R.string.error, marker), status.text.toString())
            }
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
        }
    }
}
