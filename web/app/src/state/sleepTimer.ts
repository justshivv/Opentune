import { create } from 'zustand';
import { toast } from '@/components/Toast';
import { setStopAfterCurrent, usePlayer } from './player';

/** Minutes offered, besides the end of the playing song. */
export const SLEEP_CHOICES = [15, 30, 45, 60, 90] as const;

interface SleepTimer {
  /** When playback pauses, as a Date.now() time; null when off or set to the song's end. */
  endsAt: number | null;
  /** Stop when the playing song finishes. */
  endOfSong: boolean;
  set: (choice: number | 'end' | null) => void;
}

let timer = 0;

function stopped() {
  useSleepTimer.setState({ endsAt: null, endOfSong: false });
  toast('Sleep timer: paused');
}

/** Pauses the music after a while, or once the playing song ends, like a bedside radio. */
export const useSleepTimer = create<SleepTimer>((set) => ({
  endsAt: null,
  endOfSong: false,
  set(choice) {
    clearTimeout(timer);
    setStopAfterCurrent(null);
    if (choice === null) return set({ endsAt: null, endOfSong: false });
    if (choice === 'end') {
      setStopAfterCurrent(stopped);
      return set({ endsAt: null, endOfSong: true });
    }
    timer = window.setTimeout(() => {
      const player = usePlayer.getState();
      if (player.status === 'playing' || player.status === 'buffering') player.togglePlay();
      stopped();
    }, choice * 60_000);
    set({ endsAt: Date.now() + choice * 60_000, endOfSong: false });
  },
}));

/** "23 min left", for the menu. */
export function sleepLabel(endsAt: number | null, endOfSong: boolean, now = Date.now()): string | null {
  if (endOfSong) return 'At the end of this song';
  if (!endsAt) return null;
  return `${Math.max(1, Math.ceil((endsAt - now) / 60_000))} min left`;
}
