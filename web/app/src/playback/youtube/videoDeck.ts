import shaka from 'shaka-player/dist/shaka-player.compiled';
import { useAddons } from '@/addons/client';
import { getVideoElement, useVideo } from '@/state/video';
import { trackLog } from '../trackLog';
import type { YouTubeEngine } from './engine';

/**
 * A music video's picture, as Apple Music plays one: a second Shaka player,
 * video only and muted, on the shared <video> element (state/video.ts),
 * held to the audio element of the deck in front. The audio stays the clock;
 * the picture follows it, nudging its rate for small drift and seeking for
 * large. It streams only while a surface shows it.
 */

/** Past this the picture jumps to the audio; under it, its rate is nudged. */
const SEEK_DRIFT_SEC = 0.35;
const IGNORE_DRIFT_SEC = 0.02;
const SYNC_EVERY_MS = 250;

let player: shaka.Player | null = null;
let source: { engine: YouTubeEngine; audio: HTMLAudioElement } | null = null;
/** The video whose manifest is loaded (or loading) in the picture player. */
let loaded: string | null = null;
let generation = 0;
let timer: ReturnType<typeof setInterval> | null = null;
let listening: HTMLAudioElement | null = null;

async function ensurePlayer(): Promise<shaka.Player> {
  if (player) return player;
  const created = new shaka.Player();
  created.configure({
    manifest: { disableAudio: true, disableText: true, disableThumbnails: true },
    // H.264 first: every browser decodes it in hardware.
    preferredVideoCodecs: ['avc1', 'vp9', 'av01'],
    restrictions: { maxHeight: 1080 },
    streaming: {
      bufferingGoal: 30,
      rebufferingGoal: 0.5,
      bufferBehind: 30,
      retryParameters: { maxAttempts: 2, baseDelay: 1000, backoffFactor: 2, fuzzFactor: 0.5, timeout: 30_000 },
    },
  });
  await created.attach(getVideoElement());
  created.addEventListener('error', (event) => {
    const error = (event as unknown as { detail: shaka.util.Error }).detail;
    trackLog.d(`video: Shaka error ${error.code} (severity ${error.severity})`, loaded);
  });
  player = created;
  return created;
}

/** The deck in front changed track, or recovered it: show its picture, if it has one. */
export function followVideo(engine: YouTubeEngine, audio: HTMLAudioElement): void {
  source = { engine, audio };
  listen(audio);
  void refresh();
}

/** Nothing is playing: drop the picture. */
export function stopVideo(): void {
  source = null;
  void refresh();
}

useVideo.subscribe((state, previous) => {
  if (state.surfaces > 0 !== previous.surfaces > 0) void refresh();
});

// A track whose audio may still move to JioSaavn or a FLAC add-on shows its cover until that's
// settled: the picture would flash for a second and go when the audio leaves YouTube, and it
// wouldn't match the other recording anyway. Asked again once the search is over.
useAddons.subscribe((state, previous) => {
  if (state.checkingVideoId !== previous.checkingVideoId) void refresh();
});

async function refresh(): Promise<void> {
  const run = ++generation;
  const wanted = source && useVideo.getState().surfaces > 0 ? source : null;
  const stream = wanted ? await wanted.engine.videoStream().catch(() => null) : null;
  if (run !== generation) return;
  if (!stream || useAddons.getState().checkingVideoId === stream.videoId) {
    await unload();
    return;
  }
  if (stream.videoId === loaded) return;
  await unload();
  loaded = stream.videoId;
  const video = getVideoElement();
  try {
    const shakaPlayer = await ensurePlayer();
    await shakaPlayer.load(`data:application/dash+xml;base64,${btoa(stream.manifest)}`, wanted!.audio.currentTime, 'application/dash+xml');
    if (run !== generation) return;
    if (video.readyState < HTMLMediaElement.HAVE_CURRENT_DATA) {
      await new Promise<void>((resolve) => video.addEventListener('loadeddata', () => resolve(), { once: true }));
    }
    if (run !== generation) return;
    const active = shakaPlayer.getVariantTracks().find((t) => t.active);
    trackLog.i(`video: ${active?.height ?? '?'}p ${active?.videoCodec ?? ''}`, stream.videoId);
    useVideo.setState({ videoId: stream.videoId, aspect: stream.aspect });
    sync();
    timer ??= setInterval(sync, SYNC_EVERY_MS);
  } catch (error) {
    if (run !== generation) return;
    trackLog.w(`video: couldn't load the picture: ${error instanceof Error ? error.message : String(error)}`, stream.videoId);
    loaded = null;
    useVideo.setState({ videoId: null });
  }
}

async function unload(): Promise<void> {
  if (timer) clearInterval(timer);
  timer = null;
  loaded = null;
  useVideo.setState({ videoId: null });
  await player?.unload().catch(() => {});
}

function listen(audio: HTMLAudioElement) {
  if (listening === audio) return;
  for (const type of ['play', 'pause', 'seeked', 'ratechange'] as const) {
    listening?.removeEventListener(type, sync);
    audio.addEventListener(type, sync);
  }
  listening = audio;
}

/** Holds the picture to the audio: same play state, same rate, the same moment. */
function sync() {
  const audio = source?.audio;
  const video = getVideoElement();
  if (!audio || !loaded || useVideo.getState().videoId !== loaded || video.readyState < HTMLMediaElement.HAVE_METADATA) return;
  const drift = video.currentTime - audio.currentTime;
  if (Math.abs(drift) > SEEK_DRIFT_SEC || audio.paused) {
    if (Math.abs(drift) > IGNORE_DRIFT_SEC) video.currentTime = audio.currentTime;
    video.playbackRate = audio.playbackRate;
  } else {
    // Catch up (or wait) over about a second, rather than jumping.
    const nudge = Math.abs(drift) > IGNORE_DRIFT_SEC ? Math.max(-0.1, Math.min(0.1, -drift)) : 0;
    video.playbackRate = audio.playbackRate * (1 + nudge);
  }
  if (audio.paused && !video.paused) video.pause();
  else if (!audio.paused && video.paused) void video.play().catch(() => {});
}

