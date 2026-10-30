import { AudioLines, X } from 'lucide-react';
import { useEffect, useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import { useAddons } from '@/addons/client';
import { usePlayer } from '@/state/player';
import { qualityLabel } from './quality';
export function QualityLabel() {
  const track = usePlayer(s => s.nowPlaying);
  const status = usePlayer(s => s.status);
  const search = useAddons(s => s.status);
  const enabled = useAddons(s => s.jioSaavn || s.sources.some(a => a.enabled));
  const upgrading = useAddons(s => s.checkingVideoId === track?.videoId);
  const [details, setDetails] = useState(false);
  const dialog = useRef<HTMLDialogElement>(null);
  useEffect(() => { if (details) dialog.current?.showModal(); }, [details]);
  const close = () => { dialog.current?.close(); setDetails(false); };
  const quality = qualityLabel(track?.streamInfo ?? null, enabled && upgrading, status);
  return <>
    <button className="ot-quality" data-lossless={quality.lossless} onClick={() => setDetails(true)} aria-label={`Audio details: ${quality.text}`}>
      {quality.lossless ? <AudioLines size={17} /> : <span className="ot-hq" aria-hidden="true">HQ</span>}<span>{quality.text}</span>
    </button>
    {/* In the body, not inside the player: the player's idle fade and drag handling would reach it there. */}
    {details && createPortal(<dialog
      ref={dialog}
      className="ot-audio-details"
      aria-label="Audio details"
      onCancel={e => { e.preventDefault(); close(); }}
      // A click on the dimmed area around the sheet lands on the dialog itself.
      onClick={e => { if (e.target === e.currentTarget) close(); }}
      onKeyDown={e => e.stopPropagation()}
    >
      <header><h2>Audio pipeline</h2><button className="ot-audio-close" onClick={close} aria-label="Close"><X size={20} /></button></header>
      <div className="ot-audio-body">
        <p>{track?.streamInfo ?? 'Waiting for decoded audio'}</p>
        <p className="whitespace-pre-line">{search}</p><p>The browser and operating system control the final output sample rate.</p>
      </div>
      <button className="ot-audio-done" autoFocus onClick={close}>Done</button>
    </dialog>, document.body)}
  </>;
}
