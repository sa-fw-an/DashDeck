/*
 * Copyright (C) 2026 Safwan Sayeed (https://github.com/sa-fw-an/DashDeck)
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
import android.net.Uri
import android.view.LayoutInflater
import androidx.core.view.isVisible
import com.google.android.material.bottomsheet.BottomSheetDialog
import org.dashdeck.app.R
import org.dashdeck.app.data.BrowserPreferences
import org.dashdeck.app.databinding.SheetShieldsBinding
import org.dashdeck.app.web.AdBlocker

/** Per-site Shields controls, opened from the shield in the top bar. */
object ShieldsPanel {
    fun show(context: Context, pageUrl: String?, onSiteToggled: () -> Unit, onOpenSettings: () -> Unit) {
        val host = pageUrl?.let { Uri.parse(it).host }?.lowercase()?.removePrefix("www.")
        val sheet = BottomSheetDialog(context)
        val view = SheetShieldsBinding.inflate(LayoutInflater.from(context))
        val globallyOn = BrowserPreferences.isShieldsEnabled(context)

        fun render() {
            val siteOn = globallyOn && host != null && host !in BrowserPreferences.getShieldsDisabledHosts(context)
            view.shieldsSiteTitle.text = when {
                host == null -> context.getString(R.string.shields_panel_title)
                siteOn -> context.getString(R.string.shields_panel_site_on, host)
                else -> context.getString(R.string.shields_panel_site_off, host)
            }
            view.shieldsBlocked.text = context.getString(R.string.shields_panel_blocked, AdBlocker.blockedCount(pageUrl))
            view.shieldsBlocked.isVisible = siteOn
            view.shieldsGlobalOff.isVisible = !globallyOn
            view.shieldsSiteSwitch.isEnabled = globallyOn && host != null
            view.shieldsSiteSwitch.setOnCheckedChangeListener(null)
            view.shieldsSiteSwitch.isChecked = siteOn
            view.shieldsSiteSwitch.setOnCheckedChangeListener { _, checked ->
                host ?: return@setOnCheckedChangeListener
                BrowserPreferences.setShieldsDisabledForHost(context, host, !checked)
                render()
                onSiteToggled()
            }

            val blocksCookies = !BrowserPreferences.allowsThirdPartyCookies(context, host)
            view.shieldsCookiesSwitch.isEnabled = host != null && BrowserPreferences.isBlockThirdPartyCookies(context)
            view.shieldsCookiesHint.setText(
                if (BrowserPreferences.isBlockThirdPartyCookies(context)) R.string.shields_panel_cookies_hint
                else R.string.shields_panel_cookies_off_globally
            )
            view.shieldsCookiesSwitch.setOnCheckedChangeListener(null)
            view.shieldsCookiesSwitch.isChecked = blocksCookies
            view.shieldsCookiesSwitch.setOnCheckedChangeListener { _, checked ->
                host ?: return@setOnCheckedChangeListener
                BrowserPreferences.setThirdPartyCookiesAllowedForHost(context, host, !checked)
                render()
                onSiteToggled()
            }
            view.shieldsClearCookies.isEnabled = pageUrl != null
        }

        render()
        view.shieldsClearCookies.setOnClickListener {
            pageUrl ?: return@setOnClickListener
            org.dashdeck.app.web.clearCookiesFor(pageUrl)
            android.widget.Toast.makeText(context, context.getString(R.string.shields_panel_cookies_cleared, host), android.widget.Toast.LENGTH_SHORT).show()
            onSiteToggled()
        }
        view.shieldsSettings.setOnClickListener {
            sheet.dismiss()
            onOpenSettings()
        }
        sheet.setContentView(view.root)
        sheet.show()
    }
}
