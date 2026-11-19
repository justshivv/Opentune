import { useMemo } from 'react';
import { usePlayer, type NowPlaying } from '@/state/player';
import { useControlling } from './connect';
import type { Device } from './session';

/** A track's "3:45" length in seconds; 0 when there's none. */
export const lengthSec = (length: string | null | undefined): number => {
  const parts = length?.split(':').map(Number) ?? [];
  return parts.length && parts.every(Number.isFinite) ? parts.reduce((a, b) => a * 60 + b, 0) : 0;
};

/** What [device] plays, shaped as this player's own now-playing. */
export function remoteNowPlaying(device: Device): NowPlaying | null {
  const s = device.state;
  if (!s) return null;
  const t = s.track;
  if (!t) return { videoId: '', title: 'Something of its own', artist: '', artworkUrl: null, durationSec: 0, streamInfo: null };
  return { videoId: t.id, title: t.title, artist: t.artist ?? '', artworkUrl: t.thumb ?? null, durationSec: lengthSec(t.length), streamInfo: null };
}

/**
 * What the player shows: the song of the person's other device while it's
 * the one playing (Connect), otherwise this player's own. The full-screen
 * player, the lyrics and the seek line read this, so they look the same
 * whichever device the music is on.
 */
export function useShownNowPlaying(): NowPlaying | null {
  const remote = useControlling();
  const local = usePlayer((s) => s.nowPlaying);
  const key = remote ? `${remote.id}|${remote.state?.track?.id ?? ''}|${remote.state?.track?.thumb ?? ''}` : null;
  // eslint-disable-next-line react-hooks/exhaustive-deps
  return useMemo(() => (remote ? remoteNowPlaying(remote) : local), [key, local]);
}
