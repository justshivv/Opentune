import { Radio, Shuffle } from 'lucide-react';
import { useEffect, useMemo, useRef, useState, type CSSProperties } from 'react';
import { useParams } from 'react-router';
import { PlayIcon } from '@/components/icons/Transport';
import { Artwork } from '@/components/music/Artwork';
import { DetailBar } from '@/components/music/DetailBar';
import { browseHref } from '@/components/music/links';
import { playList, shuffleList } from '@/components/music/play';
import { usePlayer } from '@/state/player';
import { Shelves, TrackShelf } from '@/components/music/ShelfView';
import { LoadError, NeedsExtension } from '@/components/music/States';
import { requestAppleAccess, useAppleArtistLookup, useUngrantedAppleOrigins, type AppleArtistArt } from '@/data/apple/artistArt';
import { useArtist, useYouTubeReady } from '@/data/music/queries';
import type { ArtworkColor } from '@/lib/artworkColor';
import { usePageTint } from '@/lib/usePageTint';
import { ArtistSkeleton } from '@/skeletons/PageSkeletons';

/** The smaller round buttons either side of Play, glass over the photograph. */
const heroSide =
  'ot-press grid size-10 place-items-center rounded-[999px] bg-[#0000004d] text-white [backdrop-filter:blur(20px)_saturate(1.6)] transition-[scale,background-color] disabled:opacity-40 enabled:hover:bg-[#00000066]';

function luminance(color: string): number {
  const [r, g, b] = [1, 3, 5].map((i) => parseInt(color.slice(i, i + 2), 16) / 255);
  return 0.2126 * r! + 0.7152 * g! + 0.0722 * b!;
}

/**
 * The name, or Apple's title logo in its place (AppleArtistArt.kt). Until the
 * logo has drawn, the name stands in, so the header is never left empty; a
 * logo that fails to load leaves the name.
 */
function ArtistName({ name, apple, heading }: { name: string; apple: AppleArtistArt | null; heading: React.RefObject<HTMLHeadingElement | null> }) {
  const logo = apple?.logoUrl ?? null;
  const [shown, setShown] = useState<string | null>(null);
  const logoShown = !!logo && shown === logo;
  return (
    <h1
      ref={heading}
      className="type-header-emphasized text-[clamp(34px,5vw,64px)] leading-[1.05] [text-shadow:0_2px_20px_#00000059] data-logo:w-full data-logo:[text-shadow:none]"
      data-logo={logoShown || undefined}
    >
      {!logoShown && name}
      {logo && (
        <img
          key={logo}
          // Apple's title logo in place of the name: at most 82% of the page's width and 30% of the
          // photo's height (the photo is the column's width over 1.93, and never taller than 70vh).
          className="mx-auto mt-0 mb-1.5 block h-auto max-h-[min(calc((100vw-var(--web-navigation-width))/1.93*0.3),21vh)] w-auto max-w-[min(82%,640px)] [filter:drop-shadow(0_2px_16px_#00000040)]"
          src={logo}
          alt={logoShown ? name : ''}
          hidden={!logoShown}
          // Its proportions are known before it decodes; the CSS caps its size.
          style={{ aspectRatio: String(apple!.logoAspect) }}
          decoding="async"
          onLoad={() => setShown(logo)}
        />
      )}
    </h1>
  );
}

/**
 * An artist (report §4): full-bleed hero with the name and Play, Top Songs,
 * then shelves. Where Apple Music has the artist, the page takes Apple's
 * header as the phone does: its photograph, its title logo over it, its
 * colour behind the whole page, and its colour on the Play button.
 */
/** How long a page waits for Apple's header before settling on YouTube's for this visit. */
const APPLE_WAIT_MS = 2000;

/**
 * Which header the page shows, decided once per visit: Apple's, when its lookup answers within
 * APPLE_WAIT_MS of the artist being known and its photograph has loaded, or else YouTube's.
 * Undefined while deciding. Drawing YouTube's photo and then swapping in Apple's, with its
 * colour arriving after, made the page change under the eye; now photo, logo and colour come
 * together. A later visit finds Apple's answer remembered and decides at once.
 */
function useHeaderChoice(lookup: { art: AppleArtistArt | null; settled: boolean }, known: boolean) {
  const [choice, setChoice] = useState<{ apple: AppleArtistArt | null }>();
  const decided = choice !== undefined;
  useEffect(() => {
    if (decided || !known) return;
    const timer = setTimeout(() => setChoice((c) => c ?? { apple: null }), APPLE_WAIT_MS);
    return () => clearTimeout(timer);
  }, [decided, known]);
  useEffect(() => {
    if (decided || !lookup.settled) return;
    const art = lookup.art;
    if (!art) {
      setChoice((c) => c ?? { apple: null });
      return;
    }
    let live = true;
    // The photograph must load for Apple's header to be used; the title logo is only fetched ahead.
    const load = (url: string) =>
      new Promise<void>((resolve, reject) => {
        const image = new Image();
        image.onload = () => resolve();
        image.onerror = () => reject(new Error(url));
        image.src = url;
      });
    if (art.logoUrl) void load(art.logoUrl).catch(() => {});
    load(art.heroUrl).then(
      () => live && setChoice((c) => c ?? { apple: art }),
      () => live && setChoice((c) => c ?? { apple: null }),
    );
    return () => {
      live = false;
    };
  }, [decided, lookup.settled, lookup.art]);
  return choice;
}

export default function ArtistPage() {
  const id = decodeURIComponent(useParams().id ?? '');
  const { missing } = useYouTubeReady();
  const artist = useArtist(id);
  const header = useHeaderChoice(useAppleArtistLookup(artist.data?.name), !!artist.data);
  const apple = header?.apple ?? null;
  const appleUngranted = useUngrantedAppleOrigins();
  const tint = useMemo<ArtworkColor | null>(
    () => (apple ? { background: apple.background, dark: luminance(apple.background) < 0.6 } : null),
    [apple],
  );
  usePageTint(tint);
  const heading = useRef<HTMLHeadingElement>(null);

  if (missing) return <NeedsExtension />;
  if (artist.isError) return <LoadError error={artist.error} onRetry={() => void artist.refetch()} />;
  if (!artist.data) return <ArtistSkeleton />;

  const data = artist.data;
  const topSongs = { title: `${data.name}: Top Songs`, type: 'artist' as const, id: data.id };
  const play = () => playList(data.topSongs, 0, { source: topSongs });
  return (
    <>
      {/* Back, and once the name scrolls away, the artist's photo, name and Play along the top. */}
      <DetailBar
        title={data.name}
        thumbnail={apple?.heroUrl ?? data.heroImage}
        watch={heading}
        onPlay={play}
        canPlay={data.topSongs.length > 0}
        round
        overPhoto
      />
      {/* The hero (report §4): full-bleed, ~1.93:1 of the content column, the name and actions centred near the bottom. */}
      <header className="relative max-h-[70vh] overflow-hidden">
        <div className="ot-parallax relative w-full">
          {/* Decided once (useHeaderChoice): Apple's photograph, already loaded, or YouTube's. Until then the frame's own colour. */}
          <Artwork src={header && !apple ? data.heroImage : null} displayWidth={1200} aspect={1165 / 604} eager />
          {apple && (
            <img
              key={apple.heroUrl}
              className="ot-hero-in absolute inset-0 size-full object-cover"
              src={apple.heroUrl}
              alt=""
              decoding="async"
            />
          )}
        </div>
        {/* The image melts into the page at the bottom (Apple's masked blur frame), into Apple's colour when the page is tinted with it. */}
        <div
          className="pointer-events-none absolute inset-0 bg-[linear-gradient(180deg,transparent_45%,color-mix(in_srgb,var(--page-tint,var(--pageBG))_70%,transparent)_80%,var(--page-tint,var(--pageBG))_100%)]"
          aria-hidden="true"
        />
        <div
          className={`ot-detail-text absolute inset-x-0 bottom-6 flex flex-col items-center gap-1.5 px-(--body-gutter) text-center transition-opacity duration-300 ${header ? '' : 'opacity-0'}`}
        >
          <ArtistName name={data.name} apple={apple} heading={heading} />
          {data.audience && <p className="type-callout-emphasized text-secondary">{data.audience}</p>}
          {/* Shuffle · Play · Radio: the artist's top songs in order or mixed, or a station built from them. */}
          <div className="mt-2.5 flex items-center gap-4">
            <button
              className={heroSide}
              aria-label={`Shuffle ${data.name}`}
              title="Shuffle"
              disabled={!data.topSongs.length}
              onClick={() => shuffleList(data.topSongs, { source: topSongs })}
            >
              <Shuffle size={18} strokeWidth={2.2} />
            </button>
            <button
              // The 56px key-colour play button.
              className="ot-press grid size-14 place-items-center rounded-full bg-(--play-fill,var(--keyColorBG)) text-(--play-glyph,#fff) shadow-[0_10px_28px_-10px_#000a,inset_0_0_2px_#ffffff1a] disabled:opacity-40 enabled:hover:brightness-110"
              // Apple's fill for the Play circle, with the triangle in the page's colour,
              // as the phone cuts it out of the circle; white without a key colour.
              style={
                apple
                  ? ({ '--play-fill': apple.keyColor ?? '#ffffff', '--play-glyph': apple.background } as CSSProperties)
                  : undefined
              }
              aria-label={`Play ${data.name}`}
              disabled={!data.topSongs.length}
              onClick={play}
            >
              <PlayIcon size={26} />
            </button>
            <button
              className={heroSide}
              aria-label={`${data.name} Radio`}
              title="Radio"
              disabled={!data.topSongs.length}
              onClick={() => {
                const player = usePlayer.getState();
                // The top song, then AutoPlay keeps the artist's sound going.
                if (!player.autoplay) player.setAutoplay(true);
                player.playTrack(data.topSongs[0]!, { title: `${data.name} Radio`, type: 'radio', id: data.id });
              }}
            >
              <Radio size={18} strokeWidth={2.2} />
            </button>
          </div>
          {!apple && appleUngranted.length > 0 && (
            // The lyrics panel's permission notice, as a pill over the photograph.
            <div className="mt-3 flex items-center gap-2.5 rounded-[999px] bg-[#0000004d] py-1.5 pr-2 pl-3.5 type-callout text-[#ffffffcc] [backdrop-filter:blur(20px)_saturate(1.6)]">
              <span>Apple Music artist art needs the extension’s permission.</span>
              <button
                className="flex-none rounded-[999px] bg-[#ffffff26] px-2.5 py-1 type-callout-emphasized text-white hover:bg-[#ffffff40]"
                // Stays until the extension confirms the grant (checked when this tab comes back).
                onClick={requestAppleAccess}
              >
                Allow
              </button>
            </div>
          )}
        </div>
      </header>

      {data.topSongs.length > 0 && (
        <TrackShelf
          title="Top Songs"
          to={data.topSongsSeeAll ? browseHref(data.topSongsSeeAll) : null}
          tracks={data.topSongs}
          source={topSongs}
        />
      )}

      <Shelves shelves={data.shelves} />

      {data.description && (
        <section className="mx-(--body-gutter) mt-3 mb-10" aria-label={`About ${data.name}`}>
          <h2 className="type-title-2-emphasized">About {data.name}</h2>
          <p className="mt-2 max-w-[720px] type-body-tall whitespace-pre-line text-secondary">{data.description}</p>
        </section>
      )}
    </>
  );
}
