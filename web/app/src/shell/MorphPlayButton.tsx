import { useEffect, useRef } from 'react';
import { PauseIcon, PlayIcon } from '@/components/icons/Transport';
import { motionAllowed } from './playerMorph';

/** How deep the waves are, as a part of the radius, and how many, as the app's Morph button (PlayerButtons.kt). */
const DEPTH = 0.075;
const WAVES = 9;

/** A circle of radius 50 around (50, 50) whose edge rises and falls in [WAVES] waves, [depth] of the radius deep. */
export function wavyCircle(depth: number): string {
  const steps = 180;
  let d = '';
  for (let i = 0; i <= steps; i++) {
    const a = (2 * Math.PI * i) / steps;
    const r = 50 * (1 - depth + depth * Math.cos(WAVES * a));
    d += `${i ? 'L' : 'M'}${(50 + r * Math.cos(a)).toFixed(2)} ${(50 + r * Math.sin(a)).toFixed(2)}`;
  }
  return d + 'Z';
}

/**
 * The full player's play button: a disc while paused that turns into a slowly
 * turning wavy shape while music plays. The change rides a soft spring
 * (damping 0.5, stiffness 170), so the waves run a little past and settle.
 */
export function MorphPlayButton({ playing, buffering, disabled, onClick }: { playing: boolean; buffering: boolean; disabled: boolean; onClick: () => void }) {
  const path = useRef<SVGPathElement>(null);
  const state = useRef({ x: playing ? 1 : 0, v: 0 });

  useEffect(() => {
    const target = playing ? 1 : 0;
    const s = state.current;
    const draw = () => path.current?.setAttribute('d', wavyCircle(DEPTH * Math.max(-0.2, s.x)));
    if (!motionAllowed()) {
      s.x = target; s.v = 0; draw();
      return;
    }
    let frame = 0;
    let last = performance.now();
    const step = (now: number) => {
      const dt = Math.min(0.032, (now - last) / 1000);
      last = now;
      const k = 170, c = 2 * 0.5 * Math.sqrt(k);
      s.v += (-k * (s.x - target) - c * s.v) * dt;
      s.x += s.v * dt;
      if (Math.abs(s.x - target) < 0.001 && Math.abs(s.v) < 0.001) { s.x = target; s.v = 0; draw(); return; }
      draw();
      frame = requestAnimationFrame(step);
    };
    frame = requestAnimationFrame(step);
    return () => cancelAnimationFrame(frame);
  }, [playing]);

  return (
    <button
      className="ot-morph ot-press size-17 disabled:opacity-40"
      disabled={disabled}
      aria-label={playing ? 'Pause' : 'Play'}
      data-playing={(playing && !buffering) || undefined}
      data-buffering={buffering || undefined}
      onClick={onClick}
    >
      <svg viewBox="0 0 100 100" aria-hidden="true">
        <path ref={path} d={wavyCircle(DEPTH * state.current.x)} fill="currentColor" />
      </svg>
      <span data-hidden={playing || undefined}><PlayIcon size={44} /></span>
      <span data-hidden={!playing || undefined}><PauseIcon size={44} /></span>
    </button>
  );
}
