import {
  BRIDGE_CHANNEL,
  errorResponse,
  EVENTS_PORT,
  isBridgeRequest,
  type BridgeEvent,
  type BridgeEventMessage,
  type BridgeResponse,
} from '@opentune/shared/bridge';

/** How long a dropped event port waits before reconnecting, while the page is visible. */
const RECONNECT_MS = 2000;

/**
 * Firefox has no externally_connectable for web pages, so this relays the
 * page's window messages to the background and posts the answers back
 * (prompt §4.2). Chromium builds leave it out. `matches` is replaced with the
 * site origins by the manifest hook in wxt.config.ts.
 *
 * It also holds the event port and forwards each push to the page, which is
 * the relay's half of `Bridge.subscribe`.
 */
export default defineContentScript({
  matches: ['http://localhost/*'],
  include: ['firefox'],
  runAt: 'document_start',
  main() {
    // Updating or reloading the extension leaves this copy of the script in the open page, cut off from
    // the extension: every call into it then throws "Extension context invalidated". So each call checks
    // first, and once the extension is gone this copy stops listening and stops reconnecting, quietly. The
    // page's requests meanwhile get an answer that says what happened, rather than no answer at all.
    const alive = () => {
      try {
        return !!browser.runtime?.id;
      } catch {
        return false;
      }
    };
    const GONE = 'OpenTune Extension was updated or reloaded. Reload this page to reconnect to it.';
    let port: Browser.runtime.Port | null = null;
    let retry: ReturnType<typeof setTimeout> | undefined;

    const onMessage = (event: MessageEvent) => {
      if (event.source !== window || event.origin !== location.origin) return;
      if (!isBridgeRequest(event.data)) return;
      const request = event.data;
      if (!alive()) {
        window.postMessage(errorResponse(request.id, 'internal', GONE), location.origin);
        return stop();
      }
      try {
        browser.runtime
          .sendMessage(request)
          .then((response: BridgeResponse) => window.postMessage(response, location.origin))
          .catch((error: unknown) =>
            window.postMessage(errorResponse(request.id, 'internal', alive() ? String(error) : GONE), location.origin),
          );
      } catch {
        window.postMessage(errorResponse(request.id, 'internal', GONE), location.origin);
        stop();
      }
    };

    const connect = () => {
      if (port || document.hidden) return;
      if (!alive()) return stop();
      try {
        port = browser.runtime.connect({ name: EVENTS_PORT });
      } catch {
        return stop();
      }
      port.onMessage.addListener((payload: BridgeEvent) => {
        const message: BridgeEventMessage = { channel: BRIDGE_CHANNEL, direction: 'event', payload };
        window.postMessage(message, location.origin);
      });
      port.onDisconnect.addListener(() => {
        port = null;
        // A dropped port is retried; one dropped because the extension went away is not.
        if (alive()) retry = setTimeout(connect, RECONNECT_MS);
        else stop();
      });
    };

    function stop() {
      clearTimeout(retry);
      port = null;
      window.removeEventListener('message', onMessage);
      document.removeEventListener('visibilitychange', connect);
    }

    window.addEventListener('message', onMessage);
    document.addEventListener('visibilitychange', connect);
    connect();
  },
});
