import { X } from 'lucide-react';
import { Artwork } from '@/components/music/Artwork';
import { playQueuedRemote, removeQueuedRemote } from './connect';
import type { Device } from './session';

/**
 * Up Next while the person's other device plays (Connect): what comes next
 * there, as that device last told it. A click plays one there, the ✕ takes
 * it out.
 */
export function RemoteUpNext({ device }: { device: Device }) {
  const queue = device.state?.queue ?? [];
  return (
    <>
      <div className="flex items-center gap-1.5 px-5 pt-[23px] pb-3">
        <h3 className="flex-1 type-title-2-emphasized">Up Next</h3>
      </div>
      <div className="min-h-0 flex-1 overflow-y-auto px-2.5 pb-4">
        <p className="px-2.5 pb-2 type-callout text-secondary">On {device.name || 'another device'}</p>
        {queue.length ? (
          <ul className="flex flex-col">
            {queue.map((t, i) => (
              <li
                key={`${t.id}-${i}`}
                className="group/row grid h-[55px] grid-cols-[40px_minmax(0,1fr)_auto] items-center gap-x-3 rounded-row px-2.5 hover:bg-quinary"
                onDoubleClick={() => playQueuedRemote(i, t)}
              >
                <div className="relative size-10">
                  <Artwork src={t.thumb ?? null} displayWidth={40} radius="var(--radius-artwork-small)" />
                  <button
                    className="absolute -top-1.5 -left-1.5 hidden size-[22px] place-items-center rounded-full bg-thick-material text-primary shadow-[0_1px_3px_#0004] [backdrop-filter:blur(10px)] group-hover/row:grid [@media(hover:none)]:grid"
                    aria-label={`Remove ${t.title}`}
                    onClick={() => removeQueuedRemote(i, t)}
                  >
                    <X size={12} strokeWidth={3} />
                  </button>
                </div>
                <button className="flex min-w-0 flex-col text-start" onClick={() => playQueuedRemote(i, t)} aria-label={`Play ${t.title}`}>
                  <span className="truncate type-body text-primary">{t.title}</span>
                  <span className="truncate type-body text-secondary">{t.artist}</span>
                </button>
                <span className="type-body tabular-nums text-secondary">{t.length ?? ''}</span>
              </li>
            ))}
          </ul>
        ) : (
          <p className="px-2.5 type-callout text-secondary">Nothing after this one.</p>
        )}
      </div>
    </>
  );
}
