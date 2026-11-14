import { publicHttps } from '@opentune/shared/addons';
import { BridgeCallError } from '@opentune/shared/bridge';

type Call = (method: 'addon.fetch', params: { url: string }) => Promise<unknown>;

/**
 * Whether the extension may reach [origin], asked through [call] (null without the extension)
 * with a small request it refuses before sending anything when it may not.
 */
export async function askHost(call: Call | null, origin: string): Promise<boolean | null> {
  if (!call) return null;
  try {
    await call('addon.fetch', { url: `${publicHttps(origin).origin}/robots.txt` });
    return true;
  } catch (error) {
    // No answer from the extension says nothing either way.
    if (!(error instanceof BridgeCallError) || error.code === 'timeout' || error.code === 'unavailable') return null;
    // Any other refusal (the site down, a redirect) comes after the permission check passed.
    return error.code !== 'permission';
  }
}
