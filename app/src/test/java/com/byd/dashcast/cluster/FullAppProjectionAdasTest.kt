package com.byd.dashcast.cluster

import android.app.Application
import android.os.Binder
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import com.byd.dashcast.cluster.display.ProjectionCommandBus
import com.byd.dashcast.clusterlive.ClusterFullAppAdas
import com.byd.dashcast.clusterlive.ClusterThemeProjectionDebt
import com.byd.dashcast.proxy.ClusterThemeSession
import com.byd.dashcast.proxy.ProxyClient
import com.byd.dashcast.proxy.daemon.ProxyDaemonContract
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = Application::class)
class FullAppProjectionAdasTest {
    private val round = "b".repeat(32)
    private fun field(name: String) = ProxyClient::class.java.getDeclaredField(name).apply { isAccessible = true }
    private fun authority(owner: FullAppProjectionAdas, field: String): Int {
        val binder = FullAppProjectionAdas::class.java.getDeclaredField(field).apply { isAccessible = true }.get(owner) as IBinder
        val d = Parcel.obtain(); val r = Parcel.obtain()
        return try {
            d.writeInterfaceToken("com.byd.dashcast.RccReservation")
            assertTrue(binder.transact(1, d, r, 0)); r.readException(); r.readInt()
        } finally { d.recycle(); r.recycle() }
    }
    private fun fixture(blockedRead: CountDownLatch?, release: CountDownLatch, recovery: CountDownLatch): Binder = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            data.enforceInterface(ProxyDaemonContract.DESCRIPTOR)
            if (code == ProxyDaemonContract.TXN_ADAS_BODYWORK_READ) {
                data.readInt(); blockedRead?.countDown(); release.await(5, TimeUnit.SECONDS)
                reply!!.writeNoException(); reply.writeInt(3); return true
            }
            assertEquals(ProxyDaemonContract.TXN_CLUSTER_LIVE_TEST, code)
            val op = data.readInt()
            if (op == 25) {
                data.readString(); data.readString(); val action = data.readInt()
                if (action == ClusterFullAppAdas.RECOVERY) recovery.countDown()
            }
            reply!!.writeNoException()
            reply.writeString("""{"filename":"DashCast_${"a".repeat(32)}.rcc","fullAppAdasAccepted":true,"fullAppAdas":{"debt":true,"round":"$round"}}""")
            return true
        }
    }
    @Test fun `old hide token never gains recovery authority when explicit restoration starts`() {
        val app = RuntimeEnvironment.getApplication()
        val release = CountDownLatch(1); val recovered = CountDownLatch(1)
        val binder = fixture(null, release, recovered)
        val previous = field("sBinder").get(null); val version = field("sDaemonVer").get(null)
        field("sBinder").set(null, binder); field("sDaemonVer").set(null, "44")
        val reservation = checkNotNull(ProjectionCommandBus.reserveDiagnostic())
        try {
            ClusterThemeProjectionDebt.begin(app, "a".repeat(32))
            val owner = FullAppProjectionAdas(app, ClusterThemeSession.pin(app), reservation, round, "test.maps", 8) { true }
            assertEquals(1, authority(owner, "hideValidity")); assertEquals(0, authority(owner, "recoveryValidity"))
            owner.restore {}
            assertEquals(0, authority(owner, "hideValidity")); assertEquals(1, authority(owner, "recoveryValidity"))
            assertTrue(recovered.await(2, TimeUnit.SECONDS))
            owner.cancelHide()
        } finally {
            release.countDown(); ProjectionCommandBus.endSession(reservation)
            field("sBinder").set(null, previous); field("sDaemonVer").set(null, version)
            shadowOf(Looper.getMainLooper()).idle()
        }
    }
    @Test fun `blocked vendor poll cannot prevent recovery admission on its independent lane`() {
        val app = RuntimeEnvironment.getApplication()
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val recovered = CountDownLatch(1)
        val binder = fixture(entered, release, recovered)
        val previous = field("sBinder").get(null); val version = field("sDaemonVer").get(null)
        field("sBinder").set(null, binder); field("sDaemonVer").set(null, "44")
        val reservation = checkNotNull(ProjectionCommandBus.reserveDiagnostic())
        try {
            ClusterThemeProjectionDebt.begin(app, "a".repeat(32))
            val owner = FullAppProjectionAdas(app, ClusterThemeSession.pin(app), reservation, round, "test.maps", 8) { true }
            owner.start(); assertTrue(entered.await(2, TimeUnit.SECONDS))
            owner.restore {}
            assertTrue(recovered.await(2, TimeUnit.SECONDS))
            assertEquals(0, authority(owner, "hideValidity"))
            owner.cancelHide()
        } finally {
            release.countDown(); ProjectionCommandBus.endSession(reservation)
            field("sBinder").set(null, previous); field("sDaemonVer").set(null, version)
            shadowOf(Looper.getMainLooper()).idle()
        }
    }
}
