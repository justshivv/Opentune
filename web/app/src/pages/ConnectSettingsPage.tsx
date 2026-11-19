import { AudioLines, Check, Copy } from 'lucide-react';
import { useEffect, useMemo, useState } from 'react';
import { useNavigate, useSearchParams } from 'react-router';
import { renderSVG } from 'uqr';
import { PageHeader } from '@/components/PageHeader';
import { disconnectAll, ensureGroup, joinGroup, setAccountSync, setDeviceName, setEnabled, transferTo, useConnect } from '@/connect/connect';
import { DeviceIcon, doing } from '@/connect/parts';
import { Toggle } from './IntegrationSettings';
import { settingsUi } from './settingsStyles';

const primary = 'flex-none rounded-pill bg-key-bg px-5 py-2 type-body-emphasized text-white disabled:opacity-45';
const secondary = 'flex-none rounded-pill bg-quaternary px-4 py-2 type-body-emphasized text-primary disabled:opacity-45';
const field = 'min-w-0 flex-1 rounded-[12px] border-0 bg-quaternary px-3 py-2.5 type-body text-primary';
// A device, as in the player's Devices panel: its icon on a tile, its name over what it's doing,
// the playing mark at the end. A click moves the music there.
const deviceRow = 'relative grid w-full grid-cols-[40px_minmax(0,1fr)_24px] items-center gap-x-3 px-4 py-3 text-left transition-colors duration-150 hover:bg-quaternary hairline-[68px]';
const deviceTile = 'grid size-10 place-items-center rounded-[10px] bg-quaternary text-secondary';

/**
 * Settings › Your devices: this browser among the person's phones and
 * computers. Adding the phone is scanning the code here with it; a link from
 * the phone (/settings/devices?join=…) adds this browser.
 */
export default function ConnectSettingsPage() {
  const saved = useConnect((s) => s.saved);
  const link = useConnect((s) => s.link);
  const view = useConnect((s) => s.view);
  const [params] = useSearchParams();
  const navigate = useNavigate();
  const [paste, setPaste] = useState('');
  const [error, setError] = useState('');
  const [name, setName] = useState(saved.name);
  const [copied, setCopied] = useState(false);
  const [confirmReset, setConfirmReset] = useState(false);

  // A link from another device: straight in. This browser's other devices come along.
  useEffect(() => {
    const join = params.get('join');
    if (!join) return;
    navigate('/settings/devices', { replace: true });
    joinGroup(join);
  }, [params, navigate]);

  // Every visit to this page has a group to show: made here if this is the first device.
  useEffect(() => {
    if (!saved.secret) void ensureGroup();
  }, [saved.secret]);

  const qr = useMemo(() => (link ? renderSVG(link.app, { ecc: 'M', border: 0, pixelSize: 4, blackColor: '#000', whiteColor: '#fff' }) : null), [link]);

  const add = () => {
    if (!joinGroup(paste)) return setError('That isn’t a devices link. Copy it from OpenTune on your phone: Settings › Link a device › Your devices.');
    setError('');
    setPaste('');
  };
  const copy = () => {
    if (!link) return;
    void navigator.clipboard.writeText(link.web).then(() => {
      setCopied(true);
      setTimeout(() => setCopied(false), 1600);
    });
  };

  return (
    <div className={settingsUi.centered}>
      <PageHeader title="Your devices" back={{ to: '/settings', label: 'Settings' }} />
      <div className={settingsUi.body}>
        <p className="mx-4 mb-5 type-body-tall text-secondary">
          See what plays on your phone from here and control it, or move the music from one to the other. Only your own devices can read what they say to each other.
        </p>

        <section className={settingsUi.group}>
          <Toggle label="Connect to your devices" checked={saved.enabled} onChange={setEnabled} />
          <div className={`${settingsUi.controlRow} gap-3`}>
            <span className={settingsUi.rowText}>This browser’s name</span>
            <input
              className={`${field} max-w-60`}
              value={name}
              maxLength={40}
              onChange={(e) => setName(e.currentTarget.value)}
              onBlur={() => setDeviceName(name)}
              onKeyDown={(e) => { if (e.key === 'Enter') e.currentTarget.blur(); }}
            />
          </div>
        </section>

        <h3 className={settingsUi.sectionHeader}>Add your phone</h3>
        <section className={`${settingsUi.group} flex flex-wrap items-center gap-5 p-4`}>
          {qr && <div className="size-36 flex-none rounded-[16px] bg-white p-3" role="img" aria-label="QR code that adds a phone" dangerouslySetInnerHTML={{ __html: qr }} />}
          <div className="min-w-56 flex-1">
            <p className="type-body-emphasized text-primary">Scan this with your phone</p>
            <p className="mt-1 type-callout text-secondary">
              With the phone’s camera, or in OpenTune: Settings › Link a device › In your browser › Your devices › Scan a code.
            </p>
            <button className={`${secondary} mt-3 inline-flex items-center gap-2`} onClick={copy} disabled={!link}>
              {copied ? <Check size={16} /> : <Copy size={16} />}
              {copied ? 'Copied' : 'Copy link'}
            </button>
          </div>
        </section>

        <h3 className={settingsUi.sectionHeader}>Or paste a link from your phone</h3>
        <section className={`${settingsUi.group} p-4`}>
          <div className="flex gap-2">
            <input className={field} value={paste} placeholder="https://justshivv.github.io/Opentune/player/#/settings/devices?join=…" onChange={(e) => setPaste(e.currentTarget.value)} onKeyDown={(e) => { if (e.key === 'Enter') add(); }} />
            <button className={primary} onClick={add} disabled={!paste.trim()}>Add</button>
          </div>
          {error && <p className="mt-2 type-callout text-[var(--systemRed)]">{error}</p>}
        </section>

        <section className={`${settingsUi.group} mt-5`}>
          <Toggle
            label="Sync through my YouTube account"
            detail="Devices signed in to the same account join by themselves. Keeps a private, empty playlist named OpenTune Connect in your library."
            checked={saved.accountSync === true}
            onChange={setAccountSync}
          />
        </section>

        <h3 className={settingsUi.sectionHeader}>Here now</h3>
        {view ? (
          <section className={settingsUi.group}>
            {[{ id: view.myId, name: `${saved.name || 'This browser'} (this browser)`, form: 'web' as const, line: null }, ...view.devices.map((d) => ({ id: d.id, name: d.name || 'Another device', form: d.form, line: doing(d) }))].map((d) => {
              const active = view.activeId === d.id;
              return (
                <button key={d.id} className={deviceRow} onClick={() => transferTo(d.id)} title={active ? 'Playing here' : 'Play here'}>
                  <span className={deviceTile}><DeviceIcon form={d.form} /></span>
                  <span className="min-w-0">
                    <span className={`block truncate type-body-emphasized ${active ? 'text-key' : 'text-primary'}`}>{d.name}</span>
                    <span className={`block truncate type-callout ${active ? 'text-key' : 'text-secondary'}`}>{d.line ?? (active ? 'Playing here' : 'Ready to play')}</span>
                  </span>
                  {active ? <AudioLines size={18} className="text-key" /> : <span />}
                </button>
              );
            })}
          </section>
        ) : null}
        {view && !view.devices.length && (
          <p className={settingsUi.footer}>{view.relays ? 'Open OpenTune on your phone and it shows up here.' : 'Connecting…'}</p>
        )}

        <section className={`${settingsUi.group} mt-5 p-4`}>
          {confirmReset ? (
            <>
              <p className="type-body-emphasized text-primary">Disconnect all devices?</p>
              <p className="mt-1 type-callout text-secondary">Every other device stops seeing this one until you add it again.</p>
              <div className="mt-3 flex gap-2">
                <button className={primary} onClick={() => { void disconnectAll(); setConfirmReset(false); }}>Disconnect</button>
                <button className={secondary} onClick={() => setConfirmReset(false)}>Cancel</button>
              </div>
            </>
          ) : (
            <button className={secondary} onClick={() => setConfirmReset(true)}>Disconnect all devices</button>
          )}
        </section>
      </div>
    </div>
  );
}
