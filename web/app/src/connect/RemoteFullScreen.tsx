import { X } from 'lucide-react';
import { useEffect, useRef, useState } from 'react';
import { BackwardIcon, ForwardIcon } from '@/components/icons/Transport';
import { sizedArtwork } from '@/lib/artwork';
import { usePlayer } from '@/state/player';
import { useUi } from '@/state/ui';
import { MeshBackdrop } from '@/shell/MeshBackdrop';
import { MorphPlayButton } from '@/shell/MorphPlayButton';
import { clock } from '@/shell/useScrubber';
import { positionAt, type Device } from './session';
import { seekRemote } from './connect';

const seconds = (length: string | null | undefined): number => {
  const parts = length?.split(':').map(Number) ?? [];
  return parts.length && parts.every(Number.isFinite) ? parts.reduce((a, b) => a * 60 + b, 0) : 0;
};

const button =
  'grid place-items-center rounded-full transition-[background-color,scale] duration-100 ease-in enabled:hover:bg-quinary enabled:active:scale-90 disabled:opacity-40';

/**
 * The full-screen player while another of the person's devices plays
 * (Connect): its song over the artwork's mesh, how far in, and the buttons,
 * which act there. The player bar's middle opens it, as it opens the usual one.
 */
export function RemoteFullScreen({ device, closing = false, onClosed }: { device: Device; closing?: boolean; onClosed?: () => void }) {
  const setFullScreen = useUi((s) => s.setFullScreen);
  const { togglePlay, next, previous } = usePlayer.getState();
  const [now, setNow] = useState(Date.now);
  const close = useRef<HTMLButtonElement>(null);
  useEffect(() => {
    const timer = setInterval(() => setNow(Date.now()), 250);
    return () => clearInterval(timer);
  }, []);
  useEffect(() => {
    if (closing) onClosed?.();
  }, [closing, onClosed]);
  useEffect(() => {
    close.current?.focus();
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') setFullScreen(false); };
    addEventListener('keydown', onKey);
    return () => removeEventListener('keydown', onKey);
  }, [setFullScreen]);

  const s = device.state;
  const track = s?.track;
  const playing = !!s?.playing;
  const total = seconds(track?.length);
  const at = Math.min(positionAt(device, now) / 1000, total || Infinity);
  const done = total ? Math.min(1, at / total) : 0;
  const art = track?.thumb ?? null;
  const seekAt = (e: React.PointerEvent<HTMLElement>) => {
    if (!total) return;
    const rect = e.currentTarget.getBoundingClientRect();
    seekRemote(Math.min(1, Math.max(0, (e.clientX - rect.left) / rect.width)) * total * 1000);
  };

  return (
    <div
      className="fixed inset-0 z-[calc(var(--z-contextual-menus)-2)] isolate animate-fade-in overflow-hidden text-primary [--art:min(32vw,calc(100dvh-330px),720px)] max-md:[--art:min(84vw,calc(100dvh-330px),420px)]"
      role="dialog"
      aria-modal="true"
      aria-label="Now Playing"
      data-full-screen
      data-paused={!playing || undefined}
    >
      <MeshBackdrop artworkUrl={art} playing={playing} />
      <div className="ot-playing-from">Playing on<br /><strong>{device.name || 'another device'}</strong></div>
      <button ref={close} className="absolute top-4 left-5 z-1 grid size-8 place-items-center rounded-full text-secondary hover:bg-quinary hover:text-primary" aria-label="Close" onClick={() => setFullScreen(false)}>
        <X size={20} />
      </button>
      <div className="grid h-full grid-cols-[var(--art)] items-center justify-center px-6 py-16">
        <section className="flex w-(--art) min-w-0 flex-col" aria-label="Player">
          <div className={`relative aspect-square w-(--art) rounded-[28px] bg-[#ffffff12] transition-[scale] duration-450 ease-(--ot-ease-settle) motion-reduce:transition-none${playing ? '' : ' scale-92'}`}>
            {art && <img className="absolute inset-0 size-full rounded-[inherit] object-cover shadow-[0_4px_10px_#0000001a]" src={sizedArtwork(art, 600)} alt="" referrerPolicy="no-referrer" />}
          </div>
          <div className="mt-[clamp(20px,3.2vh,32px)] flex min-w-0 flex-col gap-0.5">
            <span className="truncate type-[600_17px/22px]">{track?.title ?? 'Something of its own'}</span>
            <span className="truncate type-title-3 text-secondary">{track?.artist ?? ''}</span>
          </div>
          <div className="mt-3.5">
            <div className="flex h-4 cursor-pointer items-center" onPointerUp={seekAt}>
              <div className="h-[5px] w-full rounded-full bg-quaternary">
                <div className="h-full rounded-full bg-primary/80" style={{ width: `${done * 100}%` }} />
              </div>
            </div>
            <div className="mt-1 flex justify-between type-caption-1 text-secondary">
              <span>{clock(at)}</span>
              <span>-{clock(total - at)}</span>
            </div>
          </div>
          <div className="mx-auto mt-[clamp(8px,1.6vh,16px)] flex h-17 w-[min(100%,240px)] items-center justify-between">
            <button className={`${button} size-12 text-primary`} aria-label="Previous" onClick={previous}>
              <BackwardIcon size={40} />
            </button>
            <MorphPlayButton playing={playing} buffering={false} disabled={false} onClick={togglePlay} />
            <button className={`${button} size-12 text-primary`} aria-label="Next" onClick={next}>
              <ForwardIcon size={40} />
            </button>
          </div>
        </section>
      </div>
    </div>
  );
}
