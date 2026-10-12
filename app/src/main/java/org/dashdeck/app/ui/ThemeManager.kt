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
