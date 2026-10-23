import { expect, it } from 'vitest';
import type { Shelf } from '@/data/music/types';
import { homeSections } from './homeSections';
const shelf = (title: string, layout: Shelf['layout']): Shelf => ({ title, layout, items: [], strapline: null, seeAll: null });
it('keeps quick picks first without duplicating them and separates song rows', () => {
  const quick = shelf('Quick picks', 'tracks');
  const list = [shelf('Because you played', 'tracks'), quick, shelf('Your favorites', 'tracks'), shelf('Made for you', 'square'), shelf('Playlists', 'square')];
  const result = homeSections(list);
  expect(result.quick).toBe(quick);
  expect(result.shelves.map(s => s.layout)).toEqual(['square', 'tracks', 'square', 'tracks']);
  expect(list).toHaveLength(5);
});
it('does not invent recommendation data for an empty account', () => {
  expect(homeSections([])).toEqual({ quick: undefined, shelves: [] });
});
