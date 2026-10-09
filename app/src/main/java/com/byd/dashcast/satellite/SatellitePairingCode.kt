package com.byd.dashcast.satellite

import java.security.SecureRandom

/** A short human secret used only by the password-authenticated J-PAKE exchange. */
internal object SatellitePairingCode {
    const val PORT = 47833
    const val TTL_MS = 120_000L
    const val CODE_LENGTH = 6
    const val MAX_FRAME_BYTES = 24_576
    const val MAX_PROFILE_BYTES = 16_384
    private val random = SecureRandom()

    fun newCode(): String = buildString { repeat(CODE_LENGTH) { append(('0'.code + random.nextInt(10)).toChar()) } }

    fun normalize(raw: String): String {
        require(raw.length <= 32) { "Invalid pairing code" }
        val code = raw.filterNot { it == ' ' || it == '-' }
        require(code.length == CODE_LENGTH && code.all { it in '0'..'9' }) { "Invalid pairing code" }
        return code
    }

    fun format(code: String): String = normalize(code).chunked(3).joinToString("-")
}
