import { useEffect, useState } from 'react';
import { PageHeader } from '@/components/PageHeader';
import { addSource, allowSource, syncPublished, useAddons } from '@/addons/client';
import { JIOSAAVN_ORIGIN } from '@/addons/jiosaavn';
import { SourceList } from './SourceList';
import { settingsUi } from './settingsStyles';
export default function AudioAddonsPage() {
  const status = useAddons(s => s.status);
  const jioSaavn = useAddons(s => s.jioSaavn);
  const setJioSaavn = useAddons(s => s.setJioSaavn);
  const [url, setUrl] = useState(''); const [notice, setNotice] = useState(''); const [busy, setBusy] = useState(false);
  // The owner's published sources, looked at afresh each time this page opens.
  useEffect(() => { void syncPublished(true); }, []);
  const button = 'rounded-pill bg-quinary px-4 py-2 disabled:opacity-45';

  return <div className={settingsUi.centered}><PageHeader title="Audio sources" /><div className={settingsUi.body}>
    <p className="mx-4 mb-4 text-secondary">Your sources, in your order. OpenTune checks matching recordings and chooses the highest measured FLAC quality. Equal quality keeps your priority. Sources marked “Added by admin” are published by OpenTune; turn them off or reorder them as you like.</p>
    <section className={`${settingsUi.group} mb-4`} aria-label="JioSaavn">
      <div className="ot-source-row">
        <span /><span className="ot-saavn-mark" aria-hidden="true">J</span>
        <div><strong>JioSaavn</strong><small>320 kbps AAC. Plays while FLAC is looked for, and when there is none</small></div>
        <input type="checkbox" role="switch" aria-label="Use JioSaavn" checked={jioSaavn} onChange={e => setJioSaavn(e.target.checked)} />
        <span />
      </div>
      <p className="px-4 pb-4 text-secondary">JioSaavn’s catalog is read through the extension: allow <code>www.jiosaavn.com</code> there once. <button type="button" className={button} onClick={() => void allowSource(JIOSAAVN_ORIGIN).then(() => setNotice('Approve JioSaavn in the extension tab that opened.')).catch(e => setNotice(e instanceof Error ? e.message : 'Permission unavailable'))}>Allow through extension</button></p>
    </section>
    <section className={settingsUi.group} aria-label="Source priority"><SourceList /><div className="p-4">YouTube Music <span className="text-secondary">· fallback through the extension</span></div></section>
    <form className="p-4" onSubmit={e => { e.preventDefault(); setBusy(true); void addSource(url).then(() => { setUrl(''); setNotice('Source added'); }).catch(e => setNotice(e instanceof Error ? e.message : 'Could not add source')).finally(() => setBusy(false)); }}>
      <label htmlFor="addon-url">Add a source URL</label><input id="addon-url" type="url" required value={url} onChange={e => setUrl(e.target.value)} placeholder="https://your-addon.example/manifest.json" className="block w-full p-3 my-3 rounded-lg bg-quinary" />
      <div className="flex flex-wrap gap-2"><button className={button} disabled={busy}>{busy ? 'Checking…' : 'Add source'}</button><button type="button" className={button} onClick={() => void allowSource(url).then(() => setNotice('Approve access in the extension tab, then add the source again.')).catch(e => setNotice(e instanceof Error ? e.message : 'Permission unavailable'))}>Allow through extension</button></div>
      <p role="status" className="mt-3 whitespace-pre-line text-secondary">{notice || status}</p>
    </form><p className="mx-4 text-secondary">This browser preview supports direct FLAC with media CORS enabled. Sources requiring a verified physical lossless output are skipped because browsers cannot confirm that route. Segmented addon playback remains Android-only.</p>
    <p className="m-4"><a href="https://justshivv.github.io/Opentune/addons/">Build an OpenTune add-on ↗</a></p>
  </div></div>;
}
