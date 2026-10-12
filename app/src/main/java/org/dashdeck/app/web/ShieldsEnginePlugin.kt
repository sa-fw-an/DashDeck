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
import android.os.Build
import android.util.Base64
import android.util.Log
import java.io.File
import java.net.URI
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request
import org.dashdeck.app.BuildConfig
import org.json.JSONObject

/**
 * Signed, swappable Brave adblock-rust engine plugins.
 *
 * Trust chain: the manifest is signed with the developer's Ed25519 key (public half
 * compiled into the app), the manifest pins the plugin's SHA-256 and size, and the
 * plugin must pass [ShieldsProbeService]'s self-tests in a separate process before
 * it is activated. If the app then fails to start cleanly twice in a row with the
 * plugin, it falls back to the engine built into the APK.
 */
object ShieldsEnginePlugin {
    private const val TAG = "DashDeckShields"
    const val API_VERSION = 2
    private const val PREFS = "shields_engine_plugin"
    private const val KEY_BOOT_ATTEMPTS = "boot_attempts"
    private const val KEY_FAILED_VERSION = "failed_version"
    private const val MAX_FAILED_BOOTS = 2

    data class Manifest(
        val apiVersion: Int,
        val engineVersion: String,
        val abi: String,
        val file: String,
        val sha256: String,
        val size: Long,
        val signature: String,
        val sourceUrl: String,
    ) {
        /** Must match the payload signed by scripts/shields-engine-release.sh. */
        fun signedPayload() = "dashdeck-shields-engine|$apiVersion|$engineVersion|$abi|$file|$sha256|$size"

        fun toJson(): String = JSONObject()
            .put("apiVersion", apiVersion).put("engineVersion", engineVersion).put("abi", abi)
            .put("file", file).put("sha256", sha256).put("size", size)
            .put("signature", signature).put("sourceUrl", sourceUrl)
            .toString()

        companion object {
            fun parse(json: String, sourceUrl: String): Manifest {
                val o = JSONObject(json)
                return Manifest(
                    o.getInt("apiVersion"), o.getString("engineVersion"), o.getString("abi"),
                    o.getString("file"), o.getString("sha256").lowercase(), o.getLong("size"),
                    o.getString("signature"), o.optString("sourceUrl", sourceUrl),
                )
            }
        }
    }

    private val client by lazy { OkHttpClient.Builder().callTimeout(120, TimeUnit.SECONDS).build() }

    private fun dir(context: Context) = File(context.filesDir, "shields/engine")
    private fun activeDir(context: Context) = File(dir(context), "active")
    private fun stagedDir(context: Context) = File(dir(context), "staged")
    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    val isFeedConfigured get() = BuildConfig.SHIELDS_ENGINE_FEED.isNotBlank()

    /** The installed plugin, if any (whether or not it is in use). */
    fun activeManifest(context: Context): Manifest? {
        val file = File(activeDir(context), "manifest.json")
        if (!file.isFile) return null
        return runCatching { Manifest.parse(file.readText(), "") }.getOrNull()
    }

    /**
     * Plugin library to load at startup, or null for the built-in engine. Counts the
     * attempt; [markHealthy] clears it once the engine has run without crashing.
     */
    fun libraryToLoad(context: Context): File? {
        val manifest = activeManifest(context) ?: return null
        val lib = File(activeDir(context), manifest.file)
        if (!lib.isFile || manifest.apiVersion != API_VERSION || !verifyFile(lib, manifest)) {
            Log.w(TAG, "Installed engine plugin is unusable; using the built-in engine")
            revertToBuiltIn(context, failedVersion = manifest.engineVersion)
            return null
        }
        val attempts = prefs(context).getInt(KEY_BOOT_ATTEMPTS, 0)
        if (attempts >= MAX_FAILED_BOOTS) {
            Log.e(TAG, "Engine plugin ${manifest.engineVersion} did not start cleanly $attempts times; reverting")
            revertToBuiltIn(context, failedVersion = manifest.engineVersion)
            return null
        }
        prefs(context).edit().putInt(KEY_BOOT_ATTEMPTS, attempts + 1).commit()
        return lib
    }

    fun markHealthy(context: Context) {
        if (prefs(context).getInt(KEY_BOOT_ATTEMPTS, 0) != 0) {
            prefs(context).edit().putInt(KEY_BOOT_ATTEMPTS, 0).apply()
        }
    }

    /** Engine version that was rolled back automatically, to tell the user once. */
    fun consumeFailedVersion(context: Context): String? {
        val failed = prefs(context).getString(KEY_FAILED_VERSION, null) ?: return null
        prefs(context).edit().remove(KEY_FAILED_VERSION).apply()
        return failed
    }

    /** Called on the main thread when a plugin is rolled back automatically. */
    @Volatile var onReverted: ((Context) -> Unit)? = null

    fun revertToBuiltIn(context: Context, failedVersion: String? = null) {
        activeDir(context).deleteRecursively()
        prefs(context).edit()
            .putInt(KEY_BOOT_ATTEMPTS, 0)
            .apply { if (failedVersion != null) putString(KEY_FAILED_VERSION, failedVersion) }
            .commit()
        if (failedVersion != null) {
            android.os.Handler(android.os.Looper.getMainLooper()).post { onReverted?.invoke(context) }
        }
    }

    /** Fetches and verifies the feed's manifest. Blocks; throws with a readable message. */
    fun fetchManifest(): Manifest {
        check(isFeedConfigured) { "No engine update feed is configured for this build." }
        val feed = BuildConfig.SHIELDS_ENGINE_FEED
        val body = download(feed) ?: error("Could not download the engine manifest.")
        val manifest = Manifest.parse(String(body), feed)
        check(verifySignature(manifest)) { "The engine manifest signature is invalid. It was not published by the DashDeck developer." }
        check(manifest.apiVersion == API_VERSION) { "Engine ${manifest.engineVersion} needs a newer DashDeck version." }
        check(manifest.abi in Build.SUPPORTED_ABIS) { "Engine ${manifest.engineVersion} does not support this phone (${manifest.abi})." }
        check(!manifest.file.contains('/') && manifest.file.endsWith(".so")) { "Engine manifest names an invalid file." }
        return manifest
    }

    /** Downloads the plugin into staging and checks its size and hash. Blocks. */
    fun downloadPlugin(context: Context, manifest: Manifest): File {
        val staged = stagedDir(context).apply { deleteRecursively(); mkdirs() }
        val url = URI(manifest.sourceUrl).resolve(manifest.file).toString()
        val bytes = download(url) ?: error("Could not download engine ${manifest.engineVersion}.")
        val lib = File(staged, manifest.file)
        lib.writeBytes(bytes)
        check(verifyFile(lib, manifest)) {
            staged.deleteRecursively()
            "The downloaded engine does not match its signed checksum."
        }
        // Android 14+ only loads native code from files that are not writable.
        check(lib.setReadOnly()) { "Could not secure the downloaded engine." }
        File(staged, "manifest.json").writeText(manifest.toJson())
        return lib
    }

    /** Makes the staged plugin active for the next app start. */
    fun activateStaged(context: Context) {
        val staged = stagedDir(context)
        check(File(staged, "manifest.json").isFile) { "No staged engine to activate." }
        val active = activeDir(context)
        active.deleteRecursively()
        check(staged.renameTo(active)) { "Could not install the engine." }
        prefs(context).edit().putInt(KEY_BOOT_ATTEMPTS, 0).remove(KEY_FAILED_VERSION).commit()
    }

    fun discardStaged(context: Context) {
        stagedDir(context).deleteRecursively()
    }

    private fun verifySignature(manifest: Manifest): Boolean = runCatching {
        val key = KeyFactory.getInstance("Ed25519").generatePublic(
            X509EncodedKeySpec(Base64.decode(BuildConfig.SHIELDS_ENGINE_PUBLIC_KEY, Base64.DEFAULT))
        )
        Signature.getInstance("Ed25519").run {
            initVerify(key)
            update(manifest.signedPayload().toByteArray())
            verify(Base64.decode(manifest.signature, Base64.DEFAULT))
        }
    }.onFailure { Log.w(TAG, "Signature check failed", it) }.getOrDefault(false)

    private fun verifyFile(file: File, manifest: Manifest): Boolean {
        if (file.length() != manifest.size) return false
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) } == manifest.sha256
    }

    private fun download(url: String): ByteArray? {
        client.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (!response.isSuccessful) return null
            return response.body.bytes()
        }
    }
}
