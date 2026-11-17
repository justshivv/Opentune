import { create } from 'zustand';

/**
 * Banners and featured playlists from the control dashboard that the listener
 * closed on Home, kept in this browser. The same rules as the phone app's
 * HomeDismissals: a closed banner stays closed and a new one (a new id) shows;
 * the featured playlists come back once one is added that wasn't there when
 * the shelf was closed.
 */
interface HomeDismissals {
  banners: string[];
  /** The featured playlists there were when the shelf was closed. */
  playlists: string[];
  dismissBanner: (id: string) => void;
  dismissPlaylists: (ids: string[]) => void;
}

const KEY = 'opentune:home-dismissals';

type Saved = Pick<HomeDismissals, 'banners' | 'playlists'>;

/** What was saved, or nothing closed when there's nothing readable (blocked storage, an old value). */
export function savedOf(raw: string | null): Saved {
  try {
    const value = JSON.parse(raw ?? '{}') as Partial<Record<keyof Saved, unknown>>;
    const strings = (list: unknown) => (Array.isArray(list) ? list.filter((x): x is string => typeof x === 'string') : []);
    return { banners: strings(value.banners), playlists: strings(value.playlists) };
  } catch {
    return { banners: [], playlists: [] };
  }
}

function load(): Saved {
  try {
    return savedOf(localStorage.getItem(KEY));
  } catch {
    return savedOf(null);
  }
}

function save(saved: Saved) {
  try {
    localStorage.setItem(KEY, JSON.stringify(saved));
  } catch {
    // Not remembered; it's still closed until the page reloads.
  }
}

export const useHomeDismissals = create<HomeDismissals>()((set, get) => ({
  ...load(),
  dismissBanner: (id) => {
    const banners = get().banners.includes(id) ? get().banners : [...get().banners, id];
    set({ banners });
    save({ banners, playlists: get().playlists });
  },
  dismissPlaylists: (ids) => {
    const playlists = [...new Set(ids)];
    set({ playlists });
    save({ banners: get().banners, playlists });
  },
}));

/** Whether the featured shelf shows: something in it the listener hasn't closed. */
export const showsPlaylists = (ids: string[], dismissed: string[]): boolean => ids.some((id) => !dismissed.includes(id));

/** The banners still to show: switched on for the web, and not closed here. */
export const openBanners = <T extends { id: string; enabled: boolean; platforms: string[] }>(banners: T[], dismissed: string[]): T[] =>
  banners.filter((b) => b.enabled && b.platforms.includes('web') && !dismissed.includes(b.id));
