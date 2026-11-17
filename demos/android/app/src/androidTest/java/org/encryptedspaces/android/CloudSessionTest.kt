package org.encryptedspaces.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CloudSessionTest {
    @Test fun failedReadsReconnectAndRetryOnce() {
        for (command in listOf("list", "read", "stat")) {
            var attempts = 0
            var reconnects = 0
            val result = CloudSession.recoverRead(command, {
                attempts++
                if (attempts == 1) throw CloudSession.NativeRequestException("closed", true)
                "recovered"
            }, { reconnects++ })
            assertEquals("recovered", result)
            assertEquals(2, attempts)
            assertEquals(1, reconnects)
        }
    }

    @Test fun failedRecoverySurfacesBothFailuresWithoutAnotherRetry() {
        for (restoreFails in listOf(true, false)) {
            val original = CloudSession.NativeRequestException("closed", true)
            val final = CloudSession.NativeRequestException("still offline", true)
            var attempts = 0
            var reconnects = 0
            try {
                CloudSession.recoverRead("read", {
                    attempts++
                    throw if (attempts == 1) original else final
                }, {
                    reconnects++
                    if (restoreFails) throw final
                })
                fail("Recovery failure was swallowed")
            } catch (error: CloudSession.NativeRequestException) {
                assertSame(final, error)
                assertArrayEquals(arrayOf(original), error.suppressed)
            }
            assertEquals(if (restoreFails) 1 else 2, attempts)
            assertEquals(1, reconnects)
        }
    }

    @Test fun mutationsAndNonTransportFailuresAreNeverRetried() {
        for (command in listOf("join", "restore", "disconnect", "create", "write", "rename",
            "delete", "set_user_name", "snapshot", "list", "read", "stat")) {
            val transportFailure = command !in listOf("list", "read", "stat")
            val original = CloudSession.NativeRequestException("operation failed", transportFailure)
            var attempts = 0
            var reconnects = 0
            try {
                CloudSession.recoverRead(command, {
                    attempts++
                    throw original
                }, { reconnects++ })
                fail("Request failure was swallowed")
            } catch (error: CloudSession.NativeRequestException) {
                assertSame(original, error)
            }
            assertEquals(1, attempts)
            assertEquals(0, reconnects)
        }
    }
}
