import { useEffect, useRef, useState } from 'react';
import { seekRemote, useControlling } from '@/connect/connect';
import { positionAt } from '@/connect/session';
import { lengthSec } from '@/connect/shown';
import { getAudio, resumePosition, usePlayer } from '@/state/player';

export function clock(seconds: number): string {
  if (!Number.isFinite(seconds) || seconds < 0) return '0:00';
  const s = Math.floor(seconds);
  return `${Math.floor(s / 60)}:${String(s % 60).padStart(2, '0')}`;
}

/**
 * A seek line drawn from the media clock every frame while playing, by writing
 * `--progress` on the bar and the times into their spans: no React state, so
 * nothing re-renders. Pressing or dragging on the scrubber seeks on release.
 * Shared by the LCD and the full-screen player. While the person's other
 * device is the one playing (Connect), the line follows and seeks that one.
 */
export function useScrubber() {
  const bar = useRef<HTMLDivElement>(null);
  const elapsed = useRef<HTMLSpanElement>(null);
  const remaining = useRef<HTMLSpanElement>(null);
  const status = usePlayer((s) => s.status);
  const deck = usePlayer((s) => s.deck);
  const restoredDuration = usePlayer((s) => s.nowPlaying?.durationSec ?? 0);
  const seek = usePlayer((s) => s.seek);
  const [dragging, setDragging] = useState<number | null>(null);
  const remote = useControlling();
  const remoteRef = useRef(remote);
  remoteRef.current = remote;
  const far = !!remote;

  // The other device's position, counted on from its last report.
  useEffect(() => {
    const element = bar.current;
    if (!far || !element) return;
    let frame = 0;
    const draw = () => {
      const d = remoteRef.current;
      if (!d) return;
      const duration = lengthSec(d.state?.track?.length);
      const time = dragging !== null ? dragging * duration : Math.min(positionAt(d, Date.now()) / 1000, duration || Infinity);
      element.style.setProperty('--progress', String(duration > 0 ? Math.min(1, Math.max(0, time / duration)) : 0));
      if (elapsed.current) elapsed.current.textContent = clock(time);
      if (remaining.current) remaining.current.textContent = `-${clock(duration - time)}`;
      frame = requestAnimationFrame(draw);
    };
    draw();
    return () => cancelAnimationFrame(frame);
  }, [far, dragging]);

  useEffect(() => {
    if (far) return;
    const audio = getAudio();
    const element = bar.current;
    if (!element) return;
    if (!audio) {
      // A queue restored from the last visit: where it was left, until Play loads it.
      const time = resumePosition();
      element.style.setProperty('--progress', String(restoredDuration > 0 ? Math.min(1, time / restoredDuration) : 0));
      if (elapsed.current) elapsed.current.textContent = clock(time);
      if (remaining.current) remaining.current.textContent = `-${clock(restoredDuration - time)}`;
      return;
    }
    let frame = 0;
    const draw = () => {
      const duration = audio.duration;
      const time = dragging !== null && Number.isFinite(duration) ? dragging * duration : audio.currentTime;
      const fraction = duration > 0 ? time / duration : 0;
      element.style.setProperty('--progress', String(Math.min(1, Math.max(0, fraction))));
      if (elapsed.current) elapsed.current.textContent = clock(time);
      if (remaining.current) remaining.current.textContent = `-${clock((duration || 0) - time)}`;
      if (!audio.paused || dragging !== null) frame = requestAnimationFrame(draw);
    };
    draw();
    const onSeeked = () => draw();
    audio.addEventListener('seeked', onSeeked);
    return () => {
      cancelAnimationFrame(frame);
      audio.removeEventListener('seeked', onSeeked);
    };
  }, [status, deck, dragging, restoredDuration, far]);

  const fractionAt = (event: React.PointerEvent<HTMLElement>) => {
    const rect = event.currentTarget.getBoundingClientRect();
    return Math.min(1, Math.max(0, (event.clientX - rect.left) / rect.width));
  };

  const handlers = {
    onPointerDown: (e: React.PointerEvent<HTMLElement>) => {
      if (!getAudio() && !remoteRef.current) return;
      e.currentTarget.setPointerCapture(e.pointerId);
      setDragging(fractionAt(e));
    },
    onPointerMove: (e: React.PointerEvent<HTMLElement>) => {
      if (dragging !== null) setDragging(fractionAt(e));
    },
    onPointerUp: (e: React.PointerEvent<HTMLElement>) => {
      if (dragging === null) return;
      const d = remoteRef.current;
      if (d) {
        seekRemote(fractionAt(e) * lengthSec(d.state?.track?.length) * 1000);
        setDragging(null);
        return;
      }
      const audio = getAudio();
      if (audio && Number.isFinite(audio.duration)) seek(fractionAt(e) * audio.duration);
      setDragging(null);
    },
    onPointerCancel: () => setDragging(null),
  };

  return { bar, elapsed, remaining, dragging: dragging !== null, handlers };
}

