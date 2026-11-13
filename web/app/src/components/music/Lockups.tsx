import { PrefetchLink } from '@/components/PrefetchLink';
import { PlayIcon } from '@/components/icons/Transport';
import type { MusicItem } from '@/data/music/types';
import { Artwork } from './Artwork';
import { ExplicitBadge } from './ExplicitBadge';
import { artistHref, hrefFor } from './links';
import { playTrack } from './play';
import { TrackMenu, useTrackContextMenu } from './TrackMenu';

const isPlayable = (item: MusicItem) => item.kind === 'song' || item.kind === 'video';

/* Lockups (report §2). */
const lockup = 'flex min-w-0 flex-col';
const caption = 'mt-2 flex min-w-0 flex-col gap-px';
const title = 'flex min-w-0 items-center type-callout [&>:first-child]:truncate';
const subtitle = 'truncate type-callout text-secondary';
const overlayLink = 'absolute inset-0 z-1 rounded-[inherit]';

/** Artist names as links where YouTube links them, else the given subtitle. */
function Byline({ item }: { item: MusicItem }) {
  const linked = item.artists.some((a) => a.id);
  if (!linked || item.kind === 'album' || item.kind === 'playlist') return <>{item.subtitle}</>;
  return (
    <>
      {item.artists.map((artist, i) => {
        const href = artistHref(artist.id);
        return (
          <span key={`${artist.name}-${i}`}>
            {i > 0 && ', '}
            {href ? (
              <PrefetchLink to={href} className="hover:underline">
                {artist.name}
              </PrefetchLink>
            ) : (
              artist.name
            )}
          </span>
        );
      })}
    </>
  );
}

function PlayPlatter({ item }: { item: MusicItem }) {
  return (
    <button
      className="ot-platter pointer-events-auto absolute right-2.5 bottom-2.5 grid size-10 place-items-center rounded-full bg-key-bg text-white shadow-[0_6px_18px_rgb(0_0_0/0.35)]"
      aria-label={`Play ${item.title}`}
      onClick={(e) => {
        e.preventDefault();
        e.stopPropagation();
        playTrack(item);
      }}
    >
      <PlayIcon size={18} />
    </button>
  );
}

/**
 * The artwork, linking to the item or playing it. On hover it lifts, the cover
 * zooms a little inside its frame, a soft shade rises from the bottom and the
 * play disc comes up with it (.ot-lockup-art in opentune.css).
 */
function LockupArtwork({ item, width, aspect }: { item: MusicItem; width: number; aspect: number }) {
  const href = hrefFor(item);
  return (
    <div className="ot-lockup-art group/art relative rounded-artwork-md">
      <Artwork src={item.thumbnail} displayWidth={width} aspect={aspect} radius="var(--radius-artwork-medium)" />
      {href ? (
        <PrefetchLink to={href} className={overlayLink} aria-label={item.title} />
      ) : (
        <button className={overlayLink} aria-label={`Play ${item.title}`} onClick={() => playTrack(item)} />
      )}
      <div className="ot-lockup-shade pointer-events-none absolute inset-0 z-2 rounded-[inherit]">{isPlayable(item) && <PlayPlatter item={item} />}</div>
    </div>
  );
}

/** Square lockup (report §2): artwork, hover scrim with play platter, title and subtitle. */
/** The ⋯ button beside a playable card's title: on hover and focus, always on touch screens. */
const cardMenu =
  'ms-auto grid size-6 flex-none place-items-center rounded-full text-secondary opacity-0 transition-opacity hover:bg-quinary hover:text-primary focus-visible:opacity-100 group-hover/card:opacity-100 data-open:opacity-100 [@media(hover:none)]:opacity-100';

export function SquareLockup({ item, width = 220 }: { item: MusicItem; width?: number }) {
  const href = hrefFor(item);
  const playable = isPlayable(item);
  const context = useTrackContextMenu(playable ? item : null);
  return (
    <div className={`${lockup} group/card`} onContextMenu={playable ? context.onContextMenu : undefined}>
      <LockupArtwork item={item} width={width} aspect={1} />
      <div className={caption}>
        <div className={title}>
          {href ? (
            <PrefetchLink to={href} className="hover:underline">
              {item.title}
            </PrefetchLink>
          ) : (
            <span>{item.title}</span>
          )}
          {item.explicit && <ExplicitBadge />}
          {playable && <TrackMenu track={item} className={cardMenu} />}
        </div>
        <div className={subtitle}>
          <Byline item={item} />
        </div>
      </div>
      {context.menu}
    </div>
  );
}

/** 16:9 video lockup (report §2, type C). */
export function VideoLockup({ item, width = 300 }: { item: MusicItem; width?: number }) {
  const context = useTrackContextMenu(item);
  return (
    <div className={`${lockup} group/card`} onContextMenu={context.onContextMenu}>
      <LockupArtwork item={item} width={width} aspect={16 / 9} />
      <div className={caption}>
        <div className={title}>
          <span>{item.title}</span>
          {item.explicit && <ExplicitBadge />}
          <TrackMenu track={item} className={cardMenu} />
        </div>
        <div className={subtitle}>{item.subtitle}</div>
      </div>
      {context.menu}
    </div>
  );
}

/** Circular artist lockup (report §2, type H). */
export function CircleLockup({ item, width = 160 }: { item: MusicItem; width?: number }) {
  return (
    <PrefetchLink to={hrefFor(item) ?? '#'} className="ot-circle-lockup group/circle flex min-w-0 flex-col items-center gap-2.5">
      <Artwork src={item.thumbnail} displayWidth={width} radius="50%" />
      <span className="max-w-full truncate text-center type-body group-hover/circle:underline">{item.title}</span>
    </PrefetchLink>
  );
}

/** A song row inside a shelf (report §2, track lockup): 40px art, title, byline. */
export function TrackLockup({ item, onPlay }: { item: MusicItem; onPlay?: () => void }) {
  const play = onPlay ?? (() => playTrack(item));
  const context = useTrackContextMenu(item);
  return (
    // 52px art column (40 + 12 gap), content, the ⋯ button; 55px tall.
    <div
      className="ot-track-lockup group/track grid h-[55px] grid-cols-[40px_minmax(0,1fr)_auto] items-center gap-x-3 py-[7.5px] pr-2 pl-0"
      onContextMenu={context.onContextMenu}
    >
      <button className="group/art relative w-10 rounded-artwork-sm" aria-label={`Play ${item.title}`} onClick={play}>
        <Artwork src={item.thumbnail} displayWidth={40} radius="var(--radius-artwork-small)" />
        <span
          className="absolute inset-0 grid place-items-center rounded-[inherit] bg-lockup-hover text-white opacity-0 transition-opacity duration-200 ease-out group-hover/track:opacity-100 group-focus-visible/art:opacity-100"
          aria-hidden="true"
        >
          <PlayIcon size={16} />
        </span>
      </button>
      <div className="flex min-w-0 flex-col gap-px px-1">
        <div className="flex min-w-0 items-center type-body">
          <button className="min-w-0 truncate text-left" onClick={play}>
            {item.title}
          </button>
          {item.explicit && <ExplicitBadge />}
        </div>
        <div className="truncate type-callout text-secondary">
          <Byline item={item} />
        </div>
      </div>
      <TrackMenu
        track={item}
        className="grid size-7 place-items-center rounded-full text-key opacity-0 transition-opacity hover:bg-quinary focus-visible:opacity-100 group-hover/track:opacity-100 data-open:opacity-100 [@media(hover:none)]:opacity-100"
      />
      {context.menu}
    </div>
  );
}
