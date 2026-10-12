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

package org.dashdeck.app.ui

import android.util.TypedValue
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.ColorUtils
import org.dashdeck.app.databinding.ActivityMainBinding

class ThemeManager(
    private val activity: AppCompatActivity,
    private val binding: ActivityMainBinding
) {

    fun resolveThemeColor(attrRes: Int): Int {
        val typedValue = TypedValue()
        if (activity.theme.resolveAttribute(attrRes, typedValue, true)) {
            if (typedValue.resourceId != 0) {
                return androidx.core.content.ContextCompat.getColor(activity, typedValue.resourceId)
            }
            return typedValue.data
        }
        return android.graphics.Color.TRANSPARENT
    }

    fun resolveReadableTextColor(backgroundColor: Int, preferredColor: Int, fallbackColor: Int): Int {
        val preferredContrast = ColorUtils.calculateContrast(preferredColor, backgroundColor)
        val fallbackContrast = ColorUtils.calculateContrast(fallbackColor, backgroundColor)
        return if (preferredContrast >= fallbackContrast) {
            preferredColor
        } else {
            fallbackColor
        }
    }
}
