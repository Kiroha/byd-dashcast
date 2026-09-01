const { randomBytes } = require('node:crypto');

function parseContentLength(value) {
    if (!/^[0-9]+$/.test(value || '')) return null;
    const length = Number(value);
    return Number.isSafeInteger(length) ? length : null;
}

function field(boundary, name, value) {
    return Buffer.from(
        `--${boundary}\r\n` +
        `Content-Disposition: form-data; name="${name}"\r\n\r\n` +
        `${value}\r\n`,
        'utf8');
}

function createMultipartPlan({ chatId, thread, caption, filename, bodyLength }) {
    const boundary = '----dashcastrelay' + randomBytes(12).toString('hex');
    const chunks = [field(boundary, 'chat_id', chatId)];
    if (thread) chunks.push(field(boundary, 'message_thread_id', thread));
    if (caption) chunks.push(field(boundary, 'caption', caption));
    chunks.push(Buffer.from(
        `--${boundary}\r\n` +
        `Content-Disposition: form-data; name="document"; filename="${filename}"\r\n` +
        'Content-Type: application/octet-stream\r\n\r\n',
        'utf8'));
    const prefix = Buffer.concat(chunks);
    const suffix = Buffer.from(`\r\n--${boundary}--\r\n`, 'utf8');
    return {
        boundary,
        prefix,
        suffix,
        contentLength: prefix.length + bodyLength + suffix.length,
    };
}

async function* streamMultipart(body, plan, expectedLength) {
    if (!body) throw new Error('request body missing');
    yield plan.prefix;
    const reader = body.getReader();
    let received = 0;
    let complete = false;
    try {
        while (true) {
            const { done, value } = await reader.read();
            if (done) break;
            const chunk = Buffer.from(value);
            received += chunk.length;
            if (received > expectedLength) throw new Error('request body exceeds Content-Length');
            yield chunk;
        }
        if (received !== expectedLength) throw new Error('request body does not match Content-Length');
        complete = true;
    } finally {
        if (!complete) {
            try { await reader.cancel('multipart forwarding stopped'); } catch { /* already closed */ }
        }
        reader.releaseLock();
    }
    yield plan.suffix;
}

module.exports = { createMultipartPlan, parseContentLength, streamMultipart };