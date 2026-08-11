package com.byd.dashcast.report

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.byd.dashcast.util.AppLogger

/**
 * Where the reporting credentials come from — device storage, not the binary.
 *
 * The audit's only P0 (AUD-001) is that the Telegram bot token is a DEX constant of a publicly
 * downloadable APK: `unzip` plus `strings` hands it to anyone. R8 changes nothing, a `String`
 * constant is inlined into the pool. The Azure SAS added in 1.8.21 repeats the pattern for a second
 * destination — better scoped (create/write only, no read, no list) but still public by the same
 * reasoning its own KDoc gives: "a token shipped inside an APK has to be treated as public".
 *
 * The fix D2 settled on is not a relay — a single maintainer cannot be asked to keep a service
 * alive, and the day it stops the whole fleet goes silent with no OTA to recover it. Credentials are
 * provisioned at runtime instead: pasted once per device, held in [EncryptedSharedPreferences], and
 * rotatable in the two minutes it takes to edit a pinned message rather than never.
 *
 * **There is no build-time fallback.** The five `buildConfigField` entries that used to feed this
 * are gone, so an unpaired device simply has no transport and every emitter takes its local exit.
 * That is the point: nothing in the APK can leak what the APK no longer contains.
 *
 * **Values are never logged.** Not on success, not on failure, not in an exception message.
 * [AppLogger] is appended to every bug report by [BugReportCapture], so one careless log line would
 * copy a credential into every artefact uploaded afterwards — a leak worse than the one being
 * fixed, because it repeats. Anything added here must respect that.
 */
object ReportChannel {

    private const val TAG = "ReportChannel"
    private const val PREFS_NAME = "dashcast_report_channel"

    private const val K_BOT_TOKEN = "bot_token"
    private const val K_CHAT_ID = "chat_id"
    private const val K_THREAD_ID = "thread_id"
    private const val K_HUD_THREAD_ID = "hud_thread_id"
    private const val K_AZURE_URL = "azure_url"
    private const val K_AZURE_SAS = "azure_sas"

    @Volatile private var sPrefs: android.content.SharedPreferences? = null
    @Volatile private var sApp: Context? = null

    /**
     * Registers the application context so the accessors can be called without one.
     *
     * TelegramBugReporter.isConfigured() has 17 call sites and no Context to hand. Threading one
     * through all of them to read a credential would be a large mechanical change across live
     * reporting paths, for no benefit: an Application context lives as long as the process, so
     * holding it is not the leak that holding an Activity would be.
     *
     * Call [warm] afterwards, off the main thread.
     */
    @JvmStatic
    fun init(ctx: Context) {
        sApp = ctx.applicationContext
    }

    /**
     * Forces the encrypted store open, off the main thread.
     *
     * Creating EncryptedSharedPreferences is KeyStore IPC plus a file read. Before this class the
     * credential was a compile-time constant and reading it was free, so every isConfigured() call
     * — several of them on the main thread — could afford to be naive. Warming the cache at startup
     * keeps it that way. A main-thread read arriving before the warm finishes pays the cost once
     * rather than never, which is a smaller regression than blocking on it at every call.
     */
    @JvmStatic
    fun warm() {
        sApp?.let { runCatching { prefs(it) } }
    }

    /**
     * Test seam. [EncryptedSharedPreferences] needs the Android KeyStore, which Robolectric does
     * not emulate, so without this the precedence and normalisation rules below would ship with no
     * coverage at all on a security-critical path. Production never calls it; it is not a fallback
     * and there is deliberately no automatic downgrade to plaintext preferences when the encrypted
     * store is unavailable — storing a credential in the clear to keep a feature working would
     * defeat the point of this class.
     */
    @androidx.annotation.VisibleForTesting
    @JvmStatic
    fun setPrefsForTesting(p: android.content.SharedPreferences?) {
        sPrefs = p
    }

    // ── reads ───────────────────────────────────────────────────────────────────────────────

    // No-Context overloads for the call sites that have none. They resolve through the registered
    // application context and degrade to the build value when [init] has not run yet.
    @JvmStatic fun botToken(): String = sApp?.let { botToken(it) } ?: ""
    @JvmStatic fun chatId(): String = sApp?.let { chatId(it) } ?: ""
    @JvmStatic fun threadId(): String = sApp?.let { threadId(it) } ?: ""
    @JvmStatic fun hudThreadId(): String = sApp?.let { hudThreadId(it) } ?: ""
    @JvmStatic fun azureUrl(): String = sApp?.let { azureUrl(it) } ?: ""
    @JvmStatic fun azureSas(): String = sApp?.let { azureSas(it) } ?: ""

    @JvmStatic fun hasTelegram(): Boolean = botToken().isNotEmpty() && chatId().isNotEmpty()
    @JvmStatic fun hasAzure(): Boolean = azureUrl().isNotEmpty() && azureSas().isNotEmpty()

    @JvmStatic fun botToken(ctx: Context): String = read(ctx, K_BOT_TOKEN, "")
    @JvmStatic fun chatId(ctx: Context): String = read(ctx, K_CHAT_ID, "")
    @JvmStatic fun threadId(ctx: Context): String = read(ctx, K_THREAD_ID, "")
    @JvmStatic fun hudThreadId(ctx: Context): String = read(ctx, K_HUD_THREAD_ID, "")
    @JvmStatic fun azureUrl(ctx: Context): String = read(ctx, K_AZURE_URL, "")
    @JvmStatic fun azureSas(ctx: Context): String = read(ctx, K_AZURE_SAS, "")

    /** True when the bot can be used: a token and a destination chat. */
    @JvmStatic
    fun hasTelegram(ctx: Context): Boolean =
        botToken(ctx).isNotEmpty() && chatId(ctx).isNotEmpty()

    /** True when the container can be used: a URL and a SAS. */
    @JvmStatic
    fun hasAzure(ctx: Context): Boolean =
        azureUrl(ctx).isNotEmpty() && azureSas(ctx).isNotEmpty()

    /** True when this device has been paired, i.e. at least one credential set is on the device. */
    @JvmStatic
    fun isPairedOnDevice(ctx: Context): Boolean {
        val p = prefs(ctx) ?: return false
        return !p.getString(K_BOT_TOKEN, "").isNullOrEmpty() ||
               !p.getString(K_AZURE_SAS, "").isNullOrEmpty()
    }

    // ── writes ──────────────────────────────────────────────────────────────────────────────

    /**
     * Stores the Telegram triplet. Blank arguments clear their key, so pairing can also un-pair.
     *
     * @return true when the write reached encrypted storage.
     */
    @JvmStatic
    fun saveTelegram(ctx: Context, token: String, chat: String, thread: String, hudThread: String): Boolean {
        val p = prefs(ctx) ?: return false
        return try {
            p.edit()
                .putString(K_BOT_TOKEN, token.trim())
                .putString(K_CHAT_ID, chat.trim())
                .putString(K_THREAD_ID, thread.trim())
                .putString(K_HUD_THREAD_ID, hudThread.trim())
                .commit()
                .also { AppLogger.i(TAG, "telegram credentials stored on device") }
        } catch (t: Throwable) {
            // Deliberately does not echo the exception message: it can quote the value.
            AppLogger.w(TAG, "storing telegram credentials failed (" + t.javaClass.simpleName + ")")
            false
        }
    }

    /** Stores the Azure pair. Same contract as [saveTelegram]. */
    @JvmStatic
    fun saveAzure(ctx: Context, url: String, sas: String): Boolean {
        val p = prefs(ctx) ?: return false
        return try {
            p.edit()
                .putString(K_AZURE_URL, url.trim())
                .putString(K_AZURE_SAS, sas.trim().removePrefix("?"))
                .commit()
                .also { AppLogger.i(TAG, "azure credentials stored on device") }
        } catch (t: Throwable) {
            AppLogger.w(TAG, "storing azure credentials failed (" + t.javaClass.simpleName + ")")
            false
        }
    }

    /** Forgets every credential held on this device. */
    @JvmStatic
    fun clear(ctx: Context): Boolean {
        val p = prefs(ctx) ?: return false
        return try {
            p.edit().clear().commit().also { AppLogger.i(TAG, "device credentials cleared") }
        } catch (t: Throwable) {
            AppLogger.w(TAG, "clearing credentials failed (" + t.javaClass.simpleName + ")")
            false
        }
    }

    // ── provisioning ────────────────────────────────────────────────────────────────────────

    /** File name looked for, wherever it has been dropped. */
    const val IMPORT_NAME = "dashcast_channel.properties"

    /**
     * Where the provisioning file is looked for, in order.
     *
     * `Download` comes first and that is the whole point: it removes the computer from the
     * procedure. A tester can put the file there from a USB stick, a file manager, or a download on
     * the head unit itself — no ADB, no IP address, no `adb push`. `/data/local/tmp` stays as the
     * maintainer's route, and because the read goes through the uid-2000 shell rather than the
     * app's own file access, neither location needs a storage permission and A13's Android/data
     * restriction is irrelevant.
     */
    @JvmField
    val IMPORT_PATHS = listOf(
        "/sdcard/Download/" + IMPORT_NAME,
        "/storage/emulated/0/Download/" + IMPORT_NAME,
        "/data/local/tmp/" + IMPORT_NAME)

    /** Kept for the Diagnostics label; the search covers [IMPORT_PATHS]. */
    const val IMPORT_PATH = "/sdcard/Download/dashcast_channel.properties"

    /**
     * Imports credentials from the first of [IMPORT_PATHS] that exists.
     *
     * This is the provisioning route D2 chose as the primary one for a fleet whose owners already
     * sideload over ADB — it needs no new screen, no new translated string, and no paste on a car
     * touchscreen, an interaction this application has no precedent for.
     *
     * **The file is read with `executeShellWithResultUnlogged`, and that is not a detail.** The
     * ordinary shell helper echoes stdout into AppLogger, which BugReportCapture appends to every
     * bug report. Reading a credential file through it would copy the token into every artefact
     * uploaded afterwards — a leak strictly worse than the one being fixed, because it repeats on
     * every report instead of sitting in one binary. The D2 analysis flagged exactly this trap in
     * the design it recommended.
     *
     * The file is deliberately NOT deleted after import: the README documents a full uninstall as a
     * normal step, and that clears the encrypted store, so the file is what makes re-pairing
     * possible without another round trip to the maintainer.
     *
     * @param done invoked with a human-readable outcome that never contains a credential.
     */
    @JvmStatic
    fun importFromDevice(ctx: Context, done: (String) -> Unit) {
        com.byd.dashcast.infrastructure.AdbLocalClient.executeShellWithResultUnlogged(
            ctx.applicationContext,
            IMPORT_PATHS.joinToString(" || ") { "cat '" + it + "' 2>/dev/null" },
            object : com.byd.dashcast.infrastructure.AdbLocalClient.Callback {
                override fun onSuccess(out: String?) {
                    val text = out ?: ""
                    if (text.isBlank()) {
                        done("no " + IMPORT_NAME + " found (looked in Download and /data/local/tmp)")
                        return
                    }
                    val kv = HashMap<String, String>()
                    for (line in text.lineSequence()) {
                        val t = line.trim()
                        if (t.isEmpty() || t.startsWith("#") || !t.contains('=')) continue
                        val i = t.indexOf('=')
                        kv[t.substring(0, i).trim()] = t.substring(i + 1).trim()
                    }
                    var applied = 0
                    val tok = kv["bugReport.botToken"].orEmpty()
                    if (tok.isNotEmpty()) {
                        if (saveTelegram(ctx, tok, kv["bugReport.chatId"].orEmpty(),
                                kv["bugReport.threadId"].orEmpty(),
                                kv["bugReport.hudThreadId"].orEmpty())) applied++
                    }
                    val sas = kv["azure.sas"].orEmpty()
                    if (sas.isNotEmpty()) {
                        if (saveAzure(ctx, kv["azure.blobUrl"].orEmpty(), sas)) applied++
                    }
                    // Counts only — never the keys' values.
                    done(if (applied > 0) "paired: $applied credential set(s) stored on this device"
                         else "provisioning file found but no usable credentials in it")
                }
                override fun onError(err: String?) {
                    done("could not read " + IMPORT_NAME + " (" + (err ?: "no detail") + ")")
                }
            })
    }

    /**
     * Pairs on its own when a provisioning file is present and the device is not paired yet.
     *
     * Removes the last manual step: drop the file in `Download`, open the app, done. The tester
     * never has to find a button in a developer screen — a place they have no reason to look.
     *
     * Runs only while unpaired, so it stops costing anything the moment it succeeds. Must be called
     * off the main thread: it goes through the shell, which may have to wake a cold daemon.
     */
    @JvmStatic
    fun autoPairIfNeeded(ctx: Context) {
        if (isPairedOnDevice(ctx)) return
        try {
            importFromDevice(ctx) { outcome ->
                // Only worth a line when something actually happened; an absent file is the normal
                // case for a device that was never meant to upload, and must not look like an error.
                if (outcome.startsWith("paired")) AppLogger.i(TAG, "auto-pair: " + outcome)
            }
        } catch (t: Throwable) {
            AppLogger.w(TAG, "auto-pair skipped (" + t.javaClass.simpleName + ")")
        }
    }

    // ── internals ───────────────────────────────────────────────────────────────────────────

    private fun read(ctx: Context, key: String, buildDefault: String): String {
        val onDevice = try { prefs(ctx)?.getString(key, "") ?: "" } catch (_: Throwable) { "" }
        return if (onDevice.isNotEmpty()) onDevice else buildDefault
    }

    /**
     * Encrypted preferences, cached — the KeyStore round-trip is IPC and this is read on hot paths.
     * Returns null rather than throwing when the KeyStore is unavailable, which happens on some
     * ROMs; callers then fall back to the build-time value and the feature degrades instead of
     * crashing. Mirrors the pattern already used by LlmVoiceEngine.
     */
    private fun prefs(ctx: Context): android.content.SharedPreferences? {
        sPrefs?.let { return it }
        synchronized(ReportChannel::class.java) {
            sPrefs?.let { return it }
            return try {
                val masterKey = MasterKey.Builder(ctx.applicationContext)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build()
                val p = EncryptedSharedPreferences.create(
                    ctx.applicationContext,
                    PREFS_NAME,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                )
                sPrefs = p
                p
            } catch (t: Throwable) {
                AppLogger.w(TAG, "encrypted store unavailable (" + t.javaClass.simpleName + ")")
                null
            }
        }
    }
}
