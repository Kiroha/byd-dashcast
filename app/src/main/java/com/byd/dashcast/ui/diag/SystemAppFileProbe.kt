package com.byd.dashcast.ui.diag

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.attribute.BasicFileAttributes

/** Metadata under the app identity only. Attribute failures stay failures, never size zero. */
internal object SystemAppFileProbe {
    data class Attributes(val regular: Boolean, val size: Long)
    data class Result(val canonical: String, val size: Long)
    interface Access {
        fun canonical(path: String): String
        fun attributes(canonical: String): Attributes
        fun readable(path: String): Boolean
    }
    private val actual = object : Access {
        override fun canonical(path: String) = File(path).canonicalPath
        override fun attributes(canonical: String): Attributes {
            val value = Files.readAttributes(File(canonical).toPath(), BasicFileAttributes::class.java)
            return Attributes(value.isRegularFile, value.size())
        }
        override fun readable(path: String) = File(path).canRead()
    }

    fun inspect(path: String, profile: SystemExportProfile, access: Access = actual): Result {
        if (!profile.allowed(path)) throw IOException("Source path outside selected policy")
        val canonical = access.canonical(path)
        if (!profile.accepted(path, canonical)) throw IOException("Canonical path outside selected policy: $canonical")
        val attributes = access.attributes(canonical)
        if (!attributes.regular) throw IOException("Canonical target is not a regular file")
        if (attributes.size <= 0) throw IOException("Regular file is empty or has invalid size")
        if (!access.readable(path)) throw IOException("Regular file read denied under app identity")
        return Result(canonical, attributes.size)
    }
}
