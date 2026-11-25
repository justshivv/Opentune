import { Check } from 'lucide-react';
import { useCallback, useEffect, useState, type ReactNode } from 'react';
import { createPortal } from 'react-dom';
import { allowSources, checkAccess, syncPublished, useAddons } from '@/addons/client';
import { currentBrowser, downloadsFor } from '@/extension/downloads';
import { recheckExtension, useExtension } from '@/extension/useExtension';
import { usePublishedExtension } from '@/extension/useExtensionUpdate';
import { useHostAccess } from '@/extension/useHostAccess';
import { isMobileBrowser } from '@/lib/platform';
import { LYRIC_EXTENSION_ORIGINS } from '@/lyrics/http';

const mobile = isMobileBrowser();
/** Safari has no OpenTune extension: the popup says which browsers do. */
const safari = /^((?!chrome|chromium|android|crios|fxios|edg).)*safari/i.test(navigator.userAgent);
const DONE_KEY = 'opentune:allow-gate-done';
/** How long a returning visitor waits for the extension before a quiet "Connecting…" shows. */
const CONNECTING_HINT_MS = 1500;

function rememberedDone(): boolean {
  try {
    return localStorage.getItem(DONE_KEY) === '1';
  } catch {
    return false;
  }
}

function remember(done: boolean) {
  try {
    if (done) localStorage.setItem(DONE_KEY, '1');
    else localStorage.removeItem(DONE_KEY);
  } catch {
    /* Asked again next time. */
  }
}

type StepState = 'done' | 'waiting' | 'todo' | 'checking';

/**
 * Whether the add-on sources' hosts are all allowed: their manifests are read
 * (through the extension where the page can't) once it is there, and again
 * whenever the tab comes back from the extension's permission page.
 */
function useAddonAccess(present: boolean) {
  const sources = useAddons((s) => s.sources);
  const locked = useAddons((s) => s.locked);
  const [checked, setChecked] = useState(false);
  useEffect(() => {
    if (!present) return;
    let live = true;
    const check = () =>
      void syncPublished()
        .then(() => checkAccess())
        .finally(() => live && setChecked(true));
    check();
    const onVisible = () => document.visibilityState === 'visible' && check();
    window.addEventListener('focus', check);
    document.addEventListener('visibilitychange', onVisible);
    return () => {
      live = false;
      window.removeEventListener('focus', check);
      document.removeEventListener('visibilitychange', onVisible);
    };
  }, [present]);
  const waiting = sources.filter((s) => s.enabled && !s.ownerOff && locked.includes(s.id));
  return { checked, waiting };
}

/**
 * The web player's first-run popup: install the OpenTune extension, then let it
 * reach the lyric providers and the audio add-ons' hosts. The player behind it
 * is locked until all three are done. Phones skip it: their browsers can't run
 * the extension, and the shell already points them at the app.
 */
export function useAllowGate(): { locked: boolean; gate: ReactNode } {
  const extension = useExtension();
  const present = extension.state === 'present';
  const refused = extension.state === 'missing' ? (extension.refused ?? null) : null;
  // It answered on this browser before: while it wakes (or if it has stopped answering) the
  // player isn't put behind the setup popup, only a small note. A refusal still gets the popup.
  const quiet = !mobile && extension.state !== 'present' && !!extension.seenBefore && !refused;
  const lyrics = useHostAccess(LYRIC_EXTENSION_ORIGINS, present ? extension.extension : null);
  const addons = useAddonAccess(present);
  const [asked, setAsked] = useState<'lyrics' | 'addons' | null>(null);
  const [message, setHint] = useState('');

  const extensionStep: StepState = extension.state === 'checking' ? 'checking' : present ? 'done' : 'todo';
  // An extension too old to answer can't be asked either: it doesn't hold the player back.
  const lyricsStep: StepState = !present
    ? 'waiting'
    : lyrics.access === 'checking'
      ? 'checking'
      : lyrics.access === 'needed'
        ? 'todo'
        : 'done';
  const addonsStep: StepState = !present ? 'waiting' : !addons.checked ? 'checking' : addons.waiting.length ? 'todo' : 'done';
  const steps = [extensionStep, lyricsStep, addonsStep];
  const allDone = steps.every((s) => s === 'done');
  // Someone who has been through it before isn't shown it while the checks run, only if one fails.
  const needed = mobile || quiet ? false : steps.includes('todo') || (!rememberedDone() && !allDone);

  // Once open it stays open until "Start Listening", so the last step's tick is seen.
  const [open, setOpen] = useState(false);
  useEffect(() => {
    if (needed) setOpen(true);
  }, [needed]);
  useEffect(() => {
    if (mobile || !present) return;
    // Only a host that is no longer allowed undoes it; the extension being slow to answer doesn't.
    if (allDone) remember(true);
    else if (steps.includes('todo')) remember(false);
  });
  const hint = useConnectingHint(quiet && extension.state === 'checking');

  const allowLyrics = useCallback(() => {
    lyrics.request();
    setAsked('lyrics');
    setHint('Press Allow in the extension tab that opened, then come back here.');
  }, [lyrics]);
  const allowAddons = useCallback(() => {
    setAsked('addons');
    void allowSources(addons.waiting)
      .then(() => setHint('Press Allow in the extension tab that opened, then come back here.'))
      .catch((e: unknown) => setHint(e instanceof Error ? e.message : 'The extension couldn’t open its permission page.'));
  }, [addons.waiting]);

  const shown = needed || open;
  if (!shown) {
    if (quiet && extension.state === 'missing') return { locked: false, gate: <NotResponding /> };
    return { locked: false, gate: hint ? <ConnectingHint /> : null };
  }

  return {
    locked: !allDone,
    gate: (
      <GateDialog
        extensionStep={extensionStep}
        version={present ? extension.extension.info.version : null}
        lyricsStep={lyricsStep}
        addonsStep={addonsStep}
        addonCount={addons.waiting.length}
        asked={asked}
        hint={message}
        refused={refused}
        allDone={allDone}
        onAllowLyrics={allowLyrics}
        onAllowAddons={allowAddons}
        onFinish={() => setOpen(false)}
      />
    ),
  };
}

interface GateProps {
  extensionStep: StepState;
  version: string | null;
  lyricsStep: StepState;
  addonsStep: StepState;
  addonCount: number;
  asked: 'lyrics' | 'addons' | null;
  hint: string;
  refused: { code: string; message: string } | null;
  allDone: boolean;
  onAllowLyrics: () => void;
  onAllowAddons: () => void;
  onFinish: () => void;
}

const allowButton =
  'flex-none rounded-pill bg-key-bg px-3.5 py-1.5 type-callout-emphasized text-white hover:brightness-110 disabled:opacity-50';

function Step({
  n,
  title,
  detail,
  state,
  doneText,
  action,
}: {
  n: number;
  title: string;
  detail: string;
  state: StepState;
  doneText: string;
  action?: ReactNode;
}) {
  return (
    <li className="flex items-start gap-3 py-3" data-step-state={state}>
      <span
        className={`mt-px grid size-6 flex-none place-items-center rounded-full type-callout-emphasized ${
          state === 'done' ? 'bg-green text-white' : state === 'waiting' ? 'bg-quaternary text-tertiary' : 'bg-key-bg text-white'
        }`}
        aria-hidden="true"
      >
        {state === 'done' ? <Check size={14} strokeWidth={3} /> : n}
      </span>
      <span className="flex min-w-0 flex-1 flex-col gap-0.5">
        <span className={`type-body-emphasized ${state === 'waiting' ? 'text-tertiary' : 'text-primary'}`}>{title}</span>
        <span className="type-callout text-secondary text-pretty">{detail}</span>
      </span>
      <span className="flex-none self-center type-callout text-secondary">
        {state === 'done' && doneText}
        {state === 'checking' && 'Checking…'}
        {state === 'todo' && action}
      </span>
    </li>
  );
}

function GateDialog(props: GateProps) {
  const { extensionStep, version, lyricsStep, addonsStep, addonCount, asked, hint, allDone } = props;
  const downloads = downloadsFor(usePublishedExtension());
  const here = downloads.find((d) => d.browser === currentBrowser)!;
  const other = downloads.find((d) => d.browser !== currentBrowser)!;
  const [chosen, setChosen] = useState<typeof here | null>(null);

  return createPortal(
    <div className="fixed inset-0 z-(--z-modal) grid animate-fade place-items-center overflow-y-auto bg-scrim p-4 [backdrop-filter:blur(6px)]">
      <div
        className="flex w-[min(540px,100%)] animate-zoom flex-col rounded-[18px] bg-page px-6 pt-6 pb-5 shadow-glass"
        role="dialog"
        aria-modal="true"
        aria-labelledby="allow-gate-title"
        data-allow-gate=""
      >
        <h2 id="allow-gate-title" className="type-title-2-emphasized">
          {allDone ? 'You’re all set' : 'Set up the web player'}
        </h2>
        <p className="mt-1.5 type-body text-secondary text-pretty">
          {allDone
            ? 'The extension is connected and can reach your lyric providers and audio add-ons.'
            : 'The NepTune extension does the work a web page isn’t allowed to: playback, sign-in, lyrics from most providers and lossless add-ons. Finish these steps to start listening.'}
        </p>

        <ol className="mt-3 divide-y-[0.5px] divide-divider" aria-live="polite">
          <Step
            n={1}
            title="Install the NepTune extension"
            detail={
              safari
                ? 'Safari can’t run it. Open NepTune in Chrome, Edge, Brave or Firefox.'
                : props.refused
                  ? props.refused.code === 'protocol'
                    ? 'The installed extension is too old for this page. Download the new one below and install it over the old one.'
                    : `The installed extension didn’t accept this page: ${props.refused.message}`
                  : 'This page notices it on its own once it’s loaded.'
            }
            state={extensionStep}
            doneText={version ? `Connected · v${version}` : 'Connected'}
            action={<span>Waiting…</span>}
          />
          <Step
            n={2}
            title="Allow lyric providers"
            detail="Musixmatch, KuGou, Genius, Apple Music and PaxSenix don’t answer web pages directly, so the extension asks for them."
            state={lyricsStep}
            doneText="Allowed"
            action={
              <button type="button" className={allowButton} onClick={props.onAllowLyrics}>
                {asked === 'lyrics' ? 'Ask Again' : 'Allow'}
              </button>
            }
          />
          <Step
            n={3}
            title="Allow audio add-ons"
            detail={
              addonsStep === 'todo'
                ? `${addonCount === 1 ? 'One lossless source needs' : `${addonCount} lossless sources need`} the extension to reach ${addonCount === 1 ? 'its host' : 'their hosts'}.`
                : 'Lossless sources whose hosts web pages can’t reach directly.'
            }
            state={addonsStep}
            doneText="Allowed"
            action={
              <button type="button" className={allowButton} onClick={props.onAllowAddons}>
                {asked === 'addons' ? 'Ask Again' : addonCount > 1 ? 'Allow All' : 'Allow'}
              </button>
            }
          />
        </ol>

        {extensionStep === 'todo' && !safari && (
          <div className="mt-2 rounded-[14px] bg-quinary p-3">
            <div className="flex flex-wrap items-center gap-2">
              <a className={`${allowButton} inline-flex items-center gap-2`} href={here.href} download onClick={() => setChosen(here)}>
                <here.Icon size={15} />
                {here.title}
              </a>
              <a
                className="rounded-pill px-3 py-1.5 type-callout-emphasized text-key hover:bg-quaternary"
                href={other.href}
                download
                onClick={() => setChosen(other)}
              >
                {other.title}
              </a>
            </div>
            {chosen ? (
              <ol className="mt-2.5 list-decimal ps-[18px] type-callout text-secondary [&>li+li]:mt-1">
                {chosen.steps.map((step) => (
                  <li key={step}>{step}</li>
                ))}
              </ol>
            ) : (
              <p className="mt-2 type-callout text-secondary">{here.detail}. Download it, then follow the steps shown.</p>
            )}
          </div>
        )}

        {hint && !allDone && (
          <p className="mt-3 type-callout text-secondary" role="status">
            {hint}
          </p>
        )}

        {allDone && (
          <button
            type="button"
            className="mt-4 self-end rounded-pill bg-key-bg px-5 py-2 type-body-emphasized text-white hover:brightness-110"
            onClick={props.onFinish}
            autoFocus
          >
            Start Listening
          </button>
        )}
      </div>
    </div>,
    document.body,
  );
}

/** True once [waiting] has lasted CONNECTING_HINT_MS. */
function useConnectingHint(waiting: boolean): boolean {
  const [late, setLate] = useState(false);
  useEffect(() => {
    if (!waiting) return setLate(false);
    const timer = setTimeout(() => setLate(true), CONNECTING_HINT_MS);
    return () => clearTimeout(timer);
  }, [waiting]);
  return waiting && late;
}

const noteClass =
  'fixed bottom-[calc(var(--player-pill-height,64px)+var(--player-bottom-offset,16px)+12px)] left-1/2 z-(--z-modal) flex max-w-[min(520px,calc(100vw-32px))] -translate-x-1/2 animate-fade items-center gap-3 rounded-[14px] bg-thick-material px-4 py-2.5 type-callout text-secondary shadow-glass [backdrop-filter:saturate(2.2)_blur(32px)]';

/** A returning visitor's extension is taking a moment: said quietly, nothing is blocked. */
function ConnectingHint() {
  return createPortal(
    <div className={noteClass} role="status" data-extension-hint="connecting">
      Connecting to the NepTune extension…
    </div>,
    document.body,
  );
}

/** It answered here before and now doesn't: say what to do, without the first-run popup. */
function NotResponding() {
  const downloads = downloadsFor(usePublishedExtension());
  const here = downloads.find((d) => d.browser === currentBrowser)!;
  const [hidden, setHidden] = useState(false);
  if (hidden) return null;
  return createPortal(
    <div className={noteClass} role="alert" data-extension-hint="silent">
      <span className="min-w-0 flex-1 text-pretty">
        <span className="type-callout-emphasized text-primary">The NepTune extension isn’t responding.</span> Reload it on your
        browser’s extensions page, then reload this page. If you removed it,{' '}
        <a className="text-key underline-offset-2 hover:underline" href={here.href} download>
          download it again
        </a>
        .
      </span>
      <button type="button" className={allowButton} onClick={recheckExtension}>
        Try Again
      </button>
      <button type="button" className="flex-none type-callout text-secondary hover:text-primary" onClick={() => setHidden(true)} aria-label="Dismiss">
        ✕
      </button>
    </div>,
    document.body,
  );
}
