package com.byd.dashcast.satellite

import com.byd.dashcast.util.AppLogger
import java.net.InetAddress
import java.net.NetworkInterface

/** Profiles advertise reachable IPv4 candidates; IPv6 interface scopes do not transfer between devices. */
internal object SatelliteLanAddresses {
    fun select(addresses: Iterable<InetAddress>): List<String> = addresses
        .filter { it.address.size == 4 && !it.isLoopbackAddress && SatelliteProtocol.isLocalAddress(it) }
        .mapNotNull { it.hostAddress }.distinct().take(16)

    fun current(): List<String> = select(NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
        .filter { it.isUp }.flatMap { it.inetAddresses.toList() })
}

/** A failed preparation remains visible until explicit retry, cancellation or the original deadline. */
internal object SatellitePairingPreparation {
    fun prepare(attempt: Long, hosts: () -> List<String>, profile: (List<String>) -> String,
        createServer: (String, SatellitePairingSession.Snapshot) -> SatellitePairingServer) {
        var failure = SatellitePairingSession.Failure.NO_IPV4
        var created: SatellitePairingServer? = null
        try {
            SatellitePairingSession.snapshot()?.takeIf { it.attempt == attempt && it.failure == null } ?: return
            val addresses = hosts()
            if (!SatellitePairingSession.isCurrent(attempt)) return
            if (addresses.isEmpty()) { SatellitePairingSession.fail(attempt, failure); return }
            failure = SatellitePairingSession.Failure.CREDENTIALS
            val encoded = profile(addresses)
            val current = SatellitePairingSession.snapshot()?.takeIf {
                it.attempt == attempt && it.failure == null
            } ?: return
            failure = SatellitePairingSession.Failure.LISTENER
            created = createServer(encoded, current)
            if (!created.start()) { SatellitePairingSession.fail(attempt, failure); return }
            if (!SatellitePairingSession.attach(attempt, created, encoded, addresses)) {
                created.close()
            }
        } catch (error: Exception) {
            failed(attempt, failure, error)
        } catch (error: LinkageError) {
            failed(attempt, failure, error)
        } finally {
            if (SatellitePairingSession.snapshot()?.takeIf { it.attempt == attempt }?.failure != null ||
                !SatellitePairingSession.isCurrent(attempt)) created?.close()
        }
    }

    private fun failed(attempt: Long, failure: SatellitePairingSession.Failure, error: Throwable) {
        AppLogger.w("Satellite", "pairing preparation ${failure.name}: ${error.javaClass.simpleName}")
        SatellitePairingSession.fail(attempt, failure)
    }
}
