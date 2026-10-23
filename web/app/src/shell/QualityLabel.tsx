import { AudioLines, Headphones } from 'lucide-react';
import { useEffect, useRef, useState } from 'react';
import { useAddons } from '@/addons/client';
import { usePlayer } from '@/state/player';
import { qualityLabel } from './quality';
export function QualityLabel() {
  const track = usePlayer(s => s.nowPlaying);
  const status = usePlayer(s => s.status);
  const search = useAddons(s => s.status);
  const enabled = useAddons(s => s.sources.some(a => a.enabled));
  const upgrading = useAddons(s => s.checkingVideoId === track?.videoId);
  const [details, setDetails] = useState(false);
  const dialog = useRef<HTMLDialogElement>(null);
  useEffect(() => { if (details) dialog.current?.showModal(); }, [details]);
  const quality = qualityLabel(track?.streamInfo ?? null, enabled && upgrading, status);
  return <>
    <button className="ot-quality" data-lossless={quality.lossless} onClick={() => setDetails(true)} aria-label={`Audio details: ${quality.text}`}>
      {quality.lossless ? <AudioLines size={17} /> : <Headphones size={17} />}<span>{quality.text}</span>
    </button>
    {details && <dialog ref={dialog} className="ot-audio-details" aria-label="Audio details" onCancel={e => { e.preventDefault(); dialog.current?.close(); setDetails(false); }} onKeyDown={e => e.stopPropagation()}>
      <h2>Audio pipeline</h2><p>{track?.streamInfo ?? 'Waiting for decoded audio'}</p>
      <p className="whitespace-pre-line">{search}</p><p>The browser and operating system control the final output sample rate.</p>
      <button autoFocus onClick={() => { dialog.current?.close(); setDetails(false); }}>Done</button>
    </dialog>}
  </>;
}
