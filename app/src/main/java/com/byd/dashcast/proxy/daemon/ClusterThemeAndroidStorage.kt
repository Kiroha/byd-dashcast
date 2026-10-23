package com.byd.dashcast.proxy.daemon

import android.system.Os
import android.system.OsConstants
import android.os.ParcelFileDescriptor
import com.byd.dashcast.clusterlive.ClusterThemePolicy
import java.io.File

/** The sole Android-side alias researched for the host's cell2 media directory. */
internal object ClusterThemeAndroidStorage {
    data class Directory(val path: String, val identity: String)
    fun directory(create: Boolean): Directory = withDirectory(create) { _, directory -> directory }
    fun <T> withDirectory(create: Boolean = false, action: (String, Directory) -> T): T {
        // /sdcard itself is an Android mount alias; children must be real directories, never links.
        val base = File("/sdcard").canonicalFile
        check(base.path == "/storage/emulated/0") { "Only Android primary media user 0 matches the researched host path" }
        val descriptors = ArrayList<ParcelFileDescriptor>()
        val identities = ArrayList<String>()
        try {
            var path = base.path
            for (part in listOf("", ".cluster", "translate")) {
                if (part.isNotEmpty()) {
                    path = "/proc/self/fd/${descriptors.last().fd}/$part"
                    if (create && !File(path).exists()) Os.mkdir(path, 493)
                }
                val fd = Os.open(path, OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW or OsConstants.O_CLOEXEC or OsConstants.O_NONBLOCK, 0)
                val descriptor = try { ParcelFileDescriptor.dup(fd) } finally { Os.close(fd) }
                descriptors.add(descriptor)
                val st = Os.fstat(descriptor.fileDescriptor)
                check(OsConstants.S_ISDIR(st.st_mode)) { "Carrier directory is missing, replaced or a link" }
                identities.add("${st.st_dev}:${st.st_ino}")
            }
            val expected = File(base, ".cluster/translate")
            check(File(ClusterThemePolicy.ANDROID_DIRECTORY).canonicalFile == expected) { "Android carrier directory alias changed" }
            // Verify the named directories still resolve to the pinned generations before acting.
            listOf(base, File(base, ".cluster"), expected).forEachIndexed { index, file ->
                val st = Os.lstat(file.path)
                check(OsConstants.S_ISDIR(st.st_mode) && "${st.st_dev}:${st.st_ino}" == identities[index]) { "Carrier parent generation changed" }
            }
            return action("/proc/self/fd/${descriptors.last().fd}/", Directory(expected.path + "/", identities.joinToString("/")))
        } finally { descriptors.asReversed().forEach { runCatching { it.close() } } }
    }
}
