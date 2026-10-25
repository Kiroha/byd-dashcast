package com.byd.dashcast.infrastructure

import android.os.IBinder
import android.os.Parcel
import com.byd.dashcast.BuildConfig
import com.byd.dashcast.proxy.daemon.ProxyDaemonContract
import com.byd.dashcast.proxy.daemon.SurfaceDaemon
import java.util.EnumSet
import java.util.concurrent.FutureTask
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Bounded migration for a pre-freshness daemon which explicitly rejects the installed app. */
internal class LegacyDaemonRecovery {
    enum class Access { ALLOWED, DENIED, UNAVAILABLE }
    enum class Endpoint { PROXY, SURFACE }
    data class Generation(val pid: Int, val started: Long)

    // One migration per endpoint per app process. An unrepairable metadata/signing failure must
    // never create a kill/spawn loop through a succession of new Binder generations.
    private val attempted = EnumSet.noneOf(Endpoint::class.java)

    /** Called on the bootstrap worker, never from the input path. No verb is replayed here. */
    fun recover(endpoint: Endpoint, expected: IBinder, shell: (String) -> String,
                installedApk: String? = null,
                isCurrent: () -> Boolean): Boolean {
        if (!isCurrent() || probe(endpoint, expected) != Access.DENIED) return false
        synchronized(attempted) { if (!attempted.add(endpoint)) return false }
        val generation = parseGeneration(shell(inspectCommand(endpoint, installedApk))) ?: return false
        // The probe which failed before the ADB roundtrip may already belong to an old Binder.
        if (!isCurrent() || probe(endpoint, expected) != Access.DENIED || !isCurrent()) return false
        return shell(terminateCommand(endpoint, generation, installedApk)).trim() == "RETIRED"
    }

    companion object {
        private const val PROBE_TIMEOUT_MS = 1000L
        // Binder cancellation cannot interrupt a wedged remote transaction. A single bounded lane
        // per endpoint leaves at most one such call alive. One pending slot permits immediate
        // sequential probes while the worker finishes its previous FutureTask. Timed-out pending
        // tasks are removed, so repeated reconnects cannot accumulate Binder work.
        private val probeWorkers = Endpoint.entries.associateWith { endpoint ->
            ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, ArrayBlockingQueue(1)) { task ->
                Thread(task, "daemon-auth-probe-${endpoint.name.lowercase()}").apply { isDaemon = true }
            }
        }

        /** Both probes are read only, supported by old daemons, and leave surfaces/leases intact. */
        fun probe(endpoint: Endpoint, binder: IBinder): Access {
            val task = FutureTask { probeNow(endpoint, binder) }
            return try {
                probeWorkers.getValue(endpoint).execute(task)
                task.get(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            } catch (error: Exception) {
                task.cancel(true)
                probeWorkers.getValue(endpoint).remove(task)
                if (error is InterruptedException) Thread.currentThread().interrupt()
                Access.UNAVAILABLE
            }
        }

        private fun probeNow(endpoint: Endpoint, binder: IBinder): Access {
            if (!binder.isBinderAlive) return Access.UNAVAILABLE
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            return try {
                val code = when (endpoint) {
                    Endpoint.PROXY -> {
                        data.writeInterfaceToken(ProxyDaemonContract.DESCRIPTOR)
                        ProxyDaemonContract.TXN_WHOAMI
                    }
                    Endpoint.SURFACE -> {
                        data.writeInterfaceToken(SurfaceDaemon.DESCRIPTOR)
                        data.writeString("") // Query an absent slot; never attach/release a resource.
                        SurfaceDaemon.TRANSACT_QUERY_SLOT
                    }
                }
                if (!binder.transact(code, data, reply, 0)) Access.UNAVAILABLE else {
                    reply.readException()
                    // A malformed/empty response is not evidence of successful authorization.
                    when (endpoint) {
                        Endpoint.PROXY -> {
                            val uid = reply.readInt()
                            val pid = reply.readInt()
                            val version = reply.readString()
                            if (uid < 0 || pid <= 0 || version.isNullOrEmpty()) Access.UNAVAILABLE else Access.ALLOWED
                        }
                        Endpoint.SURFACE -> if (reply.dataAvail() >= 4) {
                            reply.readInt()
                            Access.ALLOWED
                        } else Access.UNAVAILABLE
                    }
                }
            } catch (rejected: SecurityException) {
                // Only the historical cached-UID refusal warrants migration. A current daemon's
                // fresh-PM/signing failure is unavailable, and must preserve held surfaces.
                if (rejected.message?.matches(Regex("caller uid [0-9]+ not permitted")) == true)
                    Access.DENIED else Access.UNAVAILABLE
            } catch (_: Exception) {
                Access.UNAVAILABLE
            } finally {
                reply.recycle()
                data.recycle()
            }
        }

        fun parseGeneration(output: String): Generation? {
            val fields = output.trim().split(' ')
            if (fields.size != 3 || fields[0] != "GENERATION") return null
            val pid = fields[1].toIntOrNull() ?: return null
            val started = fields[2].toLongOrNull() ?: return null
            return if (pid > 0 && started > 0) Generation(pid, started) else null
        }

        fun inspectCommand(endpoint: Endpoint, installedApk: String? = null): String = identityCommand(endpoint, installedApk) +
            "; echo GENERATION \"\$PID\" \"\$STARTED\""

        fun terminateCommand(endpoint: Endpoint, expected: Generation, installedApk: String? = null): String {
            require(expected.pid > 0 && expected.started > 0)
            return identityCommand(endpoint, installedApk) +
                "; [ \"\$PID\" = '${expected.pid}' ] && [ \"\$STARTED\" = '${expected.started}' ] || exit 4" +
                "; kill -9 \"\$PID\" 2>/dev/null && echo RETIRED"
        }

        private fun identityCommand(endpoint: Endpoint, installedApk: String?): String {
            val marker = if (endpoint == Endpoint.PROXY) "/data/local/tmp/dashcast_proxy.pid"
                else SurfaceDaemon.VERSION_FILE
            val runtimeNames = if (endpoint == Endpoint.PROXY) "dashcast_proxy"
                else "com.byd.dashcast.mirrordaemon|byd.mirror.daemon"
            val processPredicate = if (endpoint == Endpoint.PROXY) "\$NF==\"dashcast_proxy\""
                else "(\$NF==\"com.byd.dashcast.mirrordaemon\" || \$NF==\"byd.mirror.daemon\")"
            val packageName = BuildConfig.APPLICATION_ID
            // Android's package-specific install directory remains identifiable after uninstall
            // removes the old APK. A system install is accepted only at the current PM APK path.
            val apkPattern = "/data/app/$packageName-*/base.apk|/data/app/*/$packageName-*/base.apk" +
                (installedApk?.let { "|" + SurfaceDaemonStartCommand.quote(it) } ?: "")
            val buildCheck = if (endpoint == Endpoint.PROXY)
                "[ \"\$(cat /data/local/tmp/dashcast_proxy_ver 2>/dev/null)\" = '${BuildConfig.VERSION_CODE}' ]"
            else "[ \"\$(cat \"\$MARKER\" 2>/dev/null)\" = \"\$PID:${BuildConfig.VERSION_CODE}\" ]"
            // Only shell-owned, marker-identified processes are eligible. setArgV0 removes the
            // main-class argv on some ROMs, so verify its exact runtime identity and APK classpath.
            // /proc start ticks prevent a stale callback from terminating a recycled PID.
            return "MARKER=" + SurfaceDaemonStartCommand.quote(marker) +
                "; [ \"\$(stat -c %u \"\$MARKER\" 2>/dev/null)\" = 2000 ] || exit 2" +
                "; PID=\$(cut -d: -f1 \"\$MARKER\" 2>/dev/null)" +
                "; case \"\$PID\" in ''|*[!0-9]*) exit 2;; esac" +
                "; $buildCheck || exit 2" +
                "; OWNED=\$(ps -A 2>/dev/null | awk '(\$1==\"shell\" || \$1==\"2000\") && $processPredicate {print \$2}')" +
                "; [ \"\$OWNED\" = \"\$PID\" ] || exit 2" +
                "; [ \"\$(awk '/^Uid:/{print \$2}' /proc/\$PID/status 2>/dev/null)\" = 2000 ] || exit 2" +
                "; NAME=\$(tr '\\000' '\\n' < /proc/\$PID/cmdline 2>/dev/null | head -n1)" +
                "; case \"\$NAME\" in $runtimeNames) ;; *) exit 2;; esac" +
                "; APK=\$(tr '\\000' '\\n' < /proc/\$PID/environ 2>/dev/null | sed -n 's/^CLASSPATH=//p')" +
                "; case \"\$APK\" in $apkPattern) ;; *) exit 2;; esac" +
                "; case \"\$APK\" in *:*) exit 2;; esac" +
                "; STARTED=\$(sed 's/.*) //' /proc/\$PID/stat 2>/dev/null | cut -d' ' -f20)" +
                "; case \"\$STARTED\" in ''|*[!0-9]*) exit 2;; esac"
        }
    }
}
