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

package org.dashdeck.app.web

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request
import org.dashdeck.app.data.BrowserPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * Updates DashDeck Shields safely.
 *
 * Filter lists are downloaded into a staging folder, a candidate engine is built
 * from them and run through the native self-test suite, and only a candidate that
 * passes every test replaces the running engine. Anything else is discarded and
 * the current engine keeps running.
 *
 * The adblock-rust engine itself is native code compiled into the APK; Android
 * apps must not download and execute native code, so a newer engine release is
 * reported here and delivered as an app update.
 */
object ShieldsUpdater {
    private const val TAG = "DashDeckShields"
    private val AUTO_UPDATE_INTERVAL_MS = TimeUnit.DAYS.toMillis(4)
    private const val MIN_LIST_BYTES = 10_000
    private const val ENGINE_TAGS_URL = "https://api.github.com/repos/brave/adblock-rust/tags?per_page=20"

    data class CheckResult(
        val changedLists: List<String>,
        val engineVersion: String,
        /** Signed plugin newer than the running engine, ready to install. */
        val enginePlugin: ShieldsEnginePlugin.Manifest?,
        /** Newest adblock-rust release published by Brave. */
        val upstreamEngineVersion: String?,
        val engineFeedError: String?,
        val error: String?
    ) {
        val listsUpdateAvailable get() = changedLists.isNotEmpty()
        val engineUpdateAvailable get() = enginePlugin != null
        /** Brave has released an engine that no signed DashDeck plugin carries yet. */
        val upstreamAhead get() = upstreamEngineVersion != null &&
            compareVersions(upstreamEngineVersion, enginePlugin?.engineVersion ?: engineVersion) > 0
        val anythingToInstall get() = listsUpdateAvailable || engineUpdateAvailable
    }

    data class TestResult(val name: String, val passed: Boolean)

    data class InstallResult(
        val installed: Boolean,
        val tests: List<TestResult>,
        val rules: Int,
        val error: String?,
        /** A new engine was installed; it is used from the next app start. */
        val engineInstalled: String? = null,
        val engineError: String? = null,
        val engineTests: List<TestResult> = emptyList(),
    )

    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "DashDeckShieldsUpdate").apply { priority = Thread.MIN_PRIORITY } }
    private val main = Handler(Looper.getMainLooper())
    private val client by lazy { OkHttpClient.Builder().callTimeout(60, TimeUnit.SECONDS).build() }

    fun listsDir(context: Context) = File(context.filesDir, "shields/lists")
    private fun stagingDir(context: Context) = File(context.filesDir, "shields/staging")

    /** Downloads the latest lists into staging and reports what changed. */
    fun check(context: Context, onResult: (CheckResult) -> Unit) {
        val app = context.applicationContext
        worker.execute {
            val result = checkBlocking(app)
            main.post { onResult(result) }
        }
    }

    /** Validates the staged lists and installs them only if every self-test passes. */
    fun install(context: Context, onResult: (InstallResult) -> Unit) {
        val app = context.applicationContext
        worker.execute {
            val result = installBlocking(app)
            main.post { onResult(result) }
        }
    }

    /** Background refresh every few days, through the same tested install path. */
    fun updateIfStale(context: Context) {
        val app = context.applicationContext
        if (System.currentTimeMillis() - BrowserPreferences.getShieldsListsUpdatedAt(app) < AUTO_UPDATE_INTERVAL_MS) return
        worker.execute {
            val check = checkBlocking(app)
            if (check.anythingToInstall) {
                val install = installBlocking(app)
                Log.i(TAG, "Automatic update: installed=${install.installed} error=${install.error}")
            }
        }
    }

    private fun checkBlocking(context: Context): CheckResult {
        val staging = stagingDir(context).apply { deleteRecursively(); mkdirs() }
        val changed = mutableListOf<String>()
        var error: String? = null
        for (list in BraveAdblockNative.FILTER_LISTS) {
            val text = runCatching { download(list.url) }.getOrNull()
            if (text == null || !looksLikeFilterList(text)) {
                error = "Could not download ${list.file}"
                continue
            }
            if (text != BraveAdblockNative.readList(context, list.file)) {
                File(staging, list.file).writeText(text)
                changed += list.file
            }
        }
        val running = BraveAdblockNative.activeEngineVersion
        var feedError: String? = null
        val plugin = if (ShieldsEnginePlugin.isFeedConfigured) {
            runCatching { ShieldsEnginePlugin.fetchManifest() }
                .onFailure { feedError = it.message }
                .getOrNull()
                ?.takeIf { compareVersions(it.engineVersion, running) > 0 }
        } else null
        val upstream = runCatching { latestEngineRelease() }
            .onFailure { Log.w(TAG, "Could not check adblock-rust releases", it) }
            .getOrNull()
        if (error == null) BrowserPreferences.setShieldsListsUpdatedAt(context, System.currentTimeMillis())
        pendingPlugin = plugin
        return CheckResult(changed, running, plugin, upstream, feedError, error)
    }

    @Volatile private var pendingPlugin: ShieldsEnginePlugin.Manifest? = null

    private fun installBlocking(context: Context): InstallResult {
        val engine = pendingPlugin?.let { installEngine(context, it) }
        pendingPlugin = null
        return installLists(context).copy(
            engineInstalled = engine?.first,
            engineError = engine?.second,
            engineTests = engine?.third.orEmpty(),
        )
    }

    /**
     * Download → verify signature and hash → self-test in an isolated process →
     * activate. Returns (installed version, error, tests). Never touches the
     * running engine; a failed candidate is simply discarded.
     */
    private fun installEngine(context: Context, manifest: ShieldsEnginePlugin.Manifest): Triple<String?, String?, List<TestResult>> {
        return try {
            val lib = ShieldsEnginePlugin.downloadPlugin(context, manifest)
            val report = JSONObject(ShieldsProbeService.probe(context, lib.absolutePath))
            val tests = report.optJSONArray("tests").toTests()
            if (!report.optBoolean("passed")) {
                ShieldsEnginePlugin.discardStaged(context)
                val reason = report.optString("error").ifBlank { "Engine ${manifest.engineVersion} failed ${tests.count { !it.passed }} safety test(s)." }
                Log.w(TAG, "Engine plugin rejected: $reason")
                return Triple(null, reason, tests)
            }
            ShieldsEnginePlugin.activateStaged(context)
            Log.i(TAG, "Engine plugin ${manifest.engineVersion} passed all tests; active from next start")
            Triple(manifest.engineVersion, null, tests)
        } catch (t: Throwable) {
            ShieldsEnginePlugin.discardStaged(context)
            Log.w(TAG, "Engine plugin install failed", t)
            Triple(null, t.message ?: "The engine could not be installed.", emptyList())
        }
    }

    private fun installLists(context: Context): InstallResult {
        val staging = stagingDir(context)
        val candidate = BraveAdblockNative.FILTER_LISTS.map { list ->
            File(staging, list.file).takeIf { it.isFile }?.readText()
                ?: BraveAdblockNative.readList(context, list.file)
        }
        val report = BraveAdblockNative.validate(context, candidate)
            ?: return InstallResult(false, emptyList(), 0, "The filter engine could not be loaded.").also { staging.deleteRecursively() }

        val json = JSONObject(report)
        val tests = json.optJSONArray("tests").toTests()
        val rules = json.optInt("rules")
        if (!json.optBoolean("passed")) {
            staging.deleteRecursively()
            Log.w(TAG, "Update rejected; keeping the current engine. ${tests.filterNot { it.passed }}")
            return InstallResult(false, tests, rules, "The new filters failed ${tests.count { !it.passed }} safety test(s).")
        }

        // Commit: move staged lists into place, then rebuild the live engine from them.
        val target = listsDir(context).apply { mkdirs() }
        staging.listFiles()?.forEach { file ->
            val dest = File(target, file.name)
            if (!file.renameTo(dest)) file.copyTo(dest, overwrite = true)
        }
        staging.deleteRecursively()
        BraveAdblockNative.rebuild(context)
        Log.i(TAG, "Update installed: $rules rules, ${tests.size} tests passed")
        return InstallResult(true, tests, rules, null)
    }

    private fun JSONArray?.toTests(): List<TestResult> {
        if (this == null) return emptyList()
        return (0 until length()).map { i ->
            val t = getJSONObject(i)
            TestResult(t.optString("name"), t.optBoolean("passed"))
        }
    }

    private fun download(url: String): String? {
        client.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (!response.isSuccessful) return null
            return response.body.string()
        }
    }

    private fun latestEngineRelease(): String? {
        val body = download(ENGINE_TAGS_URL) ?: return null
        val tags = JSONArray(body)
        return (0 until tags.length())
            .map { tags.getJSONObject(it).optString("name").removePrefix("v") }
            .filter { it.matches(Regex("""\d+(\.\d+)*""")) }
            .maxWithOrNull(::compareVersions)
    }

    private fun looksLikeFilterList(text: String): Boolean {
        if (text.length < MIN_LIST_BYTES) return false
        val head = text.trimStart().take(64)
        return head.startsWith("[Adblock") || head.startsWith("!")
    }
}
