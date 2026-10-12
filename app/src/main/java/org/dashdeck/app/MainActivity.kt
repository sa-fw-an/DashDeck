package org.dashdeck.app

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Display
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.View
import android.widget.LinearLayout
import android.webkit.WebChromeClient
import android.widget.Toast
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.ColorUtils
import androidx.core.view.isVisible
import com.google.android.material.button.MaterialButton
import org.dashdeck.app.AppConstants.MENU_BUTTON_AUTO_HIDE_DELAY_MS
import org.dashdeck.app.AppConstants.MENU_BUTTON_SHOW_DELAY_MS
import org.dashdeck.app.AppConstants.REQUEST_CODE_POST_NOTIFICATIONS
import org.dashdeck.app.AppConstants.REQUEST_CODE_RECORD_AUDIO
import org.dashdeck.app.bookmarks.BookmarkManager
import org.dashdeck.app.data.BrowserPreferences
import org.dashdeck.app.data.SiteIconCache
import org.dashdeck.app.databinding.ActivityMainBinding
import org.dashdeck.app.model.QuickActionButtonMode
import org.dashdeck.app.model.UserAgentProfile
import org.dashdeck.app.mirror.DashDeckMirrorState
import org.dashdeck.app.mirror.DashDeckTouchService
import org.dashdeck.app.navigation.NavigationManager
import org.dashdeck.app.permissions.PermissionManager
import org.dashdeck.app.startpage.StartPageManager
import org.dashdeck.app.tabs.BrowserTab
import org.dashdeck.app.tabs.TabManager
import org.dashdeck.app.ui.BrowserUIManager
import org.dashdeck.app.ui.MainActivitySetup
import org.dashdeck.app.ui.OverlayManager
import org.dashdeck.app.ui.ThemeManager
import org.dashdeck.app.web.BrowserCallbacks
import org.dashdeck.app.web.releaseCompletely
import org.dashdeck.app.web.updateDesktopMode
import org.dashdeck.app.web.updateUserAgentProfile

private const val STATE_SETTINGS_SCROLL = "settings_scroll"

/** The browser. Runs on the phone and, launched by Android Auto, on the car display. */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val handler: Handler = Handler(Looper.getMainLooper())
    private val isCarDisplay: Boolean get() = display?.displayId != null && display?.displayId != Display.DEFAULT_DISPLAY
    private var mirrorOverlayRequested = false
    private var mirrorTouchActive = false
    private var carConnectionType = androidx.car.app.connection.CarConnection.CONNECTION_TYPE_NOT_CONNECTED
    private val carScreenTarget: (String) -> Unit = { url ->
        runOnUiThread { navigationManager.loadUrlFromIntent(url) }
    }
    private val mirrorStateListener: (Boolean) -> Unit = { active ->
        runOnUiThread {
            if (active && isCarDisplay) mirrorOverlayRequested = true
            if (!active) mirrorOverlayRequested = false
            updateMirrorUi()
        }
    }
    
    private val themeManager: ThemeManager by lazy { 
        ThemeManager(this, binding) 
    }
    
    private val permissionManager: PermissionManager by lazy { 
        PermissionManager(this) 
    }
    
    private val bookmarkManager: BookmarkManager by lazy { 
        BookmarkManager(this, binding, createBookmarkCallbacks()) 
    }
    
    private val startPageManager: StartPageManager by lazy { 
        StartPageManager(this, binding, bookmarkManager, createStartPageCallbacks()) 
    }
    
    private val tabManager: TabManager by lazy { 
        TabManager(this, binding, bookmarkManager, createTabCallbacks()) 
    }
    
    private val uiManager: BrowserUIManager by lazy { 
        BrowserUIManager(this, binding, tabManager, bookmarkManager, startPageManager, createUICallbacks()) 
    }
    
    private val navigationManager: NavigationManager by lazy { 
        NavigationManager(this, binding, tabManager, permissionManager, startPageManager, uiManager, createNavigationCallbacks()) 
    }
    
    private val overlayManager: OverlayManager by lazy { 
        OverlayManager(this, binding, tabManager, bookmarkManager, startPageManager, uiManager, createOverlayCallbacks()) 
    }

    private val isDebugBuild: Boolean by lazy { 
        val flags = applicationInfo.flags
        (flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0 
    }

    var webView: android.webkit.WebView? = null
        private set
        
    var currentUrl: String = ""
        private set
        
    var currentPageTitle: String = ""
        private set
        
    private var currentUserAgentProfile: UserAgentProfile = UserAgentProfile.ANDROID_CHROME
    private var shouldForceSessionRestore: Boolean = false
    
    var latestReleaseUrl: String = AppConstants.RELEASES_URL
        private set

    val currentUrlProxy: String
        get() {
            return currentUrl
        }
    val latestReleaseUrlProxy: String
        get() {
            return latestReleaseUrl
        }
    
    fun updateNavigationButtonsProxy() { 
        updateNavigationButtons() 
    }
    
    fun handleQuickActionButtonPressedProxy() { 
        handleQuickActionButtonPressed() 
    }
    
    fun showStartPageProxy() { 
        showStartPage() 
    }

    private val pickStartPageBackgroundLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        startPageManager.handleStartPageBackgroundPicked(uri)
        rebuildSettingsContent()
    }

    private val autoHideMenuFab = Runnable {
        if (::binding.isInitialized && !startPageManager.isShowingStartPage && !BrowserPreferences.isQuickActionButtonAlwaysVisible(this)) {
            binding.menuFab.hide()
        }
    }
    
    private val showMenuFabRunnable = Runnable {
        if (::binding.isInitialized && !uiManager.isToolbarEnabled() && !uiManager.isInFullscreen() && !binding.menuOverlay.isVisible) {
            binding.menuFab.show()
            if (!startPageManager.isShowingStartPage && !BrowserPreferences.isQuickActionButtonAlwaysVisible(this)) {
                handler.postDelayed(autoHideMenuFab, MENU_BUTTON_AUTO_HIDE_DELAY_MS)
            }
        }
    }

    override fun attachBaseContext(newBase: Context?) {
        val scaled = newBase?.let { BrowserPreferences.createScaledContext(it) }
        super.attachBaseContext(scaled ?: newBase)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(BrowserPreferences.getThemeMode(this).style)
        super.onCreate(savedInstanceState)
        
        shouldForceSessionRestore = (savedInstanceState != null)
        org.dashdeck.app.web.BraveAdblockNative.warmUp(this)
        org.dashdeck.app.web.ShieldsUpdater.updateIfStale(this)
        org.dashdeck.app.web.ShieldsEnginePlugin.onReverted = engineRevertedListener
        showEngineRevertedNotice()
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setupCarMirror()
        DashDeckMirrorState.addListener(mirrorStateListener)
        
        binding.menuVersion.text = "v${BuildConfig.VERSION_NAME}"
        applyWindowInsets()
        setupUi()
        setupTopBar()
        setupContinueOnCar()
        restoreOpenSettings(savedInstanceState)
        org.dashdeck.app.ui.ThemeTransition.onCreated(this)
        org.dashdeck.app.web.BraveAdblockNative.onReady = engineReadyListener
        setupBackPressHandling()
        
        permissionManager.ensureNotificationPermissionIfNeeded(REQUEST_CODE_POST_NOTIFICATIONS)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val url = navigationManager.extractBrowsableUrl(intent)
        if (url != null) {
            navigationManager.loadUrlFromIntent(url)
        }
    }

    override fun onResume() {
        super.onResume()
        webView?.onResume()
        refreshHomePageMode()
        bookmarkManager.refreshBookmarks()
        tabManager.refreshTabs()
        startPageManager.refreshStartPage()
        updateMirrorUi()
        syncUserAgentProfile()
        uiManager.applyPersistentAddressBarPreference()
        uiManager.applyQuickActionButtonPreferences()
    }

    override fun onPause() {
        uiManager.exitFullscreen()
        webView?.onPause()
        super.onPause()
    }

    /**
     * Pads the layout for system bars and the keyboard, and stops the insets there.
     * Passed on, the WebView reports them to pages as a CSS safe area; in Android
     * Auto that made sites such as YouTube pad and blur a large part of the screen.
     */
    private fun applyWindowInsets() {
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val types = androidx.core.view.WindowInsetsCompat.Type.systemBars() or
                androidx.core.view.WindowInsetsCompat.Type.displayCutout()
            val bars = insets.getInsets(types)
            val ime = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.ime())
            view.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, ime.bottom))
            androidx.core.view.WindowInsetsCompat.CONSUMED
        }
    }

    private fun setupTopBar() {
        binding.topBarBack.setOnClickListener {
            webView?.takeIf { it.canGoBack() }?.goBack()
            updateNavigationButtons()
        }
        binding.topBarTabs.setOnClickListener {
            uiManager.showMenuOverlay()
            tabManager.showTabManager()
        }
        binding.topBarShields.setOnClickListener {
            val pageUrl = currentUrl.takeIf { !startPageManager.isShowingStartPage && it.isNotBlank() }
            org.dashdeck.app.ui.ShieldsPanel.show(
                this,
                pageUrl,
                onSiteToggled = { webView?.reload() },
                onOpenSettings = {
                    uiManager.showMenuOverlay()
                    overlayManager.showSettingsView()
                }
            )
        }
    }

    private fun updateTopBar() {
        if (!::binding.isInitialized) return
        val onPage = !startPageManager.isShowingStartPage && currentUrl.isNotBlank()
        binding.topBarBack.isEnabled = onPage && webView?.canGoBack() == true
        binding.topBarTabs.text = tabManager.browserTabs.size.coerceAtLeast(1).toString()
        val blocked = if (onPage) org.dashdeck.app.web.AdBlocker.blockedCount(currentUrl) else 0
        binding.topBarShields.text = if (blocked > 0) blocked.toString() else ""
    }

    /** "Continue on car screen": hands the current page to DashDeck on the car display. */
    private fun setupContinueOnCar() {
        if (isCarDisplay) {
            binding.buttonContinueOnCar.isVisible = false
            org.dashdeck.app.ui.CarScreen.register(carScreenTarget)
            return
        }
        androidx.car.app.connection.CarConnection(this).type.observe(this) { carConnectionType = it }
        binding.buttonContinueOnCar.setOnClickListener {
            val url = currentUrl.takeIf { !startPageManager.isShowingStartPage && bookmarkManager.isActiveWebsiteUrl(it) }
            when {
                url == null -> Toast.makeText(this, R.string.continue_on_car_nothing, Toast.LENGTH_SHORT).show()
                org.dashdeck.app.ui.CarScreen.open(url) -> {
                    // Playback moves to the car; don't keep playing on the phone too.
                    webView?.evaluateJavascript("document.querySelectorAll('video,audio').forEach(function(m){m.pause()})", null)
                    uiManager.hideMenuOverlay()
                    Toast.makeText(this, R.string.continue_on_car_sent, Toast.LENGTH_SHORT).show()
                }
                else -> {
                    val connected = carConnectionType == androidx.car.app.connection.CarConnection.CONNECTION_TYPE_PROJECTION
                    com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                        .setTitle(R.string.continue_on_car_not_connected_title)
                        .setMessage(if (connected) R.string.continue_on_car_open_on_car else R.string.continue_on_car_not_connected)
                        .setPositiveButton(android.R.string.ok, null)
                        .show()
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (binding.menuOverlay.isVisible && binding.settingsViewRoot.isVisible) {
            outState.putInt(STATE_SETTINGS_SCROLL, binding.settingsViewRoot.scrollY)
        }
    }

    /** After a theme change recreates the activity, reopen Settings where it was. */
    private fun restoreOpenSettings(savedInstanceState: Bundle?) {
        val scroll = savedInstanceState?.getInt(STATE_SETTINGS_SCROLL, -1)?.takeIf { it >= 0 } ?: return
        uiManager.showMenuOverlay(animate = false)
        overlayManager.showSettingsView()
        binding.settingsViewRoot.post { binding.settingsViewRoot.scrollTo(0, scroll) }
    }

    private val engineRevertedListener: (Context) -> Unit = { showEngineRevertedNotice() }
    private val engineReadyListener: () -> Unit = { startPageManager.refreshShieldsStatus() }

    private fun showEngineRevertedNotice() {
        if (isFinishing || isDestroyed) return
        val version = org.dashdeck.app.web.ShieldsEnginePlugin.consumeFailedVersion(this) ?: return
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(R.string.shields_engine_reverted_title)
            .setMessage(getString(R.string.shields_engine_reverted_message, version))
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    override fun onDestroy() {
        if (org.dashdeck.app.web.ShieldsEnginePlugin.onReverted === engineRevertedListener) {
            org.dashdeck.app.web.ShieldsEnginePlugin.onReverted = null
        }
        if (org.dashdeck.app.web.BraveAdblockNative.onReady === engineReadyListener) {
            org.dashdeck.app.web.BraveAdblockNative.onReady = null
        }
        DashDeckMirrorState.removeListener(mirrorStateListener)
        if (isCarDisplay) {
            DashDeckMirrorState.clearSurface()
            org.dashdeck.app.ui.CarScreen.unregister(carScreenTarget)
        }
        handler.removeCallbacks(autoHideMenuFab)
        handler.removeCallbacks(showMenuFabRunnable)
        uiManager.exitFullscreen()
        startPageManager.onDestroy()
        
        tabManager.browserTabs.forEach { tab ->
            tab.speechBridge.destroy()
            tab.webView.releaseCompletely()
        }
        binding.webViewContainer.removeAllViews()
        tabManager.browserTabs.clear()
        webView = null
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        permissionManager.handleRequestPermissionsResult(requestCode, grantResults) { granted ->
            val speechTab = tabManager.browserTabs.firstOrNull { it.id == permissionManager.pendingSpeechBridgeTabId }
            speechTab?.speechBridge?.onPermissionResult(granted)
        }
    }

    private fun setupUi() {
        tabManager.initializeTabs(
            navigationManager.extractBrowsableUrl(intent),
            BrowserPreferences.getHomePageUrl(this),
            BrowserPreferences.getLastVisitedUrl(this),
            BrowserPreferences.shouldRestoreTabsOnLaunch(this),
            BrowserPreferences.shouldResumeLastPageOnLaunch(this),
            shouldForceSessionRestore
        )
        currentUserAgentProfile = BrowserPreferences.getUserAgentProfile(this)
        binding.desktopSwitch.isChecked = BrowserPreferences.shouldUseDesktopMode(this)
        binding.desktopSwitch.setOnCheckedChangeListener { _, isChecked ->
            BrowserPreferences.setDesktopMode(this, isChecked)
            tabManager.browserTabs.forEach { tab ->
                tab.webView.updateDesktopMode(isChecked, currentUserAgentProfile)
            }
            Toast.makeText(
                this,
                if (isChecked) "Desktop site mode on" else "Desktop site mode off",
                Toast.LENGTH_SHORT
            ).show()
        }
        
        uiManager.configureAddressField(binding.addressEdit, binding.buttonClearAddress, binding.buttonGo, true)
        uiManager.configureAddressField(binding.persistentAddressEdit, binding.persistentButtonClearAddress, binding.persistentButtonGo, false)
        uiManager.syncAddressFieldsFrom(binding.addressEdit)
        uiManager.updateAddressClearButtons()
        uiManager.setupManualDragLogic()
        
        val setup = MainActivitySetup(
            this,
            binding,
            MainActivitySetup.Managers(
                bookmarkManager,
                startPageManager,
                tabManager,
                uiManager,
                navigationManager,
                overlayManager
            )
        )
        setup.setupClickListeners()
        
        updateNavigationButtons()
        tabManager.refreshTabs()
        startPageManager.refreshStartPage()
        showMenuButtonTemporarily()
        bookmarkManager.refreshBookmarks()
        refreshHomePageMode()
        uiManager.applyPersistentAddressBarPreference()
        uiManager.applyQuickActionButtonPreferences()
    }

    private fun setupBackPressHandling() {
        onBackPressedDispatcher.addCallback(this) {
            when {
                binding.carMirrorOverlay.isVisible -> {
                    mirrorOverlayRequested = false
                    updateMirrorUi()
                }
                uiManager.isInFullscreen() -> uiManager.exitFullscreen()
                binding.checkLatestViewRoot.isVisible -> overlayManager.hideCheckLatestView()
                binding.qrCodeViewRoot.isVisible -> overlayManager.hideQrCodeView()
                binding.tabManagerRoot.isVisible -> tabManager.hideTabManager()
                binding.bookmarkManagerRoot.isVisible -> bookmarkManager.hideBookmarkManager()
                binding.settingsViewRoot.isVisible -> overlayManager.hideSettingsView()
                binding.menuOverlay.isVisible -> uiManager.hideMenuOverlay()
                startPageManager.isShowingStartPage && currentUrl.isNotBlank() -> hideStartPage()
                webView?.canGoBack() == true -> webView?.goBack()
                else -> {
                    // Let the system handle this one press, then re-arm: in Android
                    // Auto the activity usually survives, and a callback left
                    // disabled would skip page history on every later Back.
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                    isEnabled = true
                }
            }
            updateNavigationButtons()
        }
    }

    private fun buildBrowserCallbacks(tab: BrowserTab): BrowserCallbacks {
        return BrowserCallbacks(
            onUrlChange = { url ->
                runOnUiThread {
                    tab.currentUrl = url
                    BrowserPreferences.persistUrl(this, url)
                    bookmarkManager.prefetchSiteIcon(url)
                    tabManager.persistTabSession()
                    if (tab.id == tabManager.activeTabId) {
                        currentUrl = url
                        if (!startPageManager.isShowingStartPage && binding.addressEdit.text?.toString() != url) {
                            binding.addressEdit.setText(url)
                            binding.addressEdit.setSelection(url.length)
                        }
                        updateNavigationButtons()
                        if (!startPageManager.isShowingStartPage) {
                            uiManager.updateConnectionSecurityIcon(url)
                        }
                        startPageManager.refreshStartPage()
                        tabManager.refreshTabs()
                    }
                }
            },
            onTitleChange = { title ->
                runOnUiThread {
                    tab.currentTitle = title.orEmpty()
                    tabManager.persistTabSession()
                    if (tab.id == tabManager.activeTabId) {
                        currentPageTitle = tab.currentTitle
                        if (!startPageManager.isShowingStartPage) {
                            binding.pageTitle.text = tab.currentTitle.ifBlank { tabManager.displayTitleForTab(tab) }
                        }
                    }
                    tabManager.refreshTabs()
                }
            },
            onFaviconReceived = { url, icon ->
                runOnUiThread {
                    SiteIconCache.cacheIcon(this, url, icon)
                    if (startPageManager.isShowingStartPage) startPageManager.refreshStartPage()
                    if (binding.bookmarkManagerRoot.isVisible) bookmarkManager.refreshBookmarks()
                    if (binding.tabManagerRoot.isVisible) tabManager.refreshTabs()
                }
            },
            onProgressChange = { p ->
                if (tab.id == tabManager.activeTabId) {
                    runOnUiThread {
                        updateProgress(p)
                        updateTopBar()
                    }
                }
            },
            onShowDownloadPrompt = { uri ->
                runOnUiThread {
                    uiManager.openUriExternally(uri)
                }
            },
            onCleartextNavigationRequested = { uri, once, host, cancel ->
                runOnUiThread {
                    permissionManager.showCleartextNavigationDialog(uri, once, host, cancel)
                }
            },
            onError = { _, d ->
                runOnUiThread {
                    if (isDebugBuild && tab.id == tabManager.activeTabId) {
                        Toast.makeText(
                            this,
                            d ?: getString(R.string.error_generic_message),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            },
            onEnterFullscreen = { v, c ->
                runOnUiThread {
                    uiManager.enterFullscreen(v, c)
                }
            },
            onExitFullscreen = {
                runOnUiThread {
                    uiManager.exitFullscreen(true)
                }
            },
            onPermissionRequest = { r ->
                runOnUiThread {
                    permissionManager.handleWebPermissionRequest(r, REQUEST_CODE_RECORD_AUDIO)
                }
            },
            onGeolocationPermissionRequest = { origin, callback ->
                runOnUiThread {
                    permissionManager.handleGeolocationPermissionRequest(origin, callback)
                }
            }
        )
    }

    private fun showStartPage() { 
        startPageManager.showStartPage()
        webView?.visibility = View.INVISIBLE 
    }

    private fun hideStartPage() { 
        startPageManager.hideStartPage(currentPageTitle, currentUrl)
        webView?.visibility = View.VISIBLE 
    }
    
    private fun updateNavigationButtons() {
        updateTopBar()
        val notStart = !startPageManager.isShowingStartPage
        val can = notStart && currentUrl.isNotBlank()
        binding.buttonBack.isEnabled = notStart && webView?.canGoBack() == true
        binding.buttonForward.isEnabled = notStart && webView?.canGoForward() == true
        binding.buttonReload.isEnabled = can
        binding.buttonExternal.isEnabled = can
        // Also on the start page, so the next site opens in desktop mode.
        binding.desktopSwitch.isEnabled = true
        binding.desktopSwitch.alpha = 1.0f
        binding.desktopSwitchLabel.alpha = 1.0f
    }

    private fun updateProgress(p: Int) { 
        binding.progressIndicator.isVisible = p in 1..99
        if (p in 1..99) {
            binding.progressIndicator.setProgressCompat(p, true)
        }
    }
    
    fun showMenuButtonTemporarily() {
        handler.removeCallbacks(showMenuFabRunnable)
        handler.removeCallbacks(autoHideMenuFab)
        if (uiManager.isInFullscreen() || binding.menuOverlay.isVisible) return
        if (uiManager.isToolbarEnabled()) {
            binding.menuFab.hide()
            return
        }
        if (startPageManager.isShowingStartPage || BrowserPreferences.isQuickActionButtonAlwaysVisible(this)) {
            binding.menuFab.show()
        } else {
            handler.postDelayed(showMenuFabRunnable, MENU_BUTTON_SHOW_DELAY_MS)
        }
    }

    private fun refreshHomePageMode() { 
        binding.buttonStartPage.isVisible =
            BrowserPreferences.getHomePageUrl(this).isNullOrBlank() 
    }
    
    private fun handleHomePagePreferenceChanged() {
        refreshHomePageMode()
        bookmarkManager.refreshBookmarks()
        startPageManager.refreshStartPage()
        rebuildSettingsContent()
        val url = BrowserPreferences.getHomePageUrl(this)
        if (!url.isNullOrBlank() && startPageManager.isShowingStartPage) {
            navigationManager.loadUrlFromIntent(url)
        } else {
            updateNavigationButtons()
        }
    }

    private fun rebuildSettingsContent() {
        if (::binding.isInitialized) {
            binding.settingsContentContainer.removeAllViews()
            if (binding.settingsViewRoot.isVisible) overlayManager.showSettingsView()
        }
    }
    
    private fun handleQuickActionButtonPressed() {
        val mode = BrowserPreferences.getQuickActionButtonMode(this)
        if (mode == QuickActionButtonMode.MENU) {
            uiManager.showMenuOverlay()
        } else {
            uiManager.showMenuOverlay(focusAddressBar = true)
        }
    }

    private fun syncUserAgentProfile() {
        val p = BrowserPreferences.getUserAgentProfile(this)
        if (p != currentUserAgentProfile) {
            currentUserAgentProfile = p
            tabManager.browserTabs.forEach { tab ->
                tab.webView.updateUserAgentProfile(p, BrowserPreferences.shouldUseDesktopMode(this))
            }
        }
    }

    fun launchMirrorCompanion() {
        if (DashDeckMirrorState.isMirroring()) {
            if (isCarDisplay) {
                mirrorOverlayRequested = true
                updateMirrorUi()
            } else {
                DashDeckMirrorState.stop(this)
            }
        } else {
            MirrorLauncher.launch(this)
        }
    }

    private fun setupCarMirror() {
        binding.carMirrorClose.setOnClickListener {
            mirrorOverlayRequested = false
            updateMirrorUi()
        }
        binding.carMirrorStop.setOnClickListener { DashDeckMirrorState.stop(this) }
        if (!isCarDisplay) return
        binding.carMirrorSurface.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) = attachMirrorSurface(holder)
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = attachMirrorSurface(holder)
            override fun surfaceDestroyed(holder: SurfaceHolder) { DashDeckMirrorState.clearSurface() }
        })
        binding.carMirrorSurface.setOnTouchListener { _, event ->
            handleMirrorTouch(event)
            true
        }
    }

    /** Streams a car touch to the phone as a live gesture. */
    private fun handleMirrorTouch(event: MotionEvent) {
        fun point(x: Float, y: Float, t: Long, clamp: Boolean) =
            DashDeckMirrorState.mapToPhone(x, y, clamp)?.let { DashDeckTouchService.TouchPoint(it.x, it.y, t) }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                mirrorTouchActive = false
                point(event.x, event.y, event.eventTime, clamp = false)?.let {
                    mirrorTouchActive = true
                    DashDeckTouchService.down(it)
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (!mirrorTouchActive) return
                for (i in 0 until event.historySize) {
                    point(event.getHistoricalX(i), event.getHistoricalY(i), event.getHistoricalEventTime(i), clamp = true)
                        ?.let(DashDeckTouchService::move)
                }
                point(event.x, event.y, event.eventTime, clamp = true)?.let(DashDeckTouchService::move)
            }
            MotionEvent.ACTION_UP -> {
                if (!mirrorTouchActive) return
                mirrorTouchActive = false
                DashDeckTouchService.up(point(event.x, event.y, event.eventTime, clamp = true))
            }
            MotionEvent.ACTION_CANCEL -> {
                if (mirrorTouchActive) DashDeckTouchService.cancel()
                mirrorTouchActive = false
            }
        }
    }

    private fun attachMirrorSurface(holder: SurfaceHolder) {
        if (isCarDisplay && holder.surface.isValid) {
            DashDeckMirrorState.setSurface(holder.surface, binding.carMirrorSurface.width,
                binding.carMirrorSurface.height, resources.displayMetrics.densityDpi)
        }
    }

    private fun updateMirrorUi() {
        if (!::binding.isInitialized) return
        val active = DashDeckMirrorState.isMirroring()
        val start = binding.startPageContent
        start.buttonStartPageMirror.setText(
            when {
                active && isCarDisplay -> R.string.mirror_show
                active -> R.string.mirror_stop
                else -> R.string.mirror_start
            }
        )
        start.startPageMirrorStatus.setText(if (active) R.string.mirror_status_active else R.string.mirror_status_idle)
        binding.carMirrorOverlay.isVisible = isCarDisplay && active && mirrorOverlayRequested
    }

    private fun createBookmarkCallbacks() = object : BookmarkManager.BookmarkCallbacks {
        override fun onNavigateToUrl(url: String) { 
            navigationManager.loadUrlFromIntent(url)
            uiManager.hideMenuOverlay() 
        }
        
        override fun onRefreshStartPage() {
            startPageManager.refreshStartPage()
        }
        
        override fun onRebuildSettings() {
            rebuildSettingsContent()
        }
        
        override fun getCurrentUrl(): String {
            return currentUrl
        }
        
        override fun isShowingStartPage(): Boolean {
            return startPageManager.isShowingStartPage
        }
        
        override fun resolveThemeColor(attrRes: Int): Int {
            return themeManager.resolveThemeColor(attrRes)
        }
        
        override fun resolveReadableTextColor(backgroundColor: Int, preferredColor: Int, fallbackColor: Int): Int {
            return themeManager.resolveReadableTextColor(backgroundColor, preferredColor, fallbackColor)
        }
        
        override fun isHomePageEnabled(): Boolean {
            return BrowserPreferences.getHomePageUrl(this@MainActivity).isNullOrBlank().not()
        }
        
        override fun handleHomePagePreferenceChanged() {
            this@MainActivity.handleHomePagePreferenceChanged()
        }
    }

    private fun createStartPageCallbacks() = object : StartPageManager.StartPageCallbacks {
        override fun onNavigateToUrl(url: String) { 
            navigationManager.loadUrlFromIntent(url)
            uiManager.hideMenuOverlay() 
        }
        
        override fun onShowMenuOverlay() {
            uiManager.showMenuOverlay()
        }
        
        override fun onHideMenuOverlay() {
            uiManager.hideMenuOverlay()
        }
        
        override fun onEnterFullscreen() {
        }
        
        override fun onExitFullscreen() {
            uiManager.exitFullscreen()
        }
        
        override fun isInFullscreen(): Boolean {
            return uiManager.isInFullscreen()
        }
        
        override fun getCurrentUrl(): String {
            return currentUrl
        }
        
        override fun resolveThemeColor(attrRes: Int): Int {
            return themeManager.resolveThemeColor(attrRes)
        }
        
        override fun updateNavigationButtons() {
            this@MainActivity.updateNavigationButtons()
        }
        
        override fun updateConnectionSecurityIcon(url: String?) {
            uiManager.updateConnectionSecurityIcon(url)
        }
        
        override fun showMenuButtonTemporarily() {
            this@MainActivity.showMenuButtonTemporarily()
        }
        
        override fun loadUrlFromIntent(url: String) {
            navigationManager.loadUrlFromIntent(url)
        }
        
        override fun resolveReadableTextColor(bg: Int, pr: Int, fb: Int): Int {
            return themeManager.resolveReadableTextColor(bg, pr, fb)
        }
    }

    private fun createTabCallbacks() = object : TabManager.TabCallbacks {
        override fun onTabChanged(tab: BrowserTab) { 
            webView = tab.webView
            currentUrl = tab.currentUrl
            currentPageTitle = tab.currentTitle 
        }
        
        override fun buildBrowserCallbacks(tab: BrowserTab): BrowserCallbacks {
            return this@MainActivity.buildBrowserCallbacks(tab)
        }
        
        override fun onNavigateToUrl(url: String) {
            navigationManager.loadUrlFromIntent(url)
        }
        
        override fun onShowStartPage() {
            showStartPage()
        }
        
        override fun onHideStartPage() {
            hideStartPage()
        }
        
        override fun onShowMenuOverlay(focusAddressBar: Boolean) {
            uiManager.showMenuOverlay(focusAddressBar)
        }
        
        override fun onHideMenuOverlay() {
            uiManager.hideMenuOverlay()
        }
        
        override fun resolveThemeColor(attrRes: Int): Int {
            return themeManager.resolveThemeColor(attrRes)
        }
        
        override fun resolveReadableTextColor(backgroundColor: Int, preferredColor: Int, fallbackColor: Int): Int {
            return themeManager.resolveReadableTextColor(backgroundColor, preferredColor, fallbackColor)
        }
        
        override fun requestSpeechRecognitionMicrophoneAccess(tabId: Long, pageUrl: String?) {
            permissionManager.requestSpeechRecognitionMicrophoneAccess(tabId, pageUrl) { granted -> 
                val tab = tabManager.browserTabs.firstOrNull { it.id == tabId }
                tab?.speechBridge?.onPermissionResult(granted) 
            }
        }
        
        override fun onSpeechTabClosed(tabId: Long) {
            if (permissionManager.pendingSpeechBridgeTabId == tabId) {
                permissionManager.pendingSpeechBridgeTabId = null
            }
        }
        
        override fun sanitizeJsExternalUrl(sourceWebView: android.webkit.WebView, rawUrl: String?): Uri? {
            return uiManager.sanitizeJsExternalUrl(sourceWebView, rawUrl)
        }
        
        override fun openUriExternally(uri: Uri) {
            uiManager.openUriExternally(uri)
        }
        
        override fun updateNavigationButtons() {
            this@MainActivity.updateNavigationButtons()
        }
        
        override fun applyPersistentAddressBarPreference() {
            uiManager.applyPersistentAddressBarPreference()
        }
        
        override fun syncAddressFieldsFrom(source: com.google.android.material.textfield.TextInputEditText) {
            uiManager.syncAddressFieldsFrom(source)
        }
        
        override fun updateAddressClearButtons() {
            uiManager.updateAddressClearButtons()
        }
        
        override fun updateConnectionSecurityIcon(url: String?) {
            uiManager.updateConnectionSecurityIcon(url)
        }
        
        override fun showMenuButtonTemporarily() {
            this@MainActivity.showMenuButtonTemporarily()
        }
    }

    private fun createUICallbacks() = object : BrowserUIManager.UICallbacks {
        override fun onNavigateToAddress(raw: String, closeMenuAfterNavigate: Boolean) {
            navigationManager.navigateToAddress(raw, closeMenuAfterNavigate)
        }
        
        override fun onShowQrCodeView() {
            overlayManager.showQrCodeView(currentUrl)
        }
        
        override fun onShowCheckLatestView() {
            overlayManager.showCheckLatestView()
        }
        
        override fun onShowSettingsView() {
            overlayManager.showSettingsView()
        }
        
        override fun handleQuickActionButtonPressed() {
            this@MainActivity.handleQuickActionButtonPressed()
        }
        
        override fun resolveThemeColor(attrRes: Int): Int {
            return themeManager.resolveThemeColor(attrRes)
        }
        
        override fun showMenuButtonTemporarily() {
            this@MainActivity.showMenuButtonTemporarily()
        }
    }

    private fun createNavigationCallbacks() = object : NavigationManager.NavigationCallbacks {
        override fun onNavigationStarted(url: String) {
            currentUrl = url
        }
        
        override fun onNavigationFinished(url: String) {
            currentUrl = url
        }
        
        override fun onHideStartPage() {
            hideStartPage()
        }
        
        override fun getCurrentUrl(): String {
            return currentUrl
        }
        
        override fun setCurrentUrl(url: String) {
            currentUrl = url
        }
        
        override fun setCurrentPageTitle(title: String) {
            currentPageTitle = title
        }
    }

    private fun createOverlayCallbacks() = object : OverlayManager.OverlayCallbacks {
        override fun onRecreateRequested() {
            recreate()
        }
        
        override fun onHomePageChanged() {
            handleHomePagePreferenceChanged()
        }
        
        override fun onPickBackgroundRequested() {
            pickStartPageBackgroundLauncher.launch(arrayOf("image/*"))
        }
        
        override fun onVersionInfoReceived(latestUrl: String, tagName: String) {
            latestReleaseUrl = latestUrl
        }
    }
}
