import { useState } from 'react';
import { PageHeader } from '@/components/PageHeader';
import { addSource, allowSource, useAddons } from '@/addons/client';
import { SourceList } from './SourceList';
import { settingsUi } from './settingsStyles';
export default function AudioAddonsPage() {
  const status = useAddons(s => s.status);
  const [url, setUrl] = useState(''); const [notice, setNotice] = useState(''); const [busy, setBusy] = useState(false);
  const button = 'rounded-pill bg-quinary px-4 py-2 disabled:opacity-45';

  return <div className={settingsUi.centered}><PageHeader title="Audio sources" /><div className={settingsUi.body}>
    <p className="mx-4 mb-4 text-secondary">Your sources, in your order. OpenTune checks matching recordings and chooses the highest measured FLAC quality. Equal quality keeps your priority.</p>
    <section className={settingsUi.group} aria-label="Source priority"><SourceList /><div className="p-4">YouTube Music <span className="text-secondary">· fallback through the extension</span></div></section>
    <form className="p-4" onSubmit={e => { e.preventDefault(); setBusy(true); void addSource(url).then(() => { setUrl(''); setNotice('Source added'); }).catch(e => setNotice(e instanceof Error ? e.message : 'Could not add source')).finally(() => setBusy(false)); }}>
      <label htmlFor="addon-url">Add a source URL</label><input id="addon-url" type="url" required value={url} onChange={e => setUrl(e.target.value)} placeholder="https://your-addon.example/manifest.json" className="block w-full p-3 my-3 rounded-lg bg-quinary" />
      <div className="flex flex-wrap gap-2"><button className={button} disabled={busy}>{busy ? 'Checking…' : 'Add source'}</button><button type="button" className={button} onClick={() => void allowSource(url).then(() => setNotice('Approve access in the extension tab, then add the source again.')).catch(e => setNotice(e instanceof Error ? e.message : 'Permission unavailable'))}>Allow through extension</button></div>
      <p role="status" className="mt-3 whitespace-pre-line text-secondary">{notice || status}</p>
    </form><p className="mx-4 text-secondary">This browser preview supports direct FLAC with media CORS enabled. Sources requiring a verified physical lossless output are skipped because browsers cannot confirm that route. Segmented addon playback remains Android-only.</p>
    <p className="m-4"><a href="https://justshivv.github.io/Opentune/addons/">Build an OpenTune add-on ↗</a></p>
  </div></div>;
}
