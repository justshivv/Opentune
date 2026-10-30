import { ChevronLeft, ChevronRight } from 'lucide-react';
import { useCallback, useEffect, useRef, useState, type ReactNode } from 'react';
import { PrefetchLink } from '@/components/PrefetchLink';
import { shelfHeader, shelfKinds, shelfList, shelfSection, type ShelfKind } from '@/components/shelf';
import type { MusicItem, Shelf, Track } from '@/data/music/types';
import type { QueueSource } from '@/playback/queue/timeline';
import { useLiquidGlass } from '@/lib/liquidGlass';
import { browseHref } from './links';
import { CircleLockup, SquareLockup, TrackLockup, VideoLockup } from './Lockups';
import { playList } from './play';

// Arrow buttons: round frosted discs at each end, only ≥1000px, rising in on shelf hover (.ot-arrow).
const arrow =
  'ot-arrow absolute top-1/2 z-3 hidden size-10 place-items-center rounded-full text-primary disabled:pointer-events-none lg:grid';

function renderItem(shelf: Shelf, item: MusicItem): ReactNode {
  switch (shelf.layout) {
    case 'tracks':
      return <TrackLockup item={item} />;
    case 'video':
      return <VideoLockup item={item} />;
    case 'circle':
      return <CircleLockup item={item} />;
    default:
      return <SquareLockup item={item} />;
  }
}

/**
 * A shelf's scrolling row with Apple's hover arrows (report §2), around any
 * list items: the shelves below, and the artist page's Top Songs.
 */
export function ShelfScroller({ kind, children }: { kind: ShelfKind; children: ReactNode }) {
  const list = useRef<HTMLUListElement>(null);
  const [edges, setEdges] = useState({ start: true, end: true });
  const back = useLiquidGlass<HTMLButtonElement>(14, 12);
  const forward = useLiquidGlass<HTMLButtonElement>(14, 12);

  const measure = useCallback(() => {
    const el = list.current;
    if (!el) return;
    setEdges({ start: el.scrollLeft <= 2, end: el.scrollLeft + el.clientWidth >= el.scrollWidth - 2 });
  }, []);

  useEffect(() => {
    measure();
    const el = list.current;
    if (!el) return;
    const observer = new ResizeObserver(measure);
    observer.observe(el);
    return () => observer.disconnect();
  }, [measure]);

  /** Pages by one viewport of columns, as Apple's arrows do. */
  const page = (direction: 1 | -1) => {
    const el = list.current;
    if (!el) return;
    const gutter = Number.parseFloat(getComputedStyle(el).paddingInlineStart) || 0;
    el.scrollBy({ left: direction * (el.clientWidth - gutter * 2), behavior: 'smooth' });
  };

  return (
    <div className="group/shelf relative">
      <button
        ref={back}
        className={`${arrow} left-3`}
        aria-label="Previous Page"
        disabled={edges.start}
        onClick={() => page(-1)}
      >
        <ChevronLeft size={22} strokeWidth={2.4} />
      </button>
      <ul ref={list} className={`ot-shelf ${shelfList} ${shelfKinds[kind]}`} onScroll={measure}>
        {children}
      </ul>
      <button
        ref={forward}
        className={`${arrow} right-3`}
        aria-label="Next Page"
        disabled={edges.end}
        onClick={() => page(1)}
      >
        <ChevronRight size={22} strokeWidth={2.4} />
      </button>
    </div>
  );
}

/** A shelf's title, a link with a chevron when there's a page behind it. */
function ShelfTitle({ title, to }: { title: string; to?: string | null }) {
  return (
    <div className={shelfHeader}>
      <h2 className="type-title-2-emphasized tracking-[-0.01em]">
        {to ? (
          <PrefetchLink to={to} className="group/title inline-flex items-center gap-0.5">
            {title}
            <ChevronRight size={18} strokeWidth={2.6} className="text-secondary transition-[translate,color] duration-200 ease-(--ot-ease-settle) group-hover/title:translate-x-0.5 group-hover/title:text-primary" />
          </PrefetchLink>
        ) : (
          title
        )}
      </h2>
    </div>
  );
}

export const trackItem = (track: Track): MusicItem => ({
  kind: 'song',
  id: track.videoId,
  title: track.title,
  subtitle: track.album?.name ?? '',
  artists: track.artists,
  album: track.album,
  thumbnail: track.thumbnail,
  wide: false,
  durationSec: track.durationSec,
  explicit: track.explicit,
});

/**
 * Songs in four rows, as the phone's Top Songs and Recently Played: any one
 * plays the whole shelf from there.
 */
export function TrackShelf({ title, to, tracks, source }: { title: string; to?: string | null; tracks: Track[]; source: QueueSource }) {
  return (
    <section className={shelfSection} aria-label={title}>
      <ShelfTitle title={title} to={to} />
      <ShelfScroller key={tracks.length} kind="tracks">
        {tracks.map((track, i) => (
          <li key={track.videoId}>
            <TrackLockup item={trackItem(track)} onPlay={() => playList(tracks, i, { source })} />
          </li>
        ))}
      </ShelfScroller>
    </section>
  );
}

/** A titled horizontal shelf (report §2): grid columns per breakpoint, scroll-snap, hover arrows. */
export function ShelfView({ shelf }: { shelf: Shelf }) {
  return (
    <section className={shelfSection} aria-label={shelf.title}>
      <ShelfTitle title={shelf.title} to={shelf.seeAll ? browseHref(shelf.seeAll) : null} />
      <ShelfScroller key={shelf.items.length} kind={shelf.layout}>
        {shelf.items.map((item, i) => (
          <li key={`${item.id}-${i}`}>{renderItem(shelf, item)}</li>
        ))}
      </ShelfScroller>
    </section>
  );
}

/** Shelves in order, each its own section. */
export function Shelves({ shelves }: { shelves: Shelf[] }) {
  return (
    <>
      {shelves.map((shelf, i) => (
        <ShelfView key={`${shelf.title}-${i}`} shelf={shelf} />
      ))}
    </>
  );
}
