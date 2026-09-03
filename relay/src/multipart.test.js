const test = require('node:test');
const assert = require('node:assert/strict');
const http = require('node:http');
const { Readable } = require('node:stream');
const { readFileSync } = require('node:fs');
const { join } = require('node:path');
const {
    createMultipartPlan,
    drainStream,
    parseContentLength,
    streamMultipart,
} = require('./multipart');

function body(chunks) {
    return new ReadableStream({
        start(controller) {
            for (const chunk of chunks) controller.enqueue(Buffer.from(chunk));
            controller.close();
        },
    });
}

async function collect(iterable) {
    const chunks = [];
    for await (const chunk of iterable) chunks.push(Buffer.from(chunk));
    return Buffer.concat(chunks);
}

test('content length parser accepts only safe decimal integers', () => {
    assert.equal(parseContentLength('64'), 64);
    assert.equal(parseContentLength(''), null);
    assert.equal(parseContentLength('-1'), null);
    assert.equal(parseContentLength('1.5'), null);
    assert.equal(parseContentLength('9007199254740993'), null);
});

test('successful upstream response is completely drained', async () => {
    let pulls = 0;
    const responseBody = new ReadableStream({
        pull(controller) {
            pulls++;
            if (pulls <= 3) controller.enqueue(Buffer.from('response'));
            else controller.close();
        },
    });

    await drainStream(responseBody);

    assert.equal(pulls, 4);
    assert.equal(responseBody.locked, false);
});

test('function enables Azure request streaming before route registration', () => {
    const source = readFileSync(join(__dirname, 'functions', 'report.js'), 'utf8');
    assert.ok(source.indexOf('app.setup({ enableHttpStream: true })') >= 0);
    assert.ok(source.indexOf('app.setup({ enableHttpStream: true })') < source.indexOf("app.http('report'"));
    assert.doesNotMatch(source, /request\.arrayBuffer\(|new Blob\(/);
});

test('multipart stream preserves bytes and exact declared length', async () => {
    const payload = Buffer.from('report-body');
    const plan = createMultipartPlan({
        chatId: '-1001',
        thread: '7',
        caption: 'caption',
        filename: 'report.zip',
        bodyLength: payload.length,
    });
    const encoded = await collect(streamMultipart(body(['report-', 'body']), plan, payload.length));

    assert.equal(encoded.length, plan.contentLength);
    assert.ok(encoded.includes(payload));
    assert.match(encoded.toString('utf8'), /name="chat_id"\r\n\r\n-1001/);
    assert.match(encoded.toString('utf8'), /filename="report\.zip"/);
});

test('multipart stream rejects short and oversized request bodies', async () => {
    const plan = createMultipartPlan({
        chatId: 'chat', thread: '', caption: '', filename: 'report.txt', bodyLength: 4,
    });

    await assert.rejects(
        collect(streamMultipart(body(['abc']), plan, 4)),
        /does not match Content-Length/);
    await assert.rejects(
        collect(streamMultipart(body(['abcde']), plan, 4)),
        /exceeds Content-Length/);
});

test('aborting outbound multipart cancels an unfinished incoming request', async () => {
    let cancelled = false;
    const incoming = new ReadableStream({
        start(controller) {
            controller.enqueue(Buffer.from('partial'));
        },
        cancel() {
            cancelled = true;
        },
    });
    const plan = createMultipartPlan({
        chatId: 'chat', thread: '', caption: '', filename: 'report.txt', bodyLength: 100,
    });
    const iterator = streamMultipart(incoming, plan, 100);

    await iterator.next(); // multipart prefix
    await iterator.next(); // first request chunk
    await iterator.return(); // model fetch aborting before the Android upload finishes

    assert.equal(cancelled, true);
});

test('node fetch sends the multipart stream at its exact fixed length', async (t) => {
    const payload = Buffer.alloc(256 * 1024, 0x5a);
    const plan = createMultipartPlan({
        chatId: 'chat', thread: '', caption: '', filename: 'report.zip',
        bodyLength: payload.length,
    });
    let received;
    const server = http.createServer((request, response) => {
        const chunks = [];
        request.on('data', chunk => chunks.push(chunk));
        request.on('end', () => {
            received = Buffer.concat(chunks);
            response.writeHead(200).end('ok');
        });
    });
    await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
    t.after(() => server.close());
    const address = server.address();

    const response = await fetch(`http://127.0.0.1:${address.port}`, {
        method: 'POST',
        headers: {
            'Content-Type': `multipart/form-data; boundary=${plan.boundary}`,
            'Content-Length': String(plan.contentLength),
        },
        body: Readable.from(streamMultipart(body([payload]), plan, payload.length), {
            objectMode: false,
        }),
        duplex: 'half',
    });

    assert.equal(response.status, 200);
    assert.equal(received.length, plan.contentLength);
    assert.ok(received.includes(payload));
});