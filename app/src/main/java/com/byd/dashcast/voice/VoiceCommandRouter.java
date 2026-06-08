package com.byd.dashcast.voice;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import android.os.Bundle;
import android.os.SystemClock;
import android.speech.tts.TextToSpeech;
import android.widget.Toast;

import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.byd.dashcast.AppLogger;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * v1.4.0-beta — Maps a recognised transcript to an in-app action and speaks
 * the feedback via TTS.
 *
 * <p>The router uses a priority-ordered list of {@link Pattern} → {@link Action}
 * pairs. The first matching pattern wins. Matching is case-insensitive on a
 * lower-cased French input string (Vosk returns lower-case).
 *
 * <p><b>Supported commands</b> (French, Vosk small-FR vocabulary) :
 * <ul>
 *   <li>"cluster / tableau de bord / ouvre le cluster" → activate cluster display</li>
 *   <li>"ferme le cluster / arrête le cluster" → stop cluster projection</li>
 *   <li>"diagnostics / ouvre les diagnostics" → open DiagActivity</li>
 *   <li>"logs / affiche les logs" → open LogActivity</li>
 *   <li>"lance [app]" → lookup installed app by label and send to cluster</li>
 * </ul>
 *
 * <p>All cluster/app commands are dispatched via a {@link LocalBroadcastManager}
 * broadcast ({@link #ACTION_VOICE_COMMAND}) so that {@code MainActivity} — the
 * only owner of {@code ClusterService} — can handle them safely from its
 * already-running context. No direct coupling to MainActivity here.
 */
public final class VoiceCommandRouter {

    private static final String TAG = "VoiceCommandRouter";

    // ─── Broadcast contract (inbound to MainActivity) ─────────────────────

    /** Broadcast action. MainActivity registers for this on creation. */
    public static final String ACTION_VOICE_COMMAND = "com.byd.dashcast.voice.COMMAND";

    /** String extra — one of the CMD_* constants below. */
    public static final String EXTRA_CMD            = "cmd";
    /** Optional string extra — package name for CMD_LAUNCH_ON_CLUSTER. */
    public static final String EXTRA_PKG            = "pkg";

    public static final String CMD_CLUSTER_ON       = "cluster_on";
    public static final String CMD_CLUSTER_OFF      = "cluster_off";
    public static final String CMD_OPEN_DIAG        = "open_diag";
    public static final String CMD_OPEN_LOGS        = "open_logs";
    public static final String CMD_LAUNCH_ON_CLUSTER = "launch_on_cluster";
    public static final String CMD_UNKNOWN          = "unknown";

    // ─── Patterns (order = priority) ──────────────────────────────────────

    /** Simple enum for matched intent type. */
    private enum Action { CLUSTER_ON, CLUSTER_OFF, OPEN_DIAG, OPEN_LOGS, LAUNCH_APP, UNKNOWN }

    private static final Map<Pattern, Action> RULES = new LinkedHashMap<>();
    static {
        // Cluster ON
        RULES.put(Pattern.compile("(ouvre|active|lance|affiche|démarre|montre).*cluster|tableau.?de.?bord|lance.?le.?cluster"), Action.CLUSTER_ON);
        RULES.put(Pattern.compile("cluster"), Action.CLUSTER_ON);
        // Cluster OFF
        RULES.put(Pattern.compile("(ferme|arrête|stop|coupe|éteins|désactive).*cluster"), Action.CLUSTER_OFF);
        // Diag
        RULES.put(Pattern.compile("(ouvre|affiche|lance).*diag|diagnostic"), Action.OPEN_DIAG);
        // Logs
        RULES.put(Pattern.compile("(ouvre|affiche|lance).*log|journal"), Action.OPEN_LOGS);
        // Launch app — "lance youtube / mets waze / ouvre maps"
        RULES.put(Pattern.compile("(lance|ouvre|mets|démarre)\\s+(.+)"), Action.LAUNCH_APP);
    }

    // ─── TTS ──────────────────────────────────────────────────────────────

    private final Context      mCtx;
    private volatile TextToSpeech mTts;
    private volatile boolean   mTtsReady;
    private long               mLastSpeakAt;  // dedup guard

    public VoiceCommandRouter(Context ctx) {
        mCtx = ctx.getApplicationContext();
        mTts = new TextToSpeech(mCtx, status -> {
            if (status == TextToSpeech.SUCCESS) {
                int result = mTts.setLanguage(Locale.FRENCH);
                mTtsReady = (result != TextToSpeech.LANG_MISSING_DATA
                          && result != TextToSpeech.LANG_NOT_SUPPORTED);
                if (!mTtsReady) {
                    AppLogger.w(TAG, "TTS: French not supported on this device, falling back to default");
                    mTts.setLanguage(Locale.getDefault());
                    mTtsReady = true;
                }
                AppLogger.d(TAG, "TTS ready");
            } else {
                AppLogger.w(TAG, "TTS init failed, status=" + status);
            }
        });
    }

    // ─── Public API ───────────────────────────────────────────────────────

    /**
     * Routes the given transcript to an action and speaks the TTS feedback.
     * @param text lower-case transcript from Vosk, already trimmed.
     */
    public void route(String text) {
        if (text == null || text.isEmpty()) {
            speak("Désolé, je n'ai rien entendu.");
            return;
        }
        AppLogger.i(TAG, "Routing: \"" + text + "\"");

        Action matched = Action.UNKNOWN;
        String launchLabel = null;

        for (Map.Entry<Pattern, Action> e : RULES.entrySet()) {
            java.util.regex.Matcher m = e.getKey().matcher(text);
            if (m.find()) {
                matched = e.getValue();
                if (matched == Action.LAUNCH_APP && m.groupCount() >= 2) {
                    launchLabel = m.group(2);
                }
                break;
            }
        }

        switch (matched) {
            case CLUSTER_ON:
                speak("Activation du cluster.");
                dispatch(CMD_CLUSTER_ON, null);
                break;
            case CLUSTER_OFF:
                speak("Fermeture du cluster.");
                dispatch(CMD_CLUSTER_OFF, null);
                break;
            case OPEN_DIAG:
                speak("Ouverture des diagnostics.");
                dispatch(CMD_OPEN_DIAG, null);
                break;
            case OPEN_LOGS:
                speak("Affichage des journaux.");
                dispatch(CMD_OPEN_LOGS, null);
                break;
            case LAUNCH_APP:
                final String appLabel = launchLabel;
                // resolvePackage() iterates all installed apps — too slow for the main thread.
                new Thread(() -> {
                    String pkg = resolvePackage(appLabel);
                    if (pkg != null) {
                        speak("Lancement de " + appLabel + " sur le cluster.");
                        dispatch(CMD_LAUNCH_ON_CLUSTER, pkg);
                    } else {
                        speak("Application non trouvée : " + appLabel);
                        AppLogger.w(TAG, "LAUNCH_APP: no package found for label=\"" + appLabel + "\"");
                    }
                }, "voice-pkg-resolve").start();
                break;
            default:
                speak("Commande non reconnue : " + text);
                AppLogger.w(TAG, "No match for: \"" + text + "\"");
                dispatch(CMD_UNKNOWN, null);
                break;
        }
    }

    /** Releases TTS engine. Call from owner's onDestroy. */
    public void release() {
        if (mTts != null) {
            mTts.stop();
            mTts.shutdown();
            mTts = null;
        }
    }

    // ─── Helpers ─────────────────────────────────────────────────────────

    private void dispatch(String cmd, String pkg) {
        Intent i = new Intent(ACTION_VOICE_COMMAND);
        i.putExtra(EXTRA_CMD, cmd);
        if (pkg != null) i.putExtra(EXTRA_PKG, pkg);
        LocalBroadcastManager.getInstance(mCtx).sendBroadcast(i);
    }

    private void speak(String text) {
        // Dedup: ignore if the same call fires within 300ms (double-broadcast guard).
        long now = SystemClock.elapsedRealtime();
        if (now - mLastSpeakAt < 300L) {
            AppLogger.d(TAG, "speak() deduped: " + text);
            return;
        }
        mLastSpeakAt = now;
        AppLogger.d(TAG, "speak: " + text);
        if (mTtsReady && mTts != null) {
            Bundle params = new Bundle();
            params.putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_MUSIC);
            mTts.speak(text, TextToSpeech.QUEUE_FLUSH, params, "dashcast_voice");
        } else {
            AppLogger.d(TAG, "TTS not ready — showing Toast: " + text);
            new android.os.Handler(android.os.Looper.getMainLooper())
                    .post(() -> Toast.makeText(mCtx, text, Toast.LENGTH_SHORT).show());
        }
    }

    /**
     * Tries to resolve an app label to an installed package name.
     * Looks for a case-insensitive substring match in the app's human-readable label.
     */
    private String resolvePackage(String label) {
        if (label == null || label.isEmpty()) return null;
        PackageManager pm = mCtx.getPackageManager();
        String lowerLabel = label.toLowerCase(Locale.FRENCH).trim();
        for (android.content.pm.ApplicationInfo info :
                pm.getInstalledApplications(0)) {
            String appLabel = pm.getApplicationLabel(info).toString()
                    .toLowerCase(Locale.FRENCH);
            if (appLabel.contains(lowerLabel) || lowerLabel.contains(appLabel)) {
                // Only return launchable apps (has a LAUNCHER intent)
                if (pm.getLaunchIntentForPackage(info.packageName) != null) {
                    AppLogger.d(TAG, "resolvePackage: \"" + label + "\" → " + info.packageName);
                    return info.packageName;
                }
            }
        }
        return null;
    }
}
