import { useState } from 'react';
import { PageHeader } from '@/components/PageHeader';
import { addSource, allowSource, useAddons } from '@/addons/client';
import { settingsUi } from './settingsStyles';
export default function AudioAddonsPage() {
  const { sources, update, status } = useAddons();
  const [url, setUrl] = useState(''); const [notice, setNotice] = useState(''); const [busy, setBusy] = useState(false);
  const button = 'rounded-pill bg-quinary px-4 py-2 disabled:opacity-45';
  function move(index: number, delta: number) { const next = [...sources]; const item = next.splice(index, 1)[0]!; next.splice(index + delta, 0, item); update(next); }
  return <div className={settingsUi.centered}><PageHeader title="Audio sources" /><div className={settingsUi.body}>
    <p className="mx-4 mb-4 text-secondary">Your sources, in your order. OpenTune checks matching recordings and chooses the highest measured FLAC quality. Equal quality keeps your priority.</p>
    <section className={settingsUi.group} aria-label="Source priority">{sources.map((s, i) => <div key={s.id} className="p-4 border-b border-quinary">
      <div className="flex items-center gap-3"><span className="text-secondary">{i + 1}</span><strong className="flex-1">{s.name}</strong><label><input type="checkbox" checked={s.enabled} onChange={e => update(sources.map(x => x.id === s.id ? { ...x, enabled: e.target.checked } : x))} /> Enabled</label></div>
      <div className="flex flex-wrap gap-2 mt-3"><button className={button} disabled={!i} onClick={() => move(i, -1)}>Move up</button><button className={button} disabled={i === sources.length - 1} onClick={() => move(i, 1)}>Move down</button><button className={button} onClick={() => update(sources.filter(x => x.id !== s.id))}>Remove</button></div>
    </div>)}<div className="p-4">YouTube Music <span className="text-secondary">· fallback through the extension</span></div></section>
    <form className="p-4" onSubmit={e => { e.preventDefault(); setBusy(true); void addSource(url).then(() => { setUrl(''); setNotice('Source added'); }).catch(e => setNotice(e instanceof Error ? e.message : 'Could not add source')).finally(() => setBusy(false)); }}>
      <label htmlFor="addon-url">Add a source URL</label><input id="addon-url" type="url" required value={url} onChange={e => setUrl(e.target.value)} placeholder="https://your-addon.example/manifest.json" className="block w-full p-3 my-3 rounded-lg bg-quinary" />
      <div className="flex flex-wrap gap-2"><button className={button} disabled={busy}>{busy ? 'Checking…' : 'Add source'}</button><button type="button" className={button} onClick={() => void allowSource(url).then(() => setNotice('Approve access in the extension tab, then add the source again.')).catch(e => setNotice(e instanceof Error ? e.message : 'Permission unavailable'))}>Allow through extension</button></div>
      <p role="status" className="mt-3 whitespace-pre-line text-secondary">{notice || status}</p>
    </form><p className="mx-4 text-secondary">This browser preview supports direct FLAC with media CORS enabled. Sources requiring a verified physical lossless output are skipped because browsers cannot confirm that route. Segmented addon playback remains Android-only.</p>
    <p className="m-4"><a href="https://justshivv.github.io/Opentune/addons/">Build an OpenTune add-on ↗</a></p>
  </div></div>;
}
