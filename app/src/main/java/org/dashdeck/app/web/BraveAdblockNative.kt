package org.dashdeck.app.web

import android.content.Context
import android.util.Log
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Executors

/**
 * JNI bridge to Brave's adblock-rust engine.
 *
 * The engine is built once from the filter lists (bundled in assets, replaced by
 * fresher, self-tested downloads from [ShieldsUpdater]) and cached in serialized form, so
 * later launches load it without re-parsing every rule.
 */
object BraveAdblockNative {
    private const val TAG = "DashDeckShields"
    private const val ENGINE_FORMAT = 2 // bump when the native crate changes
    private const val HEALTHY_AFTER_CHECKS = 25

    /** adblock-rust release compiled into this build (native/brave-adblock/Cargo.toml). */
    const val ENGINE_VERSION = "0.13.3"

    /** Filter lists in load order; `trusted` lists may use uBO's trusted scriptlets. */
    internal data class FilterList(val file: String, val url: String, val trusted: Boolean)

    internal val FILTER_LISTS = listOf(
        FilterList("easylist.txt", "https://easylist.to/easylist/easylist.txt", false),
        FilterList("easyprivacy.txt", "https://easylist.to/easylist/easyprivacy.txt", false),
        FilterList("ublock-filters.txt", "https://ublockorigin.github.io/uAssets/filters/filters.txt", true),
        FilterList("ublock-unbreak.txt", "https://ublockorigin.github.io/uAssets/filters/unbreak.txt", true),
    )

    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "DashDeckShields").apply { priority = Thread.MIN_PRIORITY } }

    @Volatile private var libraryLoaded: Boolean? = null
    @Volatile var isReady = false
        private set

    /** Filter rules in the running engine, for display. */
    @Volatile var ruleCount = 0
        private set

    /** Called on the main thread whenever a (re)loaded engine becomes ready. */
    @Volatile var onReady: (() -> Unit)? = null
    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    private fun markReady(lists: List<String>) {
        ruleCount = lists.sumOf { list ->
            list.lineSequence().count { line -> line.isNotBlank() && !line.startsWith('!') && !line.startsWith('[') }
        }
        isReady = true
        main.post { onReady?.invoke() }
    }

    /** adblock-rust version actually running (a plugin's, or the built-in one). */
    @Volatile var activeEngineVersion: String = ENGINE_VERSION
        private set
    @Volatile var isPluginActive = false
        private set
    private var engineIdentity = "builtin-$ENGINE_VERSION"
    private val checksSinceStart = java.util.concurrent.atomic.AtomicInteger()
    private var appContext: Context? = null

    /**
     * Loads a verified engine plugin if one is installed and healthy, otherwise the
     * engine built into the APK. Only one can be loaded per process: both export
     * the same JNI symbols.
     */
    private fun ensureLibrary(context: Context): Boolean {
        libraryLoaded?.let { return it }
        synchronized(this) {
            libraryLoaded?.let { return it }
            appContext = context.applicationContext
            val plugin = ShieldsEnginePlugin.libraryToLoad(context)
            if (plugin != null) {
                val manifest = ShieldsEnginePlugin.activeManifest(context)
                val loaded = runCatching { System.load(plugin.absolutePath) }.isSuccess
                val compatible = loaded && runCatching { nativeApiVersion() == ShieldsEnginePlugin.API_VERSION }.getOrDefault(false)
                if (compatible) {
                    activeEngineVersion = runCatching { nativeEngineVersion() }.getOrNull() ?: manifest?.engineVersion.orEmpty()
                    isPluginActive = true
                    engineIdentity = "plugin-${manifest?.sha256}"
                    libraryLoaded = true
                    Log.i(TAG, "Using engine plugin adblock-rust $activeEngineVersion")
                    return true
                }
                Log.e(TAG, "Engine plugin could not be used; reverting to the built-in engine")
                ShieldsEnginePlugin.revertToBuiltIn(context, failedVersion = manifest?.engineVersion)
                if (loaded) {
                    // Its symbols now shadow the built-in library's; stay off until restart.
                    libraryLoaded = false
                    return false
                }
            }
            val ok = runCatching { System.loadLibrary("dashdeck_brave_adblock") }
                .onFailure { Log.e(TAG, "Native filter engine failed to load", it) }
                .isSuccess
            libraryLoaded = ok
            return ok
        }
    }

    /**
     * Runs in [ShieldsProbeService]'s process: loads [path] and self-tests it.
     * Returns the validation JSON with the plugin's reported versions added.
     */
    internal fun probe(context: Context, path: String): String {
        System.load(path)
        libraryLoaded = true
        val api = runCatching { nativeApiVersion() }.getOrNull()
        if (api != ShieldsEnginePlugin.API_VERSION) {
            return ShieldsProbeService.failure("Engine interface $api is not compatible with this DashDeck (needs ${ShieldsEnginePlugin.API_VERSION}).")
        }
        val lists = FILTER_LISTS.map { readList(context, it.file) }
        val report = nativeValidate(lists.toTypedArray(), trustedMask, bundledResources(context))
            ?: return ShieldsProbeService.failure("The engine returned no test report.")
        return org.json.JSONObject(report).put("engineVersion", nativeEngineVersion()).toString()
    }

    /** Starts loading the engine in the background. Safe to call repeatedly. */
    fun warmUp(context: Context) {
        if (isReady) return
        val appContext = context.applicationContext
        executor.execute { if (!isReady) load(appContext, forceRebuild = false) }
    }

    /** Rebuilds the engine from the current lists (after an update). */
    fun rebuild(context: Context) {
        val appContext = context.applicationContext
        executor.execute { load(appContext, forceRebuild = true) }
    }

    private fun load(context: Context, forceRebuild: Boolean) {
        if (!ensureLibrary(context)) return
        val started = System.currentTimeMillis()
        try {
            val lists = FILTER_LISTS.map { readList(context, it.file) }
            val resources = bundledResources(context)
            val key = cacheKey(lists, resources)
            val cacheDir = File(context.filesDir, "shields").apply { mkdirs() }
            val cacheFile = File(cacheDir, "engine-$key.dat")

            if (!forceRebuild && cacheFile.exists() && nativeLoad(cacheFile.readBytes(), resources)) {
                markReady(lists)
                Log.i(TAG, "Engine loaded from cache in ${System.currentTimeMillis() - started}ms")
                return
            }

            val serialized = nativeBuild(lists.toTypedArray(), trustedMask, resources) ?: run {
                Log.e(TAG, "Engine build failed")
                return
            }
            markReady(lists)
            cacheDir.listFiles { f -> f.name.startsWith("engine-") }?.forEach { it.delete() }
            runCatching { cacheFile.writeBytes(serialized) }
            Log.i(TAG, "Engine built from ${lists.sumOf { it.length } / 1024}KB of rules in ${System.currentTimeMillis() - started}ms")
        } catch (t: Throwable) {
            Log.e(TAG, "Engine load failed", t)
        }
    }

    /** Downloaded copy if present, otherwise the copy bundled in the APK. */
    internal fun readList(context: Context, file: String): String {
        val downloaded = File(ShieldsUpdater.listsDir(context), file)
        if (downloaded.isFile && downloaded.length() > 0) return downloaded.readText()
        return context.assets.open("shields/$file").bufferedReader().use { it.readText() }
    }

    private fun cacheKey(lists: List<String>, resources: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(ENGINE_FORMAT.toString().toByteArray())
        digest.update(engineIdentity.toByteArray())
        lists.forEach { digest.update(it.toByteArray()) }
        digest.update(resources.toByteArray())
        return digest.digest().take(8).joinToString("") { "%02x".format(it) }
    }

    internal val trustedMask: Int
        get() = FILTER_LISTS.foldIndexed(0) { i, mask, list -> if (list.trusted) mask or (1 shl i) else mask }

    internal fun bundledResources(context: Context): String =
        context.assets.open("shields/resources.json").bufferedReader().use { it.readText() }

    /**
     * Builds a candidate engine from [lists] without installing it and runs the
     * native self-test suite. Blocks; call off the main thread.
     */
    internal fun validate(context: Context, lists: List<String>): String? {
        if (!ensureLibrary(context)) return null
        return runCatching { nativeValidate(lists.toTypedArray(), trustedMask, bundledResources(context)) }
            .onFailure { Log.e(TAG, "Engine validation crashed", it) }
            .getOrNull()
    }

    /** Returns null to allow, "" to block, or a data: URL to serve instead. */
    fun check(url: String, sourceUrl: String, resourceType: String): String? {
        if (!isReady) return null
        val verdict = runCatching { nativeCheck(url, sourceUrl, resourceType) }.getOrNull()
        // A plugin that has handled real traffic without crashing is healthy.
        if (isPluginActive && checksSinceStart.incrementAndGet() == HEALTHY_AFTER_CHECKS) {
            appContext?.let(ShieldsEnginePlugin::markHealthy)
        }
        return verdict
    }

    fun cosmeticResources(url: String): String? {
        if (!isReady) return null
        return runCatching { nativeCosmetic(url) }.getOrNull()
    }

    fun hiddenSelectors(classesJson: String, idsJson: String, exceptionsJson: String): String? {
        if (!isReady) return null
        return runCatching { nativeHiddenSelectors(classesJson, idsJson, exceptionsJson) }.getOrNull()
    }

    @JvmStatic private external fun nativeBuild(lists: Array<String>, trustedMask: Int, resourcesJson: String): ByteArray?
    @JvmStatic private external fun nativeApiVersion(): Int
    @JvmStatic private external fun nativeEngineVersion(): String
    @JvmStatic private external fun nativeValidate(lists: Array<String>, trustedMask: Int, resourcesJson: String): String?
    @JvmStatic private external fun nativeLoad(serialized: ByteArray, resourcesJson: String): Boolean
    @JvmStatic private external fun nativeCheck(url: String, sourceUrl: String, resourceType: String): String?
    @JvmStatic private external fun nativeCosmetic(url: String): String?
    @JvmStatic private external fun nativeHiddenSelectors(classes: String, ids: String, exceptions: String): String?
}
