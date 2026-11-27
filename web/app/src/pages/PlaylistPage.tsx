import { useCallback, useEffect, useMemo, useState } from 'react';
import { useParams } from 'react-router';
import { DetailHeader } from '@/components/music/DetailHeader';
import { FindInList, findInList } from '@/components/music/FindInList';
import { playList, queueList, shuffleList } from '@/components/music/play';
import { LoadError, LoadMoreError, NeedsExtension } from '@/components/music/States';
import { TrackList } from '@/components/music/TrackList';
import { usePlaylist, useYouTubeReady } from '@/data/music/queries';
import { useArtworkColor } from '@/lib/artworkColor';
import { usePageTint } from '@/lib/usePageTint';
import { PlaylistSkeleton } from '@/skeletons/PageSkeletons';
import { detailFooter } from './pageStyles';

/** A playlist (report §14): header, then the artwork track list, loading on as you scroll. */
export default function PlaylistPage() {
  const id = decodeURIComponent(useParams().id ?? '');
  const { missing } = useYouTubeReady();
  const playlist = usePlaylist(id);
  const header = playlist.data?.pages[0]?.header ?? null;
  usePageTint(useArtworkColor(header?.thumbnail));

  const { hasNextPage, isFetchingNextPage, isFetchNextPageError, fetchNextPage } = playlist;
  const onNearEnd = useCallback(() => {
    if (hasNextPage && !isFetchingNextPage && !isFetchNextPageError) void fetchNextPage();
  }, [hasNextPage, isFetchingNextPage, isFetchNextPageError, fetchNextPage]);

  // Search within the playlist: while there's a query, the rest of a long playlist is loaded so
  // the search covers every song, not only the pages scrolled to so far.
  const [query, setQuery] = useState('');
  const searching = query.trim().length > 0;
  useEffect(() => {
    if (searching) onNearEnd();
  }, [searching, onNearEnd, playlist.data?.pages.length]);
  const tracks = useMemo(() => playlist.data?.pages.flatMap((page) => page.tracks) ?? [], [playlist.data]);
  const shown = useMemo(() => findInList(tracks, query), [tracks, query]);
  const moreComing = !!hasNextPage && !isFetchNextPageError;

  if (missing) return <NeedsExtension />;
  if (playlist.isError && !playlist.data) return <LoadError error={playlist.error} onRetry={() => void playlist.refetch()} />;
  if (!playlist.data || !header) return <PlaylistSkeleton />;

  const context = { source: { title: header.title, type: 'playlist' as const, id } };
  return (
    <>
      <DetailHeader
        info={header}
        canPlay={tracks.length > 0}
        onPlay={() => playList(tracks, 0, context)}
        onShuffle={() => shuffleList(tracks, context)}
        onQueue={(next) => queueList(tracks, context, next)}
        shareUrl={`https://music.youtube.com/playlist?list=${encodeURIComponent(id.replace(/^VL/, ''))}`}
      />
      {tracks.length > 0 && (
        <div className="mx-(--body-gutter) mb-3 flex items-center justify-between gap-4">
          <p className="min-w-0 truncate type-callout text-secondary" aria-live="polite">
            {searching &&
              (shown.length
                ? `${shown.length} of ${tracks.length} songs${moreComing ? ', searching the rest…' : ''}`
                : moreComing
                  ? 'Searching the rest of the playlist…'
                  : `No songs match “${query.trim()}”`)}
          </p>
          <FindInList query={query} onQuery={setQuery} label="Find in playlist" />
        </div>
      )}
      <TrackList
        tracks={shown}
        queue={tracks}
        variant="playlist"
        onNearEnd={searching ? undefined : onNearEnd}
        source={context.source}
      />
      {isFetchingNextPage && <div className="flex justify-center py-5 type-callout text-secondary">Loading more songs…</div>}
      {playlist.isFetchNextPageError && <LoadMoreError onRetry={() => void fetchNextPage()} />}
      <footer className={detailFooter}>
        <p>{header.meta}</p>
      </footer>
    </>
  );
}
