import { Clock, RefreshCw, Settings, Sparkles, ListMusic } from 'lucide-react';
import { PrefetchLink } from '@/components/PrefetchLink';
import { queryClient } from '@/lib/queryClient';
import { homeSections } from './homeSections';
import { useEffect, useRef } from 'react';
import { Shelves, TrackShelf } from '@/components/music/ShelfView';
import { LoadError, LoadMoreError, NeedsExtension } from '@/components/music/States';
import { useHome, useLibrary, useYouTubeReady } from '@/data/music/queries';
import type { Track } from '@/data/music/types';
import { HomeSkeleton } from '@/skeletons/PageSkeletons';
import { ShelfSkeleton } from '@/skeletons/ShelfSkeleton';

const RECENT_TITLE = 'Recently Played';
const RECENT_LIMIT = 20;
const RECENT_SOURCE = { title: RECENT_TITLE, type: 'home' as const };

/**
 * The account's listening history, newest first, one row per song
 * (YtMusicRepository.recentlyPlayed): the first 20, signed in only. The same
 * query as Library's Recently Played, so either page warms the other.
 */
function useRecentlyPlayed(): Track[] {
  const history = useLibrary('recent');
  const first = history.data?.pages[0]?.data.tracks ?? [];
  const seen = new Set<string>();
  return first.filter((t) => !seen.has(t.videoId) && seen.add(t.videoId)).slice(0, RECENT_LIMIT);
}

/** YouTube Music's home feed, as Apple's Home: shelf after shelf, more as you scroll. */
export default function HomePage() {
  const { missing } = useYouTubeReady();
  const home = useHome();
  const recent = useRecentlyPlayed();
  const sentinel = useRef<HTMLDivElement>(null);

  // Loads the next shelves before the bottom comes into view; after a failed
  // page it waits for Try Again rather than retrying on every scroll.
  useEffect(() => {
    const el = sentinel.current;
    if (!el || !home.hasNextPage || home.isFetchNextPageError) return;
    const observer = new IntersectionObserver(
      ([entry]) => {
        if (entry?.isIntersecting && !home.isFetchingNextPage) void home.fetchNextPage();
      },
      { root: document.getElementById('scrollable-page'), rootMargin: '600px' },
    );
    observer.observe(el);
    return () => observer.disconnect();
  }, [home.hasNextPage, home.isFetchingNextPage, home.isFetchNextPageError, home.fetchNextPage]);

  const hour = new Date().getHours();
  const greeting = hour < 5 ? 'Late night' : hour < 12 ? 'Good morning' : hour < 17 ? 'Good afternoon' : 'Good evening';
  const heading = <header className="ot-home-heading"><div><p>{greeting}</p><h1>What's playing?</h1></div><div>
    <button className="ot-circle-button" aria-label="Refresh recommendations" disabled={home.isFetching || missing} onClick={() => {
      void queryClient.resetQueries({ queryKey: ['music', 'home'] });
      void queryClient.invalidateQueries({ queryKey: ['music', 'library'] });
    }}><RefreshCw size={20} className={home.isFetching ? 'animate-spin' : ''} /></button>
    <PrefetchLink className="ot-circle-button" to="/settings" aria-label="Settings"><Settings size={20} /></PrefetchLink>
  </div></header>;
  if (missing) {
    return (
      <>
        {heading}
        <NeedsExtension />
      </>
    );
  }
  // Only a failed first page replaces the feed; a later one is shown under it.
  if (home.isError && !home.data) {
    return (
      <>
        {heading}
        <LoadError error={home.error} onRetry={() => void home.refetch()} />
      </>
    );
  }
  if (!home.data) return <>{heading}<HomeSkeleton /></>;

  // YouTube's feed can carry its own, older Recently Played; the history wins, as on the phone.
  const shelves = home.data.pages
    .flatMap((page) => page.data)
    .filter((shelf) => !(recent.length > 0 && shelf.title.trim().toLowerCase() === RECENT_TITLE.toLowerCase()));
  const arranged = homeSections(shelves);
  return (
    <>
      {heading}
      <div className="ot-quick-title"><h2>Quick picks</h2><PrefetchLink className="ot-circle-button" to="/library/recent" aria-label="Listening history"><Clock size={20} /></PrefetchLink></div>
      {arranged.quick ? <Shelves shelves={[{ ...arranged.quick, title: "Quick picks" }]} /> : recent.length > 0 && <TrackShelf title="Quick picks" to="/library/recent" tracks={recent} source={RECENT_SOURCE} />}
      <div className="ot-home-links"><PrefetchLink to="/library/made-for-you"><Sparkles size={18} />Made for you</PrefetchLink><PrefetchLink to="/library/playlists"><ListMusic size={18} />Your playlists</PrefetchLink></div>
      <Shelves shelves={arranged.shelves} />
      <div ref={sentinel} />
      {home.isFetchingNextPage && <ShelfSkeleton kind="square" />}
      {home.isFetchNextPageError && <LoadMoreError onRetry={() => void home.fetchNextPage()} />}
    </>
  );
}
