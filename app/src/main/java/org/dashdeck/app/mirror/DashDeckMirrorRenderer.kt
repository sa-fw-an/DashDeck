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

import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.view.Surface

class DashDeckMirrorRenderer(
    private val projection: MediaProjection,
    private var surface: Surface?,
    private var width: Int,
    private var height: Int,
    private var dpi: Int
) {
    private var display: VirtualDisplay? = null
    private var capturedWidth = 0
    private var capturedHeight = 0

    fun start() {
        if (display != null) return
        display = projection.createVirtualDisplay(
            "DashDeckMirror", width.coerceAtLeast(1), height.coerceAtLeast(1), dpi.coerceAtLeast(160),
            // This is a projection of the selected phone display/app onto the
            // Android Auto surface, not a private display owned by DashDeck.
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, surface, null, null
        )
    }

    fun updateSurface(newSurface: Surface?) { if (surface != newSurface) { surface = newSurface; display?.surface = newSurface } }
    fun resize(newWidth: Int, newHeight: Int, newDpi: Int) {
        if (width != newWidth || height != newHeight || dpi != newDpi) {
            width = newWidth; height = newHeight; dpi = newDpi
            display?.resize((if (capturedWidth > 0) capturedWidth else width).coerceAtLeast(1),
                (if (capturedHeight > 0) capturedHeight else height).coerceAtLeast(1), dpi.coerceAtLeast(160))
        }
    }

    fun resizeCapturedContent(newWidth: Int, newHeight: Int) {
        if (capturedWidth == newWidth && capturedHeight == newHeight) return
        capturedWidth = newWidth
        capturedHeight = newHeight
        display?.resize(newWidth.coerceAtLeast(1), newHeight.coerceAtLeast(1), dpi.coerceAtLeast(160))
    }
    fun stop() { display?.release(); display = null }
}
