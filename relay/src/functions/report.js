const { app } = require('@azure/functions');

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
 *
 * ## Application settings
 *
 *   TELEGRAM_BOT_TOKEN   required
 *   TELEGRAM_CHAT_ID     required, e.g. -1004472712700
 *   TELEGRAM_THREAD_BUG  optional, the bug-report topic id
 *   TELEGRAM_THREAD_HUD  optional, the HUD-report topic id
 */

// Telegram's own sendDocument ceiling is 50 MB; stop short of it so the rejection is ours and
// carries a usable message rather than arriving as an opaque Telegram error.
const MAX_BYTES = 45 * 1024 * 1024;

// A report is never empty. Anything this small is a probe, not a report.
const MIN_BYTES = 64;

const TOPICS = { bug: 'TELEGRAM_THREAD_BUG', hud: 'TELEGRAM_THREAD_HUD' };

// Deliberately strict: the name ends up in a Telegram message and in whatever the maintainer
// saves it as, so no path separators, no surprises.
const FILENAME = /^[A-Za-z0-9][A-Za-z0-9._-]{0,119}$/;

function bad(status, why) {
    return { status, jsonBody: { ok: false, error: why } };
}

app.http('report', {
    methods: ['POST'],
    authLevel: 'anonymous',
    handler: async (request, context) => {
        const topic = (request.headers.get('x-dashcast-topic') || 'bug').toLowerCase();
        if (!Object.prototype.hasOwnProperty.call(TOPICS, topic)) return bad(400, 'unknown topic');

        const filename = request.headers.get('x-dashcast-filename') || '';
        if (!FILENAME.test(filename)) return bad(400, 'bad filename');

        let caption = '';
        try {
            const raw = request.headers.get('x-dashcast-caption');
            if (raw) caption = Buffer.from(raw, 'base64').toString('utf8');
        } catch {
            return bad(400, 'bad caption encoding');
        }
        // Telegram truncates past 1024 characters and answers with an error rather than trimming.
        if (caption.length > 1024) caption = caption.slice(0, 1021) + '...';

        const body = Buffer.from(await request.arrayBuffer());
        if (body.length < MIN_BYTES) return bad(400, 'body too small to be a report');
        if (body.length > MAX_BYTES) return bad(413, 'body too large');

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

        const form = new FormData();
        form.append('chat_id', chatId);
        const thread = process.env[TOPICS[topic]];
        if (thread) form.append('message_thread_id', thread);
        if (caption) form.append('caption', caption);
        form.append('document', new Blob([body]), filename);

        let res;
        try {
            res = await fetch(`https://api.telegram.org/bot${token}/sendDocument`, {
                method: 'POST',
                body: form,
            });
        } catch (e) {
            context.error('telegram unreachable: ' + e.message);
            return bad(502, 'telegram unreachable');
        }

        if (!res.ok) {
            // Log the status and Telegram's description, never the URL — it carries the token.
            let detail = '';
            try {
                const j = await res.json();
                detail = j && j.description ? String(j.description) : '';
            } catch { /* body was not JSON */ }
            context.error(`telegram refused: HTTP ${res.status} ${detail}`);
            return bad(502, `telegram refused: HTTP ${res.status}`);
        }

        context.log(`relayed ${filename} (${body.length} bytes) to ${topic}`);
        return { status: 200, jsonBody: { ok: true } };
    },
});
