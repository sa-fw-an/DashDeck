package org.dashdeck.app.web

import android.content.Context
import android.net.Uri
import android.util.Base64
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import org.dashdeck.app.data.BrowserPreferences

/**
 * Network interception boundary for DashDeck Shields, backed by Brave's
 * adblock-rust engine ([BraveAdblockNative]).
 */
object AdBlocker {
    /** Used only for the first second or two after launch, before the engine is ready. */
    private val bootstrapHosts = setOf(
        "doubleclick.net", "googlesyndication.com", "googleadservices.com",
        "adservice.google.com", "amazon-adsystem.com", "adnxs.com", "criteo.com",
        "taboola.com", "outbrain.com", "moatads.com", "scorecardresearch.com", "pubmatic.com"
    )

    private val blockedCounts = ConcurrentHashMap<String, AtomicInteger>()

    fun isEnabledFor(context: Context, pageUrl: String?): Boolean {
        if (!BrowserPreferences.isShieldsEnabled(context)) return false
        val host = pageUrl?.let { Uri.parse(it).host }?.lowercase()?.removePrefix("www.") ?: return true
        return host !in BrowserPreferences.getShieldsDisabledHosts(context)
    }

    /** Returns a replacement response when the request should not reach the network. */
    fun intercept(context: Context, request: WebResourceRequest, pageUrl: String?): WebResourceResponse? {
        if (request.isForMainFrame) return null
        val url = request.url
        val scheme = url.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") return null
        if (!isEnabledFor(context, pageUrl)) return null

        val source = pageUrl ?: url.toString()
        val verdict = if (BraveAdblockNative.isReady) {
            BraveAdblockNative.check(url.toString(), source, resourceType(request))
        } else {
            BraveAdblockNative.warmUp(context)
            val host = url.host?.lowercase()
            if (host != null && bootstrapHosts.any { host == it || host.endsWith(".$it") }) "" else null
        } ?: return null

        pageUrl?.let { blockedCounts.getOrPut(it) { AtomicInteger() }.incrementAndGet() }
        return if (verdict.startsWith("data:")) redirectResponse(verdict) else emptyResponse()
    }

    fun blockedCount(pageUrl: String?): Int = pageUrl?.let { blockedCounts[it]?.get() } ?: 0

    fun resetCount(pageUrl: String?) {
        if (blockedCounts.size > 64) blockedCounts.clear()
        pageUrl?.let { blockedCounts.remove(it) }
    }

    /**
     * WebView does not expose a request's destination, so infer the adblock
     * resource type from the Accept header and URL, the way Brave's iOS port does.
     */
    internal fun resourceType(request: WebResourceRequest): String {
        val accept = request.requestHeaders.entries
            .firstOrNull { it.key.equals("Accept", ignoreCase = true) }?.value.orEmpty()
        return resourceType(request.url.path.orEmpty(), accept)
    }

    internal fun resourceType(rawPath: String, accept: String): String {
        val path = rawPath.lowercase()
        return when {
            accept.startsWith("text/html") -> "sub_frame"
            accept.startsWith("text/css") || path.endsWith(".css") -> "stylesheet"
            accept.startsWith("image/") || IMAGE_EXT.any(path::endsWith) -> "image"
            path.endsWith(".js") || path.endsWith(".mjs") -> "script"
            FONT_EXT.any(path::endsWith) -> "font"
            MEDIA_EXT.any(path::endsWith) || accept.startsWith("video/") || accept.startsWith("audio/") -> "media"
            else -> "xmlhttprequest"
        }
    }

    private fun emptyResponse() = WebResourceResponse(
        "text/plain", "utf-8", 204, "No Content", CORS_HEADERS, ByteArrayInputStream(ByteArray(0))
    )

    private fun redirectResponse(dataUrl: String): WebResourceResponse {
        // data:<mime>[;base64],<payload>
        val comma = dataUrl.indexOf(',')
        val meta = dataUrl.substring(5, comma.coerceAtLeast(5))
        val payload = if (comma >= 0) dataUrl.substring(comma + 1) else ""
        val isBase64 = meta.endsWith(";base64")
        val mime = meta.removeSuffix(";base64").ifBlank { "text/plain" }
        val bytes = if (isBase64) Base64.decode(payload, Base64.DEFAULT) else Uri.decode(payload).toByteArray()
        return WebResourceResponse(mime, "utf-8", 200, "OK", CORS_HEADERS, ByteArrayInputStream(bytes))
    }

    private val CORS_HEADERS = mapOf("Access-Control-Allow-Origin" to "*")
    private val IMAGE_EXT = listOf(".png", ".jpg", ".jpeg", ".gif", ".webp", ".svg", ".ico", ".avif")
    private val FONT_EXT = listOf(".woff", ".woff2", ".ttf", ".otf", ".eot")
    private val MEDIA_EXT = listOf(".mp4", ".webm", ".m3u8", ".mp3", ".m4a", ".ts", ".mpd")
}
