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
