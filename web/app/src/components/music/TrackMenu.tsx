import { ChevronLeft, ChevronRight, Disc3, Link2, ListEnd, ListPlus, ListStart, MoreHorizontal, Radio, Star, User } from 'lucide-react';
import { useCallback, useState, type MouseEvent } from 'react';
import { useNavigate } from 'react-router';
import { Menu, MenuHeader, MenuItem, MenuSeparator, type MenuPoint } from '@/components/Menu';
import { toast } from '@/components/Toast';
import {
  useAddToPlaylist,
  useLikeStatus,
  usePlaylistOptions,
  useSetLiked,
  useSignedIn,
} from '@/data/music/queries';
import type { MusicItem, Track } from '@/data/music/types';
import { usePlayer } from '@/state/player';
import { albumHref, artistHref } from './links';
import { toTrack } from './play';

function PlaylistChoices({ videoId, onDone, onBack }: { videoId: string; onDone: () => void; onBack: () => void }) {
  const options = usePlaylistOptions(videoId, true);
  const add = useAddToPlaylist();
  return (
    <>
      <MenuItem icon={<ChevronLeft size={14} />} onSelect={onBack}>
        Back
      </MenuItem>
      <MenuSeparator />
      <MenuHeader>Add to Playlist</MenuHeader>
      {options.isPending && (
        <MenuItem disabled onSelect={() => {}}>
          Loading…
        </MenuItem>
      )}
      {options.isError && (
        <MenuItem disabled onSelect={() => {}}>
          Couldn’t load your playlists
        </MenuItem>
      )}
      {options.data?.length === 0 && (
        <MenuItem disabled onSelect={() => {}}>
          No playlists yet
        </MenuItem>
      )}
      {options.data?.map((option) => (
        <MenuItem
          key={option.playlistId}
          onSelect={() => {
            add.mutate({ playlistId: option.playlistId, videoId });
            toast(`Added to ${option.title}`);
            onDone();
          }}
        >
          {option.title}
        </MenuItem>
      ))}
    </>
  );
}

/** Favorite and Add to Playlist: the account's actions, signed in only. */
function AccountItems({ track, onClose, onPlaylists }: { track: Track; onClose: () => void; onPlaylists: () => void }) {
  const status = useLikeStatus(track.videoId);
  const setLiked = useSetLiked();
  const liked = status.data === 'LIKE';
  return (
    <>
      <MenuSeparator />
      <MenuItem
        icon={<Star size={15} fill={liked ? 'currentColor' : 'none'} />}
        disabled={status.isPending}
        onSelect={() => {
          setLiked.mutate({ videoId: track.videoId, liked: !liked });
          toast(liked ? 'Removed from Favorites' : 'Added to Favorites');
          onClose();
        }}
      >
        {liked ? 'Unfavorite' : 'Favorite'}
      </MenuItem>
      <MenuItem icon={<ListPlus size={15} />} trailing={<ChevronRight size={14} />} onSelect={onPlaylists}>
        Add to Playlist
      </MenuItem>
    </>
  );
}

/** The song's link on YouTube Music, for sharing. */
const songLink = (videoId: string) => `https://music.youtube.com/watch?v=${encodeURIComponent(videoId)}`;

async function copyLink(track: Track) {
  try {
    await navigator.clipboard.writeText(songLink(track.videoId));
    toast('Link copied');
  } catch {
    toast('Couldn’t copy the link');
  }
}

function TrackMenuItems({ track, onClose }: { track: Track; onClose: () => void }) {
  const [view, setView] = useState<'main' | 'playlists'>('main');
  const signedIn = useSignedIn();
  const navigate = useNavigate();
  if (view === 'playlists') {
    return <PlaylistChoices videoId={track.videoId} onDone={onClose} onBack={() => setView('main')} />;
  }
  const player = usePlayer.getState();
  const artist = track.artists.find((a) => a.id) ?? null;
  const album = track.album?.id ? track.album : null;
  const go = (href: string | null) => {
    onClose();
    if (href) navigate(href);
  };
  return (
    <>
      <MenuItem
        icon={<ListStart size={15} />}
        onSelect={() => {
          onClose();
          player.playNext([track]);
          toast(`Playing next: ${track.title}`);
        }}
      >
        Play Next
      </MenuItem>
      <MenuItem
        icon={<ListEnd size={15} />}
        onSelect={() => {
          onClose();
          player.addToQueue([track]);
          toast(`Added to queue: ${track.title}`);
        }}
      >
        Add to Queue
      </MenuItem>
      <MenuItem
        icon={<Radio size={15} />}
        onSelect={() => {
          onClose();
          // The song, then AutoPlay keeps similar songs coming after it.
          if (!player.autoplay) player.setAutoplay(true);
          player.playTrack(track, { title: `${track.title} Radio`, type: 'other' });
        }}
      >
        Start Radio
      </MenuItem>
      {(artist || album) && <MenuSeparator />}
      {artist && (
        <MenuItem icon={<User size={15} />} onSelect={() => go(artistHref(artist.id))}>
          Go to Artist
        </MenuItem>
      )}
      {album && (
        <MenuItem icon={<Disc3 size={15} />} onSelect={() => go(albumHref(album.id))}>
          Go to Album
        </MenuItem>
      )}
      <MenuSeparator />
      <MenuItem
        icon={<Link2 size={15} />}
        onSelect={() => {
          onClose();
          void copyLink(track);
        }}
      >
        Copy Link
      </MenuItem>
      {signedIn && <AccountItems track={track} onClose={onClose} onPlaylists={() => setView('playlists')} />}
    </>
  );
}

/**
 * A song's menu opened by a right-click: returns the handler to put on the row or card, and
 * the menu to render beside it.
 */
export function useTrackContextMenu(item: Track | MusicItem | null) {
  const [point, setPoint] = useState<MenuPoint | null>(null);
  const close = useCallback(() => setPoint(null), []);
  const onContextMenu = useCallback(
    (event: MouseEvent) => {
      if (!item) return;
      event.preventDefault();
      event.stopPropagation();
      setPoint({ x: event.clientX, y: event.clientY });
    },
    [item],
  );
  const track = item ? toTrack(item) : null;
  const menu =
    point && track ? (
      <Menu anchor={point} onClose={close} label={track.title}>
        <TrackMenuItems track={track} onClose={close} />
      </Menu>
    ) : null;
  return { onContextMenu, menu };
}

/**
 * The ⋯ button for a song (report §14), in rows, on cards and for what's playing: Play Next,
 * Add to Queue and Start Radio, Go to Artist and Album, Copy Link; Favorite and Add to Playlist signed in.
 */
export function TrackMenu({ track: item, className }: { track: Track | MusicItem; className?: string }) {
  const [anchor, setAnchor] = useState<HTMLElement | null>(null);
  const close = useCallback(() => setAnchor(null), []);
  const track = toTrack(item);
  return (
    <>
      <button
        className={className}
        aria-label={`More options for ${track.title}`}
        aria-haspopup="menu"
        aria-expanded={anchor !== null}
        data-open={anchor !== null || undefined}
        onClick={(event) => {
          event.preventDefault();
          event.stopPropagation();
          setAnchor(anchor ? null : event.currentTarget);
        }}
        onDoubleClick={(event) => event.stopPropagation()}
      >
        <MoreHorizontal size={18} strokeWidth={2} />
      </button>
      {anchor && (
        <Menu anchor={anchor} onClose={close} label={track.title}>
          <TrackMenuItems track={track} onClose={close} />
        </Menu>
      )}
    </>
  );
}
