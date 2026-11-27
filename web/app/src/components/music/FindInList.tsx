import { Search, X } from 'lucide-react';
import { useRef } from 'react';
import type { Track } from '@/data/music/types';

/** Lower case, accents dropped: "Beyoncé" is found by "beyonce". */
const fold = (text: string) => text.normalize('NFD').replace(/[̀-ͯ]/g, '').toLowerCase();

/**
 * The songs of a list that match [query]: every word of it in the title, an artist or the album,
 * in any order. An empty query keeps every song.
 */
export function findInList(tracks: Track[], query: string): Track[] {
  const words = fold(query).split(/\s+/).filter(Boolean);
  if (!words.length) return tracks;
  return tracks.filter((track) => {
    const haystack = fold([track.title, ...track.artists.map((a) => a.name), track.album?.name ?? ''].join(' '));
    return words.every((word) => haystack.includes(word));
  });
}

/**
 * The search field above a playlist's songs: narrows the list as you type. Escape clears it;
 * the × button clears it and keeps the focus in the field.
 */
export function FindInList({ query, onQuery, label }: { query: string; onQuery: (query: string) => void; label: string }) {
  const input = useRef<HTMLInputElement>(null);
  return (
    <div className="relative flex w-[min(260px,100%)] items-center">
      <Search size={14} strokeWidth={2.4} className="pointer-events-none absolute left-2.5 text-secondary" aria-hidden="true" />
      <input
        ref={input}
        type="search"
        value={query}
        onChange={(e) => onQuery(e.target.value)}
        onKeyDown={(e) => {
          if (e.key === 'Escape' && query) {
            e.stopPropagation();
            onQuery('');
          }
        }}
        placeholder={label}
        aria-label={label}
        spellCheck={false}
        autoComplete="off"
        className="h-8 w-full rounded-pill border border-search-border bg-quinary pr-7 pl-7 type-callout text-primary transition-[background-color,border-color] placeholder:text-tertiary focus:border-key focus:bg-page focus:outline-none [&::-webkit-search-cancel-button]:hidden"
      />
      {query && (
        <button
          className="absolute right-2 grid size-4 place-items-center rounded-full bg-tertiary text-page"
          aria-label="Clear search"
          onClick={() => {
            onQuery('');
            input.current?.focus();
          }}
        >
          <X size={10} strokeWidth={3} />
        </button>
      )}
    </div>
  );
}
