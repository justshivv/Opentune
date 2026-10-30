import { md5 } from '@noble/hashes/legacy.js';
import { bytesToHex, utf8ToBytes } from '@noble/hashes/utils.js';

/** Last.fm's own rules: songs under 30 s aren't scrobbled; half the song or 4 minutes counts as a listen. */
const MIN_TRACK_SEC = 30;
const LISTEN_CAP_SEC = 240;

/** api_sig: md5 of every parameter (but format and callback) sorted by name, then the secret. */
export function signature(params: Record<string, string>, secret: string): string {
  const text = Object.keys(params)
    .filter((k) => k !== 'format' && k !== 'callback')
    .sort()
    .map((k) => k + params[k])
    .join('');
  return bytesToHex(md5(utf8ToBytes(text + secret)));
}

/** The first of "A, B & C": Last.fm matches better on the main artist (LastFm.primaryArtist). */
export function primaryArtist(artist: string): string {
  return artist.split(/, | & | x | feat\. | ft\. /)[0]?.trim() || artist;
}

/** Whether [heardSec] of a [durationSec] song is a listen, by Last.fm's rules. */
export function isListen(heardSec: number, durationSec: number): boolean {
  if (durationSec > 0 && durationSec < MIN_TRACK_SEC) return false;
  const needed = durationSec > 0 ? Math.min(durationSec / 2, LISTEN_CAP_SEC) : LISTEN_CAP_SEC;
  return heardSec >= needed;
}
