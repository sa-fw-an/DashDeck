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

package org.dashdeck.app.startpage

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import org.dashdeck.app.R
import org.dashdeck.app.bookmarks.BookmarkManager
import org.dashdeck.app.data.BrowserPreferences
import org.dashdeck.app.databinding.ActivityMainBinding
import org.dashdeck.app.ui.adapters.StartPageAdapter
import org.dashdeck.app.ui.adapters.QuickLinkItem
import org.dashdeck.app.databinding.DialogQuickLinkBinding
import org.dashdeck.app.web.BraveAdblockNative
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import androidx.appcompat.app.AlertDialog
import java.text.NumberFormat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class StartPageManager(
    private val activity: AppCompatActivity,
    private val binding: ActivityMainBinding,
    private val bookmarkManager: BookmarkManager,
    private val callbacks: StartPageCallbacks
) {

    interface StartPageCallbacks {
        fun onNavigateToUrl(url: String)
        fun onShowMenuOverlay()
        fun onHideMenuOverlay()
        fun onEnterFullscreen()
        fun onExitFullscreen()
        fun isInFullscreen(): Boolean
        fun getCurrentUrl(): String
        fun resolveThemeColor(attrRes: Int): Int
        fun updateNavigationButtons()
        fun updateConnectionSecurityIcon(url: String?)
        fun showMenuButtonTemporarily()
        fun loadUrlFromIntent(url: String)
        fun resolveReadableTextColor(bg: Int, pr: Int, fb: Int): Int
    }

    var isShowingStartPage: Boolean = false
    var isStartPagePhotoOnlyMode: Boolean = false
    
    private var loadedStartPageBackgroundUri: String? = null
    private var loadedStartPageBackgroundBitmap: Bitmap? = null

    private val startPageAdapter: StartPageAdapter by lazy {
        StartPageAdapter(
            bookmarkManager = bookmarkManager,
            resolveThemeColor = callbacks::resolveThemeColor,
            onOpen = callbacks::loadUrlFromIntent,
            onEdit = { showQuickLinkEditor(it) },
            onRemove = { removeQuickLink(it.url) },
            onAdd = { showQuickLinkEditor(null) },
            onReordered = { urls ->
                BrowserPreferences.setQuickLinks(activity, urls)
                bookmarkManager.refreshBookmarks()
            }
        )
    }

    private var backgroundLoadJob: Job? = null

    fun onDestroy() {
        backgroundLoadJob?.cancel()
        backgroundLoadJob = null
        loadedStartPageBackgroundBitmap?.recycle()
        loadedStartPageBackgroundBitmap = null
        loadedStartPageBackgroundUri = null
    }

    private fun setupRecyclerView() {
        val content = binding.startPageContent
        if (content.startPageQuickLinksContainer.adapter != null) return

        content.startPageQuickLinksContainer.apply {
            layoutManager = GridLayoutManager(activity, activity.resources.getInteger(R.integer.quick_link_columns))
            adapter = startPageAdapter
        }
        content.buttonStartPageEditLinks.setOnClickListener { setEditing(!startPageAdapter.editing) }

        val drag = ItemTouchHelper.UP or ItemTouchHelper.DOWN or ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT
        ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(drag, 0) {
            override fun getMovementFlags(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder): Int =
                if (startPageAdapter.canMove(viewHolder.bindingAdapterPosition)) makeMovementFlags(drag, 0) else 0

            override fun onMove(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder): Boolean {
                startPageAdapter.onItemMove(viewHolder.bindingAdapterPosition, target.bindingAdapterPosition)
                return true
            }

            override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                super.clearView(recyclerView, viewHolder)
                startPageAdapter.onMoveFinished()
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) = Unit
        }).attachToRecyclerView(content.startPageQuickLinksContainer)
    }

    private fun setEditing(editing: Boolean) {
        startPageAdapter.editing = editing
        binding.startPageContent.buttonStartPageEditLinks.apply {
            setText(if (editing) R.string.start_page_done_editing else R.string.start_page_edit_links)
            setIconResource(if (editing) R.drawable.ic_check else R.drawable.ic_edit)
        }
    }

    /** Add (link == null) or edit a quick link: name and address. */
    private fun showQuickLinkEditor(link: QuickLinkItem.Link?) {
        val links = BrowserPreferences.getQuickLinks(activity)
        if (link == null && links.size >= BrowserPreferences.MAX_START_PAGE_SITES) {
            Toast.makeText(activity, activity.getString(R.string.quick_link_full, BrowserPreferences.MAX_START_PAGE_SITES), Toast.LENGTH_SHORT).show()
            return
        }
        val view = DialogQuickLinkBinding.inflate(activity.layoutInflater)
        if (link != null) {
            view.quickLinkName.setText(BrowserPreferences.getQuickLinkTitle(activity, link.url) ?: link.title)
            view.quickLinkUrl.setText(link.url)
        } else if (bookmarkManager.isActiveWebsiteUrl(callbacks.getCurrentUrl())) {
            view.quickLinkUrl.setText(callbacks.getCurrentUrl())
        }

        val builder = MaterialAlertDialogBuilder(activity)
            .setTitle(if (link == null) R.string.quick_link_add_title else R.string.quick_link_edit_title)
            .setView(view.root)
            .setPositiveButton(R.string.quick_link_save, null)
            .setNegativeButton(android.R.string.cancel, null)
        if (link != null) builder.setNeutralButton(R.string.quick_link_remove) { _, _ -> removeQuickLink(link.url) }
        val dialog = builder.show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val raw = view.quickLinkUrl.text?.toString().orEmpty().trim()
            if (raw.isEmpty()) {
                view.quickLinkUrlLayout.error = activity.getString(R.string.quick_link_invalid_url)
                return@setOnClickListener
            }
            val url = BrowserPreferences.formatNavigableUrl(raw)
            val updated = links.toMutableList()
            val index = link?.let { updated.indexOf(it.url) } ?: -1
            if (index >= 0) updated[index] = url else updated += url
            BrowserPreferences.setQuickLinks(activity, updated)
            if (link != null && link.url != url) BrowserPreferences.setQuickLinkTitle(activity, link.url, null)
            BrowserPreferences.setQuickLinkTitle(activity, url, view.quickLinkName.text?.toString())
            refreshStartPageQuickLinks()
            dialog.dismiss()
        }
    }

    private fun removeQuickLink(url: String) {
        BrowserPreferences.setQuickLinks(activity, BrowserPreferences.getQuickLinks(activity) - url)
        BrowserPreferences.setQuickLinkTitle(activity, url, null)
        refreshStartPageQuickLinks()
    }

    fun showStartPage() {
        val homePageUrl = BrowserPreferences.getHomePageUrl(activity)
        if (!homePageUrl.isNullOrBlank()) {
            val message = activity.getString(R.string.start_page_disabled_by_home_page)
            Toast.makeText(activity, message, Toast.LENGTH_SHORT).show()
            callbacks.loadUrlFromIntent(homePageUrl)
            return
        }
        
        if (callbacks.isInFullscreen()) {
            callbacks.onExitFullscreen()
        }
        
        isShowingStartPage = true
        isStartPagePhotoOnlyMode = false
        binding.startPageRoot.visibility = View.VISIBLE
        binding.pageTitle.text = activity.getString(R.string.start_page_title)
        binding.addressEdit.setText("")
        
        callbacks.updateConnectionSecurityIcon(null)
        refreshStartPage()
        applyStartPagePhotoOnlyMode()
        callbacks.updateNavigationButtons()
        callbacks.showMenuButtonTemporarily()
    }

    fun hideStartPage(currentPageTitle: String, currentUrl: String) {
        if (!isShowingStartPage && binding.startPageRoot.visibility != View.VISIBLE) {
            return
        }
        
        isShowingStartPage = false
        isStartPagePhotoOnlyMode = false
        applyStartPagePhotoOnlyMode()
        binding.startPageRoot.visibility = View.GONE
        
        binding.pageTitle.text = currentPageTitle.ifBlank {
            currentUrl.takeIf { it.isNotBlank() }?.let { bookmarkManager.displayLabelForUrl(it) }.orEmpty()
        }
        
        if (currentUrl.isNotBlank()) {
            if (binding.addressEdit.text?.toString() != currentUrl) {
                binding.addressEdit.setText(currentUrl)
                binding.addressEdit.setSelection(currentUrl.length)
            }
        } else {
            binding.addressEdit.setText("")
        }
        
        val iconUrl = currentUrl.takeIf { bookmarkManager.isActiveWebsiteUrl(it) }
        callbacks.updateConnectionSecurityIcon(iconUrl)
        callbacks.updateNavigationButtons()
        bookmarkManager.refreshBookmarks()
    }

    fun applyStartPagePhotoOnlyMode() {
        val visibility = if (isStartPagePhotoOnlyMode) View.GONE else View.VISIBLE
        binding.startPageScroll.visibility = visibility
        binding.startPageDimOverlay.isVisible = !isStartPagePhotoOnlyMode && binding.startPageBackgroundImage.isVisible
        
        binding.startPageContent.buttonStartPagePhotoOnly.text = activity.getString(
            if (isStartPagePhotoOnlyMode) R.string.start_page_show_ui else R.string.start_page_photo_only
        )

        if (!isStartPagePhotoOnlyMode && isShowingStartPage) {
            callbacks.showMenuButtonTemporarily()
        }
    }

    fun refreshStartPage() {
        setupRecyclerView()
        refreshStartPageQuickLinks()
        refreshStartPageBackground()
        refreshStartPageResumeButton()
        refreshShieldsStatus()
    }

    fun refreshShieldsStatus() {
        binding.startPageContent.startPageShieldsStatus.text = when {
            !BrowserPreferences.isShieldsEnabled(activity) -> activity.getString(R.string.start_page_shields_off)
            BraveAdblockNative.isReady -> {
                val blocked = org.dashdeck.app.web.AdBlocker.blockedTotal(activity)
                if (blocked > 0) activity.getString(R.string.start_page_shields_on_blocked, NumberFormat.getIntegerInstance().format(blocked))
                else activity.getString(R.string.start_page_shields_on, NumberFormat.getIntegerInstance().format(BraveAdblockNative.ruleCount))
            }
            else -> activity.getString(R.string.start_page_shields_loading)
        }
    }

    fun refreshStartPageBackground() {
        val backgroundUri = BrowserPreferences.getStartPageBackgroundUri(activity)
        
        if (backgroundUri.isNullOrBlank()) {
            clearBackground()
            return
        }

        if (backgroundUri == loadedStartPageBackgroundUri && loadedStartPageBackgroundBitmap != null) {
            binding.startPageBackgroundImage.setImageBitmap(loadedStartPageBackgroundBitmap)
            binding.startPageBackgroundImage.visibility = View.VISIBLE
            binding.startPageDimOverlay.isVisible = !isStartPagePhotoOnlyMode
            return
        }

        backgroundLoadJob?.cancel()
        val uriToLoad = Uri.parse(backgroundUri)
        val metrics = activity.resources.displayMetrics
        val reqWidth = metrics.widthPixels.coerceAtLeast(1)
        val reqHeight = metrics.heightPixels.coerceAtLeast(1)

        backgroundLoadJob = activity.lifecycleScope.launch {
            val bitmap = withContext(Dispatchers.IO) {
                decodeSampledBitmapFromUri(uriToLoad, reqWidth, reqHeight)
            }
            
            loadedStartPageBackgroundBitmap?.recycle()
            loadedStartPageBackgroundBitmap = bitmap
            loadedStartPageBackgroundUri = if (bitmap != null) backgroundUri else null

            if (bitmap != null) {
                binding.startPageBackgroundImage.setImageBitmap(bitmap)
                binding.startPageBackgroundImage.visibility = View.VISIBLE
                binding.startPageDimOverlay.isVisible = !isStartPagePhotoOnlyMode
            } else {
                binding.startPageBackgroundImage.setImageBitmap(null)
                binding.startPageBackgroundImage.visibility = View.GONE
            }
        }
    }

    private fun clearBackground() {
        loadedStartPageBackgroundBitmap?.recycle()
        loadedStartPageBackgroundBitmap = null
        loadedStartPageBackgroundUri = null
        binding.startPageBackgroundImage.setImageBitmap(null)
        binding.startPageBackgroundImage.visibility = View.GONE
        binding.startPageDimOverlay.visibility = View.GONE
    }

    private fun decodeSampledBitmapFromUri(uri: Uri, reqWidth: Int, reqHeight: Int): Bitmap? {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        runCatching {
            activity.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
        }
        if (options.outWidth <= 0 || options.outHeight <= 0) {
            return null
        }

        options.inSampleSize = calculateInSampleSize(options.outWidth, options.outHeight, reqWidth, reqHeight)
        options.inJustDecodeBounds = false
        options.inPreferredConfig = Bitmap.Config.RGB_565

        return runCatching {
            activity.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
        }.getOrNull()
    }

    private fun calculateInSampleSize(srcWidth: Int, srcHeight: Int, reqWidth: Int, reqHeight: Int): Int {
        var inSampleSize = 1
        if (srcHeight > reqHeight || srcWidth > reqWidth) {
            val halfHeight = srcHeight / 2
            val halfWidth = srcWidth / 2
            while ((halfHeight / inSampleSize) >= reqHeight && (halfWidth / inSampleSize) >= reqWidth) {
                inSampleSize *= 2
            }
        }
        return inSampleSize
    }

    fun refreshStartPageQuickLinks() {
        val links = BrowserPreferences.getQuickLinks(activity).map { url ->
            QuickLinkItem.Link(url, BrowserPreferences.getQuickLinkTitle(activity, url) ?: bookmarkManager.displayTitleForUrl(url))
        }
        startPageAdapter.submitList(if (links.size < BrowserPreferences.MAX_START_PAGE_SITES) links + QuickLinkItem.Add else links)
    }

    fun refreshStartPageResumeButton() {
        binding.startPageContent.buttonStartPageResume.isVisible = false
    }

    fun handleStartPageBackgroundPicked(uri: Uri?) {
        if (uri == null) {
            return
        }
        if (activity.contentResolver.openInputStream(uri)?.use { true } != true) {
            Toast.makeText(activity, R.string.start_page_background_error, Toast.LENGTH_SHORT).show()
            return
        }
        runCatching { activity.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        val prev = BrowserPreferences.getStartPageBackgroundUri(activity)
        BrowserPreferences.setStartPageBackgroundUri(activity, uri.toString())
        if (!prev.isNullOrBlank() && prev != uri.toString()) {
            runCatching { activity.contentResolver.releasePersistableUriPermission(Uri.parse(prev), Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        }
        refreshStartPage()
        Toast.makeText(activity, R.string.start_page_background_set, Toast.LENGTH_SHORT).show()
    }

    fun clearStartPageBackground() {
        val prev = BrowserPreferences.getStartPageBackgroundUri(activity)
        if (prev == null) {
            return
        }
        runCatching { activity.contentResolver.releasePersistableUriPermission(Uri.parse(prev), Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        BrowserPreferences.clearStartPageBackgroundUri(activity)
        refreshStartPage()
        Toast.makeText(activity, R.string.start_page_background_cleared, Toast.LENGTH_SHORT).show()
    }
}
