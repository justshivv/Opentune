import assert from 'node:assert/strict';
import { describe, it } from 'node:test';
import { BadRequest, LIMITS, validateParams } from './validate.ts';

const ok = (method: string, params: unknown) => assert.doesNotThrow(() => validateParams(method, params));
const bad = (method: string, params: unknown, pattern?: RegExp) =>
  assert.throws(
    () => validateParams(method, params),
    (error: unknown) => error instanceof BadRequest && error.code === 'protocol' && (!pattern || pattern.test(error.message)),
  );

describe('validateParams', () => {
  it('accepts what the web player sends', () => {
    ok('ping', {});
    ok('yt.fetch', {
      url: 'https://music.youtube.com/youtubei/v1/browse?prettyPrint=false',
      method: 'POST',
      headers: { 'content-type': 'application/json', 'X-Goog-Visitor-Id': 'abc' },
      body: '{"context":{}}',
      auth: true,
    });
    ok('yt.fetch', { url: 'https://s.youtube.com/api/stats/playback?cpn=x', method: 'GET', headers: {}, auth: true });
    ok('lyrics.fetch', { url: 'https://lrclib.net/api/get?track_name=Paper%20Lanterns', headers: { Authorization: 'Bearer x' } });
    ok('addon.fetch', { url: 'https://addon.example.net/manifest.json' });
    ok('addon.requestPermission', { url: 'https://addon.example.net/', urls: ['https://cdn.example.net/'] });
    ok('lyrics.permissions', { origins: ['https://lrclib.net'] });
    ok('lyrics.requestPermissions', {});
    ok('yt.authState', { refresh: true });
    ok('yt.authState', {});
    ok('yt.setConnected', { connected: false });
    ok('yt.selectChannel', { channelId: '1:own' });
    ok('spotify.tracks', { playlistId: 'liked', offset: 100 });
    ok('spotify.playlists', { offset: 0 });
    ok('discord.presence', { track: null, options: { activityType: 'listening', useDetails: true } });
    ok('discord.presence', {
      track: {
        videoId: 'abc123',
        title: 'Paper Lanterns Over the Harbour',
        artist: 'The Invented Quartet',
        album: null,
        artworkUrl: 'https://example.com/a.jpg',
        positionMs: 1000,
        durationMs: 180000,
        playbackRate: 1,
      },
      options: { activityName: '' },
    });
  });

  it('treats missing params as empty', () => {
    ok('ping', undefined);
    bad('yt.fetch', undefined, /url must be a string/);
  });

  it('refuses params that are not objects', () => {
    bad('ping', 'hello', /params must be an object/);
    bad('ping', [1, 2], /params must be an object/);
  });

  it('checks URLs', () => {
    bad('yt.fetch', { url: 42 });
    bad('yt.fetch', { url: 'not a url' }, /isn't a URL/);
    bad('lyrics.fetch', { url: 'file:///etc/hosts' }, /http or https/);
    bad('lyrics.fetch', { url: 'https://user:pw@lrclib.net/' }, /user name or password/);
    bad('lyrics.fetch', { url: `https://lrclib.net/${'a'.repeat(LIMITS.url)}` }, /longer than/);
  });

  it('checks methods, bodies and headers', () => {
    bad('yt.fetch', { url: 'https://www.youtube.com/', method: 'DELETE' }, /method must be/);
    bad('yt.fetch', { url: 'https://www.youtube.com/', method: 'GET', body: 'x' }, /can't have a body/);
    bad('yt.fetch', { url: 'https://www.youtube.com/', method: 'POST', body: 'x'.repeat(LIMITS.body + 1) }, /longer than/);
    bad('yt.fetch', { url: 'https://www.youtube.com/', method: 'POST', body: '***', bodyEncoding: 'base64' }, /base64/);
    bad('yt.fetch', { url: 'https://www.youtube.com/', method: 'POST', body: 'x', bodyEncoding: 'hex' }, /bodyEncoding/);
    bad('lyrics.fetch', { url: 'https://lrclib.net/', headers: { 'Bad Name': 'x' } }, /header name/);
    bad('lyrics.fetch', { url: 'https://lrclib.net/', headers: { Accept: 'a\r\nCookie: b' } }, /line break/);
    bad('lyrics.fetch', { url: 'https://lrclib.net/', headers: { Accept: 7 } }, /must be a string/);
    const many = Object.fromEntries(Array.from({ length: LIMITS.headers + 1 }, (_, i) => [`x-h${i}`, 'v']));
    bad('lyrics.fetch', { url: 'https://lrclib.net/', headers: many }, /more than/);
  });

  it('keeps add-on requests to plain GETs', () => {
    bad('addon.fetch', { url: 'https://addon.example.net/', method: 'POST', body: '{}' }, /method must be one of GET/);
  });

  it('checks flags, ids, offsets and lists', () => {
    bad('yt.setConnected', {}, /connected must be/);
    bad('yt.setConnected', { connected: 'yes' }, /connected must be/);
    bad('yt.authState', { refresh: 1 }, /refresh must be/);
    bad('yt.selectChannel', { channelId: 'x'.repeat(LIMITS.id + 1) }, /longer than/);
    bad('spotify.playlists', { offset: -1 }, /whole number/);
    bad('spotify.playlists', { offset: 1.5 }, /whole number/);
    bad('spotify.searchTracks', { query: null }, /must be a string/);
    bad('lyrics.permissions', { origins: 'https://lrclib.net' }, /must be a list/);
    bad('lyrics.permissions', { origins: Array.from({ length: LIMITS.origins + 1 }, () => 'https://lrclib.net') }, /more than/);
    bad('addon.requestPermission', { url: 'https://a.example/', urls: ['ftp://b.example/'] }, /http or https/);
    bad('discord.presence', { track: { videoId: 'a', title: 'b', artist: 'c', positionMs: 'soon' }, options: {} }, /must be a number/);
  });

  it('leaves unknown methods to the dispatcher', () => {
    ok('future.method', { anything: true });
  });
});
