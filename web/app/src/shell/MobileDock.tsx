import { Compass, Home, Library, Search, Settings } from 'lucide-react';
import { NavLink } from 'react-router';
import { PauseIcon, PlayIcon, ForwardIcon } from '@/components/icons/Transport';
import { sizedArtwork } from '@/lib/artwork';
import { usePlayer } from '@/state/player';
import { useUi } from '@/state/ui';
export function MobileDock() {
  const track = usePlayer(s => s.nowPlaying);
  const playing = usePlayer(s => s.status === 'playing' || s.status === 'buffering');
  const { togglePlay, next } = usePlayer.getState();
  return <div className="ot-mobile-dock">
    {track && <section className="ot-mini glass-pill" aria-label="Mini player">
      <button className="ot-mini-track" onClick={() => useUi.getState().setFullScreen(true)} aria-label={`Open player: ${track.title}`}>
        {track.artworkUrl && <img src={sizedArtwork(track.artworkUrl, 96)} alt="" />}<span><strong>{track.title}</strong><small>{track.artist}</small></span>
      </button>
      <button aria-label={playing ? 'Pause' : 'Play'} onClick={togglePlay}>{playing ? <PauseIcon size={26} /> : <PlayIcon size={26} />}</button>
      <button aria-label="Next" onClick={next}><ForwardIcon size={24} /></button>
    </section>}
    <nav className="ot-bottom-nav glass-pill" aria-label="Main navigation">
      {[[Home, '/home', 'Home'], [Compass, '/new', 'Explore'], [Library, '/library/playlists', 'Library'], [Search, '/search', 'Search'], [Settings, '/settings', 'Settings']].map(([Icon, to, name]) => {
        const Glyph = Icon as typeof Home;
        return <NavLink key={to as string} to={to as string}><Glyph size={21} /><span>{name as string}</span></NavLink>;
      })}
    </nav>
  </div>;
}
