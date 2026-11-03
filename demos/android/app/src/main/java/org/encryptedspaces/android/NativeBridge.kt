package org.encryptedspaces.android

object NativeBridge {
    init {
        System.loadLibrary("spaces_android")
    }

    @JvmStatic external fun request(request: String): String
}
