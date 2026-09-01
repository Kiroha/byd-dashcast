const assert = require('node:assert/strict');
const test = require('node:test');

const { handleReport } = require('./functions/report');

process.env.TELEGRAM_BOT_TOKEN = '123456789:abcdefghijklmnopqrstuvwxyzABCDE';
process.env.TELEGRAM_CHAT_ID = '-100123456789';

function headers(bodyLength) {
    return new Headers({
        'content-length': String(bodyLength),
        'x-dashcast-topic': 'bug',
        'x-dashcast-filename': 'report.zip',
    });
}

function context() {
    const logs = [];
    return {
        logs,
        log: (message) => logs.push(String(message)),
        error: (message) => logs.push(String(message)),
    };
}

test('handler aborts a stalled Telegram upload at its deadline', async () => {
    let incomingCancelled = false;
    let outboundSignal;
    const body = new ReadableStream({
        start(controller) {
            controller.enqueue(new Uint8Array(64));
        },
        cancel() {
            incomingCancelled = true;
        },
    });
    const request = { headers: headers(100), body };
    const fakeFetch = async (_url, options) => {
        outboundSignal = options.signal;
        options.body.resume();
        return new Promise((resolve, reject) => {
            options.signal.addEventListener('abort', () => reject(options.signal.reason), {
                once: true,
            });
        });
    };

    const result = await handleReport(request, context(), {
        fetch: fakeFetch,
        telegramTimeoutMs: 20,
    });
    await new Promise((resolve) => setImmediate(resolve));

    assert.equal(result.status, 504);
    assert.equal(result.jsonBody.error, 'telegram timeout');
    assert.equal(outboundSignal.aborted, true);
    assert.equal(incomingCancelled, true);
});

test('handler drains a successful Telegram response and returns 200', async () => {
    let responsePulled = false;
    const responseBody = new ReadableStream({
        pull(controller) {
            responsePulled = true;
            controller.enqueue(new Uint8Array([1, 2, 3]));
            controller.close();
        },
    });
    const request = {
        headers: headers(64),
        body: new ReadableStream({
            start(controller) {
                controller.enqueue(new Uint8Array(64));
                controller.close();
            },
        }),
    };
    const fakeFetch = async (_url, options) => {
        for await (const _chunk of options.body) { /* consume request */ }
        return { ok: true, status: 200, body: responseBody };
    };

    const result = await handleReport(request, context(), {
        fetch: fakeFetch,
        telegramTimeoutMs: 1_000,
    });

    assert.equal(result.status, 200);
    assert.equal(result.jsonBody.ok, true);
    assert.equal(responsePulled, true);
});

test('deadline while reading a Telegram error body remains a timeout', async () => {
    const request = {
        headers: headers(64),
        body: new ReadableStream({
            start(controller) {
                controller.enqueue(new Uint8Array(64));
                controller.close();
            },
        }),
    };
    const fakeFetch = async (_url, options) => {
        for await (const _chunk of options.body) { /* consume request */ }
        return {
            ok: false,
            status: 429,
            json: () => new Promise((resolve, reject) => {
                options.signal.addEventListener('abort', () => reject(options.signal.reason), {
                    once: true,
                });
            }),
        };
    };

    const result = await handleReport(request, context(), {
        fetch: fakeFetch,
        telegramTimeoutMs: 20,
    });

    assert.equal(result.status, 504);
    assert.equal(result.jsonBody.error, 'telegram timeout');
});

test('handler keeps ordinary Telegram transport failures distinct from timeout', async () => {
    const request = {
        headers: headers(64),
        body: new ReadableStream({
            start(controller) {
                controller.enqueue(new Uint8Array(64));
                controller.close();
            },
        }),
    };

    const result = await handleReport(request, context(), {
        fetch: async () => { throw new Error('connection refused'); },
        telegramTimeoutMs: 1_000,
    });

    assert.equal(result.status, 502);
    assert.equal(result.jsonBody.error, 'telegram unreachable');
});