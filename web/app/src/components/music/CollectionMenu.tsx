import { Check, ExternalLink, Link2, ListEnd, ListStart, Play, Plus, Shuffle } from 'lucide-react';
import { useCallback, useEffect, useState, type MouseEvent } from 'react';
import { useNavigate } from 'react-router';
import { Menu, MenuItem, MenuSeparator, type MenuPoint } from '@/components/Menu';
import { toast } from '@/components/Toast';
import { albumQuery, playlistQuery, useSetListSaved, useSignedIn } from '@/data/music/queries';
import type { HeaderInfo, MusicItem, Track } from '@/data/music/types';
import { queryClient } from '@/lib/queryClient';
import { hrefFor } from './links';
import { playList, queueList, shuffleList, type ListContext } from './play';

/** An album or YouTube Music playlist, as a card or a sidebar row shows it. */
export type Collection = Pick<MusicItem, 'kind' | 'id' | 'title'>;

const isCollection = (item: Collection | null): item is Collection => !!item && (item.kind === 'album' || item.kind === 'playlist');

/**
 * The list's songs and how the queue should credit them, read the way its page reads them (from
 * the cache when the page was opened or prefetched). A playlist gives its first page of songs.
 */
async function load(item: Collection): Promise<{ tracks: Track[]; context: ListContext; library: HeaderInfo['library'] }> {
  if (item.kind === 'album') {
    const album = await queryClient.fetchQuery(albumQuery(item.id));
    return {
      library: album.library,
      tracks: album.tracks,
      context: { source: { title: album.title, type: 'album', id: album.id }, fallbackArtwork: album.thumbnail, fallbackArtists: album.artists },
    };
  }
  const playlist = await queryClient.fetchInfiniteQuery(playlistQuery(item.id));
  const header = playlist.pages[0]?.header;
  return {
    library: header?.library ?? null,
    tracks: playlist.pages.flatMap((page) => page.tracks),
    context: { source: { title: header?.title ?? item.title, type: 'playlist', id: item.id } },
  };
}

function shareUrl(item: Collection): string {
  return item.kind === 'album'
    ? `https://music.youtube.com/browse/${encodeURIComponent(item.id)}`
    : `https://music.youtube.com/playlist?list=${encodeURIComponent(item.id.replace(/^VL/, ''))}`;
}

/**
 * An album's or playlist's menu opened by a right-click, as songs have theirs: play it, shuffle
 * it, queue it, open it or copy its link. Returns the handler for the card or row and the menu to
 * render beside it; null items (songs, artists) get no handler.
 */
export function useCollectionContextMenu(item: Collection | null) {
  const [point, setPoint] = useState<MenuPoint | null>(null);
  const navigate = useNavigate();
  const close = useCallback(() => setPoint(null), []);
  const collection = isCollection(item) ? item : null;
  const signedIn = useSignedIn();
  const setSaved = useSetListSaved();
  // Whether the list is in the account's library, as YouTube's page says, read when the menu
  // opens (from the cache when the page was seen). Null: YouTube offers no toggle (your own lists).
  const [library, setLibrary] = useState<HeaderInfo['library'] | undefined>(undefined);
  useEffect(() => {
    if (!point || !collection || !signedIn) return;
    let live = true;
    setLibrary(undefined);
    load(collection).then(({ library: known }) => live && setLibrary(known), () => live && setLibrary(null));
    return () => {
      live = false;
    };
  }, [point, collection, signedIn]);
  const onContextMenu = useCallback(
    (event: MouseEvent) => {
      if (!collection) return;
      event.preventDefault();
      event.stopPropagation();
      setPoint({ x: event.clientX, y: event.clientY });
    },
    [collection],
  );
  const run = (action: (tracks: Track[], context: ListContext) => void) => {
    if (!collection) return;
    close();
    load(collection).then(
      ({ tracks, context }) => (tracks.length ? action(tracks, context) : toast(`${collection.title} has no songs to play`)),
      () => toast(`Couldn’t load ${collection.title}`),
    );
  };
  const href = collection ? hrefFor(collection) : null;
  const menu =
    point && collection ? (
      <Menu anchor={point} onClose={close} label={collection.title}>
        <MenuItem icon={<Play size={15} />} onSelect={() => run((tracks, context) => playList(tracks, 0, context))}>
          Play
        </MenuItem>
        <MenuItem icon={<Shuffle size={15} />} onSelect={() => run(shuffleList)}>
          Shuffle
        </MenuItem>
        <MenuItem icon={<ListStart size={15} />} onSelect={() => run((tracks, context) => queueList(tracks, context, true))}>
          Play Next
        </MenuItem>
        <MenuItem icon={<ListEnd size={15} />} onSelect={() => run((tracks, context) => queueList(tracks, context, false))}>
          Add to Queue
        </MenuItem>
        {signedIn && library !== null && (
          <>
            <MenuSeparator />
            <MenuItem
              icon={library?.saved ? <Check size={15} /> : <Plus size={15} />}
              disabled={!library || setSaved.isPending}
              onSelect={() => {
                if (!library) return;
                close();
                const saved = !library.saved;
                setSaved.mutate(
                  { playlistId: library.playlistId, saved },
                  {
                    onSuccess: () => toast(saved ? `Added to your library: ${collection.title}` : `Removed from your library: ${collection.title}`),
                    onError: () => toast(saved ? 'Couldn’t add it to your library' : 'Couldn’t remove it from your library'),
                  },
                );
              }}
            >
              {library?.saved ? 'Remove from Library' : 'Add to Library'}
            </MenuItem>
          </>
        )}
        <MenuSeparator />
        {href && (
          <MenuItem icon={<ExternalLink size={15} />} onSelect={() => { close(); void navigate(href); }}>
            {collection.kind === 'album' ? 'Go to Album' : 'Go to Playlist'}
          </MenuItem>
        )}
        <MenuItem
          icon={<Link2 size={15} />}
          onSelect={() => {
            close();
            navigator.clipboard.writeText(shareUrl(collection)).then(() => toast('Link copied'), () => toast('Couldn’t copy the link'));
          }}
        >
          Copy Link
        </MenuItem>
      </Menu>
    ) : null;
  return { onContextMenu, menu };
}
