package com.byd.dashcast.voice

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.widget.Toast

import androidx.localbroadcastmanager.content.LocalBroadcastManager

import com.byd.dashcast.util.AppLogger

import java.util.LinkedHashMap
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.regex.Pattern

/**
 * v1.4.0-beta — Maps a recognised transcript to an in-app action and speaks
 * the feedback via TTS.
 *
 * The router uses a priority-ordered list of [Pattern] → [Action] pairs. The
 * first matching pattern wins. Matching is case-insensitive on a lower-cased
 * French input string (Vosk returns lower-case).
 *
 * Supported commands (French, Vosk small-FR vocabulary):
 *  - "cluster / tableau de bord / ouvre le cluster" → activate cluster display
 *  - "ferme le cluster / arrête le cluster" → stop cluster projection
 *  - "diagnostics / ouvre les diagnostics" → open DiagActivity
 *  - "logs / affiche les logs" → open LogActivity
 *  - "lance [app]" → lookup installed app by label and send to cluster
 *
 * All cluster/app commands are dispatched via a [LocalBroadcastManager]
 * broadcast ([ACTION_VOICE_COMMAND]) so that `MainActivity` — the only owner of
 * `ClusterService` — can handle them safely from its already-running context.
 * No direct coupling to MainActivity here.
 */
class VoiceCommandRouter(ctx: Context) {

    // applicationContext: outlives the caller, safe to retain.
    private val mCtx: Context = ctx.applicationContext

    // ─── TTS ──────────────────────────────────────────────────────────────

    // Nullable: set to null in release(); volatile because init callback and
    // release()/speak() may touch it from different threads.
    @Volatile private var mTts: TextToSpeech? = null
    @Volatile private var mTtsReady: Boolean = false
    private var mLastSpeakAt: Long = 0L  // dedup guard

    // Single-thread executor reused for all package-resolve tasks (avoids new Thread per command).
    private val mResolveExecutor: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "voice-pkg-resolve").apply { isDaemon = true }
    }

    init {
        mTts = TextToSpeech(mCtx) { status ->
            if (status == TextToSpeech.SUCCESS) {
                // mTts is assigned before this async callback can fire; snapshot
                // to satisfy null-safety without a blind !!.
                val tts = mTts
                val result = tts?.setLanguage(Locale.FRENCH) ?: TextToSpeech.LANG_NOT_SUPPORTED
                mTtsReady = (result != TextToSpeech.LANG_MISSING_DATA &&
                        result != TextToSpeech.LANG_NOT_SUPPORTED)
                if (!mTtsReady) {
                    AppLogger.w(TAG, "TTS: French not supported on this device, falling back to default")
                    tts?.setLanguage(Locale.getDefault())
                    mTtsReady = true
                }
                AppLogger.d(TAG, "TTS ready")
            } else {
                AppLogger.w(TAG, "TTS init failed, status=$status")
            }
        }
    }

    // ─── Public API ───────────────────────────────────────────────────────

    /**
     * Routes the given transcript to an action and speaks the TTS feedback.
     * @param text lower-case transcript from Vosk, already trimmed.
     */
    fun route(text: String?) {
        if (text.isNullOrEmpty()) {
            speak("Désolé, je n'ai rien entendu.")
            return
        }
        AppLogger.i(TAG, "Routing: \"$text\"")

        var matched = Action.UNKNOWN
        var launchLabel: String? = null

        for ((pattern, action) in RULES) {
            val m = pattern.matcher(text)
            if (m.find()) {
                matched = action
                if (matched == Action.LAUNCH_APP && m.groupCount() >= 2) {
                    launchLabel = m.group(2)
                }
                break
            }
        }

        when (matched) {
            Action.CLUSTER_ON -> {
                speak("Activation du cluster.")
                dispatch(CMD_CLUSTER_ON, null)
            }
            Action.CLUSTER_OFF -> {
                speak("Fermeture du cluster.")
                dispatch(CMD_CLUSTER_OFF, null)
            }
            Action.OPEN_DIAG -> {
                speak("Ouverture des diagnostics.")
                dispatch(CMD_OPEN_DIAG, null)
            }
            Action.OPEN_LOGS -> {
                speak("Affichage des journaux.")
                dispatch(CMD_OPEN_LOGS, null)
            }
            Action.LAUNCH_APP -> {
                val appLabel = launchLabel
                // resolvePackage() iterates all installed apps — too slow for the main thread.
                mResolveExecutor.execute {
                    val pkg = resolvePackage(appLabel)
                    if (pkg != null) {
                        speak("Lancement de $appLabel sur le cluster.")
                        dispatch(CMD_LAUNCH_ON_CLUSTER, pkg)
                    } else {
                        speak("Application non trouvée : $appLabel")
                        AppLogger.w(TAG, "LAUNCH_APP: no package found for label=\"$appLabel\"")
                    }
                }
            }
            Action.UNKNOWN -> {
                speak("Commande non reconnue : $text")
                AppLogger.w(TAG, "No match for: \"$text\"")
                dispatch(CMD_UNKNOWN, null)
            }
        }
    }

    /** Releases TTS engine and resolve executor. Call from owner's onDestroy. */
    fun release() {
        mResolveExecutor.shutdown()
        val tts = mTts
        if (tts != null) {
            tts.stop()
            tts.shutdown()
            mTts = null
        }
    }

    // ─── Helpers ─────────────────────────────────────────────────────────

    private fun dispatch(cmd: String, pkg: String?) {
        val i = Intent(ACTION_VOICE_COMMAND)
        i.putExtra(EXTRA_CMD, cmd)
        if (pkg != null) i.putExtra(EXTRA_PKG, pkg)
        LocalBroadcastManager.getInstance(mCtx).sendBroadcast(i)
    }

    private fun speak(text: String) {
        // Dedup: ignore if the same call fires within 300ms (double-broadcast guard).
        val now = SystemClock.elapsedRealtime()
        if (now - mLastSpeakAt < 300L) {
            AppLogger.d(TAG, "speak() deduped: $text")
            return
        }
        mLastSpeakAt = now
        AppLogger.d(TAG, "speak: $text")
        val tts = mTts
        if (mTtsReady && tts != null) {
            val params = Bundle()
            params.putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_MUSIC)
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, params, "dashcast_voice")
        } else {
            AppLogger.d(TAG, "TTS not ready — showing Toast: $text")
            sMain.post { Toast.makeText(mCtx, text, Toast.LENGTH_SHORT).show() }
        }
    }

    /**
     * Tries to resolve an app label to an installed package name.
     * Looks for a case-insensitive substring match in the app's human-readable label.
     */
    private fun resolvePackage(label: String?): String? {
        if (label.isNullOrEmpty()) return null
        val pm = mCtx.packageManager
        val lowerLabel = label.lowercase(Locale.FRENCH).trim()
        for (info in pm.getInstalledApplications(0)) {
            val appLabel = pm.getApplicationLabel(info).toString()
                .lowercase(Locale.FRENCH)
            if (appLabel.contains(lowerLabel) || lowerLabel.contains(appLabel)) {
                // Only return launchable apps (has a LAUNCHER intent)
                if (pm.getLaunchIntentForPackage(info.packageName) != null) {
                    AppLogger.d(TAG, "resolvePackage: \"$label\" → ${info.packageName}")
                    return info.packageName
                }
            }
        }
        return null
    }

    // ─── Static surface ───────────────────────────────────────────────────

    /** Simple enum for matched intent type. */
    private enum class Action { CLUSTER_ON, CLUSTER_OFF, OPEN_DIAG, OPEN_LOGS, LAUNCH_APP, UNKNOWN }

    companion object {
        private const val TAG = "VoiceCommandRouter"

        // ─── Broadcast contract (inbound to MainActivity) ─────────────────

        /** Broadcast action. MainActivity registers for this on creation. */
        const val ACTION_VOICE_COMMAND = "com.byd.dashcast.voice.COMMAND"

        /** String extra — one of the CMD_* constants below. */
        const val EXTRA_CMD = "cmd"

        /** Optional string extra — package name for CMD_LAUNCH_ON_CLUSTER. */
        const val EXTRA_PKG = "pkg"

        const val CMD_CLUSTER_ON = "cluster_on"
        const val CMD_CLUSTER_OFF = "cluster_off"
        const val CMD_OPEN_DIAG = "open_diag"
        const val CMD_OPEN_LOGS = "open_logs"
        const val CMD_LAUNCH_ON_CLUSTER = "launch_on_cluster"
        const val CMD_UNKNOWN = "unknown"

        // ─── Patterns (order = priority) ──────────────────────────────────

        private val RULES: Map<Pattern, Action> = LinkedHashMap<Pattern, Action>().apply {
            // Cluster ON
            put(Pattern.compile("(ouvre|active|lance|affiche|démarre|montre).*cluster|tableau.?de.?bord|lance.?le.?cluster"), Action.CLUSTER_ON)
            put(Pattern.compile("cluster"), Action.CLUSTER_ON)
            // Cluster OFF
            put(Pattern.compile("(ferme|arrête|stop|coupe|éteins|désactive).*cluster"), Action.CLUSTER_OFF)
            // Diag
            put(Pattern.compile("(ouvre|affiche|lance).*diag|diagnostic"), Action.OPEN_DIAG)
            // Logs
            put(Pattern.compile("(ouvre|affiche|lance).*log|journal"), Action.OPEN_LOGS)
            // Launch app — "lance youtube / mets waze / ouvre maps"
            put(Pattern.compile("(lance|ouvre|mets|démarre)\\s+(.+)"), Action.LAUNCH_APP)
        }

        // ─── TTS ──────────────────────────────────────────────────────────

        private val sMain = Handler(Looper.getMainLooper())
    }
}
