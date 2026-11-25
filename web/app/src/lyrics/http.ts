import { BridgeCallError } from '@opentune/shared/bridge';
import { getBridge } from '@/extension/bridge';

/**
 * The providers' one way out (LyricsHttp.kt): a GET that answers with the body
 * or null, never an exception. They're raced against each other, so the
 * deadline is short: a lyric that arrives after the second chorus is no use.
 *
 * - `direct`: from the page, for the hosts that allow cross-site requests;
 *   if the browser blocks it anyway (BetterLyrics allows some origins and
 *   not others), it's retried through the extension.
 * - `extension`: always through the extension's `lyrics.fetch`, for hosts
 *   that send no CORS headers or need headers a page can't set.
 */

/** Hosts the page can't reach itself: KuGou, Musixmatch, Genius and Apple's catalogue (for PaxSenix). */
export const LYRIC_EXTENSION_ORIGINS = [
  'https://mobileservice.kugou.com',
  'https://lyrics.kugou.com',
  'https://apic.musixmatch.com',
  'https://www.musixmatch.com',
  'https://genius.com',
  'https://music.apple.com',
  'https://amp-api.music.apple.com',
  'https://api.paxsenix.org',
  'https://lyrics-api.boidu.dev',
  'https://unison.boidu.dev',
];

/**
 * Hosts that never let this site read their answers (no CORS for its
 * origin): asked through the extension only, and skipped quietly without
 * it, instead of a blocked request and a console error on every song.
 */
export const EXTENSION_ONLY_ORIGINS = new Set(['https://lyrics-api.boidu.dev', 'https://lyrics.paxsenix.org', 'https://api.paxsenix.org']);
/** Hosts whose direct request the browser blocked this session: the extension from then on. */
const corsBlocked = new Set<string>();
/** Hosts that answered 429, until when: not asked again before then. */
const coolingUntil = new Map<string, number>();
/** How long a 429 without Retry-After keeps a host out. */
const COOL_DOWN_MS = 5 * 60_000;

function coolDown(origin: string, retryAfter: string | null | undefined): void {
  const seconds = Number(retryAfter);
  const ms = Number.isFinite(seconds) && seconds > 0 ? Math.min(seconds * 1000, 30 * 60_000) : COOL_DOWN_MS;
  coolingUntil.set(origin, Date.now() + ms);
}

/** For tests: forgets what this session learned about the hosts. */
export function resetLyricHosts(): void {
  corsBlocked.clear();
  coolingUntil.clear();
}

const DIRECT_TIMEOUT_MS = 6000;
const EXTENSION_TIMEOUT_MS = 15_000;

/** Lyric hosts the extension was refused for: the panel offers to allow them. */
export const ungrantedOrigins = new Set<string>();
const listeners = new Set<() => void>();
export function onUngrantedChange(listener: () => void): () => void {
  listeners.add(listener);
  return () => listeners.delete(listener);
}

const grantListeners = new Set<() => void>();
/** Called once a refused host has been allowed, so the lookups it failed can run again. */
export function onGranted(listener: () => void): () => void {
  grantListeners.add(listener);
  return () => grantListeners.delete(listener);
}

/**
 * Asks the extension which refused hosts it may reach now. The grant happens
 * on the extension's own page in another tab, so this runs when OpenTune's
 * tab comes back.
 */
export async function recheckGrants(): Promise<void> {
  const bridge = getBridge();
  if (!bridge || !ungrantedOrigins.size) return;
  const { granted } = await bridge.call('lyrics.permissions', { origins: [...ungrantedOrigins] }).catch(() => ({ granted: [] }));
  if (!granted.length) return;
  granted.forEach((o) => ungrantedOrigins.delete(o));
  listeners.forEach((l) => l());
  grantListeners.forEach((l) => l());
}

if (typeof window !== 'undefined') {
  window.addEventListener('focus', () => void recheckGrants());
  document.addEventListener('visibilitychange', () => document.visibilityState === 'visible' && void recheckGrants());
}

export interface GetOptions {
  route?: 'direct' | 'extension';
  headers?: Record<string, string>;
  /** Longer deadline for authenticated routes that look things up upstream (PaxSenix). */
  patient?: boolean;
}

async function viaExtension(url: string, headers: Record<string, string> | undefined): Promise<string | null> {
  const bridge = getBridge();
  if (!bridge) return null;
  try {
    const response = await bridge.call(
      'lyrics.fetch',
      { url, ...(headers && { headers }) },
      { timeoutMs: EXTENSION_TIMEOUT_MS },
    );
    if (response.status === 429) coolDown(new URL(url).origin, response.headers['retry-after']);
    if (response.status < 200 || response.status >= 300) return null;
    return response.bodyEncoding === 'base64' ? new TextDecoder().decode(Uint8Array.from(atob(response.body), (c) => c.charCodeAt(0))) : response.body;
  } catch (error) {
    if (error instanceof BridgeCallError && error.code === 'permission') {
      const origin = new URL(url).origin;
      if (!ungrantedOrigins.has(origin)) {
        ungrantedOrigins.add(origin);
        listeners.forEach((l) => l());
      }
    }
    return null;
  }
}

export async function lyricsGet(url: string, { route = 'direct', headers, patient }: GetOptions = {}): Promise<string | null> {
  const origin = new URL(url).origin;
  if ((coolingUntil.get(origin) ?? 0) > Date.now()) return null;
  if (route === 'extension' || EXTENSION_ONLY_ORIGINS.has(origin) || corsBlocked.has(origin)) return viaExtension(url, headers);
  try {
    const response = await fetch(url, {
      headers: { Accept: 'application/json, text/plain, */*', ...headers },
      signal: AbortSignal.timeout(patient ? EXTENSION_TIMEOUT_MS : DIRECT_TIMEOUT_MS),
      referrerPolicy: 'no-referrer',
    });
    if (response.status === 429) coolDown(origin, response.headers.get('retry-after'));
    return response.ok ? await response.text() : null;
  } catch (error) {
    // A CORS refusal looks like a network error from here; the extension isn't bound by it.
    if (error instanceof TypeError) {
      corsBlocked.add(origin);
      return viaExtension(url, headers);
    }
    return null;
  }
}

/** A URL with query parameters, skipping empty ones. */
export function urlWith(base: string, params: Record<string, string | number | null | undefined>): string {
  const url = new URL(base);
  for (const [key, value] of Object.entries(params)) {
    if (value !== null && value !== undefined && value !== '') url.searchParams.set(key, String(value));
  }
  return url.toString();
}
