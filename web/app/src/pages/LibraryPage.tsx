import { Shuffle } from 'lucide-react';
import { useEffect, useRef, useState } from 'react';
import { Navigate, useParams } from 'react-router';
import { PageHeader } from '@/components/PageHeader';
import { PlayIcon } from '@/components/icons/Transport';
import { detailButton } from '@/components/music/DetailHeader';
import { playList, shuffleList } from '@/components/music/play';
import { CircleLockup, SquareLockup, VideoLockup } from '@/components/music/Lockups';
import { EmptyState, LoadError, LoadMoreError, NeedsExtension } from '@/components/music/States';
import { TrackList } from '@/components/music/TrackList';
import { isLibrarySection, LIBRARY_SECTIONS } from '@/data/music/library';
import { useLibrary, useSignedIn, useYouTubeReady } from '@/data/music/queries';
import { useYtAuth } from '@/extension/useYtAuth';
import { PlainSkeleton } from '@/skeletons/PageSkeletons';
import { seeAllGrid, seeAllTracks } from './pageStyles';

/** Asks for the next page when [target] scrolls into view. */
function useLoadMore(onVisible: (() => void) | null) {
  const target = useRef<HTMLDivElement>(null);
  useEffect(() => {
    const node = target.current;
    if (!node || !onVisible) return;
    const observer = new IntersectionObserver((entries) => entries.some((e) => e.isIntersecting) && onVisible(), {
      rootMargin: '600px 0px',
    });
    observer.observe(node);
    return () => observer.disconnect();
  }, [onVisible]);
  return target;
}

/** A library section (prompt §5.3): Recently Played, Artists, Albums, Songs, Made for You, All Playlists. */
export default function LibraryPage() {
  const section = useParams().section ?? '';
  const { missing } = useYouTubeReady();
  const auth = useYtAuth();
  const signedIn = useSignedIn();
  const library = useLibrary(isLibrarySection(section) ? section : 'playlists');
  const [filter, setFilter] = useState('');
  const loadMore = useLoadMore(
    library.hasNextPage && !library.isFetchingNextPage && !library.isFetchNextPageError
      ? () => void library.fetchNextPage()
      : null,
  );

  if (!isLibrarySection(section)) return <Navigate to="/home" replace />;
  const title = LIBRARY_SECTIONS[section].title;
  if (missing) return <NeedsExtension what="your library" />;
  if (auth.state === 'ready' && !signedIn) {
    return (
      <>
        <PageHeader title={title} />
        <EmptyState
          title="Sign in to see your library"
          detail="OpenTune uses the YouTube Music account this browser is signed in to. Sign in on music.youtube.com, then come back."
        />
      </>
    );
  }
  if (library.isError && !library.data) return <LoadError error={library.error} onRetry={() => void library.refetch()} />;
  if (!library.data) return <PlainSkeleton />;

  const allGrid = library.data.pages.flatMap((page) => page.data.grid);
  const allTracks = library.data.pages.flatMap((page) => page.data.tracks);
  // Narrows what has loaded so far, by name, artist or album.
  const needle = filter.trim().toLowerCase();
  const matches = (...texts: (string | null | undefined)[]) => !needle || texts.some((t) => t?.toLowerCase().includes(needle));
  const grid = allGrid.filter((i) => matches(i.title, i.subtitle));
  const tracks = allTracks.filter((t) => matches(t.title, t.album?.name, ...t.artists.map((a) => a.name)));
  const source = { title, type: 'library' as const, id: section };
  return (
    <>
      <PageHeader title={title} />
      {(allGrid.length > 0 || allTracks.length > 0) && (
        <div className="mx-(--body-gutter) mt-4 flex flex-wrap items-center gap-3.5">
          {allTracks.length > 0 && (
            <>
              <button className={detailButton.circle} aria-label="Shuffle" onClick={() => shuffleList(tracks, { source })} disabled={!tracks.length}>
                <Shuffle size={18} strokeWidth={2.2} />
              </button>
              <button className={detailButton.pill} onClick={() => playList(tracks, 0, { source })} disabled={!tracks.length}>
                <PlayIcon size={18} />
                <span>Play</span>
              </button>
            </>
          )}
          <input
            type="search"
            className="ms-auto h-9 w-[min(260px,100%)] rounded-pill border border-search-border bg-page px-3.5 type-body text-primary placeholder:text-tertiary max-sm:ms-0 max-sm:w-full"
            placeholder={`Filter ${title}`}
            aria-label={`Filter ${title}`}
            value={filter}
            onChange={(e) => setFilter(e.target.value)}
          />
        </div>
      )}
      {!allGrid.length && !allTracks.length && <EmptyState title="Nothing here yet" />}
      {needle && !grid.length && !tracks.length && (
        <EmptyState title="No Matches" detail={`Nothing loaded so far matches “${filter.trim()}”.`} />
      )}
      {grid.length > 0 && (
        <ul className={seeAllGrid} data-wide={grid.every((i) => i.wide) || undefined}>
          {grid.map((item, i) => (
            <li key={`${item.id}-${i}`}>
              {item.kind === 'artist' ? (
                <CircleLockup item={item} />
              ) : item.wide ? (
                <VideoLockup item={item} />
              ) : (
                <SquareLockup item={item} />
              )}
            </li>
          ))}
        </ul>
      )}
      {tracks.length > 0 && (
        <div className={seeAllTracks}>
          <TrackList
            tracks={tracks}
            variant="playlist"
            source={source}
            onNearEnd={
              library.hasNextPage && !library.isFetchingNextPage && !library.isFetchNextPageError
                ? () => void library.fetchNextPage()
                : undefined
            }
          />
        </div>
      )}
      {allGrid.length > 0 && <div ref={loadMore} aria-hidden="true" />}
      {library.isFetchNextPageError && <LoadMoreError onRetry={() => void library.fetchNextPage()} />}
    </>
  );
}
