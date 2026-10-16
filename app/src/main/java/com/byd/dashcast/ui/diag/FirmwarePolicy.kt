package com.byd.dashcast.ui.diag

import java.io.File
import java.io.IOException
import java.util.Base64

/** Closed firmware-file scope. Neither a partition image nor an unrestricted filesystem dump. */
internal object FirmwarePolicy {
    const val PROFILE = "FIRMWARE_LARGE_V1"
    const val SCHEMA = 1
    const val MAX_SOURCES = 200_000
    const val MAX_INVENTORY_BYTES = 64L * 1024 * 1024
    const val MAX_INCIDENTS = 2048
    const val BUFFER = 256 * 1024
    const val PAGE = 16
    const val MAX_LINE = 16 * 1024
    val roots = listOf("/system", "/system_ext", "/product", "/vendor", "/odm", "/oem")
    private val apexName = Regex("[A-Za-z0-9_][A-Za-z0-9_.-]*(?:@[0-9]+)?")
    fun sane(path: String): Boolean = path.startsWith('/') && path.length <= 4096 &&
        '\u0000' !in path && path.split('/').drop(1).all { it.isNotEmpty() && it != "." && it != ".." }
    fun firmware(path: String): Boolean {
        if (!sane(path)) return false
        if (roots.any { path == it || path.startsWith("$it/") }) return true
        val part = path.removePrefix("/apex/").substringBefore('/')
        return path.startsWith("/apex/") && apexName.matches(part) && part != "apex-info-list.xml"
    }
    fun apexRoot(path: String) = sane(path) && path.startsWith("/apex/") &&
        path.removePrefix("/apex/").let { '/' !in it && apexName.matches(it) && it != "apex-info-list.xml" }
    fun packagePath(path: String): Boolean = sane(path) && path.endsWith(".apk", true) &&
        (firmware(path) || path.startsWith("/data/app/") || path.startsWith("/data/app-private/") ||
            Regex("/mnt/expand/[A-Za-z0-9-]+/app/.+\\.apk", RegexOption.IGNORE_CASE).matches(path))
    fun accepted(path: String, canonical: String, positivePackage: Boolean = false): Boolean =
        (firmware(path) && firmware(canonical)) || (positivePackage && packagePath(path) && packagePath(canonical))
    fun decoded(value: String): String = Charsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(Base64.getDecoder().decode(value))).toString()
    fun json(value: String): String = buildString {
        append('"'); for (c in value) when(c) {
            '"' -> append("\\\""); '\\' -> append("\\\\"); '\n' -> append("\\n"); '\r' -> append("\\r"); '\t' -> append("\\t")
            else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
        }; append('"')
    }
    fun boundedLines(text: String): List<String> {
        if (text.length > 240 * 1024) throw IOException("Shell reply exceeds firmware page budget")
        val rows = ArrayList<String>()
        var start = 0
        for (i in text.indices) {
            if (i - start > MAX_LINE) throw IOException("Shell inventory line exceeds $MAX_LINE characters")
            if (text[i] == '\n') { rows.add(text.substring(start, i)); start = i + 1 }
        }
        if (text.length - start > MAX_LINE) throw IOException("Shell inventory line exceeds $MAX_LINE characters")
        if (start < text.length) rows.add(text.substring(start))
        return rows
    }
    fun safeName(path: String) = File(path).name.map { if (it.isLetterOrDigit() || it in "._-") it else '_' }.joinToString("").take(100).ifEmpty { "file" }
}

internal data class FirmwareSource(val id: Long = 0, val canonical: String,
    val appPath: String? = null, val appSize: Long? = null, val appStamp: String? = null,
    val shellPath: String? = null, val shellSize: Long? = null, val shellStamp: String? = null,
    val packageOrigin: String = "", val error: String = "") {
    val size: Long get() = appSize ?: shellSize ?: 0
    val path: String get() = appPath ?: shellPath ?: canonical
    val positivePackage: Boolean get() = packageOrigin.isNotEmpty()
}
internal data class FirmwareObserved(val path: String, val canonical: String, val size: Long, val stamp: String)
internal data class FirmwareDirectory(val id: Long, val identity: String, val path: String, val offset: Int)

/** Streaming NUL records, including trailing newlines in names; no unbounded readLine/string. */
internal object FirmwareNames {
    fun read(input: java.io.InputStream, issue:(String)->Unit, consume:(String?)->Unit) {
        val buffer=ByteArray(FirmwarePolicy.BUFFER)
        val name=ByteArray(4097);var length=0;var overlong=false;var total=0L
        while(true){val n=input.read(buffer);if(n<0)break;if(n==0)throw IOException("Snapshot read made no progress")
            total+=n;if(total>16L*1024*1024)throw IOException("Snapshot exceeds 16MiB budget")
            for(i in 0 until n){val value=buffer[i]
                if(value==0.toByte()) {
                    val path=if(overlong||length==0){issue("Overlong/empty pathname omitted");null}else try {
                        Charsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(name,0,length)).toString()
                    }catch(e:java.nio.charset.CharacterCodingException){issue("Non UTF-8 pathname omitted");null}
                    consume(path);length=0;overlong=false
                }else if(length<4096)name[length++]=value else overlong=true
            }
        }
        if(length!=0||overlong)issue("Snapshot ends in an incomplete NUL pathname; entry omitted")
    }
}
