import { Check, ChevronLeft, Link2, ListEnd, ListStart, MoreHorizontal, Plus, Shuffle } from 'lucide-react';
import { useEffect, useRef, useState } from 'react';
import { useNavigate } from 'react-router';
import { Menu, MenuItem, MenuSeparator } from '@/components/Menu';
import { PrefetchLink } from '@/components/PrefetchLink';
import { toast } from '@/components/Toast';
import { PlayIcon } from '@/components/icons/Transport';
import { useSetListSaved, useSignedIn } from '@/data/music/queries';
import type { AppleMotion } from '@/data/apple/motionArt';
import type { HeaderInfo } from '@/data/music/types';
import { Artwork } from './Artwork';
import { ExplicitBadge } from './ExplicitBadge';
import { MotionArtwork } from './MotionArtwork';
import { artistHref } from './links';
import { sizedArtwork } from '@/lib/artwork';

interface DetailHeaderProps {
  info: HeaderInfo;
  onPlay: () => void;
  onShuffle: () => void;
  canPlay: boolean;
  /** More actions after the library toggle (a Spotify playlist's Import). */
  extra?: React.ReactNode;
  /** Apple's motion artwork for an album, over the cover once it plays. */
  motion?: AppleMotion | null;
  /** The ⋯ menu's Play Next and Add to Queue, for the whole list. */
  onQueue?: (next: boolean) => void;
  /** The list's YouTube Music link, for Copy Link. */
  shareUrl?: string;
}

/** ⋯ after Play: the whole album or playlist into the queue, or its link. */
function ListMenu({ title, onQueue, shareUrl, disabled }: { title: string; onQueue?: (next: boolean) => void; shareUrl?: string; disabled: boolean }) {
  const [anchor, setAnchor] = useState<HTMLElement | null>(null);
  const close = () => setAnchor(null);
  return (
    <>
      <button
        className={detailButton.circle}
        aria-label={`More options for ${title}`}
        aria-haspopup="menu"
        aria-expanded={anchor !== null}
        disabled={disabled}
        onClick={(e) => setAnchor(anchor ? null : e.currentTarget)}
      >
        <MoreHorizontal size={18} strokeWidth={2.2} />
      </button>
      {anchor && (
        <Menu anchor={anchor} onClose={close} label={title}>
          {onQueue && (
            <>
              <MenuItem icon={<ListStart size={15} />} onSelect={() => { close(); onQueue(true); }}>
                Play Next
              </MenuItem>
              <MenuItem icon={<ListEnd size={15} />} onSelect={() => { close(); onQueue(false); }}>
                Add to Queue
              </MenuItem>
            </>
          )}
          {onQueue && shareUrl && <MenuSeparator />}
          {shareUrl && (
            <MenuItem
              icon={<Link2 size={15} />}
              onSelect={() => {
                close();
                navigator.clipboard.writeText(shareUrl).then(() => toast('Link copied'), () => toast('Couldn’t copy the link'));
              }}
            >
              Copy Link
            </MenuItem>
          )}
        </Menu>
      )}
    </>
  );
}

/** Shuffle (circle) · Play (pill), report §14; also the Spotify playlist's Import button. */
export const detailButton = {
  pill: 'ot-press inline-flex h-10 min-w-[150px] items-center justify-center gap-1.5 rounded-[24px] bg-pill-bg px-4 type-title-3-emphasized text-pill shadow-[0_8px_24px_-8px_rgb(0_0_0/0.45)] transition-[scale,filter] disabled:opacity-40 enabled:hover:brightness-110',
  circle:
    'ot-press grid size-10 place-items-center rounded-full border-[0.75px] border-quaternary bg-quinary text-primary backdrop-blur-xl transition-[scale,background-color] disabled:opacity-40 enabled:hover:bg-quaternary',
};

/**
 * Album and playlist header (report §3, §14): artwork with its glow, title,
 * artist, metadata, a three-line description, then Shuffle · Play.
 */
export function DetailHeader({ info, onPlay, onShuffle, canPlay, extra, motion, onQueue, shareUrl }: DetailHeaderProps) {
  const [expanded, setExpanded] = useState(false);
  const signedIn = useSignedIn();
  const setSaved = useSetListSaved();
  const library = signedIn ? info.library : null;
  const title = useRef<HTMLHeadingElement>(null);
  return (
    <>
    <DetailBar info={info} title={title} onPlay={onPlay} canPlay={canPlay} />
    {/* Artwork beside the headings, description and actions; stacked and centred below 768px. */}
    <div className="ot-detail relative isolate mb-10 px-(--body-gutter)">
      {info.thumbnail && <CoverWash src={info.thumbnail} />}
      <div className="grid grid-cols-[auto_minmax(0,1fr)] grid-rows-[auto_1fr_auto] gap-x-[34px] pt-14 [grid-template-areas:'artwork_headings'_'artwork_description'_'artwork_actions'] max-md:grid-cols-[1fr] max-md:grid-rows-[auto] max-md:justify-items-center max-md:pt-12 max-md:text-center max-md:[grid-template-areas:'artwork'_'headings'_'actions'_'description']">
        <div className="ot-detail-cover relative w-[270px] self-start rounded-artwork-lg shadow-[0_24px_60px_-12px_var(--radiosityShadowColor)] [grid-area:artwork] max-lg:w-[220px] max-md:w-[min(270px,70vw)]">
          {info.thumbnail && (
            // A blurred copy behind the artwork: Apple's radiosity glow.
            <div className="absolute inset-0 -z-1 scale-88 opacity-30 blur-[20px] saturate-200" aria-hidden="true">
              <Artwork src={info.thumbnail} displayWidth={80} radius="var(--radius-artwork-large)" />
            </div>
          )}
          <Artwork src={info.thumbnail} displayWidth={270} radius="var(--radius-artwork-large)" eager />
          {/* Apple's motion artwork, over the cover once it's playing. */}
          {motion && (
            <MotionArtwork
              key={motion.url}
              motion={motion}
              className="absolute inset-0 size-full rounded-artwork-lg object-cover opacity-0 transition-opacity duration-600 ease-in data-ready:opacity-100"
            />
          )}
        </div>
        <div className="ot-detail-text flex flex-col pt-[37px] [grid-area:headings] max-md:items-center max-md:pt-5">
          <h1 ref={title} className="flex items-center type-large-title-emphasized leading-[30px] max-md:justify-center">
            {info.title}
            {info.explicit && <ExplicitBadge size={16} />}
          </h1>
          {info.artists.length > 0 && (
            <div className="type-large-title leading-[30px] text-primary">
              {info.artists.map((artist, i) => {
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
            </div>
          )}
          <div className="mt-1 type-callout-emphasized text-secondary">{[info.subtitle, info.meta].filter(Boolean).join(' · ')}</div>
        </div>
        {info.description && (
          <div className="mt-[38px] flex max-w-[440px] items-end type-body-tall text-secondary [grid-area:description] max-md:mt-5">
            <p className={expanded ? 'min-w-0' : 'min-w-0 line-clamp-3'}>
              {info.description}
            </p>
            {!expanded && info.description.length > 180 && (
              <button className="shrink-0 ps-[5px] type-subhead-emphasized text-primary" onClick={() => setExpanded(true)}>
                MORE
              </button>
            )}
          </div>
        )}
        <div className="ot-detail-actions mt-3.5 flex items-center gap-3.5 self-end [grid-area:actions]">
          <button className={detailButton.circle} aria-label="Shuffle" onClick={onShuffle} disabled={!canPlay}>
            <Shuffle size={18} strokeWidth={2.2} />
          </button>
          <button className={detailButton.pill} onClick={onPlay} disabled={!canPlay}>
            <PlayIcon size={18} />
            <span>Play</span>
          </button>
          {library && (
            <button
              className={detailButton.circle}
              aria-label={library.saved ? 'Remove from Library' : 'Add to Library'}
              aria-pressed={library.saved}
              title={library.saved ? 'In your library' : 'Add to Library'}
              disabled={setSaved.isPending}
              onClick={() => setSaved.mutate({ playlistId: library.playlistId, saved: !library.saved })}
            >
              {library.saved ? <Check size={18} strokeWidth={2.4} /> : <Plus size={18} strokeWidth={2.4} />}
            </button>
          )}
          {(onQueue || shareUrl) && <ListMenu title={info.title} onQueue={onQueue} shareUrl={shareUrl} disabled={!canPlay && !shareUrl} />}
          {extra}
        </div>
      </div>
    </div>
    </>
  );
}

/**
 * The bar along the top of an album or playlist page, as in the phone app: Back at its left from
 * the start, and once the page's title has scrolled up under it, a frosted strip of the page's
 * colour with the cover, the title and Play, so the list being looked at stays named.
 */
function DetailBar({ info, title, onPlay, canPlay }: { info: HeaderInfo; title: React.RefObject<HTMLHeadingElement | null>; onPlay: () => void; canPlay: boolean }) {
  const navigate = useNavigate();
  const bar = useRef<HTMLDivElement>(null);
  const [stuck, setStuck] = useState(false);
  useEffect(() => {
    const page = document.getElementById('scrollable-page');
    if (!page) return;
    // This bar has its own glass; the page's top-edge blur would only smear it.
    page.dataset.ownBar = '';
    let frame = 0;
    const mark = () => {
      frame = 0;
      const heading = title.current?.getBoundingClientRect();
      const strip = bar.current?.getBoundingClientRect();
      if (heading && strip) setStuck(heading.bottom <= strip.bottom);
    };
    const onScroll = () => { if (!frame) frame = requestAnimationFrame(mark); };
    mark();
    page.addEventListener('scroll', onScroll, { passive: true });
    return () => {
      page.removeEventListener('scroll', onScroll);
      cancelAnimationFrame(frame);
      delete page.dataset.ownBar;
    };
  }, [title]);
  // Back where the visitor came from; a page opened straight from a link goes Home instead.
  const back = () => ((window.history.state as { idx?: number } | null)?.idx ? navigate(-1) : navigate('/home'));
  return (
    <div ref={bar} className="ot-detail-bar" data-stuck={stuck || undefined}>
      <button className="ot-detail-back" aria-label="Back" title="Back" onClick={back}>
        <ChevronLeft size={22} strokeWidth={2.4} />
      </button>
      <div className="ot-detail-bar-title" aria-hidden={!stuck}>
        {info.thumbnail && (
          <span className="size-8 shrink-0">
            <Artwork src={info.thumbnail} displayWidth={32} radius="6px" />
          </span>
        )}
        <span className="min-w-0 truncate type-title-3-emphasized">{info.title}</span>
      </div>
      <button className="ot-detail-bar-play" aria-label={`Play ${info.title}`} tabIndex={stuck ? 0 : -1} aria-hidden={!stuck} onClick={onPlay} disabled={!canPlay}>
        <PlayIcon size={15} />
      </button>
    </div>
  );
}

/**
 * The cover, blurred far past recognition and stretched across the top of the
 * page, fading into the page's tint below the header: the album's colours
 * without a hard band. A 60px copy is plenty for a blur this wide.
 */
function CoverWash({ src }: { src: string }) {
  const [loaded, setLoaded] = useState(false);
  return (
    <div className="ot-wash" aria-hidden="true" data-loaded={loaded || undefined}>
      <img src={sizedArtwork(src, 60, 1)} alt="" referrerPolicy="no-referrer" decoding="async" draggable={false} onLoad={() => setLoaded(true)} />
    </div>
  );
}
