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
        }

        render()
        view.shieldsSettings.setOnClickListener {
            sheet.dismiss()
            onOpenSettings()
        }
        sheet.setContentView(view.root)
        sheet.show()
    }
}
