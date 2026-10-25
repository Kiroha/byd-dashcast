package com.byd.dashcast.proxy.daemon

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Binder
import android.os.Process
import com.byd.dashcast.BuildConfig

/**
 * Authorization belongs to the currently installed package, not the UID it had when app_process
 * started. A single PM snapshot per app call also revokes a UID reassigned to another package.
 *
 * The immutable trust anchor comes from the APK actually loaded by this daemon (CLASSPATH), never
 * systemContext.applicationInfo or the requesting app. Exact current signer sets are required:
 * certificate rotation safely refuses reuse and requires a daemon from the newly signed APK.
 * No OEM certificate, positive-identity TTL, archive IO or resource lock is used on the input path.
 */
internal class DaemonCallerIdentity(
    private val daemonUid: Int,
    private val packageName: String,
    trustedSigners: List<ByteArray>,
    private val installedPackage: () -> PackageInfo?
) {
    private val trustedSigners = trustedSigners.map(ByteArray::clone)

    fun isAllowed(callingUid: Int): Boolean {
        if (DaemonCallerPolicy.isAllowed(callingUid, daemonUid, -1)) return true
        if (callingUid < 0) return false
        return DaemonCallerPolicy.isAllowed(callingUid, daemonUid, installedUid())
    }

    fun installedUid(): Int {
        // Metadata is resolved as the daemon, not with the untrusted Binder caller's identity.
        // Restore before returning to the verb: this does not confer identity on its execution.
        val identity = Binder.clearCallingIdentity()
        return try {
            val installed = installedPackage() ?: return -1
            val uid = installed.applicationInfo?.uid ?: return -1
            val signers = signerBytes(installed)
            if (installed.packageName != packageName || uid < 0 || trustedSigners.isEmpty() ||
                signers.isEmpty() || signers.size != trustedSigners.size ||
                signers.any { signer -> trustedSigners.none { it.contentEquals(signer) } } ||
                trustedSigners.any { signer -> signers.none { it.contentEquals(signer) } }) -1 else uid
        } catch (_: Exception) {
            -1 // PM unavailable, package absent, or unreadable signing metadata: fail closed.
        } finally {
            Binder.restoreCallingIdentity(identity)
        }
    }

    companion object {
        @Suppress("DEPRECATION")
        fun fromLoadedApk(context: Context, classPath: String? = System.getenv("CLASSPATH")): DaemonCallerIdentity? {
            if (classPath.isNullOrBlank() || classPath.contains(':')) return null
            val pm = context.packageManager
            // A brief PM startup failure may recover before publication. Never publish a daemon
            // with an empty immutable anchor: it could not become usable on a later app call.
            repeat(3) { attempt ->
                val identity = Binder.clearCallingIdentity()
                val anchor = try {
                    val archive = pm.getPackageArchiveInfo(classPath,
                        PackageManager.GET_SIGNING_CERTIFICATES or PackageManager.GET_SIGNATURES)
                    if (archive?.packageName == BuildConfig.APPLICATION_ID) signerBytes(archive) else emptyList()
                } catch (_: Exception) {
                    emptyList()
                } finally {
                    Binder.restoreCallingIdentity(identity)
                }
                if (anchor.isNotEmpty()) return DaemonCallerIdentity(Process.myUid(), BuildConfig.APPLICATION_ID, anchor) {
                    pm.getPackageInfo(BuildConfig.APPLICATION_ID, PackageManager.GET_SIGNING_CERTIFICATES)
                }
                if (attempt < 2) try { Thread.sleep(100) } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return null
                }
            }
            return null
        }

        private fun signerBytes(info: PackageInfo): List<ByteArray> =
            info.signingInfo?.apkContentsSigners?.map { it.toByteArray() }.orEmpty()
    }
}
