/**
 * Messages between the popup and the background that aren't part of the
 * page bridge (@opentune/shared/bridge): only the extension's own pages may
 * send them, and the page never sees them.
 */

export const POPUP_CHANNEL = 'neptune-popup';

/** Which of these player tabs have reached the extension this session, and which hold a port now. */
export interface PlayersRequest {
  channel: typeof POPUP_CHANNEL;
  type: 'players';
  tabIds: number[];
}

export function isPopupRequest(value: unknown): value is PlayersRequest {
  const v = value as Partial<PlayersRequest> | null;
  return (
    typeof v === 'object' &&
    v !== null &&
    v.channel === POPUP_CHANNEL &&
    v.type === 'players' &&
    Array.isArray(v.tabIds) &&
    v.tabIds.length <= 64 &&
    v.tabIds.every((id) => Number.isInteger(id))
  );
}
