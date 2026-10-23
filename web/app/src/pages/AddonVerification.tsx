import { useEffect, useState } from 'react';
import { disconnectVerification, pollPairing, startPairing, verifiedUntil, type Pairing } from '@/addons/verification';

export function AddonVerification() {
  const [pair, setPair] = useState<Pairing | null>(null), [until, setUntil] = useState(0), [message, setMessage] = useState(''), [busy, setBusy] = useState(false);
  useEffect(() => { void verifiedUntil().then(setUntil).catch(() => setMessage('Browser storage is unavailable. Enable storage to verify this browser.')); }, []);
  useEffect(() => {
    if (!pair) return;
    let active = true, timer: ReturnType<typeof setTimeout>;
    const poll = async () => {
      try {
        const ready = await pollPairing(pair); if (!active) return;
        if (ready) { setUntil(await verifiedUntil()); setPair(null); setMessage('Browser verified. The protected source is ready.'); }
        else timer = setTimeout(() => void poll(), 3000);
      } catch (e) { if (active) { setPair(null); setMessage(e instanceof Error ? e.message : 'Verification failed'); } }
    };
    timer = setTimeout(() => void poll(), 3000);
    return () => { active = false; clearTimeout(timer); };
  }, [pair]);
  const button = 'rounded-pill bg-quinary px-4 py-2 disabled:opacity-45';
  return <section className="mx-4 mb-4 rounded-2xl bg-secondary/10 p-4" aria-label="OpenTune source verification">
    <h2 className="font-semibold">Protected OpenTune source</h2>
    <p className="my-3 text-secondary">Approve this browser in the current official Android or TV app. The app must pass device verification. Access lasts up to 12 hours.</p>
    {until > Date.now() && <p className="my-3">Verified until {new Date(until).toLocaleString()}</p>}
    {!pair && <button className={button} disabled={busy} onClick={() => { setBusy(true); setMessage(''); void startPairing().then(setPair).catch(e => setMessage(e instanceof Error ? e.message : 'Could not start verification')).finally(() => setBusy(false)); }}>{busy ? 'Starting…' : until > Date.now() ? 'Verify again' : 'Verify browser'}</button>}
    {until > 0 && <button className={button} onClick={() => void disconnectVerification().then(() => { setUntil(0); setMessage('This browser’s local key and session were removed.'); })}>Disconnect browser</button>}
    {pair && <div className="mt-3 space-y-3">
      <p>Confirmation code: <strong className="font-mono tracking-widest">{pair.code}</strong></p>
      <p className="text-secondary">Open this link on your verified Android or TV device. Approve only when the app shows this same code and website.</p>
      <a className={button} href={pair.link}>Open OpenTune</a>{' '}
      <button className={button} onClick={() => void navigator.clipboard.writeText(pair.link).then(() => setMessage('Approval link copied. Open it on your verified device.')).catch(() => setMessage('Copy the approval link below.'))}>Copy approval link</button>
      <input aria-label="Browser approval link" readOnly value={pair.link} className="block w-full bg-quinary p-2 rounded-lg" onFocus={e => e.currentTarget.select()} />
      <button className={button} onClick={() => { setPair(null); setMessage('Request cancelled locally. It expires automatically in five minutes.'); }}>Cancel</button>
      <p className="text-secondary">Waiting for your approval…</p>
    </div>}
    <p className="mt-3 text-secondary" role="status">{message}</p>
  </section>;
}
