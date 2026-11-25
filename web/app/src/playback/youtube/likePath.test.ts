// @vitest-environment happy-dom
import { expect, it } from 'vitest';
// The extension's own checks, run on what this page sends (they're pure, no browser APIs).
import { validateParams } from '../../../../extension/lib/validate';
import { isAllowedSigned } from '../../../../extension/lib/ytUrls';
import { signedBridgeParams } from './innertube';

/** A like as YouTube.js sends it (`actions.execute('/like/like')`): a JSON POST to InnerTube. */
function likeRequest(path: '/like/like' | '/like/removelike', videoId: string): Request {
  return new Request(`https://music.youtube.com/youtubei/v1${path}?prettyPrint=false`, {
    method: 'POST',
    headers: {
      'content-type': 'application/json',
      'x-goog-visitor-id': 'CgtWaXNpdG9ySWQ%3D',
      'x-youtube-client-name': '67',
      'x-youtube-client-version': '1.20251006.03.00',
      'accept-language': 'en-US',
    },
    body: JSON.stringify({
      context: { client: { clientName: 'WEB_REMIX', clientVersion: '1.20251006.03.00', hl: 'en', gl: 'US' } },
      target: { videoId },
    }),
  });
}

it('a like goes to the extension as a signed yt.fetch it accepts', async () => {
  for (const path of ['/like/like', '/like/removelike'] as const) {
    const params = await signedBridgeParams(likeRequest(path, 'dQw4w9WgXcQ'));
    expect(params).toMatchObject({ method: 'POST', auth: true, url: `https://music.youtube.com/youtubei/v1${path}?prettyPrint=false` });
    expect(JSON.parse(params.body!)).toMatchObject({ target: { videoId: 'dQw4w9WgXcQ' } });
    // lib/validate.ts (the shape) and lib/ytUrls.ts (where a signed request may go) both let it through.
    expect(() => validateParams('yt.fetch', params)).not.toThrow();
    expect(isAllowedSigned(params.url, params.method)).toBe(true);
  }
});

it('the same holds for the playlist calls the song menu makes', async () => {
  for (const endpoint of ['playlist/get_add_to_playlist', 'browse/edit_playlist', 'playlist/create', 'next']) {
    const request = new Request(`https://music.youtube.com/youtubei/v1/${endpoint}?prettyPrint=false`, {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: '{"context":{}}',
    });
    const params = await signedBridgeParams(request);
    expect(() => validateParams('yt.fetch', params)).not.toThrow();
    expect(isAllowedSigned(params.url, params.method)).toBe(true);
  }
});
