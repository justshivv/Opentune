import type { Shelf } from '@/data/music/types';
/** One quick-picks shelf up top; alternate later song rows with artwork shelves. */
export function homeSections(shelves: Shelf[]) {
  const quick = shelves.find(s => /quick picks/i.test(s.title)) ?? shelves.find(s => s.layout === 'tracks');
  const remaining = shelves.filter(s => s !== quick && !/recently played/i.test(s.title));
  const songs = remaining.filter(s => s.layout === 'tracks');
  const covers = remaining.filter(s => s.layout !== 'tracks');
  const arranged: Shelf[] = [];
  while (covers.length || songs.length) {
    const cover = covers.shift(); if (cover) arranged.push(cover);
    const song = songs.shift(); if (song) arranged.push(song);
  }
  return { quick, shelves: arranged };
}
