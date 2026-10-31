import { ArrowUpRight, ListMusic, PanelLeftClose, PanelLeftOpen, Settings } from 'lucide-react';
import { useEffect } from 'react';
import { MdOutlineExtension } from 'react-icons/md';
import { SiSpotify } from 'react-icons/si';
import { PrefetchLink, PrefetchNavLink } from '@/components/PrefetchLink';
import { LogoMark } from '@/components/icons/LogoMark';
import {
  AlbumsIcon,
  AllPlaylistsIcon,
  ArtistsIcon,
  HomeIcon,
  MadeForYouIcon,
  NewIcon,
  RecentlyAddedIcon,
  SearchIcon,
  SongsIcon,
  type NavIconProps,
} from '@/components/icons/NavIcons';
import { hrefFor } from '@/components/music/links';
import { config } from '@/config';
import { LIBRARY_SECTIONS, type LibrarySection } from '@/data/music/library';
import { useLibraryPlaylists, useSignedIn } from '@/data/music/queries';
import { useSpotifyPlaylists } from '@/data/spotify/queries';
import { useExtensionUpdate } from '@/extension/useExtensionUpdate';
import { useSpotifySignedIn } from '@/extension/useSpotifyAuth';
import { useUi } from '@/state/ui';
import { AccountButton } from './AccountButton';

interface NavItem {
  to: string;
  label: string;
  Icon: (props: NavIconProps) => React.ReactNode;
}

const PRIMARY: NavItem[] = [
  { to: '/search', label: 'Search', Icon: SearchIcon },
  { to: '/home', label: 'Home', Icon: HomeIcon },
  { to: '/new', label: 'New', Icon: NewIcon },
];

const LIBRARY_ICONS: Record<Exclude<LibrarySection, 'playlists'>, NavItem['Icon']> = {
  recent: RecentlyAddedIcon,
  artists: ArtistsIcon,
  albums: AlbumsIcon,
  songs: SongsIcon,
  'made-for-you': MadeForYouIcon,
};

const LIBRARY: NavItem[] = (Object.keys(LIBRARY_ICONS) as (keyof typeof LIBRARY_ICONS)[]).map((section) => ({
  to: `/library/${section}`,
  label: LIBRARY_SECTIONS[section].title,
  Icon: LIBRARY_ICONS[section],
}));

// A sidebar row; NavLink sets aria-current on the page's own row.
const item =
  'ot-nav-item group flex h-9 items-center gap-2 mb-0.5 p-1 rounded-nav type-body-reduced text-primary hover:bg-quinary aria-[current=page]:bg-nav-selected';
// Apple's glyphs, drawn at 24px (their 24-unit box), which the user preferred to Apple's own 28px.
const icon = 'inline-grid size-6 flex-none place-items-center text-primary group-aria-[current=page]:text-key';
const navLabel = 'ot-side-label min-w-0 truncate';
// "Library", "Playlists" (report §1: 12px/15px 600, secondary, padding 4px 8px).
const sectionHeader = 'ot-side-header mt-4 px-2 py-1 type-callout-emphasized text-secondary';
const list = 'flex flex-col';

/** The folded rail names each icon in a tooltip; the open sidebar already shows the names. */
const tip = (label: string) => (useUi.getState().sidebarCollapsed ? label : undefined);

function NavList({ items, onNavigate }: { items: NavItem[]; onNavigate: () => void }) {
  useUi((s) => s.sidebarCollapsed);
  return (
    <ul className={list}>
      {items.map(({ to, label, Icon }) => (
        <li key={to}>
          <PrefetchNavLink to={to} className={item} onClick={onNavigate} title={tip(label)}>
            <Icon size={24} className={icon} />
            <span className={navLabel}>{label}</span>
          </PrefetchNavLink>
        </li>
      ))}
    </ul>
  );
}

/** Library and Playlists (report §1), once YouTube requests are made as the user. */
function LibraryNav({ onNavigate }: { onNavigate: () => void }) {
  const playlists = useLibraryPlaylists();
  return (
    <>
      <h2 className={sectionHeader}>Library</h2>
      <NavList items={LIBRARY} onNavigate={onNavigate} />
      <h2 className={sectionHeader}>Playlists</h2>
      <ul className={list}>
        <li>
          <PrefetchNavLink to="/library/playlists" className={item} onClick={onNavigate} title={tip(LIBRARY_SECTIONS.playlists.title)}>
            <AllPlaylistsIcon size={24} className={icon} />
            <span className={navLabel}>{LIBRARY_SECTIONS.playlists.title}</span>
          </PrefetchNavLink>
        </li>
        {playlists.data?.map((playlist) => (
          // Each playlist's glyph is the same one, so the folded rail leaves them out.
          <li key={playlist.id} className="ot-side-extra">
            <PrefetchNavLink to={hrefFor(playlist) ?? '/library/playlists'} className={item} onClick={onNavigate}>
              <span className={icon}>
                <ListMusic size={17} strokeWidth={1.8} />
              </span>
              <span className={navLabel}>{playlist.title}</span>
            </PrefetchNavLink>
          </li>
        ))}
      </ul>
    </>
  );
}

/** Spotify's playlists, once the extension can read them as the user (prompt §7.1). */
function SpotifyNav({ onNavigate }: { onNavigate: () => void }) {
  const playlists = useSpotifyPlaylists();
  return (
    <>
      <h2 className={sectionHeader}>Spotify</h2>
      <ul className={list}>
        <li>
          <PrefetchNavLink to="/spotify" end className={item} onClick={onNavigate} title={tip('All Spotify Playlists')}>
            {/* Spotify's own mark, so it reads apart from All Playlists above it, folded or not. */}
            <span className={icon}>
              <SiSpotify size={20} />
            </span>
            <span className={navLabel}>All Spotify Playlists</span>
          </PrefetchNavLink>
        </li>
        {playlists.data?.map((playlist) => (
          <li key={playlist.id} className="ot-side-extra">
            <PrefetchNavLink to={`/spotify/playlist/${encodeURIComponent(playlist.id)}`} className={item} onClick={onNavigate}>
              <span className={icon}>
                <ListMusic size={17} strokeWidth={1.8} />
              </span>
              <span className={navLabel}>{playlist.name}</span>
            </PrefetchNavLink>
          </li>
        ))}
      </ul>
    </>
  );
}

/** The left glass sidebar (report §1). */
export function Sidebar() {
  const drawerOpen = useUi((s) => s.drawerOpen);
  const setDrawerOpen = useUi((s) => s.setDrawerOpen);
  const update = useExtensionUpdate();
  const signedIn = useSignedIn();
  const spotifySignedIn = useSpotifySignedIn();
  const folded = useUi((s) => s.sidebarCollapsed);
  const toggleSidebar = useUi((s) => s.toggleSidebar);
  const close = () => setDrawerOpen(false);
  // The page and the player pill take their left edge from --web-navigation-width (tokens.css).
  useEffect(() => {
    if (folded) document.documentElement.dataset.sidebar = 'collapsed';
    else delete document.documentElement.dataset.sidebar;
  }, [folded]);

  return (
    <>
      {/* Below 484px the sidebar is a drawer opened from the top bar's menu button. */}
      <nav
        className="ot-side absolute inset-y-0 left-0 z-(--z-web-chrome) flex w-(--web-navigation-width) flex-col glass-material max-sm:w-[min(300px,82vw)] max-sm:-translate-x-full max-sm:shadow-glass max-sm:transition-transform max-sm:duration-(--duration-panel) max-sm:ease-panel max-sm:data-[open=true]:translate-none"
        data-open={drawerOpen}
        data-folded={folded}
        aria-label="Main"
      >
        <div className="ot-side-top mt-2 flex h-18 items-center justify-between pr-3 pl-7">
          <PrefetchLink to="/home" className="ot-side-wordmark inline-flex items-center gap-2 rounded-[6px]" aria-label="OpenTune, Home" onClick={close}>
            {/* White on dark, as Apple's own wordmark; it follows the label colour so it stays visible in light. */}
            <LogoMark size={22} className="text-primary" />
            <span className="type-[700_19px/24px] tracking-[-0.01em]">OpenTune</span>
          </PrefetchLink>
          {/* Folds the sidebar to a rail of icons and back. Phones have the drawer instead. */}
          <button
            className="grid size-8 flex-none place-items-center rounded-nav text-secondary transition-colors duration-150 hover:bg-quinary hover:text-primary max-sm:hidden"
            onClick={toggleSidebar}
            aria-label={folded ? 'Expand sidebar' : 'Collapse sidebar'}
            aria-expanded={!folded}
            title={`${folded ? 'Expand' : 'Collapse'} sidebar (${navigator.platform.startsWith('Mac') ? '⌘' : 'Ctrl'}+\\)`}
          >
            {folded ? <PanelLeftOpen size={18} strokeWidth={1.9} /> : <PanelLeftClose size={18} strokeWidth={1.9} />}
          </button>
        </div>
        <div className="ot-side-scroll min-h-0 flex-1 overflow-y-auto pr-3 pl-5 [scrollbar-width:none]">
          <NavList items={PRIMARY} onNavigate={close} />
          {signedIn && <LibraryNav onNavigate={close} />}
          {spotifySignedIn && <SpotifyNav onNavigate={close} />}
        </div>
        <div className="ot-side-bottom flex flex-col gap-2 pt-3 pr-3 pb-5 pl-5">
          <PrefetchNavLink to="/extension" className={`${item} relative`} onClick={close} title={tip('OpenTune Extension')}>
            {/* Chrome's own toolbar glyph for extensions (Material "extension"). */}
            <span className={icon}>
              <MdOutlineExtension size={18} />
            </span>
            <span className={navLabel}>OpenTune Extension</span>
            {update.state === 'available' && (
              <span className="ot-side-badge ml-auto rounded-[10px] bg-key px-[7px] py-0.5 type-subhead-emphasized text-white" title={`Version ${update.latest.version} is available`}>
                Update
              </span>
            )}
          </PrefetchNavLink>
          <div className="ot-side-full flex flex-col">
            <AccountButton />
          </div>
          <div className="ot-side-compact hidden flex-col">
            <AccountButton compact />
          </div>
          <div className="ot-side-foot flex items-center justify-between">
            <a className="ot-side-android inline-flex items-center gap-1 ps-1 type-callout text-primary hover:underline" href={config.androidAppUrl} target="_blank" rel="noreferrer">
              <span>Get the Android app</span>
              <ArrowUpRight size={14} strokeWidth={2} />
            </a>
            <PrefetchNavLink to="/settings" className="grid size-7 place-items-center rounded-nav text-secondary hover:bg-quinary hover:text-primary aria-[current=page]:bg-quinary aria-[current=page]:text-primary" aria-label="Settings" title="Settings" onClick={close}>
              <Settings size={17} strokeWidth={1.9} />
            </PrefetchNavLink>
          </div>
        </div>
      </nav>
      {drawerOpen && <div className="absolute inset-0 z-[calc(var(--z-web-chrome)-1)] hidden bg-scrim max-sm:block" onClick={close} aria-hidden="true" />}
    </>
  );
}

