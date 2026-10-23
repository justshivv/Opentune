import { endpoint, type Addon } from '@opentune/shared/addons';

/** Where the site says the OpenTune Worker is; the catalog is only ever read from that Worker. */
const CONFIG_URL = 'https://justshivv.github.io/Opentune/usage-config.json';
const WORKER_HOST = 'opentune-usage.create-a-new-site-with-sites.workers.dev';
/** As on Android: looked at on start and when Audio sources opens, at most this often. */
const REFRESH_MS = 15 * 60_000;

export type Published = { id: string; name: string; url: string };

/** The published list, checked: at most 20 sources, each with a plain id, a name and a public HTTPS manifest. */
export function publishedOf(value: unknown): Published[] {
  const list = (value as { sources?: unknown })?.sources;
  if (!Array.isArray(list) || list.length > 20) throw new Error('Invalid source catalog');
  return list.flatMap((item) => {
    const { id, name, url } = (item ?? {}) as Record<string, unknown>;
    if (typeof id !== 'string' || !/^[A-Za-z0-9_-]{1,64}$/.test(id) || typeof name !== 'string' || typeof url !== 'string') return [];
    try {
      return [{ id, name: name.trim().slice(0, 80) || 'Source', url: endpoint(url, 'manifest.json') }];
    } catch {
      return [];
    }
  });
}

/**
 * The listener's sources with the published ones folded in, as Android's
 * `syncCommunity` does: their own stay as they are; a published source keeps
 * the place and on/off the listener gave it, takes the catalog's current name
 * and address, and goes when it's no longer published; a new one joins at the end, on.
 */
export function mergeCatalog(local: Addon[], published: Published[]): Addon[] {
  const incoming = new Map(published.map((p) => [`admin:${p.id}`, p]));
  const kept = local.flatMap((source) => {
    if (!source.admin) return [source];
    const update = incoming.get(source.id);
    if (!update) return [];
    incoming.delete(source.id);
    return [{ ...source, name: update.name, url: update.url }];
  });
  return [...kept, ...[...incoming.entries()].map(([id, p]) => ({ id, name: p.name, url: p.url, enabled: true, admin: true }))];
}

let lastCheck = 0;
let running: Promise<Published[]> | null = null;

/** The published list, from the Worker the site's config names (and only that Worker). */
export async function fetchPublished(force = false): Promise<Published[] | null> {
  if (!force && lastCheck && Date.now() - lastCheck < REFRESH_MS) return null;
  running ??= (async () => {
    const config = (await (await fetch(CONFIG_URL, { cache: 'no-store', credentials: 'omit', signal: AbortSignal.timeout(8000) })).json()) as { apiBase?: unknown };
    const base = new URL(String(config.apiBase));
    if (base.protocol !== 'https:' || base.hostname !== WORKER_HOST) throw new Error('Unexpected source catalog address');
    const response = await fetch(new URL('/v1/addons', base), { credentials: 'omit', redirect: 'error', signal: AbortSignal.timeout(8000) });
    if (!response.ok) throw new Error(`Source catalog returned HTTP ${response.status}`);
    const published = publishedOf(await response.json());
    lastCheck = Date.now();
    return published;
  })().finally(() => { running = null; });
  return running;
}
