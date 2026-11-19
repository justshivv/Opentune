import { Suspense, useRef, useState } from 'react';
import { lazyRecovering } from '@/lib/staleBuild';
import { useUi, type SidePanel as Panel } from '@/state/ui';
import { motionAllowed } from './playerMorph';
import { DevicesPanel } from '@/connect/DevicesPanel';
import { UpNext } from './UpNext';

// am-lyrics is large; it loads the first time the panel opens (prompt §3).
const LyricsPanel = lazyRecovering(() => import('./LyricsPanel').then((m) => ({ default: m.LyricsPanel })));

/** Up Next / Lyrics / Devices glass panel (report §7). */
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
  // Which panel was showing before: moving from one to another while open, the new one fades in.
  const before = useRef<Panel>(null);
  // The panel that came in that way keeps its class while it's up, so a redraw can't cut it short.
  const fadedIn = useRef<Panel>(null);
  if (before.current !== null && shown !== null && before.current !== shown) fadedIn.current = shown;
  if (shown) before.current = shown;
  if (!shown || fullScreen) {
    before.current = null;
    fadedIn.current = null;
    return null;
  }
  const swapped = fadedIn.current === shown;

  return (
    <aside
      className={`absolute inset-y-0 right-0 z-(--z-web-chrome) flex w-(--side-panel-width) flex-col glass-material shadow-glass max-sm:w-full ${leaving ? 'pointer-events-none animate-slide-out' : 'animate-slide-in'}`}
      aria-label={shown === 'up-next' ? 'Up Next' : shown === 'devices' ? 'Devices' : 'Lyrics'}
      aria-hidden={leaving || undefined}
      onAnimationEnd={(e) => {
        if (leaving && e.target === e.currentTarget) setShown(null);
      }}
    >
      <div key={shown} className={`flex min-h-0 flex-1 flex-col ${swapped ? 'ot-panel-swap' : ''}`}>
        {shown === 'up-next' ? (
          <UpNext />
        ) : shown === 'devices' ? (
          <DevicesPanel />
        ) : (
          <Suspense fallback={null}>
            <LyricsPanel />
          </Suspense>
        )}
      </div>
    </aside>
  );
}
