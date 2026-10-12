package org.dashdeck.app.model

import android.content.Context
import android.content.res.Configuration
import androidx.annotation.ColorInt
import androidx.annotation.StringRes
import androidx.annotation.StyleRes
import org.dashdeck.app.R

/** App color themes. Storage keys of the original light/dark/auto modes are kept. */
enum class AppThemeMode(
    val storageKey: String,
    @StyleRes val style: Int,
    @StringRes val label: Int,
    /** Background, surface and accent, for the theme picker preview. */
    @ColorInt val preview: IntArray
) {
    MIDNIGHT("dark", R.style.Theme_DashDeck_Midnight, R.string.settings_theme_midnight,
        intArrayOf(0xFF0E1013.toInt(), 0xFF1E2228.toInt(), 0xFF4C8DFF.toInt())),
    GRAPHITE("graphite", R.style.Theme_DashDeck_Graphite, R.string.settings_theme_graphite,
        intArrayOf(0xFF121212.toInt(), 0xFF242424.toInt(), 0xFFF2A93B.toInt())),
    DAYLIGHT("light", R.style.Theme_DashDeck_Daylight, R.string.settings_theme_daylight,
        intArrayOf(0xFFF4F5F7.toInt(), 0xFFFFFFFF.toInt(), 0xFF1F5EDB.toInt())),
    SYSTEM("auto", R.style.Theme_DashDeck, R.string.settings_theme_system,
        intArrayOf(0xFF0E1013.toInt(), 0xFFF4F5F7.toInt(), 0xFF4C8DFF.toInt()));

    fun isDark(context: Context): Boolean = when (this) {
        DAYLIGHT -> false
        SYSTEM -> (android.content.res.Resources.getSystem().configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        else -> true
    }

    companion object {
        val DEFAULT = MIDNIGHT

        fun fromKey(key: String?): AppThemeMode = entries.firstOrNull { it.storageKey == key } ?: DEFAULT
    }
}
