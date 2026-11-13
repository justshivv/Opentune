import { create } from 'zustand';

const KEY = 'opentune:recent-searches';
const LIMIT = 8;

function read(): string[] {
  try {
    const v = JSON.parse(localStorage.getItem(KEY) ?? '[]');
    return Array.isArray(v) ? v.filter((x): x is string => typeof x === 'string').slice(0, LIMIT) : [];
  } catch {
    return [];
  }
}

function write(list: string[]) {
  try {
    localStorage.setItem(KEY, JSON.stringify(list));
  } catch {
    /* Kept for this visit only. */
  }
}

/** The last few searches, newest first, offered when the search field is empty. */
export const useRecentSearches = create<{
  recent: string[];
  add: (query: string) => void;
  remove: (query: string) => void;
  clear: () => void;
}>((set, get) => ({
  recent: read(),
  add(query) {
    const q = query.trim();
    if (!q) return;
    const recent = [q, ...get().recent.filter((x) => x.toLowerCase() !== q.toLowerCase())].slice(0, LIMIT);
    write(recent);
    set({ recent });
  },
  remove(query) {
    const recent = get().recent.filter((x) => x !== query);
    write(recent);
    set({ recent });
  },
  clear() {
    write([]);
    set({ recent: [] });
  },
}));
