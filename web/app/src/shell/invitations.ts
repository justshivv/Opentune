/**
 * The two monthly invitations, as on Android: a GitHub star after five days of
 * use, and the Discord after ten. Each comes at most once every 30 days and
 * never within two weeks of the other, so they take turns. Kept in this
 * browser only; when storage is blocked, nothing is shown.
 */
export type Invitation = 'star' | 'discord';

export const GITHUB_URL = 'https://github.com/justshivv/Opentune';
export const DISCORD_URL = 'https://discord.gg/6udrCVh5n';

const KEY = 'opentune:invitations';
const DAY = 86_400_000;
const GAP = 30 * DAY;
const APART = 14 * DAY;
const DAYS_BEFORE: Record<Invitation, number> = { star: 5, discord: 10 };

interface State {
  lastDay: number;
  days: number;
  asked: Partial<Record<Invitation, number>>;
  never: Partial<Record<Invitation, boolean>>;
}

function read(): State | null {
  try {
    const value = JSON.parse(localStorage.getItem(KEY) ?? 'null') as Partial<State> | null;
    return { lastDay: value?.lastDay ?? -1, days: value?.days ?? 0, asked: value?.asked ?? {}, never: value?.never ?? {} };
  } catch {
    return null;
  }
}

function write(state: State) {
  try { localStorage.setItem(KEY, JSON.stringify(state)); } catch { /* Storage blocked: the invitation just won't come back. */ }
}

/** Counts today as a day of use, once a day. */
export function usedToday(now = Date.now()): void {
  const state = read();
  if (!state) return;
  const day = Math.floor(now / DAY);
  if (state.lastDay === day) return;
  write({ ...state, lastDay: day, days: state.days + 1 });
}

export function shouldAsk(state: Pick<State, 'days' | 'asked' | 'never'>, invitation: Invitation, now: number): boolean {
  const asked = state.asked[invitation] ?? 0;
  const other = Math.max(...Object.values(state.asked), 0);
  return !state.never[invitation] && state.days >= DAYS_BEFORE[invitation] && now - asked >= GAP && (other === asked || now - other >= APART);
}

/** The invitation due now, the star first when both are. */
export function due(now = Date.now()): Invitation | null {
  const state = read();
  if (!state) return null;
  return (['star', 'discord'] as const).find((invitation) => shouldAsk(state, invitation, now)) ?? null;
}

/** Shown (or turned down for good): not asked again for a month, or ever. */
export function answered(invitation: Invitation, never: boolean, now = Date.now()): void {
  const state = read();
  if (!state) return;
  write({ ...state, asked: { ...state.asked, [invitation]: now }, never: never ? { ...state.never, [invitation]: true } : state.never });
}
