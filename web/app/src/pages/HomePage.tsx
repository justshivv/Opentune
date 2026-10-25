import { useRemoteConfig } from '@/config/remote';
import { syncPublished } from '@/addons/client';
import { Clock, RefreshCw, Settings, Sparkles, ListMusic } from 'lucide-react';
import { PrefetchLink } from '@/components/PrefetchLink';
import { queryClient } from '@/lib/queryClient';
import { homeSections } from './homeSections';
import { Fragment, useEffect, useRef } from 'react';
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
  const runtime = useRemoteConfig(s => s.config.data);
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
  }, [home.hasNextPage, home.isFetchingNextPage, home.isFetchNextPageError, home.fetchNextPage, runtime.sections]);

  const hour = new Date().getHours();
  const greeting = hour < 5 ? 'Late night' : hour < 12 ? 'Good morning' : hour < 17 ? 'Good afternoon' : 'Good evening';
  const heading = <header className="ot-home-heading"><div><p>{greeting}</p><h1>What's playing?</h1></div><div>
    <button className="ot-circle-button" aria-label="Refresh recommendations" disabled={home.isFetching || missing} onClick={() => {
      void syncPublished(true);
      void queryClient.resetQueries({ queryKey: ['music', 'home'] });
      void queryClient.invalidateQueries({ queryKey: ['music', 'library'] });
    }}><RefreshCw size={20} className={home.isFetching ? 'animate-spin' : ''} /></button>
    <PrefetchLink className="ot-circle-button" to="/settings" aria-label="Settings"><Settings size={20} /></PrefetchLink>
  </div></header>;
  const banners = runtime.banners.filter(b => b.enabled && b.platforms.includes('web')).map(b => (
    <section className="ot-community" key={b.id} aria-label={b.title}><h2>{b.title}</h2><p>{b.body}</p><div><a href={b.url} target="_blank" rel="noreferrer">{b.action} ↗</a></div></section>
  ));
  if (missing) {
    return (
      <>
        {heading}{banners}
        <NeedsExtension />
      </>
    );
  }
  // Only a failed first page replaces the feed; a later one is shown under it.
  if (home.isError && !home.data) {
    return (
      <>
        {heading}{banners}
        <LoadError error={home.error} onRetry={() => void home.refetch()} />
      </>
    );
  }
  if (!home.data) return <>{heading}{banners}<HomeSkeleton /></>;

  // YouTube's feed can carry its own, older Recently Played; the history wins, as on the phone.
  const shelves = home.data.pages
    .flatMap((page) => page.data)
    .filter((shelf) => !(recent.length > 0 && shelf.title.trim().toLowerCase() === RECENT_TITLE.toLowerCase()));
  const arranged = homeSections(shelves);
  return (
    <>
      {heading}{banners}
      {runtime.sections.filter(s => s.enabled).map(section => {
        const title = (fallback: string) => section.title || fallback;
        switch (section.id) {
          case 'quick-picks': return <Fragment key={section.id}>
            <div className="ot-quick-title"><h2>{title('Quick picks')}</h2><PrefetchLink className="ot-circle-button" to="/library/recent" aria-label="Listening history"><Clock size={20} /></PrefetchLink></div>
            {arranged.quick ? <Shelves shelves={[{ ...arranged.quick, title: title('Quick picks') }]} /> : recent.length > 0 && <TrackShelf title={title('Quick picks')} to="/library/recent" tracks={recent} source={RECENT_SOURCE} />}
          </Fragment>;
          case 'mixes': return <div className="ot-home-links" key={section.id}><PrefetchLink to="/library/made-for-you"><Sparkles size={18} />{title('Made for you')}</PrefetchLink><PrefetchLink to="/library/playlists"><ListMusic size={18} />Your playlists</PrefetchLink></div>;
          case 'featured-playlists': return runtime.playlists.length > 0 && <Shelves key={section.id} shelves={[{
            title: title('From the community'), strapline: null, layout: 'square', seeAll: null,
            items: runtime.playlists.map(p => ({ kind: 'playlist', id: p.browseId, title: p.title, subtitle: 'OpenTune picks', artists: [], album: null, thumbnail: p.artwork || null, wide: false, durationSec: null, explicit: false })),
          }]} />;
          case 'recommendations': return <Fragment key={section.id}>{section.title && <h2 className="mx-5 type-title-2-emphasized">{section.title}</h2>}<Shelves shelves={arranged.shelves} /></Fragment>;
          // These shelves are native-only until their web counterparts are implemented.
          case 'speed-dial': case 'spotify-mixes': return null;
        }
      })}
      {runtime.sections.some(s => s.id === 'recommendations' && s.enabled) && <div ref={sentinel} />}
      {home.isFetchingNextPage && <ShelfSkeleton kind="square" />}
      {home.isFetchNextPageError && <LoadMoreError onRetry={() => void home.fetchNextPage()} />}
    </>
  );
}
