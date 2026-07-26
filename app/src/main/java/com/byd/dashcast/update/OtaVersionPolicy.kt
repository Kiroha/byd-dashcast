package com.byd.dashcast.update

/** Pure semantic/build version comparison used by the OTA release selector. */
object OtaVersionPolicy {

    @JvmStatic
    fun isNewer(latest: String, currentName: String, currentCode: Int): Boolean {
        val latestBuild = extractBuild(latest)
        val latestParts = parseVersion(stripSuffix(latest))
        val currentParts = parseVersion(stripSuffix(currentName))
        for (index in 0 until maxOf(latestParts.size, currentParts.size)) {
            val latestPart = if (index < latestParts.size) latestParts[index] else 0
            val currentPart = if (index < currentParts.size) currentParts[index] else 0
            if (latestPart != currentPart) return latestPart > currentPart
        }
        return latestBuild > 0 && latestBuild > currentCode
    }

    private fun extractBuild(tag: String): Int {
        val dash = tag.indexOf('-')
        if (dash < 0 || dash + 1 >= tag.length) return -1
        var suffix = tag.substring(dash + 1)
        if (suffix.startsWith("build")) {
            suffix = suffix.substring(5)
        } else if (suffix.startsWith("b") && suffix.length > 1 && suffix[1].isDigit()) {
            suffix = suffix.substring(1)
        }
        return try {
            suffix.toInt()
        } catch (ignored: NumberFormatException) {
            -1
        }
    }

    private fun stripSuffix(version: String): String {
        val dash = version.indexOf('-')
        return if (dash < 0) version else version.substring(0, dash)
    }

    private fun parseVersion(version: String): IntArray {
        val parts = version.split(".")
        val numbers = IntArray(parts.size)
        for (index in parts.indices) {
            numbers[index] = try {
                parts[index].toInt()
            } catch (ignored: NumberFormatException) {
                0
            }
        }
        return numbers
    }
}
