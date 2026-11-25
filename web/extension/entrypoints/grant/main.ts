import { publicHttps } from '@opentune/shared/addons';
import { isReservedHost } from '../../lib/hostPolicy';
import { LYRIC_ORIGINS } from '../../lib/lyricHosts';
import '../popup/style.css';
import './style.css';

// Opened by lyrics.requestPermissions and addon.requestPermission: the browser only
// shows a permission prompt in answer to a click on one of the extension's own pages.
// The list comes from this page's own URL, and is checked again here: lyric hosts
// must be on the known list, add-on hosts public HTTPS and not ones the extension
// holds sessions for.
const query = new URLSearchParams(location.search);
const addons = query.get('kind') === 'addons';

function originOf(raw: string): string | null {
  try {
    const origin = publicHttps(raw).origin;
    if (addons) return isReservedHost(new URL(origin).hostname) ? null : origin;
    return (LYRIC_ORIGINS as readonly string[]).includes(origin) ? origin : null;
  } catch {
    return null;
  }
}

const origins = [
  ...new Set(
    (query.get('origins') ?? '')
      .split(',')
      .filter(Boolean)
      .map(originOf)
      .filter((o): o is string => o !== null),
  ),
].slice(0, 40);

if (addons) {
  document.title = 'NepTune: audio add-ons';
  document.querySelector('h1')!.textContent = origins.length > 1 ? 'Allow audio add-ons' : 'Allow this audio add-on';
  document.getElementById('why')!.textContent =
    'These add-ons don’t let web pages ask them directly, so the NepTune web player asks through the extension. Your browser will ask you to confirm.';
}

const list = document.getElementById('hosts')!;
for (const origin of origins) {
  const item = document.createElement('li');
  item.textContent = new URL(origin).host;
  list.append(item);
}

const status = document.getElementById('status')!;
const allow = document.getElementById('allow') as HTMLButtonElement;
const patterns = origins.map((o) => `${o}/*`);

if (!origins.length) {
  allow.hidden = true;
  status.textContent = 'Nothing to allow here. Go back to NepTune and try again.';
} else {
  void browser.permissions.contains({ origins: patterns }).then((has) => {
    if (!has) return;
    allow.hidden = true;
    status.textContent = 'Already allowed. You can close this tab.';
  });
}

allow.addEventListener('click', async () => {
  const granted = await browser.permissions.request({ origins: patterns }).catch(() => false);
  status.textContent = granted
    ? 'Done. You can close this tab and go back to NepTune. You can remove access any time from the NepTune toolbar button.'
    : 'Not allowed. Those sources stay off.';
  if (granted) {
    allow.hidden = true;
    setTimeout(() => window.close(), 2500);
  }
});
