package com.byd.dashcast.report

import android.content.Context
import com.byd.dashcast.util.AppLogger
import java.io.File

/**
 * The single place that decides how a diagnostic artefact leaves the car.
 *
 * Six screens produce artefacts and each one used to pick its own transport, which is why the
 * behaviour drifted between them: five spoke only to the bot, one learned about Azure, and the
 * fallbacks were written five times and were wrong in four. Centralising the decision does not by
 * itself fix anything a user can see — the value is that the next thing to add has ONE place to go.
 * The redaction stage that AUD-004 and AUD-217 require is that next thing.
 *
 * **Deliberately additive.** Nothing is removed: [TelegramBugReporter] and [AzureBlobUploader] keep
 * their public API and keep working exactly as before. Emitters move over one at a time, each
 * migration a pure delegation, so a regression can only ever affect the one screen being migrated
 * and can be reverted on its own. The stricter form of the type lock — deleting the `File`-taking
 * entry points so a future emitter *cannot* bypass this stage — is deliberately NOT done here: it
 * requires changing all six screens in one commit, and the diagnostic campaigns running on those
 * screens are worth more than the guarantee. It belongs with the migration of the last emitter.
 */
object ReportDelivery {

    private const val TAG = "ReportDelivery"

    /** Where an artefact ended up, for the caller to show. */
    enum class Sink { TELEGRAM, AZURE }

    interface Listener {
        /** @param sink which transport accepted it; [detail] is a URL for Azure, empty otherwise. */
        fun onSent(sink: Sink, detail: String)
        /** Every transport refused or failed. The artefact is still on disk. */
        fun onFailed(message: String)
    }

    /** True when at least one transport can be attempted. */
    @JvmStatic
    fun isAnyTransportConfigured(): Boolean =
        TelegramBugReporter.isConfigured() || AzureBlobUploader.isConfigured()

    /** Which transport an artefact should be offered to, or [Route.NONE] when there is no exit. */
    enum class Route { TELEGRAM, AZURE, NONE }

    /**
     * The routing decision, as a pure function of the three things it depends on.
     *
     * Kept separate from [deliver] on purpose. The configuration flags come from `BuildConfig`, so
     * anything that reads them directly is untestable off a configured build machine — the decision
     * would only ever be verified by running it. Extracted like this it is covered by unit tests,
     * which is the same shape the rest of this codebase already uses for its policies.
     */
    @JvmStatic
    fun route(telegramConfigured: Boolean, azureConfigured: Boolean, sizeBytes: Long): Route = when {
        telegramConfigured && sizeBytes < TELEGRAM_MAX_BYTES -> Route.TELEGRAM
        azureConfigured -> Route.AZURE
        else -> Route.NONE
    }

    /**
     * Delivers [file], choosing the transport by size and availability.
     *
     * The bot is preferred while the artefact fits under its 50 MB ceiling, because it lands in the
     * topic the maintainer already watches and carries the caption. Azure takes over above that
     * ceiling, or when the bot is not configured — it has no practical size limit but no caption and
     * no routing, so it is the second choice rather than the first.
     *
     * Never throws: a caller on a background thread gets its answer through [cb], on that same
     * thread for the Azure path and on the main looper for the bot path, exactly as before.
     */
    @JvmStatic
    fun deliver(ctx: Context, file: File, caption: String, topic: String, cb: Listener) {
        val tgFits = file.length() < TELEGRAM_MAX_BYTES
        val first = route(TelegramBugReporter.isConfigured(), AzureBlobUploader.isConfigured(),
                          file.length())
        if (first == Route.TELEGRAM) {
            TelegramBugReporter.send(ctx.applicationContext, file, caption, topic,
                object : TelegramBugReporter.Callback {
                    override fun onSent() = cb.onSent(Sink.TELEGRAM, "")
                    override fun onFailed(message: String) {
                        // Only worth a second attempt on a different transport when there IS one
                        // and it can take what the bot could not.
                        if (AzureBlobUploader.isConfigured()) {
                            AppLogger.w(TAG, "bot refused ($message) — trying Azure")
                            azure(file, cb)
                        } else {
                            cb.onFailed(message)
                        }
                    }
                })
            return
        }
        if (first == Route.AZURE) {
            if (!tgFits) AppLogger.i(TAG, "artefact over the messaging ceiling — using Azure")
            azure(file, cb)
            return
        }
        cb.onFailed(
            if (tgFits) "no transport configured"
            else "artefact too large for the messaging channel and no container configured")
    }

    private fun azure(file: File, cb: Listener) {
        AzureBlobUploader.upload(file, "dilink/" + file.name, { /* progress ignored here */ },
            object : AzureBlobUploader.Callback {
                override fun onUploaded(url: String) = cb.onSent(Sink.AZURE, url)
                override fun onFailed(message: String) = cb.onFailed(message)
            })
    }

    /** Telegram's sendDocument ceiling, minus the margin ApkExtractionPolicy already assumes. */
    const val TELEGRAM_MAX_BYTES = 45L * 1024 * 1024
}
