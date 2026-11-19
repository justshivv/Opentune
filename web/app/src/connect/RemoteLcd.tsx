import { useEffect, useState } from 'react';
import { useUi } from '@/state/ui';
import { positionAt, type Device } from './session';

const seconds = (length: string | null | undefined): number => {
  const parts = length?.split(':').map(Number) ?? [];
  return parts.length && parts.every(Number.isFinite) ? parts.reduce((a, b) => a * 60 + b, 0) : 0;
};

/**
 * The player bar's middle while another of the person's devices plays: its
 * song, "Playing on" it, and how far in, counted on from its last report.
 */
export function RemoteLcd({ device }: { device: Device }) {
  const [now, setNow] = useState(Date.now);
  useEffect(() => {
    const timer = setInterval(() => setNow(Date.now()), 500);
    return () => clearInterval(timer);
  }, []);
  const s = device.state;
  const track = s?.track;
  const total = seconds(track?.length) * 1000;
  const done = total ? Math.min(1, positionAt(device, now) / total) : 0;
  const open = useUi((s) => s.setFullScreen);
  return (
    <button className="relative flex h-full w-full items-center gap-3 overflow-hidden text-left" aria-label="Open Now Playing" onClick={() => open(true)}>
      {track?.thumb && <img src={track.thumb} alt="" className="size-10 flex-none rounded-[6px] object-cover" />}
      <div className="min-w-0 flex-1">
        <p className="truncate type-body-emphasized text-primary">{track?.title ?? 'Something of its own'}</p>
        <p className="truncate type-caption-1 text-key">Playing on {device.name || 'another device'}</p>
      </div>
      <div className="absolute right-0 bottom-1 left-0 h-[3px] rounded-full bg-quaternary">
        <div className="h-full rounded-full bg-key-bg" style={{ width: `${done * 100}%` }} />
      </div>
    </button>
  );
}
