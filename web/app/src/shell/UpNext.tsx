import { ArrowDown, ArrowUp, GripVertical, Infinity as InfinityIcon, ListStart, Moon, MoreHorizontal, Shuffle, X } from 'lucide-react';
import { useCallback, useState, type CSSProperties } from 'react';
import { Menu, MenuHeader, MenuItem, MenuSeparator } from '@/components/Menu';
import { Artwork } from '@/components/music/Artwork';
import { formatDuration } from '@/components/music/links';
import { useReorder, type Reorder } from '@/components/music/reorder';
import { toast } from '@/components/Toast';
import { autoplaySectionStart, upcomingUserQueueIndices, type QueueEntry } from '@/playback/queue/timeline';
import { CROSSFADE_CHOICES, usePlayer } from '@/state/player';
import { SLEEP_CHOICES, sleepLabel, useSleepTimer } from '@/state/sleepTimer';

// The panel's square buttons: AutoPlay (the key colour when on) and the options menu.
const square = 'grid size-7 place-items-center rounded-[6px] text-secondary transition-colors duration-150 hover:bg-quinary aria-pressed:text-key';
const list = 'flex flex-col';

export const ROW_HEIGHT = 55;

/** Up Next's row: 55px, [handle] 16px [art] 40px [title] 1fr [right] 40px, gap 12px, radius 6px. */
export const rowClass =
  'group/row relative grid h-[55px] grid-cols-[16px_40px_minmax(0,1fr)_40px] items-center gap-x-2.5 rounded-row ps-1 pe-2.5 hover:bg-quinary data-dragging:bg-thick-material data-dragging:shadow-glass';

/** The drag handle: grab it with the mouse or a finger, or focus it and use the arrow keys. */
export const handleClass =
  'grid h-10 w-4 cursor-grab touch-none place-items-center rounded-[4px] text-tertiary opacity-0 group-hover/row:opacity-100 focus-visible:opacity-100 focus-visible:outline-2 focus-visible:outline-key active:cursor-grabbing data-dragging:opacity-100 [@media(hover:none)]:opacity-100';

function Row({
  entry,
  at,
  first,
  last,
  handle,
  style,
  dragging,
}: {
  entry: QueueEntry;
  at: number;
  /** The first and last upcoming rows: nothing to move above or below them. */
  first: boolean;
  last: boolean;
  handle: ReturnType<Reorder['handleProps']>;
  style: CSSProperties | undefined;
  dragging: boolean;
}) {
  const { jumpTo, removeAt, playNext, move } = usePlayer.getState();
  const [anchor, setAnchor] = useState<HTMLElement | null>(null);
  const close = useCallback(() => setAnchor(null), []);
  const { track } = entry;

  return (
    <li className={rowClass} style={style} data-dragging={dragging || undefined} onDoubleClick={() => jumpTo(at)}>
      <button type="button" className={handleClass} data-dragging={dragging || undefined} {...handle}>
        <GripVertical size={14} />
      </button>
      <div className="relative size-10">
        <Artwork src={track.thumbnail} displayWidth={40} radius="var(--radius-artwork-small)" />
        {/* The ✕ over the artwork's top-left, on hover. */}
        <button
          className="absolute -top-1.5 -left-1.5 hidden size-[22px] place-items-center rounded-full bg-thick-material text-primary shadow-[0_1px_3px_#0004] [backdrop-filter:blur(10px)] group-hover/row:grid"
          aria-label={`Remove ${track.title}`}
          onClick={() => removeAt(at)}
        >
          <X size={12} strokeWidth={3} />
        </button>
      </div>
      <button className="flex min-w-0 flex-col text-start" onClick={() => jumpTo(at)} aria-label={`Play ${track.title}`}>
        <span className="truncate type-body text-primary">{track.title}</span>
        <span className="truncate type-body text-secondary">{track.artists.map((a) => a.name).join(', ')}</span>
      </button>
      <div className="relative grid items-center justify-items-end">
        <span className="type-body tabular-nums text-secondary group-hover/row:invisible group-has-[[data-open]]/row:invisible [@media(hover:none)]:invisible">{formatDuration(track.durationSec)}</span>
        <button
          // Always there on a touch screen, where there's no hover and no dragging.
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
          <MenuItem onSelect={() => (close(), jumpTo(at))}>Play Now</MenuItem>
          {!first && (
            <MenuItem
              icon={<ListStart size={15} />}
              onSelect={() => {
                close();
                removeAt(at);
                playNext([track], entry.source);
                toast(`Playing next: ${track.title}`);
              }}
            >
              Play Next
            </MenuItem>
          )}
          {(!first || !last) && <MenuSeparator />}
          {!first && (
            <MenuItem icon={<ArrowUp size={15} />} onSelect={() => (close(), move(at, at - 1))}>
              Move Up
            </MenuItem>
          )}
          {!last && (
            <MenuItem icon={<ArrowDown size={15} />} onSelect={() => (close(), move(at, at + 1))}>
              Move Down
            </MenuItem>
          )}
          <MenuSeparator />
          <MenuItem destructive onSelect={() => (close(), removeAt(at))}>
            Remove from Up Next
          </MenuItem>
        </Menu>
      )}
    </li>
  );
}

/** One run of rows (before AutoPlay's section, or in it): dragging stays within it. */
function Section({ items, firstAt, lastAt }: { items: { entry: QueueEntry; at: number }[]; firstAt: number; lastAt: number }) {
  const move = usePlayer((s) => s.move);
  const onMove = useCallback((from: number, to: number) => move(items[from]!.at, items[to]!.at), [items, move]);
  const reorder = useReorder({ count: items.length, rowHeight: ROW_HEIGHT, onMove });
  return (
    <ol className={list}>
      {items.map(({ entry, at }, i) => (
        <Row
          key={entry.entryId}
          entry={entry}
          at={at}
          first={at === firstAt}
          last={at === lastAt}
          dragging={reorder.isDragging(i)}
          style={reorder.rowStyle(i)}
          handle={reorder.handleProps(i, `Reorder ${entry.track.title}. Use the arrow keys to move it.`)}
        />
      ))}
    </ol>
  );
}

/** Apple's Up Next (report §7), over the three-tier queue: what's next, then AutoPlay's section. */
export function UpNext() {
  const queue = usePlayer((s) => s.queue);
  const index = usePlayer((s) => s.index);
  const autoplay = usePlayer((s) => s.autoplay);
  const repeat = usePlayer((s) => s.repeat);
  const crossfadeSec = usePlayer((s) => s.crossfadeSec);
  const { clearUpNext, setAutoplay, setCrossfade, toggleShuffle } = usePlayer.getState();
  const sleep = useSleepTimer();
  const sleeping = sleepLabel(sleep.endsAt, sleep.endOfSong);
  const shuffle = usePlayer((s) => s.shuffle);
  const [anchor, setAnchor] = useState<HTMLElement | null>(null);
  const close = useCallback(() => setAnchor(null), []);

  const tiers = queue.map((e) => e.tier);
  const autoplayAt = autoplaySectionStart(tiers, index);
  const canClear = upcomingUserQueueIndices(tiers, index).length > 0;
  const upcoming = queue.map((entry, at) => ({ entry, at })).filter(({ at }) => at > index);
  const beforeAutoplay = upcoming.filter(({ at }) => at < autoplayAt);
  const afterAutoplay = upcoming.filter(({ at }) => at >= autoplayAt);
  const playingFrom = queue[index]?.source?.title;

  const firstAt = upcoming[0]?.at ?? -1;
  const lastAt = upcoming[upcoming.length - 1]?.at ?? -1;
  const rows = (items: typeof upcoming) => <Section items={items} firstAt={firstAt} lastAt={lastAt} />;

  return (
    <>
      <div className="flex items-center gap-1.5 px-5 pt-[23px] pb-3">
        <h3 className="flex-1 type-title-2-emphasized">Up Next</h3>
        {upcoming.length > 1 && (
          <button
            className={square}
            aria-label="Shuffle"
            title={shuffle ? 'Shuffle is on: Up Next is in shuffled order' : 'Shuffle Up Next'}
            aria-pressed={shuffle}
            onClick={toggleShuffle}
          >
            <Shuffle size={15} strokeWidth={2.2} />
          </button>
        )}
        {canClear && (
          <button className="mr-1 type-title-3 text-key" onClick={clearUpNext}>
            Clear
          </button>
        )}
        <button
          className={square}
          aria-label="AutoPlay"
          title="AutoPlay: keep playing similar music when the queue ends"
          aria-pressed={autoplay}
          onClick={() => setAutoplay(!autoplay)}
        >
          <InfinityIcon size={16} strokeWidth={2.4} />
        </button>
        <button
          className={square}
          aria-label={sleeping ? `Playback options, sleep timer ${sleeping}` : 'Playback options'}
          aria-haspopup="menu"
          aria-pressed={!!sleeping}
          title={sleeping ? `Sleep timer: ${sleeping}` : undefined}
          onClick={(e) => setAnchor(e.currentTarget)}
        >
          {sleeping ? <Moon size={15} /> : <MoreHorizontal size={16} />}
        </button>
        {anchor && (
          <Menu anchor={anchor} onClose={close} label="Playback options">
            <MenuHeader>Crossfade</MenuHeader>
            {CROSSFADE_CHOICES.map((seconds) => (
              <MenuItem key={seconds} checked={crossfadeSec === seconds} onSelect={() => (close(), setCrossfade(seconds))}>
                {seconds === 0 ? 'Off' : `${seconds} seconds`}
              </MenuItem>
            ))}
            <MenuSeparator />
            <MenuHeader>{sleeping ? `Sleep Timer · ${sleeping}` : 'Sleep Timer'}</MenuHeader>
            <MenuItem checked={!sleeping} onSelect={() => (close(), sleep.set(null))}>
              Off
            </MenuItem>
            {SLEEP_CHOICES.map((minutes) => (
              <MenuItem key={minutes} onSelect={() => (close(), sleep.set(minutes), toast(`Pausing in ${minutes} minutes`))}>
                {minutes} minutes
              </MenuItem>
            ))}
            <MenuItem checked={sleep.endOfSong} onSelect={() => (close(), sleep.set('end'), toast('Pausing after this song'))}>
              End of This Song
            </MenuItem>
          </Menu>
        )}
      </div>
      {playingFrom && <p className="truncate px-5 pb-2 type-callout text-secondary">Playing from {playingFrom}</p>}

      {upcoming.length === 0 ? (
        <div className="flex flex-1 flex-col items-center justify-center gap-3 px-6 pb-20 text-center type-callout text-secondary">
          <p className="type-callout-emphasized">Nothing in Up Next</p>
          <p>
            {autoplay && repeat === 'off' && index >= 0
              ? 'AutoPlay is finding similar music.'
              : 'Songs you play next appear here.'}
          </p>
        </div>
      ) : (
        <div className="min-h-0 flex-1 overflow-y-auto px-2 pb-[120px]">
          {rows(beforeAutoplay)}
          {afterAutoplay.length > 0 && (
            <>
              <h4 className="mx-2.5 mt-[18px] mb-0.5 flex items-center gap-1.5 type-callout-emphasized">
                <InfinityIcon size={14} strokeWidth={2.4} /> AutoPlay
              </h4>
              <p className="mx-2.5 mb-2 type-callout text-secondary">Similar music, from YouTube Music’s radio.</p>
              {rows(afterAutoplay)}
            </>
          )}
        </div>
      )}
    </>
  );
}

