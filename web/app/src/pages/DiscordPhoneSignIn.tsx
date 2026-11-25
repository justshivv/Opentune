import { useEffect, useMemo, useState } from 'react';
import { renderSVG } from 'uqr';
import { useExtension } from '@/extension/useExtension';
import { startPhoneSignIn, type PhoneSignInState } from '@/extension/phoneSignIn';
import { settingsUi } from './settingsStyles';

const primary = 'flex-none rounded-pill bg-key-bg px-5 py-2 type-body-emphasized text-white disabled:opacity-45';
const secondary = 'flex-none rounded-pill bg-quaternary px-4 py-2 type-body-emphasized text-primary disabled:opacity-45';

/**
 * Discord from the phone: a code the phone scans, after which the phone, once you approve
 * there, hands this browser's OpenTune extension its Discord sign-in, sealed so that only
 * the extension can open it. Shown only with an extension that can take it.
 */
export function DiscordPhoneSignIn() {
  const extension = useExtension();
  const present = extension.state === 'present' ? extension.extension : null;
  const able = !!present?.info.capabilities.includes('discord.phone');
  const [attempt, setAttempt] = useState(0);
  const [state, setState] = useState<PhoneSignInState | null>(null);

  useEffect(() => {
    if (!present || !able || attempt === 0) return;
    return startPhoneSignIn(present.bridge, setState);
  }, [present, able, attempt]);

  const link = state?.step === 'code' ? state.link : null;
  const qr = useMemo(() => (link ? renderSVG(link, { ecc: 'M', border: 0, pixelSize: 4, blackColor: '#000', whiteColor: '#fff' }) : null), [link]);

  if (!able) return null;
  const again = () => setAttempt((n) => n + 1);

  return (
    <>
      <h2 className={settingsUi.sectionHeader}>Or sign in with your phone</h2>
      <section className={`${settingsUi.group} flex flex-wrap items-center gap-5 p-4`}>
        {qr && <div className="size-36 flex-none rounded-[16px] bg-white p-3" role="img" aria-label="QR code that signs in with your phone" dangerouslySetInnerHTML={{ __html: qr }} />}
        <div className="min-w-56 flex-1">
          {state === null && (
            <>
              <p className="type-body-emphasized text-primary">Use the Discord sign-in on your phone</p>
              <p className="mt-1 type-callout text-secondary">If NepTune on your phone is signed in to Discord, scan a code with it and approve there. No discord.com tab needed.</p>
              <button className={`${primary} mt-3`} onClick={again}>Show the code</button>
            </>
          )}
          {state?.step === 'starting' && <p className="type-callout text-secondary">Making a code…</p>}
          {state?.step === 'access' && (
            <>
              <p className="type-body-emphasized text-primary">Allow discord.com first</p>
              <p className="mt-1 type-callout text-secondary">The extension needs to reach discord.com to show what you play. Allow it on the page that opens, then make the code.</p>
              <div className="mt-3 flex flex-wrap gap-2">
                <button className={primary} onClick={() => void present?.bridge.call('discord.allowAccess', {}).catch(() => undefined)}>Allow</button>
                <button className={secondary} onClick={again}>Make the code</button>
              </div>
            </>
          )}
          {state?.step === 'code' && (
            <>
              <p className="type-body-emphasized text-primary">Scan this with your phone</p>
              <p className="mt-1 type-callout text-secondary">
                With the phone’s camera, or in NepTune: Settings › Link a device › Computer › Open the camera. Approve on the phone. The code lasts two minutes.
              </p>
            </>
          )}
          {state?.step === 'done' && <p className="type-body-emphasized text-primary">{state.name ? `Signed in as ${state.name}.` : 'Signed in.'}</p>}
          {state?.step === 'expired' && (
            <>
              <p className="type-callout text-secondary">That code ran out.</p>
              <button className={`${secondary} mt-3`} onClick={again}>New code</button>
            </>
          )}
          {state?.step === 'failed' && (
            <>
              <p className="type-callout text-[var(--systemRed)]">{state.message}</p>
              <button className={`${secondary} mt-3`} onClick={again}>Try again</button>
            </>
          )}
        </div>
      </section>
      <p className={settingsUi.footer}>
        The phone seals its Discord session for this browser’s extension alone and sends it over public relays that can’t read it. Signing out here signs out only here.
      </p>
    </>
  );
}
