package com.byd.dashcast.proxy.daemon

import android.os.Process
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import org.json.JSONException
import org.json.JSONObject
import java.io.File

/** A helper creates its record before writing it, then fsyncs before reading GO. */
internal object ClusterThemeHelperReadiness {
    fun await(token: String, filename: String, deadline: Long, alive: () -> Boolean, read: () -> JSONObject?, clock: () -> Long,
        pause: () -> Unit): JSONObject {
        while (clock() < deadline && alive()) {
            read()?.let {
                check(it.get("token") is String && it.getString("token") == token &&
                    it.get("filename") is String && it.getString("filename") == filename &&
                    it.get("pid") is Int && it.getInt("pid") > 0 &&
                    it.get("start") is String && it.getString("start").matches(Regex("[0-9]+"))) {
                    "Helper readiness identity differs; no GO sent"
                }
                return it
            }
            pause()
        }
        error("Helper readiness unavailable before admission; childAlive=${alive()}; no GO sent")
    }

    internal fun parse(text: String): JSONObject? {
        if (text.isBlank() || !text.trimEnd().endsWith('}')) return null
        return try { JSONObject(text) } catch (_: JSONException) { null }
    }

    fun read(record: File): JSONObject? {
        val fd = try { Os.open(record.path,
            OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW or OsConstants.O_NONBLOCK or OsConstants.O_CLOEXEC, 0)
        } catch (error: ErrnoException) { if (error.errno == OsConstants.ENOENT) return null else throw error }
        try {
            val before = Os.fstat(fd)
            check(OsConstants.S_ISREG(before.st_mode) && before.st_uid == Process.myUid() && before.st_size in 0..1024) {
                "Helper readiness type/owner/size differs; no GO sent"
            }
            val buffer = ByteArray(1025)
            var total = 0
            while (total < buffer.size) {
                val count = Os.read(fd, buffer, total, buffer.size - total)
                if (count == 0) break
                check(count > 0); total += count
            }
            check(total <= 1024) { "Helper readiness oversized; no GO sent" }
            val after = Os.fstat(fd)
            val named = Os.lstat(record.path)
            check(OsConstants.S_ISREG(named.st_mode) && named.st_uid == before.st_uid &&
                named.st_dev == before.st_dev && named.st_ino == before.st_ino) { "Helper readiness replaced; no GO sent" }
            return if (after.st_size != before.st_size || total.toLong() != after.st_size || after.st_mtime != before.st_mtime) null
            else parse(String(buffer, 0, total, Charsets.UTF_8))
        } finally { Os.close(fd) }
    }
}
