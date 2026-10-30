import { Compass, Home, Library, Search, Settings } from 'lucide-react';
import { NavLink, useLocation } from 'react-router';
import { PauseIcon, PlayIcon, ForwardIcon } from '@/components/icons/Transport';
import { sizedArtwork } from '@/lib/artwork';
import { usePlayer } from '@/state/player';
import { useUi } from '@/state/ui';
import { useLiquidGlass } from '@/lib/liquidGlass';
const TABS = [[Home, '/home', 'Home'], [Compass, '/new', 'Explore'], [Library, '/library/playlists', 'Library'], [Search, '/search', 'Search'], [Settings, '/settings', 'Settings']] as const;

export function MobileDock() {
  const track = usePlayer(s => s.nowPlaying);
  const playing = usePlayer(s => s.status === 'playing' || s.status === 'buffering');
  const { togglePlay, next } = usePlayer.getState();
  const { pathname } = useLocation();
  const mini = useLiquidGlass<HTMLElement>(20, 20);
  const dock = useLiquidGlass<HTMLElement>(22, 22);
  const tab = TABS.findIndex(([, to]) => pathname.startsWith(to.replace(/\/playlists$/, '')));
  return <div className="ot-mobile-dock">
    {track && <section ref={mini} className="ot-mini glass-pill" aria-label="Mini player" data-player-origin>
      <button className="ot-mini-track" onClick={() => useUi.getState().setFullScreen(true)} aria-label={`Open player: ${track.title}`}>
        {track.artworkUrl && <img src={sizedArtwork(track.artworkUrl, 96)} alt="" data-cover-origin />}
        <span key={track.videoId} className="ot-swap-in"><strong>{track.title}</strong><small>{track.artist}</small></span>
      </button>
      <button className="ot-press" aria-label={playing ? 'Pause' : 'Play'} onClick={togglePlay}>{playing ? <PauseIcon size={26} /> : <PlayIcon size={26} />}</button>
      <button className="ot-press" aria-label="Next" onClick={next}><ForwardIcon size={24} /></button>
    </section>}
    <nav ref={dock} className="ot-bottom-nav glass-pill" aria-label="Main navigation">
      <span className="ot-dock-pill" style={{ '--i': Math.max(0, tab) } as React.CSSProperties} data-none={tab < 0 || undefined} aria-hidden="true" />
      {TABS.map(([Glyph, to, name]) => <NavLink key={to} to={to}><Glyph size={21} /><span>{name}</span></NavLink>)}
    </nav>
  </div>;
}
