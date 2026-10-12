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

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import org.dashdeck.app.R
import org.dashdeck.app.bookmarks.BookmarkManager
import org.dashdeck.app.data.BrowserPreferences
import org.dashdeck.app.databinding.ActivityMainBinding
import org.dashdeck.app.settings.SettingsCallbacks
import org.dashdeck.app.settings.SettingsViews
import org.dashdeck.app.startpage.StartPageManager
import org.dashdeck.app.tabs.TabManager
import org.dashdeck.app.web.updatePageDarkening

class OverlayManager(
    private val activity: AppCompatActivity,
    private val binding: ActivityMainBinding,
    private val tabManager: TabManager,
    private val bookmarkManager: BookmarkManager,
    private val startPageManager: StartPageManager,
    private val uiManager: BrowserUIManager,
    private val callbacks: OverlayCallbacks
) {

    interface OverlayCallbacks {
        fun onRecreateRequested()
        fun onHomePageChanged()
        fun onPickBackgroundRequested()
        fun onVersionInfoReceived(latestUrl: String, tagName: String)
    }

    fun showQrCodeView(url: String) {
        if (url.isBlank()) {
            return
        }
        
        hideAllOverlays()
        binding.qrCodeViewRoot.visibility = View.VISIBLE
        binding.qrCodeImage.setImageBitmap(null)
        binding.qrCodeUrl.text = url
        
        activity.lifecycleScope.launch {
            val bitmap = QRUtils.generateQrCodeAsync(url)
            if (bitmap != null) {
                binding.qrCodeImage.setImageBitmap(bitmap)
            }
        }
    }

    fun hideQrCodeView() {
        binding.qrCodeViewRoot.visibility = View.GONE
        binding.menuScroll.visibility = View.VISIBLE
    }

    fun showSettingsView() {
        hideAllOverlays()
        binding.settingsViewRoot.visibility = View.VISIBLE
        ensureSettingsContentPopulated()
    }

    fun hideSettingsView() {
        binding.settingsViewRoot.visibility = View.GONE
        binding.menuScroll.visibility = View.VISIBLE
    }

    fun showCheckLatestView() {
        hideAllOverlays()
        binding.checkLatestViewRoot.visibility = View.VISIBLE
        binding.checkLatestProgressIndicator.visibility = View.VISIBLE
        
        binding.checkLatestLatestVersion.text = activity.getString(R.string.menu_checking_latest)
        binding.checkLatestLatestVersion.setTextColor(getColorFromAttr(com.google.android.material.R.attr.colorOnSurface))
        binding.checkLatestOpenReleaseButton.visibility = View.GONE

        val packageName = activity.packageName
        binding.checkLatestInstalledVersion.text = activity.getString(
            R.string.installed_version_label, 
            "v${org.dashdeck.app.BuildConfig.VERSION_NAME}"
        )
        
        fetchLatestVersion()
    }

    fun hideCheckLatestView() {
        binding.checkLatestViewRoot.visibility = View.GONE
        binding.menuScroll.visibility = View.VISIBLE
    }

    private fun hideAllOverlays() {
        val views = listOf(
            binding.menuScroll,
            binding.bookmarkManagerRoot,
            binding.tabManagerRoot,
            binding.checkLatestViewRoot,
            binding.settingsViewRoot,
            binding.qrCodeViewRoot
        )
        views.forEach { 
            it.visibility = View.GONE 
        }
    }

    private fun ensureSettingsContentPopulated() {
        if (binding.settingsContentContainer.childCount > 0) {
            return
        }
        
        try {
            val settingsCallbacks = SettingsCallbacks(
                onClose = { 
                    hideSettingsView() 
                },
                onThemeChanged = { origin, toDark ->
                    org.dashdeck.app.ui.ThemeTransition.start(activity, origin, toDark)
                },
                onPageDarkeningChanged = {
                    val enabled = BrowserPreferences.isBetaForceDarkPagesEnabled(activity)
                    tabManager.browserTabs.forEach { tab ->
                        tab.webView.updatePageDarkening(enabled)
                    }
                },
                onScaleChanged = { 
                    callbacks.onRecreateRequested() 
                },
                onHomePageChanged = { 
                    callbacks.onHomePageChanged() 
                },
                onInAppControlsChanged = {
                    uiManager.applyPersistentAddressBarPreference()
                    uiManager.applyQuickActionButtonPreferences()
                },
                onPickStartPageBackground = {
                    callbacks.onPickBackgroundRequested()
                },
                onClearStartPageBackground = { 
                    startPageManager.clearStartPageBackground() 
                }
            )
            
            val contentView = SettingsViews.createSettingsContent(activity, false, settingsCallbacks)
            binding.settingsContentContainer.addView(contentView)
        } catch (e: Exception) {
            android.util.Log.e("DashDeck", "Could not build settings", e)
        }
    }

    /** Compares the installed version with the latest GitHub release. */
    private fun fetchLatestVersion() {
        Thread {
            val release = runCatching {
                val conn = java.net.URL("https://api.github.com/repos/${org.dashdeck.app.BuildConfig.GITHUB_REPO}/releases/latest")
                    .openConnection() as java.net.HttpURLConnection
                conn.setRequestProperty("Accept", "application/vnd.github+json")
                conn.connectTimeout = 10_000
                conn.readTimeout = 10_000
                if (conn.responseCode != 200) return@runCatching null
                org.json.JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            }.getOrNull()
            activity.runOnUiThread {
                binding.checkLatestProgressIndicator.visibility = View.GONE
                if (release == null) {
                    binding.checkLatestLatestVersion.text = activity.getString(R.string.check_latest_failed)
                    return@runOnUiThread
                }
                val tag = release.optString("tag_name")
                val latest = tag.removePrefix("v")
                val installed = org.dashdeck.app.BuildConfig.VERSION_NAME.removePrefix("v")
                val newer = org.dashdeck.app.web.compareVersions(latest, installed) > 0
                binding.checkLatestLatestVersion.text = activity.getString(
                    if (newer) R.string.check_latest_update_available else R.string.check_latest_up_to_date,
                    if (newer) tag else latest
                )
                binding.checkLatestLatestVersion.setTextColor(
                    getColorFromAttr(if (newer) androidx.appcompat.R.attr.colorPrimary else com.google.android.material.R.attr.colorOnSurfaceVariant)
                )
                binding.checkLatestOpenReleaseButton.visibility = if (newer) View.VISIBLE else View.GONE
                callbacks.onVersionInfoReceived(release.optString("html_url", org.dashdeck.app.AppConstants.RELEASES_URL), tag)
            }
        }.start()
    }

    private fun getColorFromAttr(attrResId: Int): Int {
        val tv = android.util.TypedValue()
        if (activity.theme.resolveAttribute(attrResId, tv, true)) {
            if (tv.resourceId != 0) {
                return androidx.core.content.ContextCompat.getColor(activity, tv.resourceId)
            }
            return tv.data
        }
        return android.graphics.Color.TRANSPARENT
    }
}
