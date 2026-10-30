import { useState } from 'react';
import { PageHeader } from '@/components/PageHeader';
import { buildHasKeys, cancelSignIn, flush, loginKeys, signIn, useLastFm } from '@/playback/lastfm';
import { Toggle } from './IntegrationSettings';
import { settingsUi } from './settingsStyles';

/** Last.fm (LastFmDialog on the phone): connect the account, scrobble or not, and what's waiting to be sent. */
export default function LastFmSettingsPage() {
  const session = useLastFm((s) => s.session);
  const scrobbling = useLastFm((s) => s.scrobbling);
  const queued = useLastFm((s) => s.queue.length);
  const { setScrobbling, signOut, setOwn } = useLastFm.getState();
  const [waiting, setWaiting] = useState(false);
  const [problem, setProblem] = useState('');
  const [key, setKey] = useState(useLastFm.getState().ownKey);
  const [secret, setSecret] = useState(useLastFm.getState().ownSecret);
  const keysReady = buildHasKeys() || (!!key.trim() && !!secret.trim());

  const connect = () => {
    if (!buildHasKeys()) setOwn(key, secret);
    if (!loginKeys()) return;
    setProblem('');
    setWaiting(true);
    signIn()
      .catch((e: unknown) => setProblem(e instanceof Error ? e.message : 'Couldn’t connect to Last.fm'))
      .finally(() => setWaiting(false));
  };

  return (
    <div className={settingsUi.centered}>
      <PageHeader title="Last.fm" back={{ to: '/settings', label: 'Settings' }} />
      <div className={settingsUi.body}>
        <p className="mx-4 mb-5 max-w-[64ch] type-body-reduced text-secondary text-pretty">
          Songs you play here are added to your Last.fm profile once you’ve heard half of them, or four minutes. Listens made offline are sent when you’re back.
        </p>

        <section className={settingsUi.group}>
          <div className={settingsUi.integrationPlain}>
            <span className={settingsUi.rowText}>
              <span>Account</span>
              {waiting && <span className={settingsUi.rowDetail}>Press “Yes, allow access” on the Last.fm tab that opened.</span>}
            </span>
            {session ? (
              <span className={settingsUi.value}>
                <span>{session.user}</span>
                <button className={settingsUi.inlineButton} onClick={signOut}>Disconnect</button>
              </span>
            ) : waiting ? (
              <span className={settingsUi.value}>
                <span>Waiting for Last.fm…</span>
                <button className={settingsUi.inlineButton} onClick={() => { cancelSignIn(); setWaiting(false); }}>Cancel</button>
              </span>
            ) : (
              <button className={settingsUi.inlineButton} disabled={!keysReady} onClick={connect}>Connect</button>
            )}
          </div>
          {session && (
            <Toggle label="Scrobble" detail="Send what you play here to Last.fm. “Now playing” shows on your profile while a song plays." checked={scrobbling} onChange={setScrobbling} />
          )}
          {session && queued > 0 && (
            <div className={settingsUi.integrationPlain}>
              <span className={settingsUi.rowText}>
                <span>Waiting to send</span>
                <span className={settingsUi.rowDetail}>Last.fm didn’t take these yet; they’re kept until it does.</span>
              </span>
              <span className={settingsUi.value}>
                <span>{queued}</span>
                <button className={settingsUi.inlineButton} onClick={flush}>Send now</button>
              </span>
            </div>
          )}
        </section>
        {problem && <p role="alert" className="mx-4 mt-2 type-body-tall text-red">{problem}</p>}

        {!buildHasKeys() && !session && (
          <>
            <h2 className={settingsUi.sectionHeader}>Your Last.fm API account</h2>
            <section className={`${settingsUi.group} p-4`}>
              <label className="block type-callout-emphasized text-secondary" htmlFor="lastfm-key">API key</label>
              <input id="lastfm-key" className="ot-field mt-1.5 mb-3 w-full" value={key} autoComplete="off" spellCheck={false} onChange={(e) => setKey(e.currentTarget.value)} />
              <label className="block type-callout-emphasized text-secondary" htmlFor="lastfm-secret">Shared secret</label>
              <input id="lastfm-secret" className="ot-field mt-1.5 w-full" type="password" value={secret} autoComplete="off" onChange={(e) => setSecret(e.currentTarget.value)} />
            </section>
            <p className={settingsUi.footer}>
              This copy of the web player has no Last.fm application of its own. Create one free at{' '}
              <a className="text-key hover:underline" href="https://www.last.fm/api/account/create" target="_blank" rel="noreferrer">last.fm/api/account/create</a>{' '}
              and paste its key and secret here. They stay in this browser.
            </p>
          </>
        )}
      </div>
    </div>
  );
}
