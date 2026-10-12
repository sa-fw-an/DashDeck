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
import android.net.Uri
import android.widget.Toast
import org.dashdeck.app.AppConstants
import org.dashdeck.app.MainActivity
import org.dashdeck.app.R
import org.dashdeck.app.data.BrowserPreferences
import org.dashdeck.app.databinding.ActivityMainBinding
import org.dashdeck.app.bookmarks.BookmarkManager
import org.dashdeck.app.startpage.StartPageManager
import org.dashdeck.app.tabs.TabManager
import org.dashdeck.app.navigation.NavigationManager

class MainActivitySetup(
    private val activity: MainActivity,
    private val binding: ActivityMainBinding,
    private val managers: Managers
) {

    data class Managers(
        val bookmarkManager: BookmarkManager,
        val startPageManager: StartPageManager,
        val tabManager: TabManager,
        val uiManager: BrowserUIManager,
        val navigationManager: NavigationManager,
        val overlayManager: OverlayManager
    )

    fun setupClickListeners() {
        setupNavigationButtons()
        setupMenuButtons()
        setupBookmarkButtons()
        setupOverlayButtons()
        setupStartPageButtons()
    }

    private fun setupNavigationButtons() {
        binding.buttonBack.setOnClickListener {
            activity.webView?.let { webView ->
                if (webView.canGoBack()) {
                    webView.goBack()
                }
            }
            activity.updateNavigationButtonsProxy()
        }
        
        binding.buttonForward.setOnClickListener {
            activity.webView?.let { webView ->
                if (webView.canGoForward()) {
                    webView.goForward()
                }
            }
            activity.updateNavigationButtonsProxy()
        }
        
        binding.buttonReload.setOnClickListener {
            activity.webView?.reload()
            managers.uiManager.hideMenuOverlay()
        }
    }

    private fun setupMenuButtons() {
        binding.persistentButtonMenu.setOnClickListener {
            managers.uiManager.showMenuOverlay()
        }
        
        binding.menuFab.setOnClickListener {
            activity.handleQuickActionButtonPressedProxy()
        }
        
        binding.buttonClose.setOnClickListener {
            managers.uiManager.hideMenuOverlay()
        }
        
        binding.menuOverlayScrim.setOnClickListener {
            managers.uiManager.hideMenuOverlay()
        }
    }

    private fun setupBookmarkButtons() {
        binding.buttonBookmarks.setOnClickListener {
            managers.bookmarkManager.showBookmarkManager()
        }
        
        binding.buttonBookmarkManagerBack.setOnClickListener {
            managers.bookmarkManager.hideBookmarkManager()
        }
        
        binding.buttonBookmarkAdd.setOnClickListener {
            managers.bookmarkManager.addBookmarkForCurrentPage()
        }
        
        binding.buttonBookmarkStartPageAdd.setOnClickListener {
            managers.bookmarkManager.showStartPageSlotPicker(activity.currentUrlProxy)
        }
        
        binding.buttonBookmarkSetHomePage.setOnClickListener {
            managers.bookmarkManager.setCurrentPageAsHomePage()
        }
    }

    private fun setupOverlayButtons() {
        binding.buttonExternal.setOnClickListener {
            managers.overlayManager.showQrCodeView(activity.currentUrlProxy)
        }
        
        binding.buttonExternalGithub.setOnClickListener {
            val uri = Uri.parse(AppConstants.GITHUB_REPO_URL)
            managers.uiManager.openUriExternally(uri)
        }
        
        binding.buttonSettings.setOnClickListener {
            managers.overlayManager.showSettingsView()
        }
        
        binding.buttonCheckLatest.setOnClickListener {
            managers.overlayManager.showCheckLatestView()
        }
        
        binding.buttonQrCodeBack.setOnClickListener {
            managers.overlayManager.hideQrCodeView()
        }
        
        binding.buttonCheckLatestBack.setOnClickListener {
            managers.overlayManager.hideCheckLatestView()
        }
        
        binding.checkLatestOpenReleaseButton.setOnClickListener {
            val uri = Uri.parse(activity.latestReleaseUrlProxy)
            managers.uiManager.openUriExternally(uri)
        }
    }

    private fun setupStartPageButtons() {
        binding.buttonTabs.setOnClickListener {
            managers.tabManager.showTabManager()
        }
        
        binding.buttonTabManagerBack.setOnClickListener {
            managers.tabManager.hideTabManager()
        }
        
        binding.buttonNewTab.setOnClickListener {
            managers.tabManager.createNewTab(true)
            managers.uiManager.hideMenuOverlay()
        }
        
        binding.buttonTabManagerAdd.setOnClickListener {
            managers.tabManager.createNewTab(true)
            managers.uiManager.hideMenuOverlay()
        }
        
        binding.buttonStartPage.setOnClickListener {
            activity.showStartPageProxy()
            managers.uiManager.hideMenuOverlay()
        }
        
        binding.startPageContent.buttonStartPageResume.setOnClickListener {
            val resumeUrl = BrowserPreferences.getLastVisitedUrl(activity)
            if (resumeUrl.isNullOrBlank()) {
                val message = activity.getString(R.string.start_page_no_last_page)
                Toast.makeText(activity, message, Toast.LENGTH_SHORT).show()
            } else {
                managers.navigationManager.loadUrlFromIntent(resumeUrl)
            }
        }
        
        binding.startPageContent.buttonStartPagePhotoOnly.setOnClickListener {
            managers.startPageManager.isStartPagePhotoOnlyMode = !managers.startPageManager.isStartPagePhotoOnlyMode
            managers.startPageManager.applyStartPagePhotoOnlyMode()
        }

        binding.startPageContent.buttonStartPageMirror.setOnClickListener {
            activity.launchMirrorCompanion()
        }
        
        binding.startPageRoot.setOnClickListener {
            if (managers.startPageManager.isStartPagePhotoOnlyMode) {
                managers.startPageManager.isStartPagePhotoOnlyMode = false
                managers.startPageManager.applyStartPagePhotoOnlyMode()
            }
        }
    }
}
