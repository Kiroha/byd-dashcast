package com.byd.dashcast.ui.diag

import android.os.IBinder
import android.os.Parcel
import com.byd.dashcast.proxy.ProxyClient
import com.byd.dashcast.proxy.daemon.ProxyDaemonContract
import com.byd.dashcast.proxy.daemon.StrictFileRead
import java.io.IOException
import java.util.concurrent.atomic.AtomicReference

/** All operations share the existing context transport quarantine. A late call only fills its
 * private result holder; the collector never consumes it or submits another operation. */
internal class OemInitSourcesDevice(
    private val binder: IBinder? = ProxyClient.getProxyDaemonBinder(),
    private val transport: (() -> DiLinkContextTransport.Reply) -> DiLinkContextTransport.Reply = DiLinkContextDevice::executeScoped,
) : OemInitSourcesExport.Device {
    private var terminal = false
    private var ready = false
    override val terminated: Boolean get() = terminal
    private class SourceReadException(message: String) : IOException(message)
    private fun <T : Any> exchange(operation: (Parcel, Parcel) -> T, code: Int): T {
        if (terminal) throw IOException("Source transport terminated; no subsequent call")
        val pinned = binder ?: throw IOException("No already-connected ProxyDaemon; no bootstrap attempted")
        val value = AtomicReference<T>()
        val sourceFailure = AtomicReference<IOException>()
        val result = transport {
            val data = Parcel.obtain(); val reply = Parcel.obtain()
            try {
                if (!pinned.isBinderAlive) throw IOException("Pinned daemon is dead; no retry")
                data.writeInterfaceToken(ProxyDaemonContract.DESCRIPTOR)
                // The writer supplied below transacts exactly once, then validates its reply.
                value.set(operation(data, reply))
                DiLinkContextTransport.Reply(0, "")
            } catch (e: SourceReadException) {
                sourceFailure.set(e)
                DiLinkContextTransport.Reply(0, "")
            } catch (e: Exception) {
                DiLinkContextTransport.Reply(null, "", "${e.javaClass.simpleName}: ${e.message}".take(256), true)
            } finally { reply.recycle(); data.recycle() }
        }
        if (result.fatal || result.exitCode != 0) {
            terminal = true
            throw IOException(result.error.ifEmpty { "Physical operation $code did not complete normally" }.take(256))
        }
        sourceFailure.get()?.let { throw it }
        return value.get() ?: run { terminal = true; throw IOException("Typed operation result missing") }
    }
    private fun transact(code: Int, data: Parcel, reply: Parcel) {
        if (binder?.transact(code, data, reply, 0) != true) throw IOException("Daemon transaction $code unsupported")
        val maximum = when (code) {
            ProxyDaemonContract.TXN_WHOAMI -> 260
            ProxyDaemonContract.TXN_FILE_READ_CAPABILITIES -> 12
            ProxyDaemonContract.TXN_EXEC -> 192 * 1024 * 2 + 36
            ProxyDaemonContract.TXN_READ_FILE_CHUNK_STRICT -> OemInitSourcesPolicy.CHUNK + 8
            else -> throw IOException("Operation outside source protocol")
        }
        if (reply.dataAvail() !in 4..maximum) throw IOException("Daemon reply size missing/excessive")
        if (code == ProxyDaemonContract.TXN_READ_FILE_CHUNK_STRICT && reply.dataAvail() >= 4) {
            val start = reply.dataPosition()
            val exception = reply.readInt()
            // Android Parcel encodes SecurityException=-1 and IllegalStateException=-5.
            // StrictFileRead IOExceptions are wrapped as IllegalStateException by our daemon.
            // Only a complete, bounded simple exception frame is a recoverable source denial.
            if (exception == -1 || exception == -5) {
                if (reply.dataAvail() !in 8..(8192 * 2 + 16)) throw IOException("Truncated/excessive strict source exception")
                val message = boundedString(reply, 8192)
                if (reply.dataAvail() != 4 || reply.readInt() != 0 || reply.dataAvail() != 0)
                    throw IOException("Unsupported strict source exception payload")
                throw SourceReadException("Strict source refused: $message".take(256))
            }
            reply.setDataPosition(start)
        }
        reply.readException()
    }
    private fun boundedString(reply: Parcel, limit: Int): String {
        if (reply.dataAvail() < 4) throw IOException("Missing bounded string header")
        // The complete reply is bounded before this native readString call; Android validates
        // a native string's declared size against remaining Parcel bytes. Do not reinterpret
        // opaque Parcel string storage as an integer (Robolectric uses typed storage too).
        val start = reply.dataPosition()
        val text = reply.readString() ?: throw IOException("Bounded string missing")
        val framed = 4 + (((text.length + 1) * 2 + 3) / 4) * 4
        if (text.length > limit || reply.dataPosition() - start != framed) throw IOException("Bounded string framing invalid")
        return text
    }
    override fun negotiate(check: () -> Unit): Map<String, String> {
        check()
        val identity = exchange({ data, reply ->
            transact(ProxyDaemonContract.TXN_WHOAMI, data, reply)
            if (reply.dataAvail() !in 16..256) throw IOException("Truncated/excessive daemon identity")
            val uid = reply.readInt(); val pid = reply.readInt(); val protocol = boundedString(reply, 16); val nonce = boundedString(reply, 32)
            if (uid != 2000 || pid <= 0 || (protocol.toIntOrNull() ?: 0) < 26 ||
                !nonce.matches(Regex("[0-9a-f]{32}")) || reply.dataAvail() != 0) throw IOException("Daemon identity/protocol refused")
            mapOf("daemon_uid" to uid.toString(), "daemon_pid" to pid.toString(), "daemon_protocol" to protocol)
        }, ProxyDaemonContract.TXN_WHOAMI)
        check()
        exchange({ data, reply ->
            transact(ProxyDaemonContract.TXN_FILE_READ_CAPABILITIES, data, reply)
            if (reply.dataAvail() != 8 || reply.readInt() != ProxyDaemonContract.STRICT_FILE_SCHEMA ||
                reply.readInt() and ProxyDaemonContract.CAP_STRICT_FILE_READ == 0) throw IOException("Strict source capability refused")
            true
        }, ProxyDaemonContract.TXN_FILE_READ_CAPABILITIES)
        ready = true
        return identity
    }
    override fun page(root: Int, offset: Int): OemInitSourcesPolicy.Page {
        check(ready)
        val command = OemInitSourcesPolicy.command(root, offset)
        val output = exchange({ data, reply ->
            data.writeString(command); transact(ProxyDaemonContract.TXN_EXEC, data, reply)
            if (reply.dataAvail() !in 8..(192 * 1024 * 2 + 32)) throw IOException("Truncated/excessive inventory reply")
            val exit = reply.readInt(); val text = boundedString(reply, 192 * 1024)
            if (reply.dataAvail() != 0 || exit != 0 || text.contains("[output truncated]")) throw IOException("Inventory failed/truncated, exit=$exit")
            text
        }, ProxyDaemonContract.TXN_EXEC)
        return try { OemInitSourcesPolicy.parse(root, offset, output) }
        catch (e: Exception) { terminal = true; throw e }
    }
    override fun chunk(entry: OemInitSourcesPolicy.Entry, offset: Long, length: Int): ByteArray {
        check(ready)
        val path = entry.path ?: throw IOException("Source path is not UTF-8")
        val expected = entry.stamp ?: throw IOException("No strict source metadata")
        require(OemInitSourcesPolicy.accepted(path, entry.root) && entry.status == "ok" &&
            length in 1..OemInitSourcesPolicy.CHUNK && offset in 0..expected.size)
        val count = minOf(length.toLong(), expected.size - offset).toInt()
        return exchange({ data, reply ->
            data.writeString(path); data.writeLong(offset); data.writeInt(length)
            listOf(expected.device, expected.inode, expected.mode, expected.size, expected.modified, expected.changed).forEach(data::writeLong)
            transact(ProxyDaemonContract.TXN_READ_FILE_CHUNK_STRICT, data, reply)
            if (reply.dataAvail() < 4) throw IOException("Missing strict chunk header")
            val position = reply.dataPosition(); val declared = reply.readInt()
            if (declared != count || reply.dataAvail() != ((count + 3) / 4) * 4) throw IOException("Strict chunk framing/length invalid")
            reply.setDataPosition(position)
            val bytes = ByteArray(count)
            reply.readByteArray(bytes)
            if (bytes.size != count || reply.dataAvail() != 0) throw IOException("Strict chunk trailing bytes")
            bytes
        }, ProxyDaemonContract.TXN_READ_FILE_CHUNK_STRICT)
    }
}
