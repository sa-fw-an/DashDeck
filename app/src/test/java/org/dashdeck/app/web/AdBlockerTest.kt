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

package org.dashdeck.app.web

import org.junit.Assert.assertEquals
import org.junit.Test

class AdBlockerTest {
    @Test
    fun infersResourceTypes() {
        assertEquals("script", AdBlocker.resourceType("/ads/gpt.js", "*/*"))
        assertEquals("stylesheet", AdBlocker.resourceType("/site.css", "*/*"))
        assertEquals("image", AdBlocker.resourceType("/pixel", "image/avif,image/webp,*/*"))
        assertEquals("sub_frame", AdBlocker.resourceType("/embed", "text/html,application/xhtml+xml"))
        assertEquals("media", AdBlocker.resourceType("/clip.mp4", ""))
        assertEquals("xmlhttprequest", AdBlocker.resourceType("/api/data", "*/*"))
    }
}
