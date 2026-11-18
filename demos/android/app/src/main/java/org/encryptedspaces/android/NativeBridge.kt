package org.encryptedspaces.android

import android.system.Os

object NativeBridge {
    init {
        // Rust caches this setting; enable it before loading or calling the SDK.
        Os.setenv("RUST_LIB_BACKTRACE", "1", true)
        System.loadLibrary("spaces_android")
    }

    @JvmStatic external fun request(request: String): String

    fun interface DownloadProgress {
        fun onProgress(stage: String, processed: Long, total: Long, complete: Boolean)
    }

    @JvmStatic external fun requestWithProgress(request: String, listener: DownloadProgress): String
}
