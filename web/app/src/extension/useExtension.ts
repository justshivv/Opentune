import { BRIDGE_PROTOCOL, BridgeCallError, type Bridge, type DetectedExtension } from '@opentune/shared/bridge';
import { useQuery } from '@tanstack/react-query';
import { config } from '@/config';
import { queryClient } from '@/lib/queryClient';
import { findExtension, KNOWN_BUDGET_MS, NEW_VISITOR_BUDGET_MS, pingOver, readSeen, rememberSeen, type Probe } from './detect';

export type ExtensionStatus =
  /** Looking. [seenBefore]: it answered on this browser before, so no setup popup while it wakes. */
  | { state: 'checking'; seenBefore?: boolean }
  /**
   * Nothing usable answered. [seenBefore]: it used to, so it's more likely
   * asleep, disabled or broken than not installed. [refused]: it answered
   * but refused this page, or is too old for it.
   */
  | { state: 'missing'; seenBefore?: boolean; refused?: { code: string; message: string } | null }
  | { state: 'present'; extension: DetectedExtension };

export const extensionQueryKey = ['extension', 'detect'] as const;

/** Re-pings after a miss, so one that wakes late (or is installed meanwhile) is picked up without a reload. */
const RECHECKS = 4;
const RECHECK_MS = 1500;
const SLOW_RECHECK_MS = 5000;
/** A known extension that misses a re-ping (on focus) gets this long before it's called gone. */
const KNOWN_RECHECK_BUDGET_MS = 4000;

/**
 * When calls through the found bridge started failing with "not delivered"
 * (after the bridge's own quick re-sends), or null while they work. A
 * re-ping that also finds nothing then reports the extension as gone, so
 * every feature stops waiting on it at once and the page shows one note.
 */
let downSince: number | null = null;

/** The bridge, noting each call that reached nobody and asking for a fresh handshake then. */
function watched(found: DetectedExtension): DetectedExtension {
  const { bridge } = found;
  const call = ((method, params, options) =>
    bridge.call(method, params, options).then(
      (value) => {
        downSince = null;
        return value;
      },
      (error: unknown) => {
        if (error instanceof BridgeCallError && error.code === 'unavailable') {
          downSince ??= Date.now();
          recheckExtension();
        }
        throw error;
      },
    )) as Bridge['call'];
  return { ...found, bridge: { transport: bridge.transport, call, subscribe: (listener) => bridge.subscribe(listener) } };
}

/** The last refusal, for the popup's wording; cleared by the next answer. */
let lastRefusal: { code: string; message: string } | null = null;

/** An extension that answers with another protocol can't be used: it's reported as a refusal. */
function usable(probe: Probe): Probe {
  if (probe.kind !== 'found' || probe.extension.info.protocol === BRIDGE_PROTOCOL) return probe;
  return {
    kind: 'refused',
    code: 'protocol',
    message: `The NepTune extension (v${probe.extension.info.version}) speaks bridge protocol ${probe.extension.info.protocol}; this page speaks ${BRIDGE_PROTOCOL}.`,
  };
}

/**
 * One detection round (detect.ts, findExtension). When the extension was
 * already found on this page, a miss keeps it: a worker that fell asleep or
 * restarted must not flip the site to "not installed" and strand everything
 * that reads the bridge until a reload. A bridge is only a function over
 * chrome.runtime, so it recovers on its own when the worker is back; a
 * removed extension fails each call instead.
 */
async function detect(): Promise<DetectedExtension | null> {
  const known = queryClient.getQueryData<DetectedExtension | null>(extensionQueryKey) ?? null;
  const budgetMs = known ? KNOWN_RECHECK_BUDGET_MS : readSeen() ? KNOWN_BUDGET_MS : NEW_VISITOR_BUDGET_MS;
  const probe = usable(await findExtension({ budgetMs, ping: pingOver(config.chromiumExtensionId) }));
  if (probe.kind === 'found') {
    lastRefusal = null;
    downSince = null;
    rememberSeen(probe.extension.info);
    return watched(probe.extension);
  }
  lastRefusal = probe.kind === 'refused' ? { code: probe.code, message: probe.message } : null;
  // Silent, and calls have been reaching nobody: gone, not asleep (the re-pings keep looking).
  if (downSince !== null) return null;
  return known;
}

/** Looks again now (a "Try Again" button, or a call that reached nobody). Joins a look already under way. */
export function recheckExtension(): void {
  void queryClient.refetchQueries({ queryKey: extensionQueryKey }, { cancelRefetch: false });
}

/**
 * Whether the companion extension is installed (prompt §4.2: a `ping` on
 * startup). Someone who had it before is never told it's missing while it
 * wakes: "checking" lasts until it answers or KNOWN_BUDGET_MS passes.
 */
export function useExtension(): ExtensionStatus {
  const query = useQuery({
    queryKey: extensionQueryKey,
    queryFn: detect,
    staleTime: 30_000,
    gcTime: Infinity,
    retry: false,
    // Re-pinged when the tab comes back, so an extension updated or reloaded
    // meanwhile reports its new version (useExtensionUpdate).
    refetchOnWindowFocus: true,
    refetchInterval: (q) =>
      q.state.data === null ? (q.state.dataUpdateCount <= RECHECKS ? RECHECK_MS : SLOW_RECHECK_MS) : false,
  });
  const seenBefore = readSeen() !== null;
  if (query.isPending) return { state: 'checking', seenBefore };
  if (!query.data) return { state: 'missing', seenBefore, refused: lastRefusal };
  return { state: 'present', extension: query.data };
}
