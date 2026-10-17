package com.byd.dashcast.ui.diag

import android.os.IBinder
import android.os.Parcel
import com.byd.dashcast.proxy.ProxyClient
import com.byd.dashcast.proxy.daemon.ProxyDaemonContract

/** Captures only the already-connected PROXY binder. Never connects, bootstraps or retries. */
internal class DiLinkContextDevice(
    private val pinnedBinder: IBinder? = ProxyClient.getProxyDaemonBinder(),
    private val profile: DiLinkContextPlan.Profile = DiLinkContextPlan.Profile.CONTEXT,
) : DiLinkContextExport.Device {

    override fun read(command: DiLinkContextPlan.Command): DiLinkContextTransport.Reply {
        // Reject fabricated commands as well as any future caller-supplied shell text.
        require(command in profile.commands) { "Command is outside the fixed ${profile.id} plan" }
        val binder = pinnedBinder ?: return DiLinkContextTransport.Reply(null, "",
            "No already-connected PROXY daemon; no connection or bootstrap attempted", true)
        return transport.execute {
            if (!binder.isBinderAlive) return@execute DiLinkContextTransport.Reply(null, "", "Pinned PROXY binder is dead; no retry", true)
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            try {
                data.writeInterfaceToken(ProxyDaemonContract.DESCRIPTOR)
                data.writeString(command.shell)
                if (!binder.transact(ProxyDaemonContract.TXN_EXEC, data, reply, 0))
                    return@execute DiLinkContextTransport.Reply(null, "", "TXN_EXEC was not handled", true)
                reply.readException()
                check(reply.dataAvail() >= 4) { "Missing exit code in TXN_EXEC reply" }
                val exit = reply.readInt()
                val output = reply.readString() ?: return@execute DiLinkContextTransport.Reply(exit, "", "Missing output in TXN_EXEC reply", true)
                DiLinkContextTransport.Reply(exit, output)
            } catch (e: Exception) {
                DiLinkContextTransport.Reply(null, "", "${e.javaClass.simpleName}: ${e.message}; no retry", true)
            } finally {
                reply.recycle()
                data.recycle()
            }
        }
    }

    companion object {
        private val transport = DiLinkContextTransport()
        fun isTransportPending() = transport.isBusy()
        fun whenTransportIdle(finished: () -> Unit) = transport.whenIdle(finished)
    }
}
