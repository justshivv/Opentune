import type { Bridge } from '@opentune/shared/bridge';
import { SHARE_KIND, shareLink, sharePacketOf, shareTag } from '@opentune/shared/share';
import { newPrivateKey } from '@/together/crypto';
import { RelayPool, signedEvent, type NostrEvent } from '@/together/nostr';
import { RELAYS, type TransportFactory } from '@/together/session';

/**
 * "Sign in with your phone" for Discord: the phone's sign-in comes to the extension sealed
 * for a one-use key only the extension holds (web/shared/src/share). This page shows the
 * code, listens on the relays, and passes what arrives to the extension unopened; it never
 * sees the token. The extension's sealed answer goes back the same way, so the phone knows.
 */
export type PhoneSignInState =
  | { step: 'starting' }
  /** The extension may not reach discord.com yet. */
  | { step: 'access' }
  | { step: 'code'; link: string; expires: number }
  | { step: 'done'; name: string | null }
  | { step: 'expired' }
  | { step: 'failed'; message: string };

const relays: TransportFactory = (filter, onEvent, onConnected) => new RelayPool(RELAYS, filter, onEvent, onConnected);

/** How often, and how many times, the answer is sent: the phone may hear it on a relay that connected late. */
const ANSWER_EVERY_MS = 2_000;
const ANSWER_TIMES = 5;

/** Starts a code; the returned function stops listening (leaving the page, or a new code). */
export function startPhoneSignIn(bridge: Bridge, onState: (state: PhoneSignInState) => void, transport: TransportFactory = relays): () => void {
  let stopped = false;
  let done = false;
  let queue: Promise<void> = Promise.resolve();
  let pool: ReturnType<TransportFactory> | null = null;
  const timers = new Set<ReturnType<typeof setTimeout>>();
  const later = (ms: number, run: () => void) => {
    const t = setTimeout(() => {
      timers.delete(t);
      run();
    }, ms);
    timers.add(t);
  };
  const stop = () => {
    stopped = true;
    timers.forEach(clearTimeout);
    timers.clear();
    pool?.close();
    pool = null;
  };
  const show = (state: PhoneSignInState) => {
    if (!stopped) onState(state);
  };

  const answer = (event: NostrEvent, left: number) => {
    if (stopped || !pool) return;
    pool.publish(event);
    if (left > 1) later(ANSWER_EVERY_MS, () => answer(event, left - 1));
    else stop();
  };

  (async () => {
    show({ step: 'starting' });
    const offer = await bridge.call('discord.phoneOffer', {});
    if (stopped) return;
    if (!offer.access) return show({ step: 'access' });
    const tag = await shareTag(offer.publicKey);
    if (stopped) return;
    const filter = { kinds: [SHARE_KIND], '#t': [tag], since: Math.floor(Date.now() / 1000) - 10 };
    pool = transport(
      filter,
      (event) => {
        if (done || stopped || event.kind !== SHARE_KIND) return;
        const packet = sharePacketOf(event.content);
        if (!packet) return;
        // One at a time, in the order they came: each relay hands an event on only once.
        queue = queue.then(async () => {
          if (done || stopped) return;
          try {
            const result = await bridge.call('discord.phoneAccept', { phoneKey: packet.k, sealed: packet.s }, { timeoutMs: 30_000 });
            done = true;
            show(
              result.took.includes('discord')
                ? { step: 'done', name: result.state.user?.name ?? null }
                : { step: 'failed', message: 'Discord didn’t accept that sign-in. Sign in to Discord on the phone again, or connect it here instead.' },
            );
            const reply = await signedEvent(newPrivateKey(), SHARE_KIND, [['t', tag]], JSON.stringify({ a: result.answer }));
            answer(reply, ANSWER_TIMES);
          } catch {
            // Not sealed for this code (or not from the phone at all): wait for the one that is.
          }
        });
      },
      () => undefined,
    );
    pool.start();
    show({ step: 'code', link: shareLink(offer.publicKey, offer.expires, ['discord']), expires: offer.expires });
    later(Math.max(0, offer.expires - Date.now()), () => {
      if (done) return;
      show({ step: 'expired' });
      stop();
    });
  })().catch((error: unknown) => {
    show({ step: 'failed', message: error instanceof Error ? error.message : 'The extension couldn’t make a code.' });
    stop();
  });

  return stop;
}
