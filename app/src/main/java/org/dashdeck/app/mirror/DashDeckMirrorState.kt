/*
 * Copyright (c) 2026 BluDood (https://github.com/BluDood/BluMirror)
 * Copyright (C) 2026 Safwan Sayeed (https://github.com/sa-fw-an/DashDeck)
 *
 * Derived from BluMirror by BluDood (https://github.com/BluDood/BluMirror),
 * MIT License; see licenses/BluMirror-MIT.txt.
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

package org.dashdeck.app.mirror

import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.WindowManager
import android.util.Log
import android.view.Surface

object DashDeckMirrorState {
    private const val TAG = "DashDeckMirror"
    private val listeners = mutableSetOf<(Boolean) -> Unit>()
    private var projection: MediaProjection? = null
    private var surface: Surface? = null
    private var renderer: DashDeckMirrorRenderer? = null
    private var width = 0
    private var height = 0
    private var dpi = 160
    private var captureWidth = 0
    private var captureHeight = 0

    fun addListener(listener: (Boolean) -> Unit) {
        listeners += listener
        listener(projection != null)
    }

    fun removeListener(listener: (Boolean) -> Unit) { listeners -= listener }

    private var sizeReportedByProjection = false
    private var displayListener: DisplayManager.DisplayListener? = null

    fun setProjection(value: MediaProjection, context: Context) {
        projection = value
        sizeReportedByProjection = false
        watchPhoneDisplay(context.applicationContext)
        Log.i(TAG, "Screen capture permission granted; waiting for car surface if needed")
        startRenderer()
        notifyListeners()
    }

    fun setSurface(value: Surface, width: Int, height: Int, dpi: Int) {
        surface = value
        this.width = width
        this.height = height
        this.dpi = dpi
        Log.i(TAG, "Car surface attached: ${width}x$height at ${dpi}dpi")
        startRenderer()
    }

    fun clearSurface() {
        surface = null
        renderer?.updateSurface(null)
        Log.i(TAG, "Car surface detached")
    }

    fun isMirroring() = projection != null

    fun setCapturedContentSize(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        sizeReportedByProjection = true
        captureWidth = width
        captureHeight = height
        renderer?.resizeCapturedContent(width, height)
        Log.i(TAG, "Captured content size: ${width}x$height")
    }

    /**
     * Maps a point on the car surface to phone screen coordinates. The mirror is
     * drawn aspect-fit and centered, so points in the letterbox bars return null.
     */
    /**
     * Some devices never call onCapturedContentResize. Until they do, use the
     * phone's real display size, and keep it current as the phone rotates.
     */
    private fun watchPhoneDisplay(context: Context) {
        val displays = context.getSystemService(DisplayManager::class.java)
        fun refresh() {
            if (sizeReportedByProjection) return
            val display = displays.getDisplay(Display.DEFAULT_DISPLAY) ?: return
            val bounds = context.createDisplayContext(display)
                .getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds
            captureWidth = bounds.width()
            captureHeight = bounds.height()
        }
        refresh()
        displayListener?.let(displays::unregisterDisplayListener)
        displayListener = object : DisplayManager.DisplayListener {
            override fun onDisplayChanged(displayId: Int) { if (displayId == Display.DEFAULT_DISPLAY) refresh() }
            override fun onDisplayAdded(displayId: Int) = Unit
            override fun onDisplayRemoved(displayId: Int) = Unit
        }.also { displays.registerDisplayListener(it, Handler(Looper.getMainLooper())) }
        displayManager = displays
    }

    private var displayManager: DisplayManager? = null

    /**
     * Maps a point on the car surface to phone screen coordinates. The mirror is
     * drawn aspect-fit and centered. Points in the letterbox bars return null,
     * unless [clamp] is set (used while a finger drags across the edge).
     */
    fun mapToPhone(x: Float, y: Float, clamp: Boolean = false): android.graphics.PointF? {
        if (projection == null || captureWidth <= 1 || captureHeight <= 1 || width <= 0 || height <= 0) return null
        val scale = minOf(width.toFloat() / captureWidth, height.toFloat() / captureHeight)
        val left = (width - captureWidth * scale) / 2f
        val top = (height - captureHeight * scale) / 2f
        val phoneX = (x - left) / scale
        val phoneY = (y - top) / scale
        val maxX = captureWidth - 1f
        val maxY = captureHeight - 1f
        if (clamp) return android.graphics.PointF(phoneX.coerceIn(0f, maxX), phoneY.coerceIn(0f, maxY))
        if (phoneX !in 0f..maxX || phoneY !in 0f..maxY) return null
        return android.graphics.PointF(phoneX, phoneY)
    }

    fun stop(context: Context? = null) {
        displayListener?.let { listener -> displayManager?.unregisterDisplayListener(listener) }
        displayListener = null
        renderer?.stop()
        renderer = null
        val activeProjection = projection
        projection = null
        captureWidth = 0
        captureHeight = 0
        activeProjection?.stop()
        context?.stopService(Intent(context, DashDeckProjectionService::class.java))
        Log.i(TAG, "Screen capture stopped")
        notifyListeners()
    }

    private fun startRenderer() {
        val activeProjection = projection ?: return
        val activeSurface = surface ?: return
        if (!activeSurface.isValid) {
            Log.w(TAG, "Car surface is invalid; waiting for another surface")
            return
        }
        val existing = renderer
        if (existing != null) {
            existing.updateSurface(activeSurface)
            existing.resize(width, height, dpi)
            return
        }
        val newRenderer = DashDeckMirrorRenderer(activeProjection, activeSurface, width, height, dpi)
        runCatching { newRenderer.start() }
            .onSuccess {
                renderer = newRenderer
                if (captureWidth > 0 && captureHeight > 0) newRenderer.resizeCapturedContent(captureWidth, captureHeight)
                Log.i(TAG, "Mirror virtual display started")
            }
            .onFailure {
                Log.e(TAG, "Mirror virtual display could not start", it)
                activeProjection.stop()
            }
    }

    private fun notifyListeners() { listeners.toList().forEach { it(projection != null) } }
}
