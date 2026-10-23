package com.byd.dashcast.proxy.daemon

import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import android.system.StructStat
import com.byd.dashcast.clusterlive.ClusterThemeCohort
import com.byd.dashcast.clusterlive.ClusterThemePolicy
import com.byd.dashcast.clusterlive.ClusterThemeVariant
import java.io.FileDescriptor
import java.security.MessageDigest

/** Fixed system paths only. Keep every FD pinned until the complete cohort is rechecked. */
internal object ClusterThemeCohortVerifier {
    private data class Opened(val path: String, val fd: FileDescriptor, var before: StructStat? = null)
    fun verify(variant: ClusterThemeVariant, deadline: Long, log: (String) -> Unit = {}): String {
        val opened = ArrayList<Opened>()
        val actual = LinkedHashMap<String, String>()
        fun current() = check(SystemClock.elapsedRealtime() < deadline) { "Installed cohort hash deadline exceeded" }
        fun same(a: StructStat, b: StructStat): Boolean = a.st_dev == b.st_dev && a.st_ino == b.st_ino &&
            a.st_mode == b.st_mode && a.st_uid == b.st_uid && a.st_size == b.st_size &&
            a.st_mtime == b.st_mtime && a.st_ctime == b.st_ctime
        try {
            for (path in ClusterThemeCohort.expected(variant).keys) {
                current()
                val fd = Os.open(path, OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW or OsConstants.O_NONBLOCK or OsConstants.O_CLOEXEC, 0)
                val entry = Opened(path, fd).also(opened::add)
                val before = Os.fstat(fd); entry.before = before
                check(OsConstants.S_ISREG(before.st_mode) && before.st_uid == 0 && before.st_size in 1..ClusterThemePolicy.MAX_ARTIFACT) {
                    "Unsupported installed cohort artifact: $path"
                }
                val digest = MessageDigest.getInstance("SHA-256"); val buffer = ByteArray(65536); var total = 0L
                while (true) {
                    current(); val n = Os.read(fd, buffer, 0, buffer.size); if (n == 0) break
                    check(n > 0); total += n; check(total <= before.st_size)
                    digest.update(buffer, 0, n)
                }
                check(total == before.st_size && same(before, Os.fstat(fd)) && same(before, Os.lstat(path))) {
                    "Installed cohort artifact changed while hashing: $path"
                }
                actual[path] = digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
                log("path=$path SHA256=${actual[path]}")
            }
            for (entry in opened) {
                current(); val before = checkNotNull(entry.before)
                check(same(before, Os.fstat(entry.fd)) && same(before, Os.lstat(entry.path))) {
                    "Installed cohort changed before completion: ${entry.path}"
                }
            }
            ClusterThemeCohort.requireMatch(variant, actual)
            return ClusterThemeCohort.id(variant)
        } finally { opened.asReversed().forEach { runCatching { Os.close(it.fd) } } }
    }
}
