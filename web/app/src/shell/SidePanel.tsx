import { lazy, Suspense, useState } from 'react';
import { useUi, type SidePanel as Panel } from '@/state/ui';
import { motionAllowed } from './playerMorph';
import { UpNext } from './UpNext';

// am-lyrics is large; it loads the first time the panel opens (prompt §3).
const LyricsPanel = lazy(() => import('./LyricsPanel').then((m) => ({ default: m.LyricsPanel })));

/** Up Next / Lyrics glass panel (report §7). */
export function SidePanel() {
  const panel = useUi((s) => s.sidePanel);
  // Hidden under the full-screen player, which has its own lyrics: one am-lyrics at a time.
  const fullScreen = useUi((s) => s.fullScreen);
  // The panel last shown stays on screen while it slides back out, rather than vanishing.
  const [shown, setShown] = useState<Panel>(panel);
  if (panel && panel !== shown) setShown(panel);
  // With motion off no animation runs, so nothing would ever end it; under the full player
  // it can't be seen leaving. Either way it goes at once.
  if (!panel && shown && (fullScreen || !motionAllowed())) setShown(null);
  const leaving = !panel && shown !== null;
  if (!shown || fullScreen) return null;

  return (
    <aside
      className={`absolute inset-y-0 right-0 z-(--z-web-chrome) flex w-(--side-panel-width) flex-col glass-material shadow-glass max-sm:w-full ${leaving ? 'pointer-events-none animate-slide-out' : 'animate-slide-in'}`}
      aria-label={shown === 'up-next' ? 'Up Next' : 'Lyrics'}
      aria-hidden={leaving || undefined}
      onAnimationEnd={(e) => {
        if (leaving && e.target === e.currentTarget) setShown(null);
      }}
    >
      {shown === 'up-next' ? (
        <UpNext />
      ) : (
        <Suspense fallback={null}>
          <LyricsPanel />
        </Suspense>
      )}
    </aside>
  );
}
