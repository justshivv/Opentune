import { useLayoutEffect, useSyncExternalStore } from 'react';
import { useArtworkColor } from '@/lib/artworkColor';
import { useAppearance } from '@/state/appearance';
import { usePlayer } from '@/state/player';

const dark = window.matchMedia('(prefers-color-scheme: dark)');
export function Appearance() {
  const settings = useAppearance();
  const artwork = usePlayer(s => s.nowPlaying?.artworkUrl);
  const color = useArtworkColor(settings.artworkColors ? artwork : null);
  const systemDark = useSyncExternalStore(
    fn => { dark.addEventListener('change', fn); return () => dark.removeEventListener('change', fn); },
    () => dark.matches,
  );
  useLayoutEffect(() => {
    const root = document.documentElement;
    root.dataset.appearance = settings.mode === 'system' ? (systemDark ? 'dark' : 'light') : settings.mode;
    root.dataset.black = String(settings.pureBlack);
    root.dataset.motion = String(settings.motion);
    root.dataset.playerStyle = settings.playerStyle;
    root.style.setProperty('--ot-seed', color?.background ?? settings.accent);
    root.style.setProperty('--ot-accent-choice', settings.accent);
  }, [settings.mode, settings.pureBlack, settings.motion, settings.playerStyle, settings.accent, systemDark, color]);
  return null;
}
