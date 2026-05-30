package com.byd.dashcast.voice;

import android.content.Context;
import android.content.Intent;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKey;

import com.byd.dashcast.AppLogger;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * v1.4.2-beta — LLM-powered voice engine.
 *
 * <p>Receives a Vosk transcript, sends it to the OpenAI chat API with a
 * structured system prompt that returns a JSON command + French reply, then
 * synthesises the reply with the OpenAI TTS API and plays it via MediaPlayer.
 *
 * <p><b>Fallback</b>: if no API key is configured, or if any network/parse
 * error occurs, execution is delegated to {@link VoiceCommandRouter} (regex
 * matching). The app can never crash due to LLM failures.
 *
 * <p><b>Security</b>: the API key is stored in {@link EncryptedSharedPreferences}
 * (AES-256-GCM). It is never logged or committed to the repository.
 */
public final class LlmVoiceEngine {

    private static final String TAG = "LlmVoiceEngine";

    // ─── Prefs ─────────────────────────────────────────────────────────────
    private static final String PREFS_NAME   = "dashcast_llm_prefs";
    public  static final String PREF_API_KEY = "openai_api_key";

    // ─── OpenAI endpoints ──────────────────────────────────────────────────
    private static final String CHAT_URL = "https://api.openai.com/v1/chat/completions";
    private static final String TTS_URL  = "https://api.openai.com/v1/audio/speech";
    private static final String CHAT_MODEL = "gpt-4o-mini";
    private static final String TTS_MODEL  = "tts-1";
    private static final String TTS_VOICE  = "nova";   // warm, natural FR

    // ─── System prompt ────────────────────────────────────────────────────
    private static final String SYSTEM_PROMPT =
        "Tu es Jarvis, l'assistant vocal embarqué d'une voiture BYD.\n" +
        "L'utilisateur te donne une commande vocale en français.\n" +
        "Réponds UNIQUEMENT avec un objet JSON valide, sans markdown, sans explication.\n" +
        "Format strict :\n" +
        "{\"cmd\":\"<commande>\",\"pkg\":\"<package_ou_null>\",\"reply\":\"<phrase_courte_fr>\"}\n\n" +
        "Commandes disponibles :\n" +
        "- cluster_on     : activer/ouvrir le cluster (tableau de bord secondaire)\n" +
        "- cluster_off    : désactiver/fermer le cluster\n" +
        "- open_diag      : ouvrir l'écran de diagnostics\n" +
        "- open_logs      : afficher les journaux\n" +
        "- launch_app     : lancer une application sur le cluster (renseigne pkg si connu)\n" +
        "- unknown        : commande non reconnue\n\n" +
        "La reply doit être une phrase naturelle courte (max 15 mots), confirmant l'action.\n" +
        "Si cmd=unknown, explique poliment que tu n'as pas compris.";

    // ─── State ─────────────────────────────────────────────────────────────
    private final Context              mCtx;
    private final VoiceCommandRouter   mFallback;
    private final Handler              mMain = new Handler(Looper.getMainLooper());    /** Guard: stop any previous TTS playback before starting a new one. */
    private MediaPlayer                mActiveMp;
    public LlmVoiceEngine(Context ctx) {
        mCtx     = ctx.getApplicationContext();
        mFallback = new VoiceCommandRouter(ctx);
    }

    // ─── Public API ────────────────────────────────────────────────────────

    /** Routes the transcript through the LLM, or falls back to regex routing. */
    public void route(String text) {
        String apiKey = readApiKey();
        if (apiKey == null || apiKey.isEmpty()) {
            AppLogger.d(TAG, "No API key — falling back to regex router");
            mFallback.route(text);
            return;
        }
        // Run network on a background thread
        new Thread(() -> routeOnBackground(text, apiKey), "llm-voice").start();
    }

    /** Releases the fallback router (TTS engine). Call from owner's onDestroy. */
    public void release() {
        mFallback.release();
        mMain.post(() -> {
            if (mActiveMp != null) {
                try { mActiveMp.stop(); mActiveMp.release(); } catch (Throwable ignore) {}
                mActiveMp = null;
            }
        });
    }

    // ─── API key storage ───────────────────────────────────────────────────

    /** Saves the OpenAI API key to EncryptedSharedPreferences. */
    public static void saveApiKey(Context ctx, String key) {
        try {
            getEncryptedPrefs(ctx).edit().putString(PREF_API_KEY, key.trim()).apply();
        } catch (Exception e) {
            AppLogger.e("LlmVoiceEngine", "saveApiKey error: " + e.getMessage());
        }
    }

    /** Returns the stored API key, or null if not set. */
    public static String readApiKey(Context ctx) {
        try {
            return getEncryptedPrefs(ctx).getString(PREF_API_KEY, null);
        } catch (Exception e) {
            AppLogger.e("LlmVoiceEngine", "readApiKey error: " + e.getMessage());
            return null;
        }
    }

    private String readApiKey() { return readApiKey(mCtx); }

    public static boolean hasApiKey(Context ctx) {
        String k = readApiKey(ctx);
        return k != null && !k.isEmpty();
    }

    private static androidx.security.crypto.EncryptedSharedPreferences getEncryptedPrefs(Context ctx)
            throws Exception {
        MasterKey masterKey = new MasterKey.Builder(ctx)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build();
        return (EncryptedSharedPreferences) EncryptedSharedPreferences.create(
                ctx,
                PREFS_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM);
    }

    // ─── LLM + TTS pipeline ────────────────────────────────────────────────

    private void routeOnBackground(String text, String apiKey) {
        try {
            // 1. Chat completion → JSON command
            String chatResponse = callChat(text, apiKey);
            if (chatResponse == null) throw new Exception("Chat API returned null");

            // 2. Parse JSON
            JSONObject json = new JSONObject(chatResponse);
            String cmd   = json.optString("cmd",   VoiceCommandRouter.CMD_UNKNOWN);
            String pkg   = json.optString("pkg",   null);
            String reply = json.optString("reply", "");
            if ("null".equalsIgnoreCase(pkg)) pkg = null;

            AppLogger.i(TAG, "LLM → cmd=" + cmd + " pkg=" + pkg + " reply=" + reply);

            // 3. Dispatch command via existing VoiceCommandRouter broadcast
            dispatchCmd(cmd, pkg);

            // 4. Synthesise reply with TTS API and play it
            if (!reply.isEmpty()) {
                byte[] mp3 = callTts(reply, apiKey);
                if (mp3 != null && mp3.length > 0) {
                    playMp3(mp3);
                } else {
                    showToast(reply);
                }
            }

        } catch (Exception e) {
            AppLogger.w(TAG, "LLM pipeline error: " + e.getMessage() + " — falling back to regex");
            mMain.post(() -> mFallback.route(text));
        }
    }

    /** Calls /v1/chat/completions and returns the assistant message content. */
    private String callChat(String userText, String apiKey) throws Exception {
        JSONObject body = new JSONObject();
        body.put("model", CHAT_MODEL);
        JSONArray messages = new JSONArray();
        messages.put(new JSONObject().put("role", "system").put("content", SYSTEM_PROMPT));
        messages.put(new JSONObject().put("role", "user").put("content", userText));
        body.put("messages", messages);
        body.put("max_tokens", 120);
        body.put("temperature", 0.2);

        String raw = postJson(CHAT_URL, body.toString(), apiKey);
        if (raw == null) return null;

        JSONObject resp = new JSONObject(raw);
        return resp.getJSONArray("choices")
                   .getJSONObject(0)
                   .getJSONObject("message")
                   .getString("content")
                   .trim();
    }

    /** Calls /v1/audio/speech and returns raw MP3 bytes. */
    private byte[] callTts(String text, String apiKey) throws Exception {
        JSONObject body = new JSONObject();
        body.put("model", TTS_MODEL);
        body.put("input", text);
        body.put("voice", TTS_VOICE);
        body.put("response_format", "mp3");

        URL url = new URL(TTS_URL);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Authorization", "Bearer " + apiKey);
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setConnectTimeout(10_000);
        conn.setReadTimeout(15_000);
        conn.setDoOutput(true);

        byte[] bodyBytes = body.toString().getBytes(StandardCharsets.UTF_8);
        try (OutputStream os = conn.getOutputStream()) {
            os.write(bodyBytes);
        }

        int code = conn.getResponseCode();
        if (code != HttpURLConnection.HTTP_OK) {
            AppLogger.w(TAG, "TTS API HTTP " + code);
            return null;
        }

        try (InputStream is = conn.getInputStream()) {
            byte[] buf = new byte[4096];
            java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
            int n;
            while ((n = is.read(buf)) != -1) baos.write(buf, 0, n);
            return baos.toByteArray();
        } finally {
            conn.disconnect();
        }
    }

    /** Writes MP3 bytes to a temp file and plays them via MediaPlayer. */
    private void playMp3(byte[] mp3) {
        try {
            File tmp = File.createTempFile("jarvis_tts_", ".mp3", mCtx.getCacheDir());
            tmp.deleteOnExit();
            try (FileOutputStream fos = new FileOutputStream(tmp)) {
                fos.write(mp3);
            }
            final File finalTmp = tmp;
            mMain.post(() -> {
                // Stop any previous TTS still playing
                if (mActiveMp != null) {
                    try { mActiveMp.stop(); mActiveMp.release(); } catch (Throwable ignore) {}
                    mActiveMp = null;
                }
                final AudioManager am = (AudioManager)
                        mCtx.getSystemService(android.content.Context.AUDIO_SERVICE);
                final AudioFocusRequest focusReq = new AudioFocusRequest.Builder(
                        AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                        .setAudioAttributes(new android.media.AudioAttributes.Builder()
                                .setUsage(android.media.AudioAttributes.USAGE_ASSISTANT)
                                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                                .build())
                        .setAcceptsDelayedFocusGain(false)
                        .build();
                int focus = am.requestAudioFocus(focusReq);
                if (focus != AudioManager.AUDIOFOCUS_REQUEST_GRANTED
                        && focus != AudioManager.AUDIOFOCUS_REQUEST_DELAYED) {
                    AppLogger.w(TAG, "Audio focus not granted ("+focus+") — playing anyway");
                }
                try {
                    MediaPlayer mp = new MediaPlayer();
                    mActiveMp = mp;
                    mp.setAudioAttributes(new android.media.AudioAttributes.Builder()
                            .setUsage(android.media.AudioAttributes.USAGE_ASSISTANT)
                            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build());
                    mp.setDataSource(finalTmp.getAbsolutePath());
                    mp.setOnCompletionListener(p -> {
                        p.release();
                        if (mActiveMp == p) mActiveMp = null;
                        finalTmp.delete();
                        am.abandonAudioFocusRequest(focusReq);
                    });
                    mp.setOnErrorListener((p, what, extra) -> {
                        AppLogger.w(TAG, "MediaPlayer error what=" + what);
                        p.release();
                        if (mActiveMp == p) mActiveMp = null;
                        finalTmp.delete();
                        am.abandonAudioFocusRequest(focusReq);
                        return true;
                    });
                    mp.setOnPreparedListener(p -> {
                        p.start();
                        AppLogger.d(TAG, "Playing TTS audio");
                    });
                    mp.prepareAsync();
                } catch (Exception e) {
                    AppLogger.w(TAG, "MediaPlayer error: " + e.getMessage());
                    am.abandonAudioFocusRequest(focusReq);
                }
            });
        } catch (Exception e) {
            AppLogger.w(TAG, "playMp3 error: " + e.getMessage());
        }
    }

    /** Posts a JSON body and returns the response body string. */
    private String postJson(String urlStr, String json, String apiKey) throws Exception {
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Authorization", "Bearer " + apiKey);
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setConnectTimeout(10_000);
        conn.setReadTimeout(20_000);
        conn.setDoOutput(true);

        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        try (OutputStream os = conn.getOutputStream()) {
            os.write(body);
        }

        int code = conn.getResponseCode();
        if (code != HttpURLConnection.HTTP_OK) {
            AppLogger.w(TAG, "Chat API HTTP " + code);
            return null;
        }

        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
            return sb.toString();
        } finally {
            conn.disconnect();
        }
    }

    /** Broadcasts a voice command via LocalBroadcastManager → MainActivity. */
    private void dispatchCmd(String cmd, String pkg) {
        Intent i = new Intent(VoiceCommandRouter.ACTION_VOICE_COMMAND);
        i.putExtra(VoiceCommandRouter.EXTRA_CMD, cmd);
        if (pkg != null) i.putExtra(VoiceCommandRouter.EXTRA_PKG, pkg);
        LocalBroadcastManager.getInstance(mCtx).sendBroadcast(i);
    }

    private void showToast(String text) {
        mMain.post(() -> Toast.makeText(mCtx, text, Toast.LENGTH_LONG).show());
    }
}
