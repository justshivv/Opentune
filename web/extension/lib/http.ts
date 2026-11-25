import type { HttpResponse } from '@opentune/shared/bridge';

/**
 * Fetch helpers for the requests the extension makes for the page: a
 * deadline on every one, a cap on how much of an answer is read, and errors
 * that say which host failed and how. Pure apart from `fetch` itself, so it
 * runs under `node --test`.
 */

export const MB = 1024 * 1024;

/** A request that failed on the way (no answer, too slow, too big). Reported to the page as `network`. */
export class NetworkError extends Error {
  readonly code = 'network';
}

export interface BoundedInit extends RequestInit {
  /** Give up after this long (default 20 s). */
  timeoutMs?: number;
}

function hostOf(url: string | URL): string {
  try {
    return new URL(url).host;
  } catch {
    return String(url);
  }
}

/**
 * `fetch` with a deadline. A timeout, an abort or a network failure becomes a
 * `NetworkError` naming the host; `redirect: 'error'` refusing a redirect does too.
 */
export async function fetchWithDeadline(url: string | URL, init: BoundedInit = {}): Promise<Response> {
  const { timeoutMs = 20_000, ...rest } = init;
  const deadline = AbortSignal.timeout(timeoutMs);
  const signal = rest.signal ? AbortSignal.any([rest.signal, deadline]) : deadline;
  try {
    return await fetch(url, { ...rest, signal });
  } catch (error) {
    const host = hostOf(url);
    if (deadline.aborted) throw new NetworkError(`${host} didn't answer within ${Math.max(1, Math.round(timeoutMs / 1000))} s`);
    if (error instanceof DOMException && error.name === 'AbortError') throw new NetworkError(`The request to ${host} was cancelled`);
    throw new NetworkError(
      rest.redirect === 'error' ? `${host} couldn't be reached, or tried to redirect elsewhere` : `${host} couldn't be reached`,
    );
  }
}

/** The body, read up to [maxBytes]; more than that is refused rather than truncated. */
export async function readBounded(response: Response, maxBytes: number): Promise<Uint8Array> {
  const declared = Number(response.headers.get('content-length'));
  if (Number.isFinite(declared) && declared > maxBytes) {
    await response.body?.cancel().catch(() => undefined);
    throw new NetworkError(`${hostOf(response.url)} answered with more than ${Math.round(maxBytes / MB)} MB`);
  }
  const reader = response.body?.getReader();
  if (!reader) return new Uint8Array(0);
  const chunks: Uint8Array[] = [];
  let size = 0;
  try {
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      size += value.byteLength;
      if (size > maxBytes) throw new NetworkError(`${hostOf(response.url)} answered with more than ${Math.round(maxBytes / MB)} MB`);
      chunks.push(value);
    }
  } finally {
    await reader.cancel().catch(() => undefined);
  }
  const bytes = new Uint8Array(size);
  let at = 0;
  for (const chunk of chunks) {
    bytes.set(chunk, at);
    at += chunk.byteLength;
  }
  return bytes;
}

export function toBase64(bytes: Uint8Array): string {
  let binary = '';
  for (let i = 0; i < bytes.length; i += 0x8000) binary += String.fromCharCode(...bytes.subarray(i, i + 0x8000));
  return btoa(binary);
}

export function fromBase64(base64: string): Uint8Array {
  return Uint8Array.from(atob(base64), (c) => c.charCodeAt(0));
}

/** Content types sent to the page as text; everything else goes as base64. */
const TEXTUAL = /json|text|javascript|xml|html/;

/**
 * The response as the bridge carries it, read up to [maxBytes]. [url] is
 * reported when the response has none (an opaque redirect).
 */
export async function serialise(response: Response, maxBytes: number, url = response.url): Promise<HttpResponse> {
  const headers: Record<string, string> = {};
  response.headers.forEach((value, key) => (headers[key] = value));
  const type = response.headers.get('content-type') ?? '';
  const textual = !type || TEXTUAL.test(type);
  const bytes = await readBounded(response, maxBytes);
  return {
    status: response.status,
    headers,
    url: response.url || url,
    bodyEncoding: textual ? 'text' : 'base64',
    body: textual ? new TextDecoder().decode(bytes) : toBase64(bytes),
  };
}
