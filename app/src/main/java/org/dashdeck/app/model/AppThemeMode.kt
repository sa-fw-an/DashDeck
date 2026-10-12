/*
 * Copyright (C) 2025 AABrowser Contributors (https://github.com/kododake/AABrowser)
 * Copyright (C) 2026 Safwan Sayeed (https://github.com/sa-fw-an/DashDeck)
 *
 * Modified by Safwan Sayeed for DashDeck in 2026.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://gnu.org>.
 */

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
