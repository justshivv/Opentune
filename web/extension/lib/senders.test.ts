import assert from 'node:assert/strict';
import { describe, it } from 'node:test';
import { isOwnPage, isPlayerSender, senderOrigin } from './senders.ts';
import { isPlayerUrl, matchPatternsFor } from './site.ts';

const SITES = ['https://justshivv.github.io', 'http://localhost:5173'];
const BASE = 'chrome-extension://abcdefghijklmnopabcdefghijklmnop/';
const OWN = 'chrome-extension://abcdefghijklmnopabcdefghijklmnop';
const ID = 'abcdefghijklmnopabcdefghijklmnop';

describe('site', () => {
  it('writes manifest patterns for the player path and local development', () => {
    assert.deepEqual(matchPatternsFor(SITES), ['https://justshivv.github.io/Opentune/player/*', 'http://localhost/*']);
  });

  it('accepts only the player path on GitHub Pages', () => {
    assert.equal(isPlayerUrl('https://justshivv.github.io/Opentune/player/#/home', SITES), true);
    assert.equal(isPlayerUrl('https://justshivv.github.io/Opentune/', SITES), false);
    assert.equal(isPlayerUrl('https://justshivv.github.io/OtherProject/player/', SITES), false);
    assert.equal(isPlayerUrl('https://justshivv.github.io/opentune/player/', SITES), false);
    // A dot segment is resolved before the path is compared.
    assert.equal(isPlayerUrl('https://justshivv.github.io/Opentune/player/../../evil/', SITES), false);
    assert.equal(isPlayerUrl('https://justshivv.github.io/Opentune/player/%2e%2e/%2e%2e/evil/', SITES), false);
  });

  it('matches the development origin by port', () => {
    assert.equal(isPlayerUrl('http://localhost:5173/', SITES), true);
    assert.equal(isPlayerUrl('http://localhost:8080/', SITES), false);
    assert.equal(isPlayerUrl('https://localhost:5173/', SITES), false);
  });

  it('refuses look-alike hosts, credentials and odd schemes', () => {
    assert.equal(isPlayerUrl('https://justshivv.github.io.example.com/Opentune/player/', SITES), false);
    assert.equal(isPlayerUrl('https://someone@justshivv.github.io/Opentune/player/', SITES), false);
    assert.equal(isPlayerUrl('blob:https://justshivv.github.io/Opentune/player/x', SITES), false);
    assert.equal(isPlayerUrl('about:srcdoc', SITES), false);
    assert.equal(isPlayerUrl('not a url', SITES), false);
    assert.equal(isPlayerUrl(undefined, SITES), false);
  });
});

describe('senders', () => {
  it('lets the player page in, with or without a reported origin', () => {
    const url = 'https://justshivv.github.io/Opentune/player/';
    assert.equal(isPlayerSender({ url, origin: 'https://justshivv.github.io' }, SITES), true);
    assert.equal(isPlayerSender({ url }, SITES), true); // Firefox's relay: no origin
  });

  it('lets in every page of the live player, hash routes and deep paths included', () => {
    const origin = 'https://justshivv.github.io';
    for (const url of [
      'https://justshivv.github.io/Opentune/player/',
      'https://justshivv.github.io/Opentune/player/#/artist/UCabcdefghijklmnopqrstuv',
      'https://justshivv.github.io/Opentune/player/#/library/playlists',
      'https://justshivv.github.io/Opentune/player/home',
      'https://justshivv.github.io/Opentune/player/artist/UCabcdefghijklmnopqrstuv?tab=songs',
      'https://justshivv.github.io/Opentune/player/library/playlists#top',
    ]) {
      assert.equal(isPlayerSender({ url, origin }, SITES), true, url);
      assert.equal(isPlayerSender({ url }, SITES), true, url);
    }
  });

  it('refuses a frame whose origin differs from its URL', () => {
    assert.equal(isPlayerSender({ url: 'about:srcdoc', origin: 'https://justshivv.github.io' }, SITES), false);
    assert.equal(isPlayerSender({ url: 'https://justshivv.github.io/Opentune/player/', origin: 'null' }, SITES), false);
    assert.equal(isPlayerSender({ url: 'https://justshivv.github.io/Opentune/player/', origin: 'https://example.com' }, SITES), false);
  });

  it('knows its own pages by id and origin', () => {
    assert.equal(isOwnPage({ id: ID, url: `${OWN}/popup.html`, origin: OWN }, BASE, ID), true);
    assert.equal(isOwnPage({ id: ID, url: `${OWN}/popup.html` }, BASE, ID), true);
    assert.equal(isOwnPage({ id: 'other', url: `${OWN}/popup.html` }, BASE, ID), false);
    assert.equal(isOwnPage({ id: ID, url: 'chrome-extension://otherextensionidotherextension/popup.html' }, BASE, ID), false);
    assert.equal(isOwnPage({ id: ID, url: `${OWN}.evil/popup.html` }, BASE, ID), false);
    // The relay content script shares the id but sits on the player's page.
    assert.equal(isOwnPage({ id: ID, url: 'https://justshivv.github.io/Opentune/player/' }, BASE, ID), false);
    assert.equal(isOwnPage({ id: ID, url: `${OWN}/popup.html`, origin: 'https://justshivv.github.io' }, BASE, ID), false);
  });

  it('reports an origin for the refusal message', () => {
    assert.equal(senderOrigin({ url: 'https://example.com/a' }), 'https://example.com');
    assert.equal(senderOrigin({ origin: 'https://example.org', url: 'https://example.com/a' }), 'https://example.org');
    assert.equal(senderOrigin({}), null);
  });
});
