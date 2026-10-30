import { ArrowUpRight } from 'lucide-react';
import { useEffect, useState } from 'react';
import { SiYoutubemusic } from 'react-icons/si';
import { PageHeader } from '@/components/PageHeader';
import { addSource, allowSource, allowSources, checkAccess, syncPublished, useAddons } from '@/addons/client';
import { JIOSAAVN_ORIGIN } from '@/addons/jiosaavn';
import { SourceList } from './SourceList';
import { settingsUi } from './settingsStyles';
import { AddonVerification } from './AddonVerification';
import { isOfficialAddon } from '@/addons/verification';

const message = (e: unknown, fallback: string) => (e instanceof Error ? e.message : fallback);

export default function AudioAddonsPage() {
  const status = useAddons(s => s.status);
  const jioSaavn = useAddons(s => s.jioSaavn);
  const setJioSaavn = useAddons(s => s.setJioSaavn);
  const [url, setUrl] = useState(''); const [notice, setNotice] = useState(''); const [busy, setBusy] = useState(false);
  const sources = useAddons(s => s.sources);
  const locked = useAddons(s => s.locked);
  const waiting = sources.filter(s => s.enabled && !s.ownerOff && locked.includes(s.id));
  // The owner's published sources, looked at afresh each time this page opens; then which hosts
  // still need the extension's OK, again on coming back from the extension's tab.
  useEffect(() => {
    void syncPublished(true).then(() => checkAccess());
    const again = () => { if (document.visibilityState === 'visible') void checkAccess(); };
    document.addEventListener('visibilitychange', again);
    return () => document.removeEventListener('visibilitychange', again);
  }, []);
  const add = () => {
    setBusy(true);
    void addSource(url)
      .then(() => { setUrl(''); setNotice('Source added'); })
      .catch(e => setNotice(message(e, 'Could not add source')))
      .finally(() => setBusy(false));
  };

  return <div className={settingsUi.centered}>
    <PageHeader title="Audio sources" back={{ to: '/settings', label: 'Settings' }} />
    <div className={settingsUi.body}>
      <p className="mx-4 mb-5 max-w-[64ch] type-body-reduced text-secondary text-pretty">Your sources, in your order. OpenTune looks for the same recording on each and plays the highest measured FLAC quality; equal quality keeps your order. Sources marked “Added by admin” are published by OpenTune, and you can turn them off or move them like your own.</p>
      {sources.some(source => isOfficialAddon(source.url)) && <AddonVerification />}
      {waiting.length > 0 && <div className="ot-access mb-5" role="status">
        <p><strong>{waiting.length === 1 ? `${waiting[0]!.name} needs your OK` : `${waiting.length} sources need your OK`}</strong>
          {waiting.length === 1 ? ' It' : ' They'} don’t let web pages ask {waiting.length === 1 ? 'it' : 'them'} directly, so the web player goes through the extension, which asks you once per host.</p>
        <button type="button" className="ot-allow" onClick={() => void allowSources(waiting).then(() => setNotice('Press Allow in the extension tab that opened, then come back here.')).catch(e => setNotice(message(e, 'Permission unavailable')))}>{waiting.length === 1 ? 'Allow' : 'Allow all'}</button>
      </div>}

      <h2 className={settingsUi.sectionHeader}>Lossless add-ons</h2>
      <section className={settingsUi.group} aria-label="Source priority">
        <SourceList />
        {sources.length === 0 && <p className="border-b-[0.5px] border-divider px-4 py-[13px] type-body-tall text-secondary">No add-ons yet. Add one by its URL below, or wait for the ones OpenTune publishes.</p>}
        <div className={settingsUi.integration}>
          <span className={settingsUi.integrationIcon} style={{ backgroundColor: '#fff', color: '#ff0033' }}><SiYoutubemusic size={15} /></span>
          <span className={settingsUi.rowText}>
            <span>YouTube Music</span>
            <span className={settingsUi.rowDetail}>Always last: what plays when no source has the song</span>
          </span>
        </div>
      </section>
      {sources.length > 1 && <p className={settingsUi.footer}>Drag the handle, or focus it and use the arrow keys, to change the order.</p>}

      <h2 className={settingsUi.sectionHeader}>JioSaavn</h2>
      <section className={settingsUi.group} aria-label="JioSaavn">
        <label className={`${settingsUi.integration} cursor-pointer`}>
          <span className="ot-saavn-mark" aria-hidden="true">J</span>
          <span className={settingsUi.rowText}>
            <span>JioSaavn</span>
            <span className={settingsUi.rowDetail}>320 kbps AAC, while FLAC is looked for and when there is none</span>
          </span>
          <input type="checkbox" role="switch" className={settingsUi.switch} checked={jioSaavn} onChange={e => setJioSaavn(e.target.checked)} />
        </label>
        <div className={`${settingsUi.integrationPlain} !grid-cols-[minmax(0,1fr)_auto]`}>
          <span className={settingsUi.rowText}>
            <span>Access to JioSaavn</span>
            <span className={settingsUi.rowDetail}>Its catalog is read through the extension: allow <code className="font-mono">www.jiosaavn.com</code> once.</span>
          </span>
          <button type="button" className={settingsUi.inlineButton} onClick={() => void allowSource(JIOSAAVN_ORIGIN).then(() => setNotice('Approve JioSaavn in the extension tab that opened.')).catch(e => setNotice(message(e, 'Permission unavailable')))}>Allow</button>
        </div>
      </section>

      <h2 className={settingsUi.sectionHeader}>Add a source</h2>
      <form className={`${settingsUi.group} p-4`} onSubmit={e => { e.preventDefault(); add(); }}>
        <label htmlFor="addon-url" className="sr-only">Source URL</label>
        <div className="flex flex-wrap gap-2">
          <input id="addon-url" type="url" required value={url} onChange={e => setUrl(e.target.value)} placeholder="https://your-addon.example/manifest.json" className="ot-field min-w-0 flex-[1_1_240px]" />
          <button className="ot-allow !mt-0 flex-none !px-5 !py-2 disabled:opacity-50" disabled={busy || !url}>{busy ? 'Checking…' : 'Add'}</button>
        </div>
        <p className="mt-3 type-callout text-secondary">
          A server that refuses web pages can still be added:{' '}
          <button type="button" className="text-key hover:underline" onClick={() => void allowSource(url).then(() => setNotice('Approve access in the extension tab, then add the source again.')).catch(e => setNotice(message(e, 'Permission unavailable')))}>allow it through the extension</button>
          , then add it again.
        </p>
        {(notice || status) && <p role="status" className="mt-3 whitespace-pre-line type-body-tall text-primary">{notice || status}</p>}
      </form>
      <p className={settingsUi.footer}>The web player plays direct FLAC from servers that allow it. Sources that need a verified lossless output are skipped, because a browser can’t confirm that route, and segmented add-on playback is in the Android app only.</p>
      <p className="mx-4 mt-4">
        <a href="https://justshivv.github.io/Opentune/addons/" target="_blank" rel="noreferrer" className="inline-flex items-center gap-1 type-body-emphasized text-key hover:underline">
          Build an OpenTune add-on <ArrowUpRight size={14} />
        </a>
      </p>
    </div>
  </div>;
}
