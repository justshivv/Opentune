import { expect, it } from 'vitest';
import type { Track } from '@/data/music/types';
import { findInList } from './FindInList';

const song = (title: string, artist: string, album: string | null = null): Track => ({
  videoId: title, title, artists: [{ name: artist, id: null }], album: album ? { name: album, id: null } : null,
  durationSec: null, explicit: false, thumbnail: null, index: null, plays: null,
});
// Invented songs and artists.
const list = [
  song('Paper Lanterns', 'Ivo Marlowe', 'Tidewater Rooms'),
  song('Driftwood Signals', 'Sela Quinn'),
  song('Café Noir', 'Odile Faro', 'Late Trams'),
];

it('keeps every song for an empty query', () => {
  expect(findInList(list, '  ')).toEqual(list);
});
it('finds by title, artist or album, ignoring case', () => {
  expect(findInList(list, 'lantern').map((t) => t.title)).toEqual(['Paper Lanterns']);
  expect(findInList(list, 'SELA').map((t) => t.title)).toEqual(['Driftwood Signals']);
  expect(findInList(list, 'trams').map((t) => t.title)).toEqual(['Café Noir']);
});
it('ignores accents and needs every word, in any order', () => {
  expect(findInList(list, 'cafe').map((t) => t.title)).toEqual(['Café Noir']);
  expect(findInList(list, 'marlowe paper').map((t) => t.title)).toEqual(['Paper Lanterns']);
  expect(findInList(list, 'marlowe driftwood')).toEqual([]);
});
