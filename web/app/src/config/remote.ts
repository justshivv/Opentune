import { create } from 'zustand';
import { defaults, verifyConfig } from './protocol';

const ORIGIN = 'https://addon-opentune.rethinknow.in';
const KEY = 'opentune:remote-config';
export const useRemoteConfig = create(() => ({ config: defaults() }));
export const currentConfig = () => { const c = useRemoteConfig.getState().config; return c.expiresAt > Date.now() ? c : defaults(); };
let pinned = '', minimumRevision = 0, lastAttempt = 0, running: Promise<void> | null = null;
let initial: Promise<void> | null = null;
function initialize() {
  return initial ??= (async () => {
    try {
      const saved = JSON.parse(localStorage.getItem(KEY) || 'null');
      if (!saved || typeof saved.key !== 'string' || !Number.isSafeInteger(saved.revision)) return;
      pinned = saved.key; minimumRevision = saved.revision;
      const config = await verifyConfig(saved.envelope, pinned, minimumRevision);
      useRemoteConfig.setState({ config });
    } catch { /* Offline/expired/corrupt: use built-in defaults; retain the pinned key. */ }
  })();
}
async function get(path: string) {
  const r = await fetch(ORIGIN + path, { credentials: 'omit', redirect: 'error', signal: AbortSignal.timeout(8000) });
  if (!r.ok || !r.body) throw new Error('Configuration unavailable');
  const reader = r.body.getReader(); let size = 0, result = ''; const decoder = new TextDecoder();
  try { while (true) { const part = await reader.read(); if (part.done) break; size += part.value.length; if (size > 100000) throw new Error('Configuration too large'); result += decoder.decode(part.value, { stream: true }); } return result + decoder.decode(); }
  finally { await reader.cancel().catch(() => {}); }
}
export async function refreshConfig(force = false): Promise<void> {
  await initialize();
  if (useRemoteConfig.getState().config.expiresAt <= Date.now()) useRemoteConfig.setState({ config: defaults() });
  if (running) return running;
  if (!force && lastAttempt && Date.now() - lastAttempt < 300000) return;
  lastAttempt = Date.now();
  running = (async () => {
    try {
      // Bootstrap only from the fixed TLS origin. Subsequent key changes are refused.
      const key: string = pinned || JSON.parse(await get('/v1/app-config/key')).publicKey;
      const envelope = await get('/v1/app-config');
      const config = await verifyConfig(envelope, key, minimumRevision);
      pinned = key; minimumRevision = config.revision;
      try { localStorage.setItem(KEY, JSON.stringify({ key, envelope, revision: minimumRevision })); } catch { /* This session still works. */ }
      useRemoteConfig.setState({ config });
    } catch { /* Retain the last valid signed copy, or defaults. */ }
  })().finally(() => { running = null; });
  return running;
}
