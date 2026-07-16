package com.byd.dashcast.voice

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.widget.Toast

import androidx.core.content.edit
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

import com.byd.dashcast.util.AppLogger
import com.byd.dashcast.util.concurrent.LifecycleGate

import org.json.JSONArray
import org.json.JSONObject

import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * v1.4.2-beta — LLM-powered voice engine.
 *
 * Receives a Vosk transcript, sends it to the OpenAI chat API with a
 * structured system prompt that returns a JSON command + French reply, then
 * synthesises the reply with the OpenAI TTS API and plays it via MediaPlayer.
 *
 * **Fallback**: if no API key is configured, or if any network/parse
 * error occurs, execution is delegated to [VoiceCommandRouter] (regex
 * matching). The app can never crash due to LLM failures.
 *
 * **Security**: the API key is stored in [EncryptedSharedPreferences]
 * (AES-256-GCM). It is never logged or committed to the repository.
 */
class LlmVoiceEngine(ctx: Context) {

    // ─── State ─────────────────────────────────────────────────────────────
    private val mCtx: Context = ctx.applicationContext
    private val mFallback: VoiceCommandRouter = VoiceCommandRouter(ctx)
    private val mMain = Handler(Looper.getMainLooper())
    /** Main-thread-owned, release-once TTS session. */
    private var mActivePlayback: PlaybackSession? = null
    private val mLifecycleGate = LifecycleGate()
    private val mReleaseOnce = AtomicBoolean(false)

    init {
        // Clean up .mp3 temp files orphaned by a previous crash
        // (deleteOnExit() is unreliable on Android — process is killed, not cleanly exited).
        val stale = mCtx.cacheDir.listFiles { _, n ->
            n.startsWith("jarvis_tts_") && n.endsWith(".mp3")
        }
        if (stale != null) for (f in stale) f.delete()
    }

    // ─── Public API ────────────────────────────────────────────────────────

    /** Routes the transcript through the LLM, or falls back to regex routing. */
    fun route(text: String?) {
        val operation = mLifecycleGate.capture()
        if (!operation.isValid) return
        // Ignore empty or whitespace-only transcripts (nothing was heard)
        if (text == null || text.trim().isEmpty()) {
            AppLogger.d(TAG, "route() ignored — empty transcript")
            return
        }
        val apiKey = readApiKey()
        if (apiKey == null || apiKey.isEmpty()) {
            AppLogger.d(TAG, "No API key — falling back to regex router")
            if (operation.isValid) mFallback.route(text)
            return
        }
        if (!sRouteActive.compareAndSet(false, true)) {
            AppLogger.d(TAG, "route() — LLM call already in flight, ignored")
            return
        }
        sLlmExecutor.execute {
            try {
                routeOnBackground(text, apiKey, operation)
            } finally {
                sRouteActive.set(false)
            }
        }
    }

    /** Releases the fallback router (TTS engine). Call from owner's onDestroy. */
    fun release() {
        if (!mReleaseOnce.compareAndSet(false, true)) return
        mLifecycleGate.invalidate()
        mFallback.release()
        mMain.post { releaseActivePlayback() }
    }

    private fun readApiKey(): String? = readApiKey(mCtx)

    // ─── LLM + TTS pipeline ────────────────────────────────────────────────

    private fun routeOnBackground(
        text: String,
        apiKey: String,
        operation: LifecycleGate.Token
    ) {
        if (!operation.isValid) return
        try {
            // 1. Chat completion → JSON command
            val chatResponse = callChat(text, apiKey) ?: throw Exception("Chat API returned null")
            if (!operation.isValid) return

            // 2. Parse JSON — GPT-4o-mini sometimes wraps the response in
            // markdown code fences (```json ... ```) despite instructions.
            // Strip them before parsing to avoid a silent fallback.
            val stripped = chatResponse
                .replace(Regex("(?s)^```[a-zA-Z]*\\s*"), "")
                .replace(Regex("(?s)\\s*```\$"), "")
                .trim()
            // Raw model output can echo the driver's spoken query + answer (conversation PII) and
            // enters the journal/bug reports — log only its length; parsed cmd/pkg below is the signal.
            AppLogger.d(TAG, "LLM raw: ${stripped.length} chars")
            val json = JSONObject(stripped)
            val cmd = json.optString("cmd", VoiceCommandRouter.CMD_UNKNOWN)
            // optString(name, null) equivalent, without passing null to a @NonNull fallback:
            // missing → null; JSON-null → the literal "null" (mapped to null below); string → value.
            var pkg: String? =
                if (json.has("pkg") && !json.isNull("pkg")) json.optString("pkg") else null
            val reply = json.optString("reply", "")
            if ("null".equals(pkg, ignoreCase = true)) pkg = null

            // Do not log the reply text (the assistant's spoken answer = conversation PII); cmd/pkg
            // are the non-PII actionable result. reply length kept as a diagnostic signal.
            AppLogger.i(TAG, "LLM → cmd=$cmd pkg=$pkg replyLen=${reply.length}")

            // 3. Dispatch command (skip for "talk" — pure conversation, no app action)
            if (operation.isValid && !"talk".equals(cmd, ignoreCase = true)) {
                dispatchCmd(cmd, pkg)
            }

            // 4. Synthesise reply with TTS API and play it
            if (operation.isValid && reply.isNotEmpty()) {
                val mp3 = callTts(reply, apiKey)
                if (!operation.isValid) return
                if (mp3 != null && mp3.isNotEmpty()) {
                    playMp3(mp3, operation)
                } else {
                    showToast(reply, operation)
                }
            }
        } catch (e: Exception) {
            if (!operation.isValid) return
            AppLogger.w(TAG, "LLM pipeline error: ${e.message} — falling back to regex")
            mMain.post { if (operation.isValid) mFallback.route(text) }
        }
    }

    /** Calls /v1/chat/completions and returns the assistant message content. */
    private fun callChat(userText: String, apiKey: String): String? {
        val body = JSONObject()
        body.put("model", CHAT_MODEL)
        val messages = JSONArray()
        messages.put(JSONObject().put("role", "system").put("content", SYSTEM_PROMPT))
        messages.put(JSONObject().put("role", "user").put("content", userText))
        body.put("messages", messages)
        body.put("max_tokens", 200)
        body.put("temperature", 0.2)

        val raw = postJson(CHAT_URL, body.toString(), apiKey) ?: return null

        val resp = JSONObject(raw)
        return resp.getJSONArray("choices")
            .getJSONObject(0)
            .getJSONObject("message")
            .getString("content")
            .trim()
    }

    /** Calls /v1/audio/speech and returns raw MP3 bytes. */
    private fun callTts(text: String, apiKey: String): ByteArray? {
        val body = JSONObject()
        body.put("model", TTS_MODEL)
        body.put("input", text)
        body.put("voice", TTS_VOICE)
        body.put("response_format", "mp3")

        val url = URL(TTS_URL)
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Authorization", "Bearer $apiKey")
        conn.setRequestProperty("Content-Type", "application/json")
        conn.connectTimeout = 10_000
        conn.readTimeout = 15_000
        conn.doOutput = true

        try {
            val bodyBytes = body.toString().toByteArray(StandardCharsets.UTF_8)
            conn.outputStream.use { os -> os.write(bodyBytes) }

            val code = conn.responseCode
            if (code != HttpURLConnection.HTTP_OK) {
                AppLogger.w(TAG, "TTS API HTTP $code")
                drainErrorStream(conn) // consume the error body so the socket can be pooled
                return null
            }

            conn.inputStream.use { input ->
                val buf = ByteArray(4096)
                val baos = ByteArrayOutputStream(65_536)
                var n: Int
                while (input.read(buf).also { n = it } != -1) baos.write(buf, 0, n)
                return baos.toByteArray()
            }
        } catch (e: Throwable) {
            // Network/read error: the socket state is unknown, so close it rather than pool a
            // half-consumed connection. The success and non-200 paths deliberately do NOT
            // disconnect so the connection returns to the keep-alive pool (saves a TLS
            // handshake on the next api.openai.com call).
            conn.disconnect()
            throw e
        }
    }

    /** Drain + close the error stream so the socket can return to the keep-alive pool
     *  instead of lingering half-consumed. Used on non-200 responses (the success path
     *  fully reads + closes the input stream via use{}). */
    private fun drainErrorStream(conn: HttpURLConnection) {
        try { conn.errorStream?.use { it.readBytes() } } catch (ignore: Throwable) {}
    }

    /** Writes MP3 bytes to a temp file and plays them via MediaPlayer. */
    private fun playMp3(mp3: ByteArray, operation: LifecycleGate.Token) {
        if (!operation.isValid) return
        var pendingTemp: File? = null
        try {
            val tmp = File.createTempFile("jarvis_tts_", ".mp3", mCtx.cacheDir)
            pendingTemp = tmp
            tmp.deleteOnExit()
            FileOutputStream(tmp).use { fos -> fos.write(mp3) }
            val posted = mMain.post {
                if (!operation.isValid) { tmp.delete(); return@post }
                val am = mCtx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                var focusReq: AudioFocusRequest? = null
                var session: PlaybackSession? = null
                try {
                    // Stop any previous TTS still playing — and abandon ITS focus grant.
                    releaseActivePlayback()
                    focusReq = AudioFocusRequest.Builder(
                        AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                        .setAudioAttributes(ASSISTANT_AUDIO_ATTRS)
                        .setAcceptsDelayedFocusGain(false)
                        .build()
                    val focus = am.requestAudioFocus(focusReq)
                    if (focus != AudioManager.AUDIOFOCUS_REQUEST_GRANTED
                        && focus != AudioManager.AUDIOFOCUS_REQUEST_DELAYED) {
                        AppLogger.w(TAG, "Audio focus not granted ($focus) — playing anyway")
                    }
                    session = PlaybackSession(tmp, focusReq, am)
                    mActivePlayback = session
                    val mp = MediaPlayer()
                    session.player = mp
                    mp.setAudioAttributes(ASSISTANT_AUDIO_ATTRS)
                    mp.setDataSource(tmp.absolutePath)
                    mp.setOnCompletionListener { p ->
                        releasePlayback(session, stopFirst = false)
                    }
                    mp.setOnErrorListener { p, what, _ ->
                        AppLogger.w(TAG, "MediaPlayer error what=$what")
                        releasePlayback(session, stopFirst = false)
                        true
                    }
                    mp.setOnPreparedListener { p ->
                        if (operation.isValid && mActivePlayback === session
                                && session.player === p) {
                            p.start()
                            AppLogger.d(TAG, "Playing TTS audio")
                        } else {
                            releasePlayback(session, stopFirst = false)
                        }
                    }
                    mp.prepareAsync()
                } catch (e: Exception) {
                    AppLogger.w(TAG, "MediaPlayer error: ${e.message}")
                    if (session != null) {
                        releasePlayback(session, stopFirst = true)
                    } else {
                        tmp.delete()
                        if (focusReq != null) {
                            try { am.abandonAudioFocusRequest(focusReq) }
                            catch (ignore: Throwable) {}
                        }
                    }
                }
            }
            if (posted) {
                pendingTemp = null // ownership transferred to the main-thread Runnable/session
            } else {
                tmp.delete()
            }
        } catch (e: Exception) {
            pendingTemp?.delete()
            AppLogger.w(TAG, "playMp3 error: ${e.message}")
        }
    }

    /** Main-thread only: releases the currently tracked playback, if any. */
    private fun releaseActivePlayback() {
        val active = mActivePlayback ?: return
        releasePlayback(active, stopFirst = true)
    }

    /** Main-thread only; identity checks make duplicate/late MediaPlayer callbacks harmless. */
    private fun releasePlayback(session: PlaybackSession, stopFirst: Boolean) {
        if (mActivePlayback === session) mActivePlayback = null
        session.release(stopFirst)
    }

    private class PlaybackSession(
        private val tempFile: File,
        private val focusRequest: AudioFocusRequest,
        private val audioManager: AudioManager
    ) {
        var player: MediaPlayer? = null
        private val released = AtomicBoolean(false)

        fun release(stopFirst: Boolean) {
            if (!released.compareAndSet(false, true)) return
            val current = player
            player = null
            if (current != null) {
                if (stopFirst) try { current.stop() } catch (ignore: Throwable) {}
                try { current.release() } catch (ignore: Throwable) {}
            }
            tempFile.delete()
            try { audioManager.abandonAudioFocusRequest(focusRequest) }
            catch (ignore: Throwable) {}
        }
    }

    /** Posts a JSON body and returns the response body string. */
    private fun postJson(urlStr: String, json: String, apiKey: String): String? {
        val url = URL(urlStr)
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Authorization", "Bearer $apiKey")
        conn.setRequestProperty("Content-Type", "application/json")
        conn.connectTimeout = 10_000
        conn.readTimeout = 20_000
        conn.doOutput = true
        try {
            val body = json.toByteArray(StandardCharsets.UTF_8)
            conn.outputStream.use { os -> os.write(body) }

            val code = conn.responseCode
            if (code != HttpURLConnection.HTTP_OK) {
                AppLogger.w(TAG, "Chat API HTTP $code")
                drainErrorStream(conn) // consume the error body (HTTP 429/500/…) so the socket pools
                return null
            }

            BufferedReader(InputStreamReader(conn.inputStream, StandardCharsets.UTF_8)).use { br ->
                val sb = StringBuilder(1024)
                var line: String?
                while (br.readLine().also { line = it } != null) sb.append(line)
                return sb.toString()
            }
        } catch (e: Throwable) {
            // Network/read error: unknown socket state — close it. Success and non-200 paths
            // keep the connection alive so back-to-back voice commands reuse the TLS session.
            conn.disconnect()
            throw e
        }
    }

    /** Broadcasts a voice command via LocalBroadcastManager → MainActivity. */
    private fun dispatchCmd(cmd: String, pkg: String?) {
        val i = Intent(VoiceCommandRouter.ACTION_VOICE_COMMAND)
        i.putExtra(VoiceCommandRouter.EXTRA_CMD, cmd)
        if (pkg != null) i.putExtra(VoiceCommandRouter.EXTRA_PKG, pkg)
        LocalBroadcastManager.getInstance(mCtx).sendBroadcast(i)
    }

    private fun showToast(text: String, operation: LifecycleGate.Token) {
        mMain.post {
            if (operation.isValid) Toast.makeText(mCtx, text, Toast.LENGTH_LONG).show()
        }
    }

    companion object {
        private const val TAG = "LlmVoiceEngine"

        // C6 fix: prevent concurrent LLM calls (rapid triggers → two threads → competing audio).
        private val sRouteActive = AtomicBoolean(false)

        // L6: single reusable thread for all LLM calls — avoids new Thread per voice command.
        private val sLlmExecutor: ExecutorService = Executors.newSingleThreadExecutor { r ->
            Thread(r, "llm-voice").apply { isDaemon = true }
        }

        // L5: immutable AudioAttributes shared across all TTS playback sessions.
        private val ASSISTANT_AUDIO_ATTRS: AudioAttributes =
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()

        // H1: cached EncryptedSharedPreferences — avoids KeyStore IPC on every route() call.
        @Volatile private var sCachedPrefs: EncryptedSharedPreferences? = null

        // ─── Prefs ─────────────────────────────────────────────────────────────
        private const val PREFS_NAME = "dashcast_llm_prefs"
        const val PREF_API_KEY = "openai_api_key"

        // ─── OpenAI endpoints ──────────────────────────────────────────────────
        private const val CHAT_URL = "https://api.openai.com/v1/chat/completions"
        private const val TTS_URL = "https://api.openai.com/v1/audio/speech"
        private const val CHAT_MODEL = "gpt-4o-mini"
        private const val TTS_MODEL = "tts-1"
        private const val TTS_VOICE = "onyx"   // deep, masculine — closest to JARVIS

        // ─── System prompt ────────────────────────────────────────────────────
        private const val SYSTEM_PROMPT =
            "Tu es JARVIS, l'IA embarquée d'une BYD Seal conduite sur route.\n" +
            "Ta sortie est lue par synthèse vocale (TTS) — pas de markdown, pas de listes, pas d'URLs.\n" +
            "\n" +
            "PERSONNALITÉ\n" +
            "Majordome britannique du XXIe siècle : précis, confiant, légèrement ironique, jamais servile.\n" +
            "INTERDIT : \"Bien sûr !\", \"Absolument !\", \"Je vais...\", \"Je suis désolé...\", \"En tant qu'IA...\".\n" +
            "Va droit au fait. Une pointe d'humour sec est bienvenue si le contexte s'y prête.\n" +
            "\n" +
            "FORMAT DE SORTIE — JSON strict, une seule ligne, sans backticks\n" +
            "{\"cmd\":\"<cmd>\",\"pkg\":<pkg_ou_null>,\"reply\":\"<texte_tts>\"}\n" +
            "\n" +
            "CONTRAINTES DE LONGUEUR\n" +
            "- cmd ≠ talk → reply ≤ 8 mots (confirmation d'action, sobre)\n" +
            "- cmd = talk  → reply ≤ 30 mots (réponse orale, pas de listes, pas de markdown)\n" +
            "- Si la réponse complète dépasse 30 mots : donne l'essentiel, propose d'approfondir.\n" +
            "\n" +
            "COMMANDES VOITURE\n" +
            "cluster_on   — activer / ouvrir le cluster secondaire\n" +
            "cluster_off  — désactiver / fermer le cluster\n" +
            "open_diag    — ouvrir le panneau de diagnostic\n" +
            "open_logs    — afficher les journaux système\n" +
            "launch_app   — lancer une application (renseigne pkg si identifiable)\n" +
            "talk         — tout le reste : questions, calcul, culture, conversation\n" +
            "\n" +
            "CORRECTION ASR (transcription Vosk imparfaite — interprète phonétiquement)\n" +
            "\"mettez\" → météo | \"clutter\" → cluster | \"diagnostique\" → diagnostic\n" +
            "\"ouvert\" → ouvrir | \"étein\" / \"stoppe\" / \"ferme\" → désactiver | \"affiche\" → activer\n" +
            "\n" +
            "DONNÉES MANQUANTES\n" +
            "- Météo temps réel, trafic, POI → tu n'as pas accès. Dis-le en une phrase, suggère l'app.\n" +
            "- Heure et date système → inaccessibles sauf si le conducteur les mentionne.\n" +
            "- Calcul, culture générale, science, histoire, conseil → réponds directement.\n" +
            "\n" +
            "EXEMPLES\n" +
            "user:\"cluster\" → {\"cmd\":\"cluster_on\",\"pkg\":null,\"reply\":\"Cluster activé.\"}\n" +
            "user:\"ferme le cluster\" → {\"cmd\":\"cluster_off\",\"pkg\":null,\"reply\":\"Cluster désactivé.\"}\n" +
            "user:\"diagnostique\" → {\"cmd\":\"open_diag\",\"pkg\":null,\"reply\":\"Diagnostic ouvert, Monsieur.\"}\n" +
            "user:\"lance YouTube\" → {\"cmd\":\"launch_app\",\"pkg\":\"com.google.android.youtube\",\"reply\":\"YouTube, Monsieur.\"}\n" +
            "user:\"combien font 347 fois 19\" → {\"cmd\":\"talk\",\"pkg\":null,\"reply\":\"6 593, Monsieur.\"}\n" +
            "user:\"météo\" → {\"cmd\":\"talk\",\"pkg\":null,\"reply\":\"Pas d'accès temps réel, Monsieur. Consultez votre appli météo.\"}\n" +
            "user:\"vitesse du son\" → {\"cmd\":\"talk\",\"pkg\":null,\"reply\":\"340 mètres par seconde dans l'air à 20 degrés, Monsieur.\"}\n" +
            "user:\"raconte une blague\" → {\"cmd\":\"talk\",\"pkg\":null,\"reply\":\"Je suis un assistant embarqué, Monsieur, pas un comédien. Mais je ferai une exception si vous insistez.\"}"

        // ─── API key storage ───────────────────────────────────────────────────

        /** Saves the OpenAI API key to EncryptedSharedPreferences. */
        @JvmStatic
        fun saveApiKey(ctx: Context, key: String) {
            try {
                getEncryptedPrefs(ctx).edit { putString(PREF_API_KEY, key.trim()) }
            } catch (e: Exception) {
                AppLogger.e("LlmVoiceEngine", "saveApiKey error: ${e.message}")
            }
        }

        /** Returns the stored API key, or null if not set. */
        @JvmStatic
        fun readApiKey(ctx: Context): String? {
            return try {
                getEncryptedPrefs(ctx).getString(PREF_API_KEY, null)
            } catch (e: Exception) {
                AppLogger.e("LlmVoiceEngine", "readApiKey error: ${e.message}")
                null
            }
        }

        @JvmStatic
        fun hasApiKey(ctx: Context): Boolean {
            val k = readApiKey(ctx)
            return k != null && k.isNotEmpty()
        }

        private fun getEncryptedPrefs(ctx: Context): EncryptedSharedPreferences {
            sCachedPrefs?.let { return it }
            synchronized(LlmVoiceEngine::class.java) {
                sCachedPrefs?.let { return it }
                val masterKey = MasterKey.Builder(ctx.applicationContext)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build()
                val p = EncryptedSharedPreferences.create(
                    ctx.applicationContext,
                    PREFS_NAME,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                ) as EncryptedSharedPreferences
                sCachedPrefs = p
                return p
            }
        }
    }
}
