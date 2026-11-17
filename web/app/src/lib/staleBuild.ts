import { lazy, type ComponentType } from 'react';

/**
 * A tab opened before a deploy still runs the old build, and its pages, lyrics panel and player are
 * separate files it fetches when they're first needed. GitHub Pages swaps the whole site at once and
 * lets browsers keep the page for ten minutes, so such a fetch can miss: the file is renamed in the
 * new build, or caught mid-swap. That surfaced as React Router's bare "Unexpected Application
 * Error! Failed to fetch dynamically imported module". The cure is the current build: the page is
 * reloaded, once. A second miss soon after isn't a deploy, and is shown as an error instead of
 * reloading in a loop.
 */

const KEY = 'opentune:stale-build-reload';
/** A reload within this long of the last one is a loop, not a new deploy. */
const WINDOW_MS = 60_000;

/** Whether [error] is a code file of this app failing to load, in any browser's words. */
export function isChunkLoadError(error: unknown): boolean {
  const message = error instanceof Error ? `${error.name} ${error.message}` : String(error ?? '');
  return /Failed to fetch dynamically imported module|error loading dynamically imported module|Importing a module script failed|Unable to preload CSS|ChunkLoadError|Loading (?:CSS )?chunk \S+ failed/i.test(message);
}

interface Session {
  getItem(key: string): string | null;
  setItem(key: string, value: string): void;
}

function session(): Session | null {
  try {
    return window.sessionStorage;
  } catch {
    return null;
  }
}

/**
 * Reloads the page for the current build, unless it was reloaded for this within the last minute.
 * True when it reloads. Without session storage it doesn't, since a loop couldn't be told apart.
 */
export function reloadOnce(storage: Session | null = session(), now = Date.now(), reload = () => window.location.reload()): boolean {
  if (!storage) return false;
  try {
    const last = Number(storage.getItem(KEY) ?? 0);
    if (now - last < WINDOW_MS) return false;
    storage.setItem(KEY, String(now));
  } catch {
    return false;
  }
  reload();
  return true;
}

/** A load that, when the file is gone, reloads for the current build instead of failing. */
export function recovering<T>(load: () => Promise<T>, reload: () => boolean = () => reloadOnce()): () => Promise<T> {
  return () =>
    load().catch((error: unknown) => {
      // The page is going away; never settling keeps the old screen up until it does.
      if (isChunkLoadError(error) && reload()) return new Promise<T>(() => {});
      throw error;
    });
}

/** React's lazy, over [recovering]. */
// The same bound React's own lazy takes, so components with props fit.
export function lazyRecovering<T extends ComponentType<any>>(load: () => Promise<{ default: T }>) {
  return lazy(recovering(load));
}

/** Vite's own signal for a dynamic import whose files failed to load: answered the same way. */
export function installStaleBuildRecovery(target: Pick<Window, 'addEventListener'> = window) {
  target.addEventListener('vite:preloadError', (event) => {
    if (reloadOnce()) event.preventDefault();
  });
}
