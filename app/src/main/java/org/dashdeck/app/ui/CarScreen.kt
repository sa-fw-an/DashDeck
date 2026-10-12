package org.dashdeck.app.ui

import java.lang.ref.WeakReference

/**
 * Tracks the DashDeck window running on the Android Auto display, so the phone
 * can hand a page over to it ("Continue on car screen").
 *
 * Android Auto only allows apps onto the car display from its own launcher, so
 * the phone cannot open DashDeck there itself; it can only reuse a window the
 * driver already opened.
 */
object CarScreen {
    private var window: WeakReference<(String) -> Unit>? = null

    val isOpen: Boolean get() = window?.get() != null

    fun register(open: (String) -> Unit) {
        window = WeakReference(open)
    }

    fun unregister(open: (String) -> Unit) {
        if (window?.get() === open) window = null
    }

    /** Returns false when no car window is open. */
    fun open(url: String): Boolean {
        val target = window?.get() ?: return false
        target(url)
        return true
    }
}
