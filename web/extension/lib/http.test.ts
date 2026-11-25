import assert from 'node:assert/strict';
import { describe, it } from 'node:test';
import { fetchWithDeadline, fromBase64, NetworkError, readBounded, serialise, toBase64 } from './http.ts';

function streamOf(chunks: Uint8Array[]): ReadableStream<Uint8Array> {
  return new ReadableStream({
    start(controller) {
      for (const chunk of chunks) controller.enqueue(chunk);
      controller.close();
    },
  });
}

describe('readBounded', () => {
  it('reads a body that fits', async () => {
    const response = new Response(streamOf([new Uint8Array([1, 2]), new Uint8Array([3])]));
    assert.deepEqual([...(await readBounded(response, 3))], [1, 2, 3]);
  });

  it('refuses a body that grows past the cap', async () => {
    const response = new Response(streamOf([new Uint8Array(4), new Uint8Array(4)]));
    await assert.rejects(readBounded(response, 6), NetworkError);
  });

  it('refuses a declared length past the cap before reading', async () => {
    const response = new Response('short', { headers: { 'content-length': '99999999' } });
    await assert.rejects(readBounded(response, 1024), /more than/);
  });

  it('reads an empty body as no bytes', async () => {
    assert.equal((await readBounded(new Response(null, { status: 204 }), 10)).length, 0);
  });
});

describe('serialise', () => {
  it('sends text types as text', async () => {
    const lyric = '[00:01.00] An invented line about lanterns';
    const result = await serialise(new Response(lyric, { headers: { 'content-type': 'text/plain; charset=utf-8' } }), 1024, 'https://lrclib.net/x');
    assert.equal(result.bodyEncoding, 'text');
    assert.equal(result.body, lyric);
    assert.equal(result.status, 200);
    assert.equal(result.url, 'https://lrclib.net/x');
    assert.equal(result.headers['content-type'], 'text/plain; charset=utf-8');
  });

  it('sends binary types as base64', async () => {
    const bytes = new Uint8Array([0, 255, 16, 32]);
    const result = await serialise(new Response(bytes, { headers: { 'content-type': 'application/x-protobuf' } }), 1024);
    assert.equal(result.bodyEncoding, 'base64');
    assert.deepEqual([...fromBase64(result.body)], [...bytes]);
  });

  it('applies the cap', async () => {
    await assert.rejects(serialise(new Response('x'.repeat(100), { headers: { 'content-type': 'application/json' } }), 10), NetworkError);
  });
});

describe('base64', () => {
  it('round-trips large buffers', () => {
    const bytes = Uint8Array.from({ length: 100_000 }, (_, i) => i % 256);
    assert.deepEqual(fromBase64(toBase64(bytes)), bytes);
  });
});

describe('fetchWithDeadline', () => {
  it('names the host when the deadline passes', async () => {
    const original = globalThis.fetch;
    globalThis.fetch = ((_: unknown, init?: RequestInit) =>
      new Promise((_, reject) => init?.signal?.addEventListener('abort', () => reject(init.signal?.reason)))) as typeof fetch;
    // AbortSignal.timeout's timer doesn't hold Node's event loop open; this does, for the test.
    const hold = setTimeout(() => undefined, 1000);
    try {
      await assert.rejects(fetchWithDeadline('https://slow.example.com/a', { timeoutMs: 20 }), {
        name: 'Error',
        message: "slow.example.com didn't answer within 1 s",
      });
    } finally {
      clearTimeout(hold);
      globalThis.fetch = original;
    }
  });

  it('turns a network failure into a NetworkError', async () => {
    const original = globalThis.fetch;
    globalThis.fetch = (() => Promise.reject(new TypeError('Failed to fetch'))) as typeof fetch;
    try {
      await assert.rejects(fetchWithDeadline('https://down.example.com/', { redirect: 'error' }), (error: unknown) => {
        return error instanceof NetworkError && error.code === 'network' && /down\.example\.com/.test(error.message);
      });
    } finally {
      globalThis.fetch = original;
    }
  });
});
