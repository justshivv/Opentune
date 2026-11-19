// Registers <am-lyrics>.
import '@uimaxbai/am-lyrics/am-lyrics.js';
import { Check, ChevronDown, Loader2, Minus, Settings2 } from 'lucide-react';
import { useCallback, useEffect, useRef, useState, useSyncExternalStore } from 'react';
import { useCanvas } from '@/data/spotify/canvas';
import { getBridge } from '@/extension/bridge';
import { Menu, MenuHeader, MenuItem, MenuSeparator } from '@/components/Menu';
import { LyricClock } from '@/lyrics/clock';
import { onUngrantedChange, ungrantedOrigins } from '@/lyrics/http';
import type { SourceState } from '@/lyrics/repository';
import { enabledSources } from '@/lyrics/settings';
import { sourceById, type LyricsSourceId } from '@/lyrics/sources';
import { useLyrics, useLyricsUi } from '@/lyrics/useLyrics';
import { seekRemote, useControlling } from '@/connect/connect';
import { positionAt } from '@/connect/session';
import { useShownNowPlaying } from '@/connect/shown';
import { getAudio, usePlayer } from '@/state/player';
import { LyricsSettings } from './LyricsSettings';

type AmLyricsElement = HTMLElement & {
  currentTime: number;
  ttml?: string;
  interpolate: boolean;
  autoScroll: boolean;
  highlightColor: string;
  fontFamily?: string;
  updateComplete?: Promise<unknown>;
};

/**
 * am-lyrics' own footer offers to fetch from its own providers; ours decide
 * (prompt §10.2). It stays in the layout, only unseen: am-lyrics measures its
 * scroll range to the footer, and `display: none` collapsed that range to zero,
 * so the lyrics never scrolled.
 */
const HIDE_FOOTER = '.lyrics-footer { visibility: hidden !important; pointer-events: none !important; }';

/** The phone's lyric typeface (@font-face in styles/global.css), then the app's. */
const LYRIC_FONT = "'DM Sans', 'DM Sans', system-ui, sans-serif";

let lyricFont: Promise<void> | null = null;
let lyricFontLoaded = false;

/**
 * Both weights, fetched once. am-lyrics measures its lines for the scroll and
 * the wipe, so it's only built once the face is in: built earlier, it laid
 * out in the fallback and then jumped when the font arrived. A slow network
 * gives up after 1.5 s and lets the fallback show.
 */
function loadLyricFont(): Promise<void> {
  lyricFont ??= Promise.race([
    Promise.all([document.fonts.load(`700 28px ${LYRIC_FONT}`), document.fonts.load(`400 28px ${LYRIC_FONT}`)]),
    new Promise((resolve) => setTimeout(resolve, 1500)),
  ])
    .catch(() => undefined)
    .then(() => {
      lyricFontLoaded = true;
    });
  return lyricFont;
}

function useLyricFont(): boolean {
  const [ready, setReady] = useState(lyricFontLoaded);
  useEffect(() => {
    if (!ready) void loadLyricFont().then(() => setReady(true));
  }, [ready]);
  return ready;
}

/**
 * am-lyrics fades the four lines either side of the current one (0.7, 0.58,
 * 0.46) and leaves every line past them at its base 0.8, so the fifth line on
 * came back brighter. Those stay at the farthest step. Gaps keep their own
 * opacity, and the library's !important rules (scrolling by hand, unsynced
 * lyrics) still win.
 */
const FAR_LINES =
  '.lyrics-line:not(.active):not(.pre-active):not(.lyrics-activest):not(.post-active-line):not(.next-active-line)' +
  ':not(.prev-2):not(.prev-3):not(.next-2):not(.next-3):not(.lyrics-gap) { opacity: 0.46; }';

/**
 * The full-screen player's sizing (report §7): its line size, set on the host
 * by breakpoint, replaces all three of am-lyrics' container tiers, and the
 * current line sits a third of the way down. Scoped to `data-full`, so the
 * side panel keeps the library's own sizes.
 */
const FULL_SIZE =
  ':host([data-full]) .lyrics-container { --lyplus-font-size-base: var(--opentune-lyric-size, 34px);' +
  ' --am-lyrics-line-height: 1.2; --am-lyrics-line-spacing: calc(var(--opentune-lyric-size, 34px) * 0.7);' +
  ' --lyrics-scroll-padding-top: 30%; --am-lyrics-inline-padding: 0px; }';

/**
 * am-lyrics, exactly as it is, fed our TTML and a steered clock (prompt §10.2).
 * The clock reads the media time each frame, stamps the reading with when it
 * was taken, and is told about every seek and track change: the "lyric clock
 * dates readings by sample time" lesson. No React state per frame: the time is
 * written straight onto the element.
 */
function LyricsView({ ttml, full }: { ttml: string; full: boolean }) {
  const host = useRef<HTMLDivElement>(null);
  const element = useRef<AmLyricsElement | null>(null);
  const deck = usePlayer((s) => s.deck);
  const videoId = useShownNowPlaying()?.videoId;
  // The person's other device playing (Connect): the lines follow its position, and a click seeks it.
  const remote = useControlling();
  const remoteRef = useRef(remote);
  remoteRef.current = remote;
  const far = !!remote;

  // Created by hand, with its TTML set before it's attached: am-lyrics starts
  // loading the moment it connects, and through React the properties arrive
  // a beat later, so its first load went to its own providers with no title
  // and finished after ours, wiping it.
  useEffect(() => {
    const el = document.createElement('am-lyrics') as AmLyricsElement;
    el.ttml = ttml;
    el.interpolate = true;
    el.autoScroll = true;
    el.highlightColor = '#ffffff';
    el.fontFamily = LYRIC_FONT;
    el.className = 'block min-h-0 flex-1 overflow-hidden [--am-lyrics-highlight-color:#fff]';
    el.toggleAttribute('data-full', full);
    const onLineClick = (event: Event) => {
      const timestamp = (event as CustomEvent<{ timestamp: number }>).detail?.timestamp;
      if (typeof timestamp !== 'number') return;
      if (remoteRef.current) seekRemote(timestamp);
      else usePlayer.getState().seek(timestamp / 1000);
    };
    el.addEventListener('line-click', onLineClick);
    host.current?.append(el);
    element.current = el;
    const unmounted = new AbortController();
    void Promise.resolve(el.updateComplete).then(() => {
      const root = el.shadowRoot;
      if (!root) return;
      const sheet = new CSSStyleSheet();
      sheet.replaceSync(HIDE_FOOTER + FAR_LINES + FULL_SIZE);
      root.adoptedStyleSheets = [...root.adoptedStyleSheets, sheet];
      // The full-screen column fades its top edge, but am-lyrics' toolbar
      // (romanize, translate, download) sits at the top of the same scroller
      // until the lines push it away. The fade grows with the scroll instead,
      // so the toolbar is never under it.
      const scroller = root.querySelector('.lyrics-container');
      const target = host.current;
      if (!full || !scroller || !target) return;
      const fade = () => target.style.setProperty('--lyrics-top-fade', `${Math.min(scroller.scrollTop, 80)}px`);
      fade();
      scroller.addEventListener('scroll', fade, { passive: true, signal: unmounted.signal });
    });
    return () => {
      unmounted.abort();
      el.removeEventListener('line-click', onLineClick);
      el.remove();
      element.current = null;
    };
    // Mounted once; a new provider's TTML is handed over below.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  useEffect(() => {
    if (element.current && element.current.ttml !== ttml) element.current.ttml = ttml;
  }, [ttml]);

  useEffect(() => {
    const el = element.current;
    if (!far || !el) return;
    let frame = 0;
    const tick = () => {
      const d = remoteRef.current;
      if (d) el.currentTime = positionAt(d, Date.now());
      frame = requestAnimationFrame(tick);
    };
    tick();
    return () => cancelAnimationFrame(frame);
  }, [far, videoId, ttml]);

  useEffect(() => {
    if (far) return;
    const audio = getAudio();
    const el = element.current;
    if (!audio || !el) return;
    const clock = new LyricClock(audio.currentTime * 1000, false);
    let discontinuity = 0;
    let frame = 0;
    let running = false;
    const tick = () => {
      const now = performance.now();
      // The reading and its stamp are taken together.
      el.currentTime = clock.frame(now, Math.trunc(audio.currentTime * 1000), now, discontinuity);
      frame = requestAnimationFrame(tick);
    };
    // Stopped, seeking or waiting for data: the words stand still where the audio is. am-lyrics
    // runs its word wipes on the page's own timeline, so they're paused as well, or a word
    // went on lighting up through a pause and the highlight was ahead once the song went on.
    const hold = () => {
      cancelAnimationFrame(frame);
      running = false;
      const ms = Math.trunc(audio.currentTime * 1000);
      clock.hold(ms, performance.now());
      el.currentTime = ms;
      freeze(el, true);
    };
    const resume = () => {
      if (audio.paused || audio.seeking) return;
      if (running) return;
      cancelAnimationFrame(frame);
      running = true;
      const ms = Math.trunc(audio.currentTime * 1000);
      el.currentTime = ms;
      freeze(el, false);
      resync(el, ms);
      clock.restart();
      frame = requestAnimationFrame(tick);
    };
    const onSeeking = () => {
      discontinuity++;
      hold();
    };
    const onSeeked = () => {
      const ms = Math.trunc(audio.currentTime * 1000);
      el.currentTime = ms;
      resync(el, ms);
      resume();
    };
    const onWaiting = () => {
      // Out of data mid-song: the audio stops while it loads, and so do the words.
      if (!audio.paused) hold();
    };
    audio.addEventListener('seeking', onSeeking);
    audio.addEventListener('seeked', onSeeked);
    audio.addEventListener('pause', hold);
    audio.addEventListener('waiting', onWaiting);
    audio.addEventListener('playing', resume);
    audio.addEventListener('play', resume);
    if (audio.paused || audio.readyState < HTMLMediaElement.HAVE_FUTURE_DATA) hold();
    else resume();
    return () => {
      cancelAnimationFrame(frame);
      freeze(el, false);
      audio.removeEventListener('seeking', onSeeking);
      audio.removeEventListener('seeked', onSeeked);
      audio.removeEventListener('pause', hold);
      audio.removeEventListener('waiting', onWaiting);
      audio.removeEventListener('playing', resume);
      audio.removeEventListener('play', resume);
    };
  }, [deck, videoId, ttml, far]);

  // am-lyrics renders and scrolls itself; it only needs the space. Full screen fades it out at the top and bottom.
  return (
    <div
      ref={host}
      className={
        full
          ? 'flex min-h-0 flex-1 [mask-image:linear-gradient(180deg,transparent,#000_var(--lyrics-top-fade,0px),#000_50%,transparent)]'
          : 'flex min-h-0 flex-1'
      }
    />
  );
}

/**
 * Pauses (or plays again) am-lyrics' running word animations. They run on the page's timeline
 * rather than the song's, so without this they carried on through a pause or a buffering stop.
 */
function freeze(el: AmLyricsElement, stop: boolean): void {
  // Only the ones paused here are played again: am-lyrics pauses some itself while it rewinds.
  const held = frozen.get(el) ?? [];
  frozen.delete(el);
  if (!stop) {
    for (const animation of held) if (animation.playState === 'paused') animation.play();
    return;
  }
  const root = el.shadowRoot;
  if (!root || typeof root.getAnimations !== 'function') return;
  const running = root.getAnimations().filter((animation) => animation.playState === 'running');
  running.forEach((animation) => animation.pause());
  frozen.set(el, [...held, ...running]);
}

const frozen = new WeakMap<AmLyricsElement, Animation[]>();

/**
 * Puts every running word wipe back where the song is. am-lyrics does this itself only for a
 * jump of more than half a second; a pause, a buffering stop or a short seek left the wipes off
 * by up to that much. Its own seek handling is used, so nothing is drawn differently.
 */
function resync(el: AmLyricsElement, ms: number): void {
  const seekTo = (el as unknown as { _onTimeChanged?: (from: number, to: number) => void })._onTimeChanged;
  if (typeof seekTo === 'function') seekTo.call(el, ms - RESYNC_FROM_MS, ms);
}

/** Further than am-lyrics' 500 ms, so it treats the update as a seek. */
const RESYNC_FROM_MS = 1000;

function useUngranted(): string[] {
  return useSyncExternalStore(onUngrantedChange, () => [...ungrantedOrigins].join(','), () => '')
    .split(',')
    .filter(Boolean);
}

const NO_STATES: Partial<Record<LyricsSourceId, SourceState>> = {};

/** The provider picker: which provider these lyrics are from, and the others' answers for this track. */
function ProviderPicker({ current, full }: { current: LyricsSourceId | null; full: boolean }) {
  const videoId = useShownNowPlaying()?.videoId ?? '';
  const states = useLyricsUi((s) => s.states[videoId]) ?? NO_STATES;
  const picked = useLyricsUi((s) => s.picked[videoId]);
  const pick = useLyricsUi((s) => s.pick);
  const [anchor, setAnchor] = useState<HTMLElement | null>(null);
  const [settings, setSettings] = useState(false);
  const close = useCallback(() => setAnchor(null), []);
  const label = current ? sourceById(current)?.label : 'Lyrics';

  return (
    // In full screen the picker sits quietly above the lines, shown on hover.
    <div
      className={
        full
          ? 'flex items-center justify-between pb-1 opacity-0 transition-opacity duration-200 group-hover/panel:opacity-100 focus-within:opacity-100'
          : 'flex items-center justify-between px-4 pt-4 pb-1'
      }
    >
      <button
        className="inline-flex items-center gap-1 rounded-[8px] px-2 py-1 type-callout-emphasized text-secondary hover:bg-quinary"
        aria-haspopup="menu"
        onClick={(e) => setAnchor(e.currentTarget)}
      >
        <span>{label}</span>
        <ChevronDown size={14} />
      </button>
      <button
        className="grid size-7 place-items-center rounded-[8px] text-secondary hover:bg-quinary"
        aria-label="Lyric settings"
        onClick={() => setSettings(true)}
      >
        <Settings2 size={16} />
      </button>
      {anchor && (
        <Menu anchor={anchor} placement="below-end" onClose={close} label="Lyrics provider">
          <MenuItem checked={!picked} onSelect={() => (close(), pick(videoId, null))}>
            Best available
          </MenuItem>
          <MenuSeparator />
          <MenuHeader>Providers</MenuHeader>
          {enabledSources().map((id) => {
            const state = states[id];
            return (
              <MenuItem
                key={id}
                checked={picked === id}
                trailing={
                  state === 'pending' ? (
                    <Loader2 size={13} className="animate-spin" />
                  ) : state === 'found' ? (
                    <Check size={13} />
                  ) : state === 'missing' ? (
                    <Minus size={13} />
                  ) : undefined
                }
                onSelect={() => (close(), pick(videoId, id))}
              >
                {sourceById(id)?.label}
              </MenuItem>
            );
          })}
          <MenuSeparator />
          <MenuItem onSelect={() => (close(), setSettings(true))}>Lyric Settings…</MenuItem>
        </Menu>
      )}
      {settings && <LyricsSettings onClose={() => setSettings(false)} />}
    </div>
  );
}

function PermissionNotice({ origins }: { origins: string[] }) {
  const names = origins.map((o) => new URL(o).host.replace(/^www\./, ''));
  return (
    <div className="mx-4 my-1.5 flex items-center gap-2.5 rounded-[10px] bg-quinary px-2.5 py-2 type-callout text-secondary">
      <span>
        {names.slice(0, 2).join(', ')}
        {names.length > 2 ? ` and ${names.length - 2} more` : ''} need the extension’s permission.
      </span>
      <button
        className="flex-none type-callout-emphasized text-key"
        // The notice stays until the extension confirms the grant (checked when this tab comes back).
        onClick={() => void getBridge()?.call('lyrics.requestPermissions', { origins })}
      >
        Allow
      </button>
    </div>
  );
}

/**
 * The track's Spotify Canvas behind the lyrics, dimmed so they stay legible.
 * Muted and looping, it fades in once it can play and pauses with the music.
 *
 * The blur runs on a quarter-size video that is then scaled up 4×: a 3px blur
 * there reads as about 12px on screen for a sixteenth of the pixels, which is
 * what keeps a blurred, playing video cheap. The 5% overscan on each side
 * keeps the soft edges out of view.
 */
function CanvasBackdrop({ url }: { url: string }) {
  const video = useRef<HTMLVideoElement>(null);
  const [ready, setReady] = useState(false);
  const playing = usePlayer((s) => s.status === 'playing' || s.status === 'buffering');
  useEffect(() => setReady(false), [url]);
  useEffect(() => {
    const el = video.current;
    if (!el || !ready) return;
    if (playing) void el.play().catch(() => undefined);
    else el.pause();
  }, [playing, ready]);
  return (
    <video
      ref={video}
      key={url}
      className="absolute top-[-5%] left-[-5%] -z-1 size-[27.5%] origin-[0_0] scale-400 object-cover opacity-0 blur-[3px] transition-opacity duration-600 [mask-image:linear-gradient(to_bottom,rgb(0_0_0/1),rgb(0_0_0/0.85))] data-ready:opacity-85"
      data-ready={ready || undefined}
      src={url}
      muted
      loop
      playsInline
      preload="auto"
      aria-hidden="true"
      onCanPlay={() => setReady(true)}
      onError={() => setReady(false)}
    />
  );
}

// Fades in once loaded: am-lyrics arrives a moment after the panel opens, and used to pop in.
const panel = 'group/panel relative isolate flex min-h-0 flex-1 flex-col overflow-hidden animate-fade-in';
// The full-screen player's column (report §7): lines 26px, then 30px from 980px, 36px from 1320px and 42px from 1680px.
const fullSizes =
  '[--opentune-lyric-size:26px] min-[980px]:[--opentune-lyric-size:30px] min-[1320px]:[--opentune-lyric-size:36px] min-[1680px]:[--opentune-lyric-size:42px]';

/**
 * The lyrics, with their provider picker and empty states: the side panel
 * (report §7), and the right-hand column of the full-screen player, where the
 * lines are larger and the player's mesh stands in for the Canvas.
 */
export function LyricsPanel({ variant = 'panel' }: { variant?: 'panel' | 'full' }) {
  const lyrics = useLyrics();
  const hasTrack = useShownNowPlaying() !== null;
  const ungranted = useUngranted();
  const canvas = useCanvas();
  const fontReady = useLyricFont();
  const result = lyrics.data;
  const full = variant === 'full';
  const empty = full
    ? 'flex flex-1 flex-col items-center justify-center gap-2 px-6 text-center type-title-3 text-secondary'
    : 'flex flex-1 flex-col items-center justify-center gap-2 px-6 pb-20 text-center type-[400_12px/16px] text-secondary';
  const emptyTitle = full ? 'type-title-1-emphasized text-primary' : 'type-[600_12px/16px]';

  return (
    <div className={full ? `${panel} ${fullSizes}` : panel}>
      {canvas && variant === 'panel' && <CanvasBackdrop url={canvas} />}
      <ProviderPicker current={result?.source ?? null} full={full} />
      {/* Only worth raising when nothing was found: then the hosts behind the extension might have it. */}
      {ungranted.length > 0 && !result && !lyrics.isFetching && <PermissionNotice origins={ungranted} />}
      {!hasTrack ? (
        <div className={empty}>
          <p className={emptyTitle}>No lyrics available</p>
          <p>Play a song to see its lyrics here.</p>
        </div>
      ) : (lyrics.isPending && lyrics.fetchStatus !== 'idle') || (result && !fontReady) ? (
        <div className={empty}>
          <Loader2 size={20} className="animate-spin" />
        </div>
      ) : result ? (
        <LyricsView ttml={result.ttml} full={full} />
      ) : (
        <div className={empty}>
          <p className={emptyTitle}>No lyrics available</p>
          <p>None of the lyric providers has this song. Try another in the list above.</p>
        </div>
      )}
    </div>
  );
}

