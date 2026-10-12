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
