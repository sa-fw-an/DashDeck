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

package org.dashdeck.app.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebStorage
import android.webkit.WebViewDatabase
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.radiobutton.MaterialRadioButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.google.zxing.BarcodeFormat
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import org.dashdeck.app.R
import org.dashdeck.app.data.BrowserPreferences
import org.dashdeck.app.model.AppThemeMode
import org.dashdeck.app.model.QuickActionButtonMode
import org.dashdeck.app.model.QuickActionButtonPosition
import org.dashdeck.app.model.UserAgentProfile
import org.dashdeck.app.mirror.DashDeckTouchService

data class SettingsCallbacks(
    val onClose: () -> Unit = {},
    /** Theme picked: the tapped swatch (reveal origin) and whether the new theme is dark. */
    val onThemeChanged: (origin: View?, toDark: Boolean) -> Unit = { _, _ -> },
    val onPageDarkeningChanged: () -> Unit = {},
    val onScaleChanged: () -> Unit = {},
    val onHomePageChanged: () -> Unit = {},
    val onInAppControlsChanged: () -> Unit = {},
    val onPickStartPageBackground: (() -> Unit)? = null,
    val onClearStartPageBackground: (() -> Unit)? = null
)

object SettingsViews {
    fun createSettingsContent(
        context: Context,
        includeDragHandle: Boolean = true,
        callbacks: SettingsCallbacks = SettingsCallbacks()
    ): View {
        fun dp(v: Int): Int = (v * context.resources.displayMetrics.density).toInt()

        fun getColorFromAttr(attrResId: Int): Int {
            val tv = TypedValue()
            if (context.theme.resolveAttribute(attrResId, tv, true)) {
                if (tv.resourceId != 0) {
                    return androidx.core.content.ContextCompat.getColor(context, tv.resourceId)
                }
                return tv.data
            }
            return android.graphics.Color.TRANSPARENT
        }

        fun createStyledCard(): MaterialCardView = MaterialCardView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(12) }
            radius = dp(8).toFloat()
            cardElevation = 0f
            strokeWidth = 0
            setCardBackgroundColor(getColorFromAttr(com.google.android.material.R.attr.colorSurfaceContainer))
        }

        /** Small uppercase label at the top of a settings group. Icon params are unused. */
        @Suppress("UNUSED_PARAMETER")
        fun createSectionTitle(
            titleText: String,
            iconRes: Int,
            iconWidthDp: Int = 20,
            iconHeightDp: Int = 20,
            tintIcon: Boolean = true,
            bottomPaddingDp: Int = 0
        ): TextView = TextView(context).apply {
            text = titleText
            setTextAppearance(R.style.TextAppearance_DashDeck_Overline)
            setPadding(dp(12), dp(4), dp(12), dp(8))
        }

        fun createListButton(idRes: Int, textStr: String, iconRes: Int): MaterialButton {
            return MaterialButton(context, null, androidx.appcompat.R.attr.borderlessButtonStyle).apply {
                id = idRes
                text = textStr
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                setTextColor(getColorFromAttr(com.google.android.material.R.attr.colorOnSurface))
                setIconResource(iconRes)
                iconSize = context.resources.getDimensionPixelSize(R.dimen.icon_size_small)
                iconPadding = dp(12)
                iconTint = ColorStateList.valueOf(getColorFromAttr(androidx.appcompat.R.attr.colorPrimary))
                iconGravity = MaterialButton.ICON_GRAVITY_TEXT_START
                iconTintMode = android.graphics.PorterDuff.Mode.SRC_IN
                backgroundTintList = ColorStateList.valueOf(Color.TRANSPARENT)
                alpha = 1.0f
                isClickable = true
                isFocusable = true
            }
        }

        fun showSuccessDialog(title: String, message: String) {
            MaterialAlertDialogBuilder(context, com.google.android.material.R.style.ThemeOverlay_Material3_MaterialAlertDialog)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }

        fun showConfirmationDialog(
            title: String,
            message: String,
            onConfirm: () -> Unit
        ) {
            MaterialAlertDialogBuilder(context, com.google.android.material.R.style.ThemeOverlay_Material3_MaterialAlertDialog)
                .setTitle(title)
                .setMessage(message)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.settings_action_delete) { _, _ -> onConfirm() }
                .show()
        }

        fun createSettingRow(
            title: String,
            statusText: String,
            iconRes: Int,
            onClick: () -> Unit
        ): LinearLayout {
            val onSurfaceColorVal = getColorFromAttr(com.google.android.material.R.attr.colorOnSurface)
            val onSurfaceVariantColorVal = getColorFromAttr(com.google.android.material.R.attr.colorOnSurfaceVariant)
            return LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                isClickable = true
                isFocusable = true
                setPadding(dp(12), dp(12), dp(12), dp(12))
                minimumHeight = dp(56)

                val rippleColor = ColorStateList.valueOf(
                    androidx.core.graphics.ColorUtils.setAlphaComponent(onSurfaceColorVal, 30)
                )
                val contentBg = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                    cornerRadius = dp(8).toFloat()
                    setColor(Color.TRANSPARENT)
                }
                val maskBg = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                    cornerRadius = dp(8).toFloat()
                    setColor(Color.WHITE)
                }
                background = android.graphics.drawable.RippleDrawable(rippleColor, contentBg, maskBg)
                
                setOnClickListener { onClick() }

                addView(ImageView(context).apply {
                    layoutParams = LinearLayout.LayoutParams(dp(24), dp(24)).apply {
                        marginEnd = dp(16)
                    }
                    setImageResource(iconRes)
                    imageTintList = ColorStateList.valueOf(onSurfaceVariantColorVal)
                })

                val textCol = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }
                textCol.addView(TextView(context).apply {
                    text = title
                    setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyLarge)
                    setTextColor(onSurfaceColorVal)
                })
                textCol.addView(TextView(context).apply {
                    text = statusText
                    setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall)
                    setTextColor(onSurfaceVariantColorVal)
                    setPadding(0, dp(2), 0, 0)
                })
                addView(textCol)

                addView(ImageView(context).apply {
                    layoutParams = LinearLayout.LayoutParams(dp(20), dp(20))
                    setImageResource(R.drawable.ic_chevron_right)
                    imageTintList = ColorStateList.valueOf(onSurfaceVariantColorVal)
                })
            }
        }

        fun createSettingSwitchRow(
            title: String,
            description: String,
            iconRes: Int,
            isCheckedValue: Boolean,
            isEnabledValue: Boolean = true,
            onCheckedChange: (Boolean) -> Unit
        ): LinearLayout {
            val onSurfaceColorVal = getColorFromAttr(com.google.android.material.R.attr.colorOnSurface)
            val onSurfaceVariantColorVal = getColorFromAttr(com.google.android.material.R.attr.colorOnSurfaceVariant)
            val switch = com.google.android.material.materialswitch.MaterialSwitch(context).apply {
                isChecked = isCheckedValue
                isEnabled = isEnabledValue
            }
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                isClickable = isEnabledValue
                isFocusable = isEnabledValue
                setPadding(dp(12), dp(12), dp(12), dp(12))
                
                if (isEnabledValue) {
                    val rippleColor = ColorStateList.valueOf(
                        androidx.core.graphics.ColorUtils.setAlphaComponent(onSurfaceColorVal, 30)
                    )
                    val contentBg = android.graphics.drawable.GradientDrawable().apply {
                        shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                        cornerRadius = dp(8).toFloat()
                        setColor(Color.TRANSPARENT)
                    }
                    val maskBg = android.graphics.drawable.GradientDrawable().apply {
                        shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                        cornerRadius = dp(8).toFloat()
                        setColor(Color.WHITE)
                    }
                    background = android.graphics.drawable.RippleDrawable(rippleColor, contentBg, maskBg)
                    setOnClickListener {
                        switch.toggle()
                    }
                } else {
                    alpha = 0.6f
                }

                addView(ImageView(context).apply {
                    layoutParams = LinearLayout.LayoutParams(dp(24), dp(24)).apply {
                        marginEnd = dp(16)
                    }
                    setImageResource(iconRes)
                    imageTintList = ColorStateList.valueOf(onSurfaceVariantColorVal)
                })

                val textCol = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }
                textCol.addView(TextView(context).apply {
                    text = title
                    setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyLarge)
                    setTextColor(onSurfaceColorVal)
                })
                textCol.addView(TextView(context).apply {
                    text = description
                    setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall)
                    setTextColor(onSurfaceVariantColorVal)
                    setPadding(0, dp(2), 0, 0)
                })
                addView(textCol)

                switch.setOnCheckedChangeListener { _, isChecked ->
                    onCheckedChange(isChecked)
                }
                addView(switch)
            }
            return row
        }

        val smallIconSize = context.resources.getDimensionPixelSize(R.dimen.icon_size_small)
        val onSurfaceColor = getColorFromAttr(com.google.android.material.R.attr.colorOnSurface)
        val onSurfaceVariantColor = getColorFromAttr(com.google.android.material.R.attr.colorOnSurfaceVariant)

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setPadding(0, 0, 0, dp(24))
        }

        if (includeDragHandle) {
            val handleFrame = FrameLayout(context).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                setPadding(0, dp(12), 0, dp(16))
            }
            handleFrame.addView(View(context).apply {
                layoutParams = FrameLayout.LayoutParams(dp(48), dp(5)).apply { gravity = Gravity.CENTER }
                setBackgroundResource(R.drawable.drag_handle_background)
            })
            container.addView(handleFrame)
        }

        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(4))
        }
        val backBtn = MaterialButton(context, null, com.google.android.material.R.attr.materialIconButtonStyle).apply {
            id = R.id.buttonSettingsBack
            contentDescription = context.getString(R.string.menu_back)
            setIconResource(R.drawable.arrow_back_24px)
            iconTint = ColorStateList.valueOf(onSurfaceColor)
            layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
        }
        header.addView(backBtn)
        header.addView(TextView(context).apply {
            id = R.id.settingsHeaderTitle
            text = context.getString(R.string.settings_title)
            setTextAppearance(R.style.TextAppearance_DashDeck_Title)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(8) }
        })
        container.addView(header)

        val appearanceCard = createStyledCard()
        val appearanceInner = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), dp(12), dp(4), dp(4))
        }
        appearanceInner.addView(
            createSectionTitle(
                context.getString(R.string.settings_appearance),
                R.drawable.settings_24px,
                bottomPaddingDp = 8
            )
        )

        appearanceInner.addView(createThemeSwatches(context, ::dp) { origin, mode ->
            val current = BrowserPreferences.getThemeMode(context)
            if (mode != current) {
                BrowserPreferences.setThemeMode(context, mode)
                callbacks.onThemeChanged(origin, mode.isDark(context))
            }
        })

        val betaDarkRow = createSettingSwitchRow(
            title = context.getString(R.string.settings_beta_dark_pages),
            description = context.getString(R.string.settings_beta_dark_pages_description),
            iconRes = R.drawable.devices_other_24px,
            isCheckedValue = BrowserPreferences.isBetaForceDarkPagesEnabled(context)
        ) { isChecked ->
            BrowserPreferences.setBetaForceDarkPagesEnabled(context, isChecked)
            callbacks.onPageDarkeningChanged()
        }
        appearanceInner.addView(betaDarkRow)

        appearanceCard.addView(appearanceInner)
        container.addView(appearanceCard)

        val shieldsCard = createStyledCard()
        val shieldsInner = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), dp(12), dp(4), dp(4))
        }
        shieldsInner.addView(createSectionTitle(context.getString(R.string.settings_shields), R.drawable.security_24px, bottomPaddingDp = 8))
        shieldsInner.addView(createSettingSwitchRow(
            title = context.getString(R.string.settings_native_shields),
            description = context.getString(R.string.settings_native_shields_description),
            iconRes = R.drawable.security_24px,
            isCheckedValue = BrowserPreferences.isShieldsEnabled(context)
        ) { isChecked ->
            BrowserPreferences.setShieldsEnabled(context, isChecked)
        })
        shieldsInner.addView(createSettingSwitchRow(
            title = context.getString(R.string.settings_block_third_party_cookies),
            description = context.getString(R.string.settings_block_third_party_cookies_description),
            iconRes = R.drawable.lock_24,
            isCheckedValue = BrowserPreferences.isBlockThirdPartyCookies(context)
        ) { isChecked ->
            BrowserPreferences.setBlockThirdPartyCookies(context, isChecked)
        })
        val listsUpdatedAt = BrowserPreferences.getShieldsListsUpdatedAt(context)
        shieldsInner.addView(createSettingRow(
            title = context.getString(R.string.settings_shields_update_lists),
            statusText = if (listsUpdatedAt > 0) {
                context.getString(
                    R.string.settings_shields_lists_updated,
                    android.text.format.DateUtils.getRelativeTimeSpanString(listsUpdatedAt)
                )
            } else {
                context.getString(R.string.settings_shields_lists_bundled)
            },
            iconRes = R.drawable.security_24px
        ) {
            ShieldsUpdateDialog(context).show()
        })
        shieldsCard.addView(shieldsInner)
        container.addView(shieldsCard)

        val mirrorCard = createStyledCard()
        val mirrorInner = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), dp(12), dp(4), dp(4))
        }
        mirrorInner.addView(createSectionTitle("Car mirror", R.drawable.devices_other_24px, bottomPaddingDp = 8))
        mirrorInner.addView(createSettingRow(
            title = "Car touch control",
            statusText = if (DashDeckTouchService.isEnabledInSettings(context)) "On" else "Off · tap to turn on",
            iconRes = R.drawable.devices_other_24px
        ) {
            MaterialAlertDialogBuilder(context, com.google.android.material.R.style.ThemeOverlay_Material3_MaterialAlertDialog)
                .setTitle("Car touch control")
                .setMessage("DashDeck can turn taps on the mirrored car display into taps on your phone. Android requires you to enable DashDeck in Accessibility settings. Only enable it when you need touch control.")
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton("Open settings") { _, _ ->
                    context.startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }
                .show()
        })
        mirrorCard.addView(mirrorInner)
        container.addView(mirrorCard)

        val displayScaleCard = createStyledCard()
        val displayScaleInner = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), dp(12), dp(4), dp(4))
        }
        displayScaleInner.addView(
            createSectionTitle(
                context.getString(R.string.settings_display_scale),
                R.drawable.computer_24,
                bottomPaddingDp = 8
            )
        )

        val currentScale = BrowserPreferences.getGlobalScalePercent(context)
        val scaleRow = createSettingRow(
            title = context.getString(R.string.settings_display_scale),
            statusText = context.getString(R.string.settings_scale_option, currentScale),
            iconRes = R.drawable.computer_24
        ) {
            val presetOptions = listOf(85, 100, 115, 130, 150)
            val customText = "Custom..."
            val dialogOptions = presetOptions.map { context.getString(R.string.settings_scale_option, it) }.toMutableList()
            dialogOptions.add(customText)

            val selectedIndex = presetOptions.indexOf(currentScale).let { if (it >= 0) it else presetOptions.size }

            MaterialAlertDialogBuilder(context, com.google.android.material.R.style.ThemeOverlay_Material3_MaterialAlertDialog)
                .setTitle("Select Page Zoom")
                .setSingleChoiceItems(dialogOptions.toTypedArray(), selectedIndex) { dialog, which ->
                    dialog.dismiss()
                    if (which == presetOptions.size) {
                        val inputLayout = TextInputLayout(context).apply {
                            hint = context.getString(R.string.settings_scale_custom_hint)
                            helperText = context.getString(
                                R.string.settings_scale_custom_helper,
                                BrowserPreferences.MIN_GLOBAL_SCALE_PERCENT,
                                BrowserPreferences.MAX_GLOBAL_SCALE_PERCENT
                            )
                            setPadding(dp(24), dp(8), dp(24), dp(8))
                        }
                        val input = TextInputEditText(context).apply {
                            inputType = InputType.TYPE_CLASS_NUMBER
                            setText(currentScale.toString())
                        }
                        inputLayout.addView(input)

                        MaterialAlertDialogBuilder(context, com.google.android.material.R.style.ThemeOverlay_Material3_MaterialAlertDialog)
                            .setTitle("Enter Custom Zoom")
                            .setView(inputLayout)
                            .setNegativeButton(android.R.string.cancel, null)
                            .setPositiveButton(android.R.string.ok) { customDialog, _ ->
                                val entered = input.text?.toString()?.trim().orEmpty()
                                val value = entered.toIntOrNull()
                                if (value != null) {
                                    val sanitized = BrowserPreferences.sanitizeGlobalScalePercent(value)
                                    if (sanitized != currentScale) {
                                        BrowserPreferences.setGlobalScalePercent(context, sanitized)
                                        callbacks.onScaleChanged()
                                    }
                                }
                            }
                            .show()
                    } else {
                        val selectedPreset = presetOptions[which]
                        if (selectedPreset != currentScale) {
                            BrowserPreferences.setGlobalScalePercent(context, selectedPreset)
                            callbacks.onScaleChanged()
                        }
                    }
                }
                .show()
        }
        displayScaleInner.addView(scaleRow)

        displayScaleCard.addView(displayScaleInner)
        container.addView(displayScaleCard)

        val homePageCard = createStyledCard()
        val homePageInner = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), dp(12), dp(4), dp(4))
        }
        homePageInner.addView(
            createSectionTitle(
                context.getString(R.string.settings_home_page),
                R.drawable.home_24px,
                bottomPaddingDp = 8
            )
        )

        val currentHomePage = BrowserPreferences.getHomePageUrl(context)
        val homePageStatusText = if (currentHomePage.isNullOrBlank()) {
            context.getString(R.string.settings_home_page_inactive)
        } else {
            currentHomePage
        }
        val homePageRow = createSettingRow(
            title = "Home Page URL",
            statusText = homePageStatusText,
            iconRes = R.drawable.home_24px
        ) {
            val inputLayout = TextInputLayout(context).apply {
                hint = context.getString(R.string.settings_home_page_hint)
                helperText = context.getString(R.string.settings_home_page_helper)
                setPadding(dp(24), dp(8), dp(24), dp(8))
            }
            val input = TextInputEditText(context).apply {
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
                setText(currentHomePage.orEmpty())
            }
            inputLayout.addView(input)

            MaterialAlertDialogBuilder(context, com.google.android.material.R.style.ThemeOverlay_Material3_MaterialAlertDialog)
                .setTitle("Set Home Page")
                .setView(inputLayout)
                .setNegativeButton(android.R.string.cancel, null)
                .setNeutralButton("Clear") { _, _ ->
                    BrowserPreferences.clearHomePageUrl(context)
                    callbacks.onHomePageChanged()
                    Toast.makeText(context, R.string.home_page_cleared, Toast.LENGTH_SHORT).show()
                }
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    val entered = input.text?.toString()?.trim().orEmpty()
                    if (entered.isNotBlank()) {
                        BrowserPreferences.setHomePageUrl(context, entered)
                        callbacks.onHomePageChanged()
                        Toast.makeText(context, R.string.home_page_set, Toast.LENGTH_SHORT).show()
                    }
                }
                .show()
        }
        homePageInner.addView(homePageRow)

        homePageCard.addView(homePageInner)
        container.addView(homePageCard)

        val startupCard = createStyledCard()
        val startupInner = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), dp(12), dp(4), dp(4))
        }
        startupInner.addView(
            createSectionTitle(
                context.getString(R.string.settings_startup),
                R.drawable.refresh_24px,
                bottomPaddingDp = 8
            )
        )

        val homePageSet = !BrowserPreferences.getHomePageUrl(context).isNullOrBlank()

        val restoreTabsRow = createSettingSwitchRow(
            title = context.getString(R.string.settings_restore_tabs_on_launch),
            description = if (homePageSet) context.getString(R.string.settings_restore_tabs_home_override) else context.getString(R.string.settings_restore_tabs_on_launch_description),
            iconRes = R.drawable.refresh_24px,
            isCheckedValue = BrowserPreferences.shouldRestoreTabsOnLaunch(context),
            isEnabledValue = !homePageSet
        ) { isChecked ->
            BrowserPreferences.setRestoreTabsOnLaunch(context, isChecked)
        }
        startupInner.addView(restoreTabsRow)

        val resumePageRow = createSettingSwitchRow(
            title = context.getString(R.string.settings_resume_last_page_on_launch),
            description = if (homePageSet) context.getString(R.string.settings_resume_last_page_home_override) else context.getString(R.string.settings_resume_last_page_on_launch_description),
            iconRes = R.drawable.refresh_24px,
            isCheckedValue = BrowserPreferences.shouldResumeLastPageOnLaunch(context),
            isEnabledValue = !homePageSet
        ) { isChecked ->
            BrowserPreferences.setResumeLastPageOnLaunch(context, isChecked)
        }
        startupInner.addView(resumePageRow)

        startupCard.addView(startupInner)
        container.addView(startupCard)

        val inAppControlsCard = createStyledCard()
        val inAppControlsInner = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), dp(12), dp(4), dp(4))
        }
        inAppControlsInner.addView(
            createSectionTitle(
                context.getString(R.string.settings_in_app_controls),
                R.drawable.search_24px,
                bottomPaddingDp = 8
            )
        )

        val alwaysShowUrlBarRow = createSettingSwitchRow(
            title = context.getString(R.string.settings_always_show_url_bar),
            description = context.getString(R.string.settings_always_show_url_bar_description),
            iconRes = R.drawable.search_24px,
            isCheckedValue = BrowserPreferences.shouldAlwaysShowUrlBar(context)
        ) { isChecked ->
            BrowserPreferences.setAlwaysShowUrlBar(context, isChecked)
            callbacks.onInAppControlsChanged()
        }
        inAppControlsInner.addView(alwaysShowUrlBarRow)

        val currentButtonMode = BrowserPreferences.getQuickActionButtonMode(context)
        val buttonModeStatus = when (currentButtonMode) {
            QuickActionButtonMode.MENU -> context.getString(R.string.settings_quick_action_button_mode_menu)
            QuickActionButtonMode.ADDRESS_BAR -> context.getString(R.string.settings_quick_action_button_mode_address_bar)
        }
        val buttonModeRow = createSettingRow(
            title = context.getString(R.string.settings_quick_action_button_mode),
            statusText = buttonModeStatus,
            iconRes = R.drawable.settings_24px
        ) {
            val modes = arrayOf(
                context.getString(R.string.settings_quick_action_button_mode_menu),
                context.getString(R.string.settings_quick_action_button_mode_address_bar)
            )
            val selectedIndex = if (currentButtonMode == QuickActionButtonMode.ADDRESS_BAR) 1 else 0
            MaterialAlertDialogBuilder(context, com.google.android.material.R.style.ThemeOverlay_Material3_MaterialAlertDialog)
                .setTitle("Select Quick Action Mode")
                .setSingleChoiceItems(modes, selectedIndex) { dialog, which ->
                    dialog.dismiss()
                    val newMode = if (which == 1) QuickActionButtonMode.ADDRESS_BAR else QuickActionButtonMode.MENU
                    if (newMode != currentButtonMode) {
                        BrowserPreferences.setQuickActionButtonMode(context, newMode)
                        callbacks.onInAppControlsChanged()
                    }
                }
                .show()
        }
        inAppControlsInner.addView(buttonModeRow)

        val alwaysVisibleRow = createSettingSwitchRow(
            title = context.getString(R.string.settings_quick_action_button_always_visible),
            description = context.getString(R.string.settings_quick_action_button_always_visible_description),
            iconRes = R.drawable.settings_24px,
            isCheckedValue = BrowserPreferences.isQuickActionButtonAlwaysVisible(context)
        ) { isChecked ->
            BrowserPreferences.setQuickActionButtonAlwaysVisible(context, isChecked)
            callbacks.onInAppControlsChanged()
        }
        inAppControlsInner.addView(alwaysVisibleRow)

        val currentPosition = BrowserPreferences.getQuickActionButtonPosition(context)
        val positionStatus = when (currentPosition) {
            QuickActionButtonPosition.BOTTOM_LEFT -> context.getString(R.string.settings_quick_action_button_position_bottom_left)
            QuickActionButtonPosition.BOTTOM_RIGHT -> context.getString(R.string.settings_quick_action_button_position_bottom_right)
            QuickActionButtonPosition.TOP_LEFT -> context.getString(R.string.settings_quick_action_button_position_top_left)
            QuickActionButtonPosition.TOP_RIGHT -> context.getString(R.string.settings_quick_action_button_position_top_right)
        }
        val positionRow = createSettingRow(
            title = context.getString(R.string.settings_quick_action_button_position),
            statusText = positionStatus,
            iconRes = R.drawable.settings_24px
        ) {
            val positions = arrayOf(
                context.getString(R.string.settings_quick_action_button_position_bottom_left),
                context.getString(R.string.settings_quick_action_button_position_bottom_right),
                context.getString(R.string.settings_quick_action_button_position_top_left),
                context.getString(R.string.settings_quick_action_button_position_top_right)
            )
            val selectedIndex = when (currentPosition) {
                QuickActionButtonPosition.BOTTOM_LEFT -> 0
                QuickActionButtonPosition.BOTTOM_RIGHT -> 1
                QuickActionButtonPosition.TOP_LEFT -> 2
                QuickActionButtonPosition.TOP_RIGHT -> 3
            }
            MaterialAlertDialogBuilder(context, com.google.android.material.R.style.ThemeOverlay_Material3_MaterialAlertDialog)
                .setTitle("Select Button Position")
                .setSingleChoiceItems(positions, selectedIndex) { dialog, which ->
                    dialog.dismiss()
                    val newPos = when (which) {
                        1 -> QuickActionButtonPosition.BOTTOM_RIGHT
                        2 -> QuickActionButtonPosition.TOP_LEFT
                        3 -> QuickActionButtonPosition.TOP_RIGHT
                        else -> QuickActionButtonPosition.BOTTOM_LEFT
                    }
                    if (newPos != currentPosition) {
                        BrowserPreferences.setQuickActionButtonPosition(context, newPos)
                        callbacks.onInAppControlsChanged()
                    }
                }
                .show()
        }
        inAppControlsInner.addView(positionRow)

        inAppControlsCard.addView(inAppControlsInner)
        container.addView(inAppControlsCard)

        val startPageCard = createStyledCard()
        val startPageInner = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        startPageInner.addView(
            createSectionTitle(
                context.getString(R.string.settings_start_page),
                R.drawable.kid_star_24px,
                bottomPaddingDp = 8
            )
        )

        val startPageCount = BrowserPreferences.getStartPageSites(context).size
        val countRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, dp(4))
            
            addView(ImageView(context).apply {
                layoutParams = LinearLayout.LayoutParams(dp(24), dp(24)).apply { marginEnd = dp(16) }
                setImageResource(R.drawable.kid_star_24px)
                imageTintList = ColorStateList.valueOf(getColorFromAttr(androidx.appcompat.R.attr.colorPrimary))
            })
            val textCol = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            textCol.addView(TextView(context).apply {
                text = "Quick Links"
                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleSmall)
                setTextColor(onSurfaceColor)
                typeface = Typeface.DEFAULT_BOLD
            })
            textCol.addView(TextView(context).apply {
                text = context.getString(
                    R.string.settings_start_page_count,
                    startPageCount,
                    BrowserPreferences.MAX_START_PAGE_SITES
                )
                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
                setTextColor(onSurfaceVariantColor)
                setPadding(0, dp(2), 0, 0)
            })
            addView(textCol)
        }
        startPageInner.addView(countRow)

        val backgroundStatus = BrowserPreferences.getStartPageBackgroundUri(context)
        val bgStatusText = if (backgroundStatus.isNullOrBlank()) {
            context.getString(R.string.settings_start_page_background_default)
        } else {
            context.getString(R.string.settings_start_page_background_custom)
        }
        
        startPageInner.addView(TextView(context).apply {
            text = "Background Image"
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleSmall)
            setTextColor(onSurfaceColor)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(12), 0, dp(2))
        })
        
        startPageInner.addView(TextView(context).apply {
            text = bgStatusText
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
            setTextColor(onSurfaceVariantColor)
            setPadding(0, 0, 0, dp(12))
        })

        val startPageButtons = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, 0)
        }
        val chooseBackgroundButton = MaterialButton(context, null, com.google.android.material.R.attr.materialButtonTonalStyle).apply {
            text = context.getString(R.string.settings_start_page_choose_background)
            setIconResource(R.drawable.search_24px)
            iconSize = smallIconSize
            iconPadding = dp(8)
            isEnabled = callbacks.onPickStartPageBackground != null
            alpha = if (isEnabled) 1f else 0.6f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(8)
            }
            setOnClickListener {
                callbacks.onPickStartPageBackground?.invoke()
            }
        }
        val clearBackgroundButton = MaterialButton(context, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = context.getString(R.string.settings_start_page_clear_background)
            setIconResource(R.drawable.delete_forever_24px)
            iconSize = smallIconSize
            iconPadding = dp(8)
            isEnabled = !backgroundStatus.isNullOrBlank() && callbacks.onClearStartPageBackground != null
            alpha = if (isEnabled) 1f else 0.6f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(8)
            }
            setOnClickListener {
                callbacks.onClearStartPageBackground?.invoke()
            }
        }
        startPageButtons.addView(chooseBackgroundButton)
        startPageButtons.addView(clearBackgroundButton)
        startPageInner.addView(startPageButtons)

        startPageCard.addView(startPageInner)
        container.addView(startPageCard)

        val uaCard = createStyledCard()
        val uaInner = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), dp(12), dp(4), dp(4))
        }
        uaInner.addView(createSectionTitle(context.getString(R.string.settings_user_agent), R.drawable.devices_other_24px, bottomPaddingDp = 8))

        val currentProfile = BrowserPreferences.getUserAgentProfile(context)
        val uaStatusText = if (currentProfile == UserAgentProfile.SAFARI) {
            context.getString(R.string.settings_user_agent_safari)
        } else {
            context.getString(R.string.settings_user_agent_android)
        }
        val uaRow = createSettingRow(
            title = context.getString(R.string.settings_user_agent),
            statusText = uaStatusText,
            iconRes = R.drawable.devices_other_24px
        ) {
            val uaOptions = arrayOf(
                context.getString(R.string.settings_user_agent_android),
                context.getString(R.string.settings_user_agent_safari)
            )
            val selectedIndex = if (currentProfile == UserAgentProfile.SAFARI) 1 else 0
            MaterialAlertDialogBuilder(context, com.google.android.material.R.style.ThemeOverlay_Material3_MaterialAlertDialog)
                .setTitle("Select User Agent")
                .setSingleChoiceItems(uaOptions, selectedIndex) { dialog, which ->
                    dialog.dismiss()
                    val selectedProfile = if (which == 1) UserAgentProfile.SAFARI else UserAgentProfile.ANDROID_CHROME
                    if (selectedProfile != currentProfile) {
                        BrowserPreferences.setUserAgentProfile(context, selectedProfile)
                    }
                }
                .show()
        }
        uaInner.addView(uaRow)

        uaCard.addView(uaInner)
        container.addView(uaCard)

        val siteDataCard = createStyledCard()
        val siteDataInner = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        siteDataInner.addView(createSectionTitle(context.getString(R.string.settings_site_data_title), R.drawable.security_24px, bottomPaddingDp = 4))
        siteDataInner.addView(TextView(context).apply {
            text = context.getString(R.string.settings_site_data_description)
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
            setTextColor(onSurfaceColor)
            setPadding(0, dp(4), 0, dp(8))
        })
        val clearSitePermissionsButton = createListButton(
            R.id.buttonClearSitePermissions,
            context.getString(R.string.settings_clear_site_permissions),
            R.drawable.lock_reset_24px
        )
        val clearHttpHostsButton = createListButton(
            R.id.buttonClearHttpHosts,
            context.getString(R.string.settings_clear_http_hosts),
            R.drawable.security_24px
        )
        val clearCookiesButton = createListButton(
            R.id.buttonClearCookies,
            context.getString(R.string.settings_clear_cookies),
            R.drawable.delete_forever_24px
        )
        siteDataInner.addView(clearSitePermissionsButton)
        siteDataInner.addView(clearHttpHostsButton)
        siteDataInner.addView(clearCookiesButton)
        siteDataCard.addView(siteDataInner)
        container.addView(siteDataCard)

        val licenseCard = createStyledCard()
        val licenseInner = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(16))
        }
        licenseInner.addView(createSectionTitle(context.getString(R.string.settings_license), R.drawable.gplv3, iconWidthDp = 48, iconHeightDp = 24, tintIcon = false, bottomPaddingDp = 8))
        licenseInner.addView(ImageView(context).apply {
            setImageResource(R.drawable.gplv3)
            contentDescription = "GPLv3"
            adjustViewBounds = true
            layoutParams = LinearLayout.LayoutParams(dp(96), dp(48)).apply { bottomMargin = dp(8) }
            scaleType = ImageView.ScaleType.FIT_START
        })
        licenseInner.addView(TextView(context).apply {
            text = context.getString(R.string.settings_license_description)
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
            setTextColor(onSurfaceColor)
            setPadding(0, 0, 0, dp(8))
        })
        licenseInner.addView(TextView(context).apply {
            text = context.getString(R.string.settings_license_fork_notice)
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
            setTextColor(onSurfaceColor)
            setPadding(0, 0, 0, dp(8))
        })
        val viewKododakeButton = createListButton(R.id.ViewKododakeButton, context.getString(R.string.kododake_name), R.drawable.ic_github)
        val viewLicenseButton = createListButton(R.id.viewLicenseButton, context.getString(R.string.settings_license), R.drawable.info_24px)
        val viewOssLicensesButton = createListButton(R.id.viewOssLicensesButton, context.getString(R.string.open_source_view_licenses), R.drawable.search_24px)
        val viewAabrowserButton = createListButton(View.generateViewId(), context.getString(R.string.settings_license_original_project), R.drawable.ic_github)
        val viewDashDeckSourceButton = createListButton(View.generateViewId(), context.getString(R.string.settings_license_dashdeck_source), R.drawable.ic_github)
        licenseInner.addView(viewKododakeButton)
        licenseInner.addView(viewAabrowserButton)
        licenseInner.addView(viewDashDeckSourceButton)
        licenseInner.addView(viewLicenseButton)
        licenseInner.addView(viewOssLicensesButton)
        licenseCard.addView(licenseInner)
        container.addView(licenseCard)

        backBtn.setOnClickListener { callbacks.onClose() }

        fun openUrl(url: String) {
            try {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
            } catch (_: Exception) {
                Toast.makeText(context, R.string.error_generic_message, Toast.LENGTH_SHORT).show()
            }
        }

        viewKododakeButton.setOnClickListener { openUrl("https://github.com/kododake") }
        viewAabrowserButton.setOnClickListener { openUrl("https://github.com/kododake/AABrowser") }
        viewDashDeckSourceButton.setOnClickListener { openUrl(org.dashdeck.app.AppConstants.GITHUB_REPO_URL) }
        viewLicenseButton.setOnClickListener { openUrl("https://www.gnu.org/licenses/gpl-3.0.html") }
        viewOssLicensesButton.setOnClickListener {
            try {
                val activityClass = Class.forName("com.google.android.gms.oss.licenses.OssLicensesMenuActivity")
                val intent = Intent(context, activityClass)
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
            } catch (_: Exception) {
                Toast.makeText(context, R.string.error_generic_message, Toast.LENGTH_SHORT).show()
            }
        }

        clearSitePermissionsButton.setOnClickListener {
            showConfirmationDialog(
                title = context.getString(R.string.settings_clear_site_permissions_title),
                message = context.getString(R.string.settings_clear_site_permissions_message)
            ) {
                BrowserPreferences.clearSavedSitePermissions(context)
                android.webkit.GeolocationPermissions.getInstance().clearAll()
                android.webkit.WebView.clearClientCertPreferences(null)
                org.dashdeck.app.web.SslErrorHandlerHelper.clearAllowedSslHosts()
                showSuccessDialog(
                    title = context.getString(R.string.settings_clear_site_permissions_success_title),
                    message = context.getString(R.string.settings_clear_site_permissions_success_message)
                )
            }
        }

        clearHttpHostsButton.setOnClickListener {
            showConfirmationDialog(
                title = context.getString(R.string.settings_clear_http_hosts_title),
                message = context.getString(R.string.settings_clear_http_hosts_message)
            ) {
                BrowserPreferences.clearAllowedCleartextHosts(context)
                showSuccessDialog(
                    title = context.getString(R.string.settings_clear_http_hosts_success_title),
                    message = context.getString(R.string.settings_clear_http_hosts_success_message)
                )
            }
        }

        clearCookiesButton.setOnClickListener {
            showConfirmationDialog(
                title = context.getString(R.string.settings_clear_cookies_title),
                message = context.getString(R.string.settings_clear_cookies_message)
            ) {
                WebStorage.getInstance().deleteAllData()
                WebViewDatabase.getInstance(context).apply {
                    clearHttpAuthUsernamePassword()
                }
                runCatching { context.deleteDatabase("webview.db") }
                runCatching { context.deleteDatabase("webviewCache.db") }
                runCatching {
                    val webViewCacheDir = java.io.File(context.cacheDir, "org.chromium.android_webview")
                    if (webViewCacheDir.exists()) {
                        webViewCacheDir.deleteRecursively()
                    }
                }
                val cookieManager = CookieManager.getInstance()
                cookieManager.removeAllCookies {
                    cookieManager.flush()
                    showSuccessDialog(
                        title = context.getString(R.string.settings_clear_cookies_success_title),
                        message = context.getString(R.string.settings_clear_cookies_success_message)
                    )
                }
            }
        }

        return container
    }

    fun createSettingsActivityView(context: Context): View = createSettingsContent(
        context = context,
        includeDragHandle = false,
        callbacks = SettingsCallbacks(
            onClose = { (context as? android.app.Activity)?.finish() },
            onThemeChanged = { origin, toDark ->
                (context as? android.app.Activity)?.let { org.dashdeck.app.ui.ThemeTransition.start(it, origin, toDark) }
            },
            onPageDarkeningChanged = { (context as? android.app.Activity)?.recreate() },
            onScaleChanged = { (context as? android.app.Activity)?.recreate() },
            onHomePageChanged = { (context as? android.app.Activity)?.recreate() }
        )
    )
}
