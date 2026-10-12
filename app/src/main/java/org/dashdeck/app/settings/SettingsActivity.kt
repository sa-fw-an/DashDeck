package org.dashdeck.app.settings

import android.content.Context
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import org.dashdeck.app.data.BrowserPreferences
import org.dashdeck.app.databinding.ActivitySettingsBinding

class SettingsActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySettingsBinding

    override fun attachBaseContext(newBase: Context?) {
        if (newBase == null) {
            super.attachBaseContext(null)
            return
        }
        super.attachBaseContext(BrowserPreferences.createScaledContext(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(BrowserPreferences.getThemeMode(this).style)
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.topAppBar.setNavigationOnClickListener { finish() }
        binding.settingsContentRoot.addView(
            SettingsViews.createSettingsActivityView(this)
        )
        org.dashdeck.app.ui.ThemeTransition.onCreated(this)
    }
}
