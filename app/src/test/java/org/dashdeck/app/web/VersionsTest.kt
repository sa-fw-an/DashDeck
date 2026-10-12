package org.dashdeck.app.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VersionsTest {
    @Test
    fun comparesVersionsNumerically() {
        assertTrue(compareVersions("0.13.10", "0.13.9") > 0)
        assertTrue(compareVersions("0.14.0", "0.13.3") > 0)
        assertTrue(compareVersions("0.13.3", "0.13.3-1") < 0)
        assertEquals(0, compareVersions("0.13.3", "0.13.3.0"))
    }
}
