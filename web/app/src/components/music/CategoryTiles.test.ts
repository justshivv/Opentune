import { describe, expect, it } from 'vitest';
import type { BrowsePage, MusicItem } from '@/data/music/types';
import { leadCover } from './CategoryTiles';

const item = (kind: MusicItem['kind'], thumbnail: string | null): MusicItem =>
  ({ kind, id: kind, title: kind, subtitle: '', artists: [], album: null, thumbnail, wide: false, durationSec: null, explicit: false }) as MusicItem;
const page = (grid: MusicItem[], shelf: MusicItem[] = []): BrowsePage =>
  ({ title: 'Chill', grid, shelves: [{ title: 'Shelf', strapline: null, items: shelf }], tracks: [] }) as unknown as BrowsePage;

describe('leadCover', () => {
  it('takes the first playlist with a cover, from the grid or the shelves', () => {
    expect(leadCover(page([item('song', 'song.jpg'), item('playlist', 'list.jpg')]))).toBe('list.jpg');
    expect(leadCover(page([], [item('playlist', null), item('playlist', 'shelf.jpg')]))).toBe('shelf.jpg');
  });
  it('falls back to anything with art, and to nothing before the page has loaded', () => {
    expect(leadCover(page([item('album', 'album.jpg')]))).toBe('album.jpg');
    expect(leadCover(undefined)).toBeNull();
    expect(leadCover(page([item('playlist', null)]))).toBeNull();
  });
});
