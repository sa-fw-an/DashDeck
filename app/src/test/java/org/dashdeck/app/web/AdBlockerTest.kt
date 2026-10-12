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
