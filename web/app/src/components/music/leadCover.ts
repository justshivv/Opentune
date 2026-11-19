import type { BrowsePage } from '@/data/music/types';

/** The first cover a mood or genre's own page leads with: its first playlist, or anything with art. */
export function leadCover(page: BrowsePage | undefined): string | null {
  if (!page) return null;
  const items = [...page.grid, ...page.shelves.flatMap((s) => s.items)];
  return (items.find((i) => i.kind === 'playlist' && i.thumbnail) ?? items.find((i) => i.thumbnail))?.thumbnail ?? null;
}
