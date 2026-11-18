package org.encryptedspaces.android

import android.content.Context
import android.view.Window
import android.view.WindowManager

internal object AppConfig {
    private const val PREFERENCES = "app_config"
    private const val BLOCK_SCREEN_CAPTURE = "block_screen_capture"

    fun blockScreenCapture(context: Context): Boolean =
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .getBoolean(BLOCK_SCREEN_CAPTURE, true)

    fun setBlockScreenCapture(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit().putBoolean(BLOCK_SCREEN_CAPTURE, enabled).apply()
    }

    fun applyScreenCapturePolicy(window: Window) {
        window.setFlags(
            if (blockScreenCapture(window.context)) WindowManager.LayoutParams.FLAG_SECURE else 0,
            WindowManager.LayoutParams.FLAG_SECURE
        )
    }
}
