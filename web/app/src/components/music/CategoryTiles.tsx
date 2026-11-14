import { useQuery } from '@tanstack/react-query';
import { useEffect, useRef, useState } from 'react';
import { PrefetchLink } from '@/components/PrefetchLink';
import { browseQuery, useYouTubeReady } from '@/data/music/queries';
import type { BrowsePage, Category, CategoryGroup } from '@/data/music/types';
import { sizedArtwork } from '@/lib/artwork';
import { browseHref } from './links';

/** The first cover a mood or genre's own page leads with: its first playlist, or anything with art. */
export function leadCover(page: BrowsePage | undefined): string | null {
  if (!page) return null;
  const items = [...page.grid, ...page.shelves.flatMap((s) => s.items)];
  return (items.find((i) => i.kind === 'playlist' && i.thumbnail) ?? items.find((i) => i.thumbnail))?.thumbnail ?? null;
}

/**
 * One tile: YouTube's colour for the mood or genre, its name at the bottom left, and, once the
 * tile has come into view, the cover its own page leads with, tilted in the top right corner.
 * YouTube sends these tiles without any picture; the page is read only when the tile is seen,
 * and the answer is the same one that page opens with, so opening it after is instant.
 */
function CategoryTile({ category }: { category: Category }) {
  const ref = useRef<HTMLLIElement>(null);
  const [seen, setSeen] = useState(false);
  const { ready } = useYouTubeReady();
  useEffect(() => {
    const node = ref.current;
    if (!node || seen) return;
    const watch = new IntersectionObserver((entries) => {
      if (entries.some((e) => e.isIntersecting)) {
        setSeen(true);
        watch.disconnect();
      }
    }, { rootMargin: '200px' });
    watch.observe(node);
    return () => watch.disconnect();
  }, [seen]);
  const page = useQuery({ ...browseQuery(category.link), enabled: ready && seen });
  const cover = leadCover(page.data);
  return (
    <li ref={ref}>
    <PrefetchLink
      to={browseHref(category.link)}
      className="relative flex aspect-video items-end overflow-hidden rounded-artwork-lg bg-[radial-gradient(120%_90%_at_100%_0%,color-mix(in_srgb,var(--tile-color)_70%,#fff)_0%,transparent_70%),linear-gradient(160deg,var(--tile-color),color-mix(in_srgb,var(--tile-color)_55%,#000))] px-3.5 py-3 text-white transition-[scale] duration-200 ease-panel hover:scale-[1.015]"
      style={{ '--tile-color': category.color } as React.CSSProperties}
    >
      {cover && (
        <img
          src={sizedArtwork(cover, 160)}
          alt=""
          loading="lazy"
          referrerPolicy="no-referrer"
          className="ot-tile-cover pointer-events-none absolute top-[12%] right-[-6%] aspect-square h-[78%] rotate-[18deg] rounded-[8px] object-cover shadow-[0_6px_18px_#00000066]"
        />
      )}
      <span className="relative type-title-2-emphasized [text-shadow:0_1px_8px_#0000004d]">{category.title}</span>
    </PrefetchLink>
    </li>
  );
}

/**
 * Browse Categories bricks (report §6): 16:9 tiles, radius 10, the title at
 * the bottom left. Coloured from YouTube's own stripe colour per mood/genre,
 * with the cover each one's page leads with.
 */
export function CategoryTiles({ groups }: { groups: CategoryGroup[] }) {
  return (
    <>
      {groups.map((group) => (
        <section key={group.title} className="pt-3" aria-label={group.title}>
          <h2 className="mx-(--body-gutter) type-title-2-emphasized">{group.title}</h2>
          {/* 4 columns at ≥1000px, gap 20px (report §6); 3, then 2, below. */}
          <ul className="mx-(--body-gutter) mt-3 mb-8 grid grid-cols-4 gap-5 max-lg:grid-cols-3 max-[607px]:grid-cols-2 max-[607px]:gap-2.5">
            {group.categories.map((category) => (
              <CategoryTile key={`${category.title}-${category.link.params}`} category={category} />
            ))}
          </ul>
        </section>
      ))}
    </>
  );
}
