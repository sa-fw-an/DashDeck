/* Derived from BluMirror by BluDood (MIT License). */
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
