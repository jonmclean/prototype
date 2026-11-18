package org.encryptedspaces.android

import android.app.Activity
import android.os.Bundle
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView

class PreferencesActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppConfig.applyScreenCapturePolicy(window)
        title = getString(R.string.preferences_title)
        val padding = (24 * resources.displayMetrics.density).toInt()
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
            addView(TextView(this@PreferencesActivity).apply {
                setText(R.string.preferences_title)
                textSize = 24f
            })
            addView(Switch(this@PreferencesActivity).apply {
                setText(R.string.block_screen_capture_title)
                setPadding(0, padding, 0, padding)
                isChecked = AppConfig.blockScreenCapture(this@PreferencesActivity)
                setOnCheckedChangeListener { _, checked ->
                    AppConfig.setBlockScreenCapture(this@PreferencesActivity, checked)
                    AppConfig.applyScreenCapturePolicy(window)
                }
            }, LinearLayout.LayoutParams(-1, -2))
            addView(TextView(this@PreferencesActivity).apply {
                setText(R.string.block_screen_capture_description)
                textSize = 16f
            })
        }
        setContentView(FrameLayout(this).apply {
            fitsSystemWindows = true
            addView(ScrollView(this@PreferencesActivity).apply { addView(content) })
        })
    }
}
