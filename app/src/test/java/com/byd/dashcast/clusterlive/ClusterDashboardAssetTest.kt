package com.byd.dashcast.clusterlive

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.security.MessageDigest

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class ClusterDashboardAssetTest {
    @Test fun `packaged closed profile hashes and Android application rectangle match native carrier manifest`() {
        val assets = File("src/main/assets").takeIf { it.isDirectory } ?: File("app/src/main/assets")
        for (variant in ClusterThemeVariant.values()) {
            val bytes = File(assets, variant.asset).readBytes()
            val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
            assertEquals(variant.id, variant.carrierSha256, hash)
        }
        val manifest = JSONObject(File(assets, "cluster_live/full_dashboard_21f7735c_manifest.json").readText())
        assertEquals(ClusterThemeVariant.FULL_DASHBOARD_21F7735C.carrierSha256, manifest.getString("carrier_sha256"))
        val rect = manifest.getJSONObject("application_rect")
        assertEquals(ClusterDashboardProfile.APP_X, rect.getInt("x")); assertEquals(ClusterDashboardProfile.APP_Y, rect.getInt("y"))
        assertEquals(ClusterDashboardProfile.APP_WIDTH, rect.getInt("width")); assertEquals(ClusterDashboardProfile.APP_HEIGHT, rect.getInt("height"))
        assertEquals(78, manifest.getInt("native_telltales")); assertEquals(84, manifest.getJSONObject("resources").length())
        assertFalse(manifest.getBoolean("vehicle_validated"))
    }
}
