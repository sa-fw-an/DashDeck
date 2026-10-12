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

package org.dashdeck.app.web

import android.app.Activity
import android.net.Uri
import android.net.http.SslError
import android.webkit.SslErrorHandler
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.dashdeck.app.R

object SslErrorHandlerHelper {

    private val allowedSslHosts = HashSet<String>()

    fun handleSslError(activity: Activity, handler: SslErrorHandler, error: SslError) {
        val url = error.url ?: ""
        val host = runCatching { Uri.parse(url).host?.lowercase() }.getOrNull()

        if (host != null && allowedSslHosts.contains(host)) {
            handler.proceed()
            return
        }

        if (activity.isFinishing || activity.isDestroyed) {
            handler.cancel()
            return
        }

        val primaryError = error.primaryError
        val errorDescription = getSslErrorDescription(activity, primaryError)
        val hostLabel = host ?: url

        val view = activity.layoutInflater.inflate(R.layout.dialog_cleartext_confirmation, null)
        val titleView = view.findViewById<android.widget.TextView>(R.id.cleartext_title)
        val messageView = view.findViewById<android.widget.TextView>(R.id.cleartext_message)
        val hostContainer = view.findViewById<android.view.View>(R.id.cleartext_host_container)
        val hostLabelView = view.findViewById<android.widget.TextView>(R.id.cleartext_host_label)
        val hostValueView = view.findViewById<android.widget.TextView>(R.id.cleartext_host_value)
        val detailView = view.findViewById<android.widget.TextView>(R.id.cleartext_detail)
        val cancelButton = view.findViewById<com.google.android.material.button.MaterialButton>(R.id.btn_cancel_dialog)
        val allowOnceButton = view.findViewById<com.google.android.material.button.MaterialButton>(R.id.btn_allow_once)
        val allowHostButton = view.findViewById<com.google.android.material.button.MaterialButton>(R.id.btn_allow_host)

        val dialog = MaterialAlertDialogBuilder(activity, com.google.android.material.R.style.ThemeOverlay_Material3_MaterialAlertDialog)
            .setView(view)
            .setCancelable(false)
            .create()

        titleView.text = activity.getString(R.string.ssl_error_title)
        messageView.text = activity.getString(R.string.ssl_error_message, hostLabel)

        hostContainer.visibility = android.view.View.VISIBLE
        hostLabelView.text = activity.getString(R.string.location_access_host_label)
        hostValueView.text = hostLabel

        detailView.visibility = android.view.View.VISIBLE
        detailView.text = activity.getString(R.string.ssl_error_reason_prefix) + " " + errorDescription

        cancelButton.text = activity.getString(R.string.ssl_error_cancel)
        allowOnceButton.text = activity.getString(R.string.ssl_error_proceed)
        allowHostButton.visibility = android.view.View.GONE
        cancelButton.setOnClickListener {
            try { dialog.dismiss() } catch (_: Exception) {}
            handler.cancel()
        }

        allowOnceButton.setOnClickListener {
            try { dialog.dismiss() } catch (_: Exception) {}
            if (host != null) {
                allowedSslHosts.add(host)
            }
            handler.proceed()
        }

        try {
            dialog.show()
            val width = (activity.resources.displayMetrics.widthPixels * 0.9).toInt()
            dialog.window?.setLayout(width, android.view.WindowManager.LayoutParams.WRAP_CONTENT)
        } catch (_: Exception) {
            handler.cancel()
        }
    }

    private fun getSslErrorDescription(activity: Activity, errorCode: Int): String {
        return when (errorCode) {
            SslError.SSL_EXPIRED -> activity.getString(R.string.ssl_error_expired)
            SslError.SSL_IDMISMATCH -> activity.getString(R.string.ssl_error_idmismatch)
            SslError.SSL_UNTRUSTED -> activity.getString(R.string.ssl_error_untrusted)
            SslError.SSL_NOTYETVALID -> activity.getString(R.string.ssl_error_notyetvalid)
            SslError.SSL_DATE_INVALID -> activity.getString(R.string.ssl_error_date_invalid)
            else -> activity.getString(R.string.ssl_error_invalid)
        }
    }

    fun clearAllowedSslHosts() {
        allowedSslHosts.clear()
    }
}
