package com.byd.dashcast.satellite

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.SystemClock
import com.byd.dashcast.R
import com.byd.dashcast.util.AppLogger
import com.byd.dashcast.util.concurrent.LatestValueDispatcher
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** User-enabled foreground receiver, independent of Activity and notification-listener lifetime. */
class SatelliteReceiverService : Service() {
    private val transportWorker = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "satellite-transport").apply { isDaemon = true }
    }
    private data class PendingNavigation(val session: String, val frame: SatelliteProtocol.Navigation,
        val receivedAtMs: Long, val generation: Long, val inputRevision: Long)
    private val navigation = LatestValueDispatcher(Executors.newSingleThreadExecutor { r ->
        Thread(r, "satellite-navigation").apply { isDaemon = true }
    }) { pending: PendingNavigation ->
        synchronized(sessionLock) {
            if (currentSession != pending.session || destroyed || pending.generation != navigationGeneration)
                return@LatestValueDispatcher
            val data = pending.frame.data
            if (data != null && SystemClock.elapsedRealtime() - pending.receivedAtMs + pending.frame.ageMs >
                SatelliteProtocol.MAX_AGE_MS) return@LatestValueDispatcher
            if (data == null) NavigationInputRouter.closeRemote(applicationContext, pending.session)
            else NavigationInputRouter.updateRemote(applicationContext, pending.session, pending.inputRevision, data)
        }
    }
    private val sessionLock = Any()
    private var currentSession: String? = null
    private var navigationGeneration = 0L
    private var ownedSession: String? = null
    @Volatile private var destroyed = false
    @Volatile private var server: SatelliteWebSocketServer? = null

    override fun onCreate() {
        super.onCreate()
        running = true
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL,
            getString(R.string.satellite_title), NotificationManager.IMPORTANCE_LOW))
        val intent = PendingIntent.getActivity(this, 0, Intent(this, SatelliteSettingsActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        startForeground(NOTIFICATION, Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_cast).setContentTitle(getString(R.string.satellite_title))
            .setContentText(getString(R.string.satellite_waiting)).setContentIntent(intent)
            .setOngoing(true).build())
        transportWorker.scheduleWithFixedDelay({ server?.tick(SystemClock.elapsedRealtime()) },
            1, 1, TimeUnit.SECONDS)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!SatellitePrefs.isEnabled(this)) { stopSelf(); return START_NOT_STICKY }
        transportWorker.execute {
            if (destroyed) return@execute
            if (intent?.action == RELOAD) {
                server?.beginShutdown()
                try { server?.stop(500) } catch (_: Exception) { stopSelf(); return@execute }
                server = null
            }
            if (server == null) {
                try {
                    val created = SatelliteWebSocketServer(applicationContext, SatelliteTls.context(),
                        SatellitePrefs.token(this), object : SatelliteWebSocketServer.Events {
                            override fun connected(session: String) {
                                synchronized(sessionLock) {
                                    currentSession = session
                                    ownedSession = session
                                    navigationGeneration++
                                    NavigationInputRouter.acquireRemote(applicationContext, session)
                                }
                            }
                            override fun navigation(session: String, frame: SatelliteProtocol.Navigation,
                                receivedAtMs: Long) {
                                synchronized(sessionLock) {
                                    if (currentSession == session && !destroyed) {
                                        navigation.submit(PendingNavigation(session, frame, receivedAtMs,
                                            ++navigationGeneration, NavigationInputRouter.remoteRevision()))
                                    }
                                }
                            }
                            override fun expired(session: String) { clearGuidance(session, false) }
                            override fun disconnected(session: String) { clearGuidance(session, true) }
                            override fun failed() { stopSelf() }
                        })
                    if (destroyed || !SatellitePrefs.isEnabled(this)) return@execute
                    server = created
                    created.start()
                } catch (e: Exception) {
                    AppLogger.w("Satellite", "receiver start failed: ${e.javaClass.simpleName}")
                    stopSelf()
                }
            }
        }
        return START_STICKY
    }

    private fun clearGuidance(session: String, disconnected: Boolean) {
        synchronized(sessionLock) {
            if (currentSession != session) return
            val generation = ++navigationGeneration
            if (disconnected) currentSession = null
            navigation.cancelPendingAndExecute {
                synchronized(sessionLock) {
                    // Cleanup from an old socket cannot erase a newly connected route.
                    if (navigationGeneration == generation &&
                        (currentSession == null || currentSession == session)) {
                        NavigationInputRouter.closeRemote(applicationContext, session, disconnected)
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        destroyed = true
        running = false
        val oldSession = synchronized(sessionLock) { currentSession = null; ownedSession }
        navigation.close { oldSession?.let { NavigationInputRouter.closeRemote(applicationContext, it, true) } }
        transportWorker.execute { try { server?.beginShutdown(); server?.stop(500) } catch (_: Exception) {} }
        transportWorker.shutdown()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL = "dashcast_satellite"
        private const val NOTIFICATION = 7307
        private const val RELOAD = "com.byd.dashcast.satellite.RELOAD"
        @Volatile private var running = false
        private var lastAttempt = Long.MIN_VALUE

        @Synchronized
        fun maybeKeepAlive(ctx: Context) {
            if (!SatellitePrefs.isEnabled(ctx) || running) return
            val now = SystemClock.elapsedRealtime()
            if (lastAttempt != Long.MIN_VALUE && now - lastAttempt < 30_000) return
            start(ctx)
        }

        @Synchronized
        fun start(ctx: Context, reload: Boolean = false) {
            if (!SatellitePrefs.isEnabled(ctx)) return
            lastAttempt = SystemClock.elapsedRealtime()
            try {
                ctx.startForegroundService(Intent(ctx, SatelliteReceiverService::class.java)
                    .apply { if (reload) action = RELOAD })
            } catch (e: Exception) {
                AppLogger.w("Satellite", "foreground start unavailable: ${e.javaClass.simpleName}")
            }
        }

        fun stop(ctx: Context) { ctx.stopService(Intent(ctx, SatelliteReceiverService::class.java)) }
    }
}
