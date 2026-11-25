import { ArrowDown, ArrowUp, GripVertical, MoreHorizontal, Shuffle, X } from 'lucide-react';
import { useCallback, useState, type CSSProperties } from 'react';
import { Menu, MenuItem, MenuSeparator } from '@/components/Menu';
import { Artwork } from '@/components/music/Artwork';
import { useReorder, type Reorder } from '@/components/music/reorder';
import { handleClass, ROW_HEIGHT, rowClass } from '@/shell/UpNext';
import { usePlayer } from '@/state/player';
import type { CTrack } from './messages';
import { moveQueuedRemote, playQueuedRemote, removeQueuedRemote } from './connect';
import type { Device } from './session';

const square = 'grid size-7 place-items-center rounded-[6px] text-secondary transition-colors duration-150 hover:bg-quinary aria-pressed:text-key';

function RemoteRow({
  track,
  at,
  count,
  handle,
  style,
  dragging,
}: {
  track: CTrack;
  at: number;
  count: number;
  handle: ReturnType<Reorder['handleProps']>;
  style: CSSProperties | undefined;
  dragging: boolean;
}) {
  const [anchor, setAnchor] = useState<HTMLElement | null>(null);
  const close = useCallback(() => setAnchor(null), []);
  return (
    <li className={rowClass} style={style} data-dragging={dragging || undefined} onDoubleClick={() => playQueuedRemote(at, track)}>
      <button type="button" className={handleClass} data-dragging={dragging || undefined} {...handle}>
        <GripVertical size={14} />
      </button>
      <div className="relative size-10">
        <Artwork src={track.thumb ?? null} displayWidth={40} radius="var(--radius-artwork-small)" />
        <button
          className="absolute -top-1.5 -left-1.5 hidden size-[22px] place-items-center rounded-full bg-thick-material text-primary shadow-[0_1px_3px_#0004] [backdrop-filter:blur(10px)] group-hover/row:grid [@media(hover:none)]:grid"
          aria-label={`Remove ${track.title}`}
          onClick={() => removeQueuedRemote(at, track)}
        >
          <X size={12} strokeWidth={3} />
        </button>
      </div>
      <button className="flex min-w-0 flex-col text-start" onClick={() => playQueuedRemote(at, track)} aria-label={`Play ${track.title}`}>
        <span className="truncate type-body text-primary">{track.title}</span>
        <span className="truncate type-body text-secondary">{track.artist}</span>
      </button>
      <div className="relative grid items-center justify-items-end">
        <span className="type-body tabular-nums text-secondary group-hover/row:invisible group-has-[[data-open]]/row:invisible [@media(hover:none)]:invisible">
          {track.length ?? ''}
        </span>
        <button
          className="absolute inset-y-0 right-0 my-auto hidden size-7 place-items-center rounded-full text-key group-hover/row:grid data-open:grid [@media(hover:none)]:grid"
          aria-label={`More options for ${track.title}`}
          aria-haspopup="menu"
          data-open={anchor !== null || undefined}
          onClick={(e) => setAnchor(anchor ? null : e.currentTarget)}
        >
          <MoreHorizontal size={16} />
        </button>
      </div>
      {anchor && (
        <Menu anchor={anchor} onClose={close} label={track.title}>
          <MenuItem onSelect={() => (close(), playQueuedRemote(at, track))}>Play Now</MenuItem>
          {count > 1 && <MenuSeparator />}
          {at > 0 && (
            <MenuItem icon={<ArrowUp size={15} />} onSelect={() => (close(), moveQueuedRemote(at, at - 1, track))}>
              Move Up
            </MenuItem>
          )}
          {at < count - 1 && (
            <MenuItem icon={<ArrowDown size={15} />} onSelect={() => (close(), moveQueuedRemote(at, at + 1, track))}>
              Move Down
            </MenuItem>
          )}
          <MenuSeparator />
          <MenuItem destructive onSelect={() => (close(), removeQueuedRemote(at, track))}>
            Remove from Up Next
          </MenuItem>
        </Menu>
      )}
    </li>
  );
}

/**
 * Up Next while the person's other device plays (Connect): what comes next
 * there, as that device last told it. A click plays one there; the handle,
 * the arrow keys or the row's menu reorder it (`move`); the ✕ takes it out;
 * Shuffle turns that device's shuffle on or off (`shuffle`). Each change
 * shows at once and is put back if the device says no.
 */
export function RemoteUpNext({ device }: { device: Device }) {
  const queue = device.state?.queue ?? [];
  const shuffle = !!device.state?.shuffle;
  const onMove = useCallback((from: number, to: number) => moveQueuedRemote(from, to, queue[from]!), [queue]);
  const reorder = useReorder({ count: queue.length, rowHeight: ROW_HEIGHT, onMove });
  return (
    <>
      <div className="flex items-center gap-1.5 px-5 pt-[23px] pb-3">
        <h3 className="flex-1 type-title-2-emphasized">Up Next</h3>
        {queue.length > 1 && (
          <button
            className={square}
            aria-label="Shuffle"
            title={shuffle ? 'Shuffle is on there' : 'Shuffle Up Next there'}
            aria-pressed={shuffle}
            // Goes to the device playing (player.ts sends shuffle there while it plays elsewhere).
            onClick={() => usePlayer.getState().toggleShuffle()}
          >
            <Shuffle size={15} strokeWidth={2.2} />
          </button>
        )}
      </div>
      <div className="min-h-0 flex-1 overflow-y-auto px-2 pb-4">
        <p className="px-2.5 pb-2 type-callout text-secondary">On {device.name || 'another device'}</p>
        {queue.length ? (
          <ul className="flex flex-col">
            {queue.map((t, i) => (
              <RemoteRow
                key={`${t.id}-${i}`}
                track={t}
                at={i}
                count={queue.length}
                dragging={reorder.isDragging(i)}
                style={reorder.rowStyle(i)}
                handle={reorder.handleProps(i, `Reorder ${t.title}. Use the arrow keys to move it.`)}
              />
            ))}
          </ul>
        ) : (
          <p className="px-2.5 type-callout text-secondary">Nothing after this one.</p>
        )}
      </div>
    </>
  );
}
