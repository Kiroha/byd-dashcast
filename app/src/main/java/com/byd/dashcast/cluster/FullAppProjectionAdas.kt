package com.byd.dashcast.cluster

import android.content.Context
import android.os.Binder
import android.os.Handler
import android.os.Looper
import android.os.Parcel
import android.os.SystemClock
import com.byd.dashcast.cluster.display.ProjectionCommandBus
import com.byd.dashcast.cluster.display.ProjectionCommandSequencer
import com.byd.dashcast.clusterlive.ClusterFullAppAdas
import com.byd.dashcast.proxy.ClusterThemeSession
import com.byd.dashcast.proxy.ProxyClient
import org.json.JSONObject

/** Existing ADAS plan, scoped to one manually opened full-app diagnostic round. */
internal class FullAppProjectionAdas(context: Context, private val pinned: ClusterThemeSession,
    private val reservation: ProjectionCommandSequencer.Session, private val round: String,
    private val pkg: String, private val display: Int, private var openingCurrent: () -> Boolean) {
    private val main = Handler(Looper.getMainLooper())
    private val binder = ProxyClient.getProxyDaemonBinder()
    @Volatile private var hideRetired = false
    @Volatile private var restoring = false
    private val callbacks = mutableListOf<(Boolean) -> Unit>()
    private val recoveryDeadline = Runnable { if (restoring) { plan.cancel(); finish(false) } }
    private fun current(recovery: Boolean) = pinned.isCurrent() && binder != null && binder.isBinderAlive &&
        ProjectionCommandBus.ownsDiagnostic(reservation) && (if (recovery) restoring else !restoring && !hideRetired && openingCurrent())
    private fun validity(recovery: Boolean) = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code != 1) return false
            data.enforceInterface("com.byd.dashcast.RccReservation")
            reply?.writeNoException(); reply?.writeInt(if (current(recovery)) 1 else 0)
            return true
        }
    }
    private val hideValidity = validity(false)
    private val recoveryValidity = validity(true)
    private fun request(action: Int, value: Int = 0, count: Int = 0): Boolean {
        val recovery = restoring
        return pinned.fullAppAdas(round, action, value, count, recovery, pkg, display,
            if (recovery) recoveryValidity else hideValidity)
    }
    private val transport = ProjectionAdasTransport(context.applicationContext, { binder }, { restoring },
        object : ProjectionAdasTransport.FixedDiagnostic {
            override fun initialize() = request(ClusterFullAppAdas.INITIALIZE)
            override fun command(code: Int) = request(ClusterFullAppAdas.COMMAND, code)
            override fun frames(frame: String, count: Int): Boolean {
                check(ClusterFullAppAdas.frames.toSet() == ProjectionAdasPlan.FRAMES)
                return request(ClusterFullAppAdas.FRAMES, ClusterFullAppAdas.frames.indexOf(frame), count)
            }
        })
    private val plan = ProjectionAdasPlan(object : ProjectionAdasPlan.Io {
        override fun later(delay: Long, action: () -> Unit) { main.postDelayed(action, delay) }
        override fun now() = SystemClock.uptimeMillis()
        override fun valid() = current(restoring)
        override fun journal(done: (Boolean) -> Unit) {
            transport.call({ current(false) }, work = { request(ClusterFullAppAdas.JOURNAL) }, mutation = true, done = { done(it == true) })
        }
        override fun initialize(valid: () -> Boolean, done: (Boolean) -> Unit) = transport.initialize(valid, done)
        override fun command(code: Int, valid: () -> Boolean, done: (Boolean) -> Unit) {
            ProjectionCommandBus.submitOwned(reservation, valid,
                { complete -> transport.command(code, valid, complete) }, { ok -> main.post { done(ok) } })
        }
        override fun frames(frame: String, count: Int, valid: () -> Boolean, done: (Boolean) -> Unit) =
            transport.frames(frame, count, valid, done)
    })
    fun start() {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (!current(false)) return
        plan.start()
        observe()
    }
    private fun observe() {
        if (!current(false) || restoring) return
        transport.call({ current(false) }, timeout = 2_500, work = { captured ->
            ProxyClient.readAdasBodywork(captured, 0x12d0002a) to ProxyClient.readAdasBodywork(captured, 0x40d00010)
        }) { reading ->
            if (!current(false) || restoring) return@call
            if (reading == null) { cancelHide(); return@call }
            plan.observe(reading.first.takeIf { it in 0..3 }, reading.second.takeIf { it in 0..254 }, true, SystemClock.uptimeMillis())
            main.postDelayed({ observe() }, 1_000)
        }
    }
    /** Immediate invalidation also retires already queued hide/periodic callbacks. */
    fun cancelHide() {
        hideRetired = true
        openingCurrent = { false } // Recovery must not retain the diagnostic Activity.
        plan.cancel()
    }
    fun restore(done: (Boolean) -> Unit) {
        check(Looper.myLooper() == Looper.getMainLooper())
        callbacks += done
        if (restoring) return
        cancelHide(); restoring = true
        main.postDelayed(recoveryDeadline, 45_000)
        transport.call({ current(true) }, work = {
            val debt = JSONObject(pinned.status()).optJSONObject("fullAppAdas")
            if (debt?.optBoolean("debt") != true) 0
            else if (debt.optString("round") != round) -1
            else if (request(ClusterFullAppAdas.RECOVERY)) 1 else -1
        }, mutation = true) { admission ->
            when (admission) {
                0 -> finish(true)
                1 -> plan.restore { ok ->
                    if (!ok) finish(false)
                    else transport.call({ current(true) }, work = { request(ClusterFullAppAdas.RESTORED) }, mutation = true,
                        done = { finish(it == true) })
                }
                else -> finish(false)
            }
        }
    }
    private fun finish(ok: Boolean) {
        main.removeCallbacks(recoveryDeadline)
        restoring = false
        val pending = callbacks.toList(); callbacks.clear()
        pending.forEach { it(ok) }
    }
}
