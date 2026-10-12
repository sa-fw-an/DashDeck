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

package org.dashdeck.app.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.dashdeck.app.R
import org.dashdeck.app.web.ShieldsUpdater

/** Check → Install → self-test → commit or roll back, with the results shown to the user. */
class ShieldsUpdateDialog(private val context: Context) {
    private val density = context.resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()

    private val progress = ProgressBar(context).apply { isIndeterminate = true }
    private fun themeColor(attr: Int): Int {
        val value = android.util.TypedValue()
        context.theme.resolveAttribute(attr, value, true)
        return value.data
    }
    private val status = TextView(context).apply {
        setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyLarge)
        setTextColor(themeColor(com.google.android.material.R.attr.colorOnSurface))
    }
    private val details = TextView(context).apply {
        setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
        setTextColor(themeColor(com.google.android.material.R.attr.colorOnSurfaceVariant))
        setPadding(0, dp(12), 0, 0)
    }
    private lateinit var dialog: AlertDialog
    private var report = ""

    fun show() {
        val body = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), 0)
            addView(progress, LinearLayout.LayoutParams(dp(32), dp(32)).apply { bottomMargin = dp(12) })
            addView(status)
            addView(details)
        }
        dialog = MaterialAlertDialogBuilder(context)
            .setTitle(R.string.shields_update_title)
            .setView(ScrollView(context).apply { addView(body) })
            .setPositiveButton(R.string.shields_update_install, null)
            .setNegativeButton(R.string.shields_update_close, null)
            .setNeutralButton(R.string.shields_update_copy_report, null)
            .show()
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).apply {
            visibility = View.GONE
            setOnClickListener { copyReport() }
        }
        check()
    }

    private fun setBusy(message: Int) {
        progress.visibility = View.VISIBLE
        status.setText(message)
        details.text = ""
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).visibility = View.GONE
    }

    private fun check() {
        setBusy(R.string.shields_update_checking)
        ShieldsUpdater.check(context) { result ->
            if (!dialog.isShowing) return@check
            progress.visibility = View.GONE
            val lines = mutableListOf<String>()
            lines += when {
                result.enginePlugin != null -> context.getString(
                    R.string.shields_update_engine_plugin_available, result.engineVersion, result.enginePlugin.engineVersion
                )
                else -> context.getString(R.string.shields_update_engine_current, result.engineVersion)
            }
            if (result.upstreamAhead) {
                lines += context.getString(R.string.shields_update_engine_upstream, result.upstreamEngineVersion)
            }
            result.engineFeedError?.let { lines += it }
            result.error?.let { lines += it }
            details.text = lines.joinToString("\n\n")

            val install = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            if (result.anythingToInstall) {
                status.text = when {
                    result.engineUpdateAvailable && result.listsUpdateAvailable ->
                        context.getString(R.string.shields_update_engine_and_lists)
                    result.engineUpdateAvailable -> context.getString(R.string.shields_update_engine_only)
                    else -> context.resources.getQuantityString(
                        R.plurals.shields_update_lists_available, result.changedLists.size, result.changedLists.size
                    )
                }
                install.visibility = View.VISIBLE
                install.setOnClickListener { install() }
            } else {
                status.setText(if (result.error == null) R.string.shields_update_up_to_date else R.string.shields_update_check_failed)
            }
        }
    }

    private fun install() {
        setBusy(R.string.shields_update_installing)
        ShieldsUpdater.install(context) { result ->
            if (!dialog.isShowing) return@install
            progress.visibility = View.GONE
            fun checklist(tests: List<ShieldsUpdater.TestResult>) =
                tests.joinToString("\n") { (if (it.passed) "✓  " else "✗  ") + it.name }
            val sections = mutableListOf<String>()
            if (result.engineTests.isNotEmpty() || result.engineError != null || result.engineInstalled != null) {
                sections += context.getString(R.string.shields_update_engine_tests) + "\n" + checklist(result.engineTests)
            }
            if (result.tests.isNotEmpty()) {
                sections += context.getString(R.string.shields_update_filter_tests) + "\n" + checklist(result.tests)
            }
            report = buildString {
                appendLine("DashDeck Shields update report")
                appendLine("Running engine: adblock-rust ${org.dashdeck.app.web.BraveAdblockNative.activeEngineVersion}")
                result.engineInstalled?.let { appendLine("Installed engine: $it") }
                result.engineError?.let { appendLine("Engine error: $it") }
                appendLine("Rules: ${result.rules}")
                result.error?.let { appendLine("Filter error: $it") }
                append(sections.joinToString("\n\n"))
            }

            val failed = result.engineError != null || !result.installed
            val messages = mutableListOf<String>()
            status.text = when {
                !failed -> context.getString(R.string.shields_update_installed, result.rules)
                result.installed -> context.getString(R.string.shields_update_filters_only, result.rules)
                else -> context.getString(R.string.shields_update_rolled_back)
            }
            result.engineInstalled?.let { messages += context.getString(R.string.shields_update_engine_restart, it) }
            result.engineError?.let { messages += it }
            result.error?.let { messages += it }
            messages += sections
            if (failed) {
                messages += context.getString(R.string.shields_update_contact_developer)
                dialog.getButton(AlertDialog.BUTTON_NEUTRAL).visibility = View.VISIBLE
            }
            details.text = messages.joinToString("\n\n")
        }
    }

    private fun copyReport() {
        context.getSystemService(ClipboardManager::class.java)
            .setPrimaryClip(ClipData.newPlainText("DashDeck Shields report", report))
        Toast.makeText(context, R.string.shields_update_report_copied, Toast.LENGTH_SHORT).show()
    }
}
