package org.dashdeck.app.settings

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import org.dashdeck.app.R
import org.dashdeck.app.data.BrowserPreferences
import org.dashdeck.app.model.AppThemeMode

/** A row of theme previews; tapping one picks that theme. */
internal fun createThemeSwatches(
    context: Context,
    dp: (Int) -> Int,
    onPick: (origin: View, mode: AppThemeMode) -> Unit
): View {
    fun attr(id: Int) = TypedValue().also { context.theme.resolveAttribute(id, it, true) }.data
    val selected = BrowserPreferences.getThemeMode(context)
    val accent = attr(androidx.appcompat.R.attr.colorPrimary)
    val outline = attr(com.google.android.material.R.attr.colorOutlineVariant)

    return LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(dp(8), dp(4), dp(8), dp(12))
        AppThemeMode.entries.forEach { mode ->
            val (bgColor, surfaceColor, modeAccent) = mode.preview.toList()
            val preview = FrameLayout(context).apply {
                background = GradientDrawable().apply {
                    cornerRadius = dp(8).toFloat()
                    setColor(bgColor)
                    setStroke(dp(if (mode == selected) 2 else 1), if (mode == selected) accent else outline)
                }
                clipToOutline = true
                if (mode == AppThemeMode.SYSTEM) {
                    // Hard split: dark half, light half.
                    addView(View(context).apply { setBackgroundColor(surfaceColor) },
                        FrameLayout.LayoutParams(-1, -1).apply { leftMargin = dp(28) })
                }
                // Mini "card" and accent bar, so the swatch reads as a screen.
                addView(View(context).apply {
                    background = GradientDrawable().apply {
                        cornerRadius = dp(3).toFloat()
                        setColor(if (mode == AppThemeMode.SYSTEM) 0x33888888 else surfaceColor)
                    }
                }, FrameLayout.LayoutParams(-1, dp(14)).apply { setMargins(dp(8), dp(10), dp(8), 0) })
                addView(View(context).apply {
                    background = GradientDrawable().apply { cornerRadius = dp(2).toFloat(); setColor(modeAccent) }
                }, FrameLayout.LayoutParams(dp(20), dp(6)).apply { setMargins(dp(8), dp(32), 0, 0) })
            }
            val label = TextView(context).apply {
                setText(mode.label)
                setTextAppearance(R.style.TextAppearance_DashDeck_Label)
                setTextColor(if (mode == selected) accent else attr(com.google.android.material.R.attr.colorOnSurfaceVariant))
                gravity = Gravity.CENTER
                maxLines = 1
            }
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                isClickable = true
                isFocusable = true
                contentDescription = context.getString(mode.label)
                foreground = context.getDrawable(R.drawable.tile_ripple)
                setPadding(dp(4), dp(4), dp(4), dp(4))
                addView(preview, LinearLayout.LayoutParams(-1, dp(52)))
                addView(label, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
                setOnClickListener { onPick(preview, mode) }
            }, LinearLayout.LayoutParams(0, -2, 1f))
        }
    }
}
