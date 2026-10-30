import { Check, Copy, Crown, LogOut, Send, UserMinus, Users } from 'lucide-react';
import { useEffect, useRef, useState } from 'react';
import { useParams } from 'react-router';
import { PageHeader } from '@/components/PageHeader';
import { approve, createRoom, dismissEnded, joinRoom, leaveRoom, parseCode, reject, removeMember, savedName, say, setAskToJoin, setOpen, useRoom } from '@/together/room';
import type { RoomView } from '@/together/session';
import { Toggle } from './IntegrationSettings';
import { settingsUi } from './settingsStyles';

const primary = 'flex-none rounded-pill bg-key-bg px-5 py-2 type-body-emphasized text-white disabled:opacity-45';
const secondary = 'flex-none rounded-pill bg-quaternary px-4 py-2 type-body-emphasized text-primary disabled:opacity-45';
const field = 'min-w-0 flex-1 rounded-[12px] border-0 bg-quaternary px-3 py-2.5 type-body text-primary';
const pretty = (raw: string) => raw.match(/.{4}/g)?.join('-') ?? raw;

const PHASE: Record<RoomView['phase'], string> = {
  connecting: 'Connecting to the relays…',
  finding: 'Looking for the host…',
  waiting: 'Waiting for the host to let you in…',
  live: 'Listening together',
  lost: 'Lost touch with the host. Trying again…',
  ended: 'The room has ended',
};

/**
 * Listen Together, the same rooms as the Android app: start one here or join
 * one by its code. Opened from Settings and from invite links (/invite/:code),
 * where joining waits for a tap, since a browser lets audio start only after
 * the listener has used the page.
 */
export default function ListenTogetherPage() {
  const params = useParams();
  const view = useRoom((s) => s.view);
  const invite = parseCode(params.code);
  return (
    <div className={settingsUi.centered}>
      <PageHeader title="Listen Together" back={{ to: '/settings', label: 'Settings' }} />
      <div className={settingsUi.body}>
        {view ? <Room view={view} /> : <StartOrJoin invite={invite} />}
      </div>
    </div>
  );
}

function StartOrJoin({ invite }: { invite: string | null }) {
  const [name, setName] = useState(savedName);
  const [code, setCode] = useState(invite ? pretty(invite) : '');
  const [error, setError] = useState('');
  const starting = useRoom((s) => s.starting);
  const join = () => void joinRoom(code, name).then((ok) => setError(ok ? '' : 'That isn’t a room code. It looks like K7QX-M2PA-9DTE.'));
  return (
    <>
      <p className="mx-4 mb-5 type-body-tall text-secondary">
        Play the same music as your friends, in step, wherever they are. Rooms work across the web player and the Android app. Everyone plays the music from their own device; only what’s playing and where travels, encrypted with the room’s code.
      </p>
      <section className={`${settingsUi.group} p-4`}>
        <label className="mb-1.5 block type-callout-emphasized text-secondary" htmlFor="together-name">Your name</label>
        <input id="together-name" className={`${field} w-full`} value={name} maxLength={30} placeholder="How others see you" onChange={(e) => setName(e.currentTarget.value)} />
      </section>
      {invite ? (
        <section className={`${settingsUi.group} mt-4 flex flex-col items-center gap-3 p-6 text-center`}>
          <Users size={28} className="text-key" />
          <p className="type-title-3-emphasized">You’re invited to a room</p>
          <p className="type-[700_28px/34px] tracking-[0.14em]">{pretty(invite)}</p>
          <button className={primary} disabled={starting} onClick={join}>{starting ? 'Joining…' : 'Join the room'}</button>
        </section>
      ) : (
        <>
          <h2 className={settingsUi.sectionHeader}>Start a room</h2>
          <section className={`${settingsUi.group} flex flex-wrap items-center justify-between gap-3 p-4`}>
            <span className="type-body text-secondary">Others follow what you play. You choose whether they can control it.</span>
            <button className={primary} disabled={starting} onClick={() => void createRoom(name)}>{starting ? 'Starting…' : 'Start a room'}</button>
          </section>
          <h2 className={settingsUi.sectionHeader}>Join a room</h2>
          <form className={`${settingsUi.group} flex gap-3 p-4`} onSubmit={(e) => { e.preventDefault(); join(); }}>
            <input className={`${field} uppercase tracking-[0.08em]`} value={code} placeholder="Room code or invite link" aria-label="Room code" onChange={(e) => setCode(e.currentTarget.value)} />
            <button className={primary} disabled={starting || !code.trim()}>{starting ? 'Joining…' : 'Join'}</button>
          </form>
        </>
      )}
      {error && <p className="mx-4 mt-3 type-callout text-red">{error}</p>}
    </>
  );
}

function Room({ view }: { view: RoomView }) {
  const [copied, setCopied] = useState<'code' | 'link' | null>(null);
  const copy = (what: 'code' | 'link') => {
    const text = what === 'code' ? pretty(view.code) : `https://justshivv.github.io/Opentune/together/#code=${view.code}`;
    void navigator.clipboard.writeText(text).then(() => { setCopied(what); setTimeout(() => setCopied(null), 1500); }, () => undefined);
  };
  const ended = view.phase === 'ended';
  return (
    <>
      <section className={`${settingsUi.group} ot-room-card flex flex-col items-center gap-2 px-4 py-7 text-center`}>
        <span className="ot-room-status" data-phase={view.phase}>{ended ? view.endedReason ?? PHASE.ended : PHASE[view.phase]}</span>
        <p className="type-[700_40px/48px] tracking-[0.16em] max-sm:text-[30px]">{pretty(view.code)}</p>
        <p className="type-callout text-secondary">
          {view.hosting ? 'Your room' : `${view.hostName ?? 'The host'}’s room`} · {view.relays} {view.relays === 1 ? 'relay' : 'relays'} connected
        </p>
        {!ended && (
          <div className="mt-2 flex flex-wrap justify-center gap-2">
            <button className={secondary} onClick={() => copy('code')}>{copied === 'code' ? <Check size={16} className="inline" /> : <Copy size={16} className="inline" />} Copy code</button>
            <button className={secondary} onClick={() => copy('link')}>{copied === 'link' ? <Check size={16} className="inline" /> : <Copy size={16} className="inline" />} Copy invite link</button>
          </div>
        )}
        {view.track && (
          <p className="mt-3 type-body text-secondary">Now playing: <span className="text-primary">{view.track.title}</span> · {view.track.artist}</p>
        )}
        {view.hostPrivate && <p className="mt-3 type-callout text-secondary">The host is playing something only their device has.</p>}
        {view.holding && <p className="mt-3 type-callout text-secondary">Paused for you. The room keeps playing; press play to catch up.</p>}
      </section>

      {view.hosting && view.requests.length > 0 && (
        <>
          <h2 className={settingsUi.sectionHeader}>Wants to join</h2>
          <section className={settingsUi.group}>
            {view.requests.map((r) => (
              <div key={r.id} className={settingsUi.row}>
                <span>{r.name}</span>
                <span className="flex gap-2">
                  <button className={secondary} onClick={() => reject(r.id)}>Decline</button>
                  <button className={primary} onClick={() => approve(r.id)}>Let in</button>
                </span>
              </div>
            ))}
          </section>
        </>
      )}

      <h2 className={settingsUi.sectionHeader}>In the room</h2>
      <section className={settingsUi.group}>
        {view.members.map((m) => (
          <div key={m.id} className={settingsUi.row}>
            <span className="flex items-center gap-2">
              {m.name}{m.id === view.myId && <span className="text-secondary"> (you)</span>}
              {m.host && <Crown size={15} className="text-key" aria-label="Host" />}
            </span>
            {view.hosting && !m.host && (
              <button className="text-secondary hover:text-red" aria-label={`Remove ${m.name}`} onClick={() => removeMember(m.id)}><UserMinus size={18} /></button>
            )}
          </div>
        ))}
      </section>

      {view.hosting && (
        <>
          <h2 className={settingsUi.sectionHeader}>Your room</h2>
          <section className={settingsUi.group}>
            <Toggle label="Ask to join" detail="People with the code wait until you let them in." checked={view.askToJoin} onChange={setAskToJoin} />
            <Toggle label="Let everyone control playback" detail="Guests can play, pause, seek and skip for the whole room." checked={view.open} onChange={setOpen} />
          </section>
        </>
      )}
      {!view.hosting && !view.open && view.phase === 'live' && (
        <p className={settingsUi.footer}>Songs you pick go to the room’s queue. Only {view.hostName ?? 'the host'} can change what’s playing.</p>
      )}

      {!ended && <Chat view={view} />}

      <div className="mt-6 flex justify-center">
        {ended ? (
          <button className={primary} onClick={dismissEnded}>Done</button>
        ) : (
          <button className={`${secondary} text-red`} onClick={leaveRoom}><LogOut size={16} className="inline" /> {view.hosting ? 'End the room' : 'Leave the room'}</button>
        )}
      </div>
    </>
  );
}

function Chat({ view }: { view: RoomView }) {
  const [text, setText] = useState('');
  const list = useRef<HTMLOListElement>(null);
  useEffect(() => {
    list.current?.scrollTo({ top: list.current.scrollHeight });
  }, [view.chat.length]);
  return (
    <>
      <h2 className={settingsUi.sectionHeader}>Chat</h2>
      <section className={`${settingsUi.group} p-3`}>
        <ol ref={list} className="ot-room-chat" aria-live="polite">
          {view.chat.map((line, i) =>
            line.system ? (
              <li key={i} className="ot-chat-system">{line.text}</li>
            ) : (
              <li key={i} className="ot-chat-line" data-mine={line.mine || undefined}><strong>{line.mine ? 'You' : line.name}</strong><span>{line.text}</span></li>
            ),
          )}
        </ol>
        <form className="mt-2 flex gap-2" onSubmit={(e) => { e.preventDefault(); say(text); setText(''); }}>
          <input className={field} value={text} maxLength={300} placeholder="Say something" aria-label="Message" onChange={(e) => setText(e.currentTarget.value)} />
          <button className={primary} aria-label="Send" disabled={!text.trim()}><Send size={16} /></button>
        </form>
      </section>
    </>
  );
}
