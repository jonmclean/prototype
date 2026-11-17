package org.encryptedspaces.android

import android.util.Log

internal fun logException(tag: String, message: String, error: Exception) {
    Log.e(tag, message)
    // Log's Throwable overload omits UnknownHostException and can truncate long traces.
    error.stackTraceToString().lineSequence().forEach { line ->
        var start = 0
        do {
            var end = minOf(start + 1000, line.length)
            if (end < line.length && line[end - 1].isHighSurrogate()) end--
            Log.e(tag, line.substring(start, end))
            start = end
        } while (start < line.length)
    }
}
