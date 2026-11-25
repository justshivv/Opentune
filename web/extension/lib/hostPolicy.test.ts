import assert from 'node:assert/strict';
import { describe, it } from 'node:test';
import { originOfPattern, patternCovers, sortGrants } from './grants.ts';
import { isReservedHost, lyricHeaders } from './hostPolicy.ts';
import { LYRIC_ORIGINS } from './lyricHosts.ts';

describe('isReservedHost', () => {
  it('reserves the hosts the extension holds sessions for, and their subdomains', () => {
    for (const host of ['youtube.com', 'music.youtube.com', 'rr1---sn-abc.googlevideo.com', 'open.spotify.com', 'discord.com', 'MUSIC.YOUTUBE.COM.']) {
      assert.equal(isReservedHost(host), true, host);
    }
  });

  it('leaves other hosts alone, look-alikes included', () => {
    for (const host of ['addon.example.net', 'notyoutube.com', 'youtube.com.example.net', 'someone.github.io', 'lrclib.net']) {
      assert.equal(isReservedHost(host), false, host);
    }
  });
});

describe('lyricHeaders', () => {
  it('keeps only the headers lyric hosts need', () => {
    assert.deepEqual(
      lyricHeaders({ Authorization: 'Bearer t', Accept: 'application/json', Cookie: 'a=b', Origin: 'https://x', Referer: 'https://x/', 'X-Other': '1' }),
      { Authorization: 'Bearer t', Accept: 'application/json' },
    );
    assert.deepEqual(lyricHeaders(undefined), {});
  });
});

describe('sortGrants', () => {
  const required = ['https://*.youtube.com/*', 'https://justshivv.github.io/Opentune/player/*'];

  it('sorts granted hosts into lyric sources and add-ons', () => {
    const granted = [
      ...required,
      'https://lrclib.net/*',
      'https://genius.com/*',
      'https://zeta-addon.example.net/*',
      'https://alpha-addon.example.net/*',
      'https://discord.com/*',
      'https://*/*',
    ];
    const grants = sortGrants(granted, required, LYRIC_ORIGINS, ['https://discord.com/*']);
    // Lyric hosts in the list's own order; add-ons alphabetically.
    assert.deepEqual(grants.lyrics, ['https://lrclib.net', 'https://genius.com']);
    assert.deepEqual(grants.addons, ['https://alpha-addon.example.net', 'https://zeta-addon.example.net']);
  });

  it('is empty when nothing optional is granted', () => {
    assert.deepEqual(sortGrants(required, required, LYRIC_ORIGINS), { lyrics: [], addons: [] });
  });

  it('leaves out install-time hosts as the browser reports them back (path dropped, subdomains)', () => {
    const reported = ['https://justshivv.github.io/*', 'https://music.youtube.com/*', 'http://localhost/*', 'https://lrclib.net/*'];
    const grants = sortGrants(reported, [...required, 'http://localhost/*'], LYRIC_ORIGINS);
    assert.deepEqual(grants, { lyrics: ['https://lrclib.net'], addons: [] });
  });

  it('matches hosts the way host permissions do', () => {
    assert.equal(patternCovers('https://*.youtube.com/*', 'https://youtube.com'), true);
    assert.equal(patternCovers('https://*.youtube.com/*', 'https://s.youtube.com'), true);
    assert.equal(patternCovers('https://*.youtube.com/*', 'https://notyoutube.com'), false);
    assert.equal(patternCovers('https://*.youtube.com/*', 'http://music.youtube.com'), false);
    assert.equal(patternCovers('https://justshivv.github.io/Opentune/player/*', 'https://justshivv.github.io'), true);
    assert.equal(patternCovers('https://justshivv.github.io/Opentune/player/*', 'https://someone.github.io'), false);
    assert.equal(patternCovers('http://localhost/*', 'http://localhost:5173'), true);
    assert.equal(patternCovers('<all_urls>', 'https://a.example'), false);
  });

  it('reads single-host patterns only', () => {
    assert.equal(originOfPattern('https://lrclib.net/*'), 'https://lrclib.net');
    assert.equal(originOfPattern('https://*.youtube.com/*'), null);
    assert.equal(originOfPattern('https://lrclib.net/api/*'), null);
    assert.equal(originOfPattern('<all_urls>'), null);
  });
});
