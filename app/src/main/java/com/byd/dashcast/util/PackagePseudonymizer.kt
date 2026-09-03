package com.byd.dashcast.util

import java.nio.charset.StandardCharsets
import java.security.GeneralSecurityException
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Creates stable per-installation package markers without exposing a global dictionary hash. */
object PackagePseudonymizer {

    private val HEX = "0123456789abcdef".toCharArray()
    private const val MARKER_BYTES = 8

    /** Returns the first 64 bits of HMAC-SHA-256 as lower-case hexadecimal. */
    @JvmStatic
    fun marker(installationKey: ByteArray?, packageName: String?): String {
        require(installationKey != null && installationKey.size >= 16) {
            "installationKey must contain at least 128 bits"
        }
        requireNotNull(packageName) { "packageName required" }
        return try {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(installationKey, "HmacSHA256"))
            val digest = mac.doFinal(packageName.toByteArray(StandardCharsets.UTF_8))
            val marker = CharArray(MARKER_BYTES * 2)
            for (i in 0 until MARKER_BYTES) {
                val value = digest[i].toInt() and 0xff
                marker[i * 2] = HEX[value ushr 4]
                marker[i * 2 + 1] = HEX[value and 0x0f]
            }
            String(marker)
        } catch (e: GeneralSecurityException) {
            throw IllegalStateException("HmacSHA256 unavailable", e)
        }
    }
}
