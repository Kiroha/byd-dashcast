package com.byd.dashcast.update

/** Pure semantic/build version comparison used by the OTA release selector. */
object OtaVersionPolicy {

    private val RELEASE_VERSION = Regex(
        "^\\d+\\.\\d+\\.\\d+(?:\\.\\d+)?(?:-(?:alpha|beta|rc)\\d*|-(?:build|b)\\d+)?$",
        RegexOption.IGNORE_CASE,
    )

    @JvmStatic
    fun isValidReleaseVersion(version: String): Boolean = RELEASE_VERSION.matches(version)

    @JvmStatic
    fun compareVersions(left: String, right: String): Int {
        val leftParts = parseVersion(stripSuffix(left))
        val rightParts = parseVersion(stripSuffix(right))
        for (index in 0 until maxOf(leftParts.size, rightParts.size)) {
            val leftPart = if (index < leftParts.size) leftParts[index] else 0
            val rightPart = if (index < rightParts.size) rightParts[index] else 0
            if (leftPart != rightPart) return leftPart.compareTo(rightPart)
        }
        val leftBuild = extractBuild(left)
        val rightBuild = extractBuild(right)
        if (leftBuild >= 0 || rightBuild >= 0) return leftBuild.compareTo(rightBuild)
        return suffixRank(left).compareTo(suffixRank(right))
    }

    @JvmStatic
    fun isNewer(latest: String, currentName: String, currentCode: Int): Boolean {
        if (!isValidReleaseVersion(latest)) return false
        val latestBuild = extractBuild(latest)
        val latestParts = parseVersion(stripSuffix(latest))
        val currentParts = parseVersion(stripSuffix(currentName))
        for (index in 0 until maxOf(latestParts.size, currentParts.size)) {
            val latestPart = if (index < latestParts.size) latestParts[index] else 0
            val currentPart = if (index < currentParts.size) currentParts[index] else 0
            if (latestPart != currentPart) return latestPart > currentPart
        }
        if (latestBuild > 0) return latestBuild > currentCode
        return compareVersions(latest, currentName) > 0
    }

    private fun suffixRank(version: String): Int {
        val suffix = version.substringAfter('-', "").lowercase()
        return when {
            suffix.isEmpty() -> 4
            suffix.startsWith("rc") -> 3
            suffix.startsWith("beta") -> 2
            suffix.startsWith("alpha") -> 1
            else -> 0
        }
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
