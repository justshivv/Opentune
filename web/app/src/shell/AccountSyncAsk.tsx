import { useEffect } from 'react';
import { createPortal } from 'react-dom';
import { setAccountSync, syncAccount, useConnect } from '@/connect/connect';
import { useSignedIn } from '@/data/music/queries';

const primary = 'flex-1 rounded-pill bg-key-bg px-5 py-2 type-body-emphasized text-white';
const secondary = 'flex-1 rounded-pill bg-quaternary px-4 py-2 type-body-emphasized text-primary';

/**
 * Asked once per account, after signing in to YouTube: may Connect keep its
 * key in the account? Until the answer is yes, nothing is created there. An
 * account that already holds the key is joined without asking.
 */
export function AccountSyncAsk() {
  const saved = useConnect((s) => s.saved);
  const ask = useConnect((s) => s.askAccount);
  const signedIn = useSignedIn();
  // Signing in later looks in the account then.
  useEffect(() => {
    if (signedIn && saved.enabled && saved.accountSync === null) void syncAccount();
  }, [signedIn, saved.enabled, saved.accountSync]);
  if (!signedIn || !saved.enabled || saved.accountSync !== null || !ask) return null;
  return createPortal(
    <div className="fixed inset-0 z-(--z-modal) grid animate-fade place-items-center bg-scrim p-4 [backdrop-filter:blur(6px)]">
      <div
        className="flex w-[min(440px,100%)] animate-zoom flex-col rounded-[18px] bg-page px-6 pt-6 pb-5 shadow-glass"
        role="dialog"
        aria-modal="true"
        aria-labelledby="account-sync-title"
      >
        <h2 id="account-sync-title" className="type-title-2-emphasized">Find your devices through YouTube?</h2>
        <p className="mt-1.5 type-body text-secondary text-pretty">
          NepTune keeps a private playlist in your YouTube account that holds a key, so your devices find each other.
        </p>
        <div className="mt-5 flex gap-2">
          <button type="button" className={secondary} onClick={() => setAccountSync(false)}>Not now</button>
          <button type="button" className={primary} onClick={() => setAccountSync(true)}>Turn on</button>
        </div>
      </div>
    </div>,
    document.body,
  );
}
