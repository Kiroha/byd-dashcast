const { app } = require('@azure/functions');
const { Readable } = require('node:stream');
const {
    createMultipartPlan,
    drainStream,
    parseContentLength,
    streamMultipart,
} = require('../multipart');

// Required for request.body to remain a live stream instead of a host-buffered payload.
// Supported by @azure/functions >=4.3 and Functions runtime >=4.28.
app.setup({ enableHttpStream: true });

/**
 * DashCast report relay.
 *
 * The car uploads here; this function forwards to Telegram. The point is what is NOT in the APK:
 * the bot token lives only in this function's application settings, so a decompiled APK yields an
 * ordinary HTTPS URL and nothing else. Rotating the token is an app-setting change and a restart,
 * with no release and no car to reach.
 *
 * ## Why the route is anonymous
 *
 * A function key would be a secret in the APK again — a weaker one than a bot token, but the same
 * shape of problem, and it would put provisioning back in front of the user for a credential whose
 * leak only enables spam. So the URL is public by design. What matters is that it grants nothing:
 * no read of anyone's report, no impersonation of the bot beyond posting into the group it already
 * posts into.
 *
 * What that leaves is abuse, and the answers to abuse are operational rather than cryptographic:
 *
 *  - the validation below rejects anything that does not look like a report;
 *  - set a daily quota on the Function App (`dailyMemoryTimeQuota`) so the worst case is the relay
 *    going quiet for the rest of the day rather than a bill;
 *  - if the URL is ever abused, redeploy under a different route name and ship it. The bot token
 *    is untouched by all of this, which is the whole point.
 *
 * ## Contract
 *
 *   POST <url>
 *   Content-Type: application/octet-stream
 *   X-DashCast-Topic:    bug | hud
 *   X-DashCast-Filename: the report file name
 *   X-DashCast-Caption:  base64(utf8(caption))   — base64 because a caption has newlines in it
 *   body: the report bytes
 *
 *   200 {"ok":true}  ·  400 bad request  ·  413 too large  ·  502 Telegram refused it
 *   504 Telegram forwarding timed out
 *
 * ## Application settings
 *
 *   TELEGRAM_BOT_TOKEN   required
 *   TELEGRAM_CHAT_ID     required, e.g. -100999999999
 *   TELEGRAM_THREAD_BUG  optional, the bug-report topic id
 *   TELEGRAM_THREAD_HUD  optional, the HUD-report topic id
 */

// Telegram's own sendDocument ceiling is 50 MB; stop short of it so the rejection is ours and
// carries a usable message rather than arriving as an opaque Telegram error.
const MAX_BYTES = 45 * 1024 * 1024;

// A report is never empty. Anything this small is a probe, not a report.
const MIN_BYTES = 64;

// Bounds every phase of the outbound exchange, including streaming the car upload and draining
// Telegram's response. Below Azure Consumption's five-minute function ceiling, but long enough
// for the largest accepted report on a slow mobile connection.
const TELEGRAM_TIMEOUT_MS = 180_000;

const TOPICS = { bug: 'TELEGRAM_THREAD_BUG', hud: 'TELEGRAM_THREAD_HUD' };

// Deliberately strict: the name ends up in a Telegram message and in whatever the maintainer
// saves it as, so no path separators, no surprises.
const FILENAME = /^[A-Za-z0-9][A-Za-z0-9._-]{0,119}$/;

function bad(status, why) {
    return { status, jsonBody: { ok: false, error: why } };
}

/**
 * Strips a bot token out of anything on its way to a log.
 *
 * One path could actually leak. A `fetch` rejection sometimes carries the request URL in its
 * message, and the request URL here is `https://api.telegram.org/bot<TOKEN>/sendDocument`. Every
 * other line is built from a status code or Telegram's own description and holds nothing — but
 * "holds nothing today" is a property that decays the moment someone adds a line. Scrubbing at the
 * point of logging makes it structural instead of a thing to keep remembering.
 */
function safe(text) {
    return String(text == null ? '' : text)
        .replace(/bot\d{6,12}:[A-Za-z0-9_-]{20,}/g, 'bot<redacted>');
}

function createAbortDeadline(parentSignal, timeoutMs) {
    const controller = new AbortController();
    let timedOut = false;
    const onParentAbort = () => controller.abort(parentSignal.reason);
    if (parentSignal) {
        if (parentSignal.aborted) onParentAbort();
        else parentSignal.addEventListener('abort', onParentAbort, { once: true });
    }
    const timer = setTimeout(() => {
        timedOut = true;
        const error = new Error('Telegram forwarding deadline exceeded');
        error.name = 'TimeoutError';
        controller.abort(error);
    }, timeoutMs);
    return {
        signal: controller.signal,
        timedOut: () => timedOut,
        dispose: () => {
            clearTimeout(timer);
            if (parentSignal) parentSignal.removeEventListener('abort', onParentAbort);
        },
    };
}

async function handleReport(request, context, dependencies = {}) {
        const topic = (request.headers.get('x-dashcast-topic') || 'bug').toLowerCase();
        if (!Object.prototype.hasOwnProperty.call(TOPICS, topic)) return bad(400, 'unknown topic');

        const filename = request.headers.get('x-dashcast-filename') || '';
        if (!FILENAME.test(filename)) return bad(400, 'bad filename');

        const contentType = (request.headers.get('content-type') || '')
            .split(';', 1)[0].trim().toLowerCase();
        if (contentType !== 'application/octet-stream') {
            return bad(400, 'application/octet-stream required');
        }

        let caption = '';
        try {
            const raw = request.headers.get('x-dashcast-caption');
            if (raw) caption = Buffer.from(raw, 'base64').toString('utf8');
        } catch {
            return bad(400, 'bad caption encoding');
        }
        // Telegram truncates past 1024 characters and answers with an error rather than trimming.
        if (caption.length > 1024) caption = caption.slice(0, 1021) + '...';

        const bodyLength = parseContentLength(request.headers.get('content-length'));
        if (bodyLength == null) return bad(400, 'valid Content-Length required');
        if (bodyLength < MIN_BYTES) return bad(400, 'body too small to be a report');
        if (bodyLength > MAX_BYTES) return bad(413, 'body too large');
        if (!request.body) return bad(400, 'request body missing');

        // Configuration is checked LAST, once the request is known to be a report.
        //
        // Two reasons, and the order is not cosmetic. Anything malformed is refused without the
        // function considering its own state, so junk costs one comparison rather than a decision
        // about credentials. And 503 stays reserved for a well-formed report that could not be
        // forwarded — a misconfigured relay must not look like a rejected report, or the car falls
        // back to a local save and the tester is told their report was refused when in fact nobody
        // was listening.
        const token = process.env.TELEGRAM_BOT_TOKEN;
        const chatId = process.env.TELEGRAM_CHAT_ID;
        if (!token || !chatId) {
            context.error('relay not configured: TELEGRAM_BOT_TOKEN or TELEGRAM_CHAT_ID missing');
            return bad(503, 'relay not configured');
        }

        const thread = process.env[TOPICS[topic]];
        const multipart = createMultipartPlan({
            chatId, thread, caption, filename, bodyLength,
        });

        const deadline = createAbortDeadline(
            request.signal,
            dependencies.telegramTimeoutMs || TELEGRAM_TIMEOUT_MS,
        );
        const outboundBody = Readable.from(
            streamMultipart(request.body, multipart, bodyLength, deadline.signal),
            { objectMode: false },
        );
        try {
            const fetchImpl = dependencies.fetch || globalThis.fetch;
            const res = await fetchImpl(`https://api.telegram.org/bot${token}/sendDocument`, {
                method: 'POST',
                headers: {
                    'Content-Type': `multipart/form-data; boundary=${multipart.boundary}`,
                    'Content-Length': String(multipart.contentLength),
                },
                body: outboundBody,
                duplex: 'half',
                signal: deadline.signal,
            });

            if (!res.ok) {
                // Status and Telegram's description only — and scrubbed on the way out regardless.
                let detail = '';
                try {
                    const j = await res.json();
                    detail = j && j.description ? String(j.description) : '';
                } catch { /* body was not JSON */ }
                if (deadline.signal.aborted) {
                    throw deadline.signal.reason || new Error('Telegram response aborted');
                }
                context.error(safe(`telegram refused: HTTP ${res.status} ${detail}`));
                return bad(502, `telegram refused: HTTP ${res.status}`);
            }

            await drainStream(res.body);
            context.log(safe(`relayed ${filename} (${bodyLength} bytes) to ${topic}`));
            return { status: 200, jsonBody: { ok: true } };
        } catch (e) {
            if (deadline.timedOut()) {
                context.error(safe(`telegram timeout after ${dependencies.telegramTimeoutMs || TELEGRAM_TIMEOUT_MS}ms`));
                return bad(504, 'telegram timeout');
            }
            if (request.signal && request.signal.aborted) {
                context.log('incoming report disconnected before forwarding completed');
                return bad(499, 'client disconnected');
            }
            context.error(safe('telegram unreachable: ' + e.message));
            return bad(502, 'telegram unreachable');
        } finally {
            deadline.dispose();
            if (!outboundBody.readableEnded && !outboundBody.destroyed) outboundBody.destroy();
        }
}

app.http('report', {
    methods: ['POST'],
    authLevel: 'anonymous',
    handler: handleReport,
});

module.exports = { createAbortDeadline, handleReport };
