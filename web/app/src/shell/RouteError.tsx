import { useEffect, useState } from 'react';
import { useRouteError } from 'react-router';
import { isChunkLoadError, reloadOnce } from '@/lib/staleBuild';

/**
 * What shows when a page can't be shown, in place of React Router's bare "Unexpected Application
 * Error!". The usual cause is a tab left open across an update, missing a file of the old build:
 * that reloads straight into the new one, once (see staleBuild.ts). Anything else, or the same miss
 * again right after a reload, is said plainly with a way back.
 */
export function RouteError() {
  const error = useRouteError();
  const stale = isChunkLoadError(error);
  const [reloading, setReloading] = useState(false);
  useEffect(() => {
    if (stale && reloadOnce()) setReloading(true);
  }, [stale]);

  return (
    <main className="grid h-full min-h-dvh place-items-center bg-(--pageBG,transparent) px-6 text-center" role="alert">
      <div className="max-w-[440px]">
        <h1 className="type-title-1-emphasized">{reloading ? 'Updating OpenTune…' : stale ? 'OpenTune was updated' : 'Something went wrong'}</h1>
        <p className="mt-2 type-body text-secondary">
          {reloading
            ? 'A newer version came out while this tab was open. Loading it now.'
            : stale
              ? 'Part of the player couldn’t load. This usually means a newer version came out while this tab was open, or the connection dropped.'
              : 'This page ran into a problem. Reloading usually fixes it.'}
        </p>
        {!reloading && (
          <div className="mt-5 flex justify-center gap-3">
            <button
              className="inline-flex h-9 items-center rounded-pill bg-key-bg px-5 type-body-emphasized text-white hover:opacity-90"
              onClick={() => window.location.reload()}
            >
              Reload
            </button>
            <a className="inline-flex h-9 items-center rounded-pill px-4 type-body-emphasized text-key hover:underline" href="#/home">
              Go to Home
            </a>
          </div>
        )}
      </div>
    </main>
  );
}
