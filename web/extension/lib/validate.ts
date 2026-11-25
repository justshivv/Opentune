/**
 * Shape checks for what a page sends, before any of it is used. The envelope
 * itself is checked by `isBridgeRequest`; this checks each method's params:
 * types, sizes and the few enumerations, so a malformed or oversized request
 * is refused with a clear message instead of failing somewhere deeper (or
 * being mistaken for a network error).
 *
 * Pure: no browser APIs, so it runs under `node --test`.
 */

/** A request the extension won't run as sent. Reported to the page with the `protocol` code. */
export class BadRequest extends Error {
  readonly code = 'protocol';
}

export const LIMITS = {
  url: 8192,
  headers: 32,
  headerName: 128,
  headerValue: 8192,
  /** Request bodies (InnerTube JSON, BotGuard's answer): well under this. */
  body: 1024 * 1024,
  origins: 64,
  id: 256,
  query: 500,
  text: 2048,
} as const;

const METHODS = ['GET', 'POST', 'HEAD'] as const;
type Method = (typeof METHODS)[number];
/** RFC 9110 token characters. */
const HEADER_NAME = /^[!#$%&'*+.^_`|~0-9A-Za-z-]+$/;

function fail(message: string): never {
  throw new BadRequest(`Malformed request: ${message}`);
}

function record(value: unknown, what: string): Record<string, unknown> {
  if (value === undefined || value === null) return {};
  if (typeof value !== 'object' || Array.isArray(value)) fail(`${what} must be an object`);
  return value as Record<string, unknown>;
}

function string(value: unknown, what: string, max: number): string {
  if (typeof value !== 'string') fail(`${what} must be a string`);
  if (value.length > max) fail(`${what} is longer than ${max} characters`);
  return value;
}

function optionalBoolean(value: unknown, what: string): void {
  if (value !== undefined && typeof value !== 'boolean') fail(`${what} must be true or false`);
}

function integer(value: unknown, what: string, min: number, max: number): void {
  if (value === undefined) return;
  if (typeof value !== 'number' || !Number.isInteger(value) || value < min || value > max) {
    fail(`${what} must be a whole number from ${min} to ${max}`);
  }
}

/** An absolute http(s) URL without embedded credentials. */
export function checkUrl(value: unknown, what = 'url'): URL {
  const raw = string(value, what, LIMITS.url);
  let url: URL;
  try {
    url = new URL(raw);
  } catch {
    fail(`${what} isn't a URL`);
  }
  if (url.protocol !== 'https:' && url.protocol !== 'http:') fail(`${what} must be http or https`);
  if (url.username || url.password) fail(`${what} must not carry a user name or password`);
  return url;
}

export function checkHeaders(value: unknown): Record<string, string> {
  const headers = record(value, 'headers');
  const entries = Object.entries(headers);
  if (entries.length > LIMITS.headers) fail(`more than ${LIMITS.headers} headers`);
  for (const [name, v] of entries) {
    if (name.length > LIMITS.headerName || !HEADER_NAME.test(name)) fail(`header name "${name.slice(0, 40)}" isn't valid`);
    const text = string(v, `header ${name}`, LIMITS.headerValue);
    if (/[\r\n\0]/.test(text)) fail(`header ${name} has a line break in it`);
  }
  return headers as Record<string, string>;
}

/** An HttpRequest (or YtFetchParams): url, method, headers and body, each checked. */
export function checkHttpRequest(value: unknown, methods: readonly Method[] = METHODS): void {
  const params = record(value, 'params');
  checkUrl(params.url);
  const method = params.method ?? 'GET';
  if (typeof method !== 'string' || !(methods as readonly string[]).includes(method)) {
    fail(`method must be one of ${methods.join(', ')}`);
  }
  checkHeaders(params.headers);
  if (params.body !== undefined) {
    if (method !== 'POST') fail(`a ${method} request can't have a body`);
    string(params.body, 'body', LIMITS.body);
  }
  if (params.bodyEncoding !== undefined && params.bodyEncoding !== 'text' && params.bodyEncoding !== 'base64') {
    fail('bodyEncoding must be text or base64');
  }
  if (params.bodyEncoding === 'base64' && typeof params.body === 'string' && !/^[A-Za-z0-9+/]*={0,2}$/.test(params.body)) {
    fail("body isn't valid base64");
  }
}

function checkOrigins(value: unknown): void {
  if (value === undefined) return;
  if (!Array.isArray(value)) fail('origins must be a list');
  if (value.length > LIMITS.origins) fail(`more than ${LIMITS.origins} origins`);
  value.forEach((o, i) => string(o, `origins[${i}]`, LIMITS.url));
}

/**
 * Checks [params] for [method]; throws `BadRequest` when they're not what
 * the method takes. Methods it doesn't know are left to the dispatcher,
 * which answers `unsupported`.
 */
export function validateParams(method: string, value: unknown): void {
  const params = record(value, 'params');
  switch (method) {
    case 'ping':
    case 'spotify.signIn':
    case 'discord.state':
    case 'discord.connect':
    case 'discord.disconnect':
      return;
    case 'yt.fetch':
      checkHttpRequest(params);
      return optionalBoolean(params.auth, 'auth');
    case 'lyrics.fetch':
      return checkHttpRequest(params);
    case 'addon.fetch':
      // Add-on requests are plain GETs; the add-on module refuses anything else as well.
      return checkHttpRequest(params, ['GET']);
    case 'yt.authState':
    case 'spotify.authState':
      return optionalBoolean(params.refresh, 'refresh');
    case 'yt.selectChannel':
      string(params.channelId, 'channelId', LIMITS.id);
      return;
    case 'yt.setConnected':
    case 'spotify.setConnected':
      if (typeof params.connected !== 'boolean') fail('connected must be true or false');
      return;
    case 'discord.setEnabled':
      if (typeof params.enabled !== 'boolean') fail('enabled must be true or false');
      return;
    case 'addon.requestPermission':
      checkUrl(params.url);
      if (params.urls !== undefined) {
        if (!Array.isArray(params.urls) || params.urls.length > LIMITS.origins) fail(`urls must be a list of at most ${LIMITS.origins}`);
        params.urls.forEach((u, i) => checkUrl(u, `urls[${i}]`));
      }
      return;
    case 'lyrics.permissions':
    case 'lyrics.requestPermissions':
      return checkOrigins(params.origins);
    case 'spotify.playlists':
      return integer(params.offset, 'offset', 0, 1_000_000);
    case 'spotify.tracks':
      string(params.playlistId, 'playlistId', LIMITS.id);
      return integer(params.offset, 'offset', 0, 1_000_000);
    case 'spotify.playlist':
      string(params.playlistId, 'playlistId', LIMITS.id);
      return;
    case 'spotify.searchTracks':
      string(params.query, 'query', LIMITS.query);
      return;
    case 'spotify.canvas':
      string(params.trackId, 'trackId', LIMITS.id);
      return;
    case 'discord.presence': {
      if (params.track !== null) {
        const track = record(params.track, 'track');
        for (const key of ['videoId', 'title', 'artist'] as const) string(track[key], `track.${key}`, LIMITS.text);
        for (const key of ['album', 'artworkUrl'] as const) if (track[key] !== null && track[key] !== undefined) string(track[key], `track.${key}`, LIMITS.url);
        for (const key of ['positionMs', 'durationMs', 'playbackRate'] as const) {
          if (typeof track[key] !== 'number' || !Number.isFinite(track[key])) fail(`track.${key} must be a number`);
        }
      }
      const options = record(params.options, 'options');
      for (const [key, v] of Object.entries(options)) {
        if (typeof v === 'string') string(v, `options.${key}`, LIMITS.text);
      }
      return;
    }
    default:
      return;
  }
}
