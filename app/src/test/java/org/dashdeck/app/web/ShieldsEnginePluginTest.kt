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

class ShieldsEnginePluginTest {
    @Test
    fun signedPayloadMatchesReleaseScript() {
        // scripts/shields-engine-release.sh signs exactly this string.
        val manifest = ShieldsEnginePlugin.Manifest(
            apiVersion = 2, engineVersion = "0.13.3", abi = "arm64-v8a",
            file = "libdashdeck_brave_adblock-0.13.3.so", sha256 = "ab12", size = 42,
            signature = "", sourceUrl = ""
        )
        assertEquals(
            "dashdeck-shields-engine|2|0.13.3|arm64-v8a|libdashdeck_brave_adblock-0.13.3.so|ab12|42",
            manifest.signedPayload()
        )
    }
}
