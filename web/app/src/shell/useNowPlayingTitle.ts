import { useEffect } from 'react';
import { usePlayer } from '@/state/player';

/**
 * The tab says what's playing ("▶ Song · Artist"), so a listener with many tabs
 * open can find the music one; paused, the song without the mark; nothing
 * loaded, the page's own title.
 */
export function useNowPlayingTitle() {
  const title = usePlayer((s) => s.nowPlaying?.title ?? '');
  const artist = usePlayer((s) => s.nowPlaying?.artist ?? '');
  const playing = usePlayer((s) => s.status === 'playing' || s.status === 'buffering');
  useEffect(() => {
    const base = document.title;
    return () => {
      document.title = base;
    };
  }, []);
  useEffect(() => {
    if (!title) return;
    const before = document.title;
    document.title = `${playing ? '▶ ' : ''}${[title, artist].filter(Boolean).join(' · ')}`;
    return () => {
      document.title = before;
    };
  }, [title, artist, playing]);
}
