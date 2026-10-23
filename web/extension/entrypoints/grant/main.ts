import { publicHttps } from '@opentune/shared/addons';
import '../popup/style.css';
import './style.css';

// Opened by lyrics.requestPermissions and addon.requestPermission: the browser only
// shows a permission prompt in answer to a click on one of the extension's own pages.
const query = new URLSearchParams(location.search);
const origins = (query.get('origins') ?? '').split(',').filter(Boolean).map(o => publicHttps(o).origin);
if (query.get('kind') === 'addons') {
  document.title = 'OpenTune: audio add-ons';
  document.querySelector('h1')!.textContent = origins.length > 1 ? 'Allow audio add-ons' : 'Allow this audio add-on';
  document.getElementById('why')!.textContent = 'These add-ons don’t let web pages ask them directly, so the OpenTune web player asks through the extension. Your browser will ask you to confirm.';
}
const list = document.getElementById('hosts')!;
for (const origin of origins) {
  const item = document.createElement('li');
  item.textContent = new URL(origin).host;
  list.append(item);
}

const status = document.getElementById('status')!;
document.getElementById('allow')!.addEventListener('click', async () => {
  const granted = await browser.permissions.request({ origins: origins.map((o) => `${o}/*`) }).catch(() => false);
  status.textContent = granted ? 'Done. You can close this tab and go back to OpenTune.' : 'Not allowed. Those sources stay off.';
  if (granted) setTimeout(() => window.close(), 1200);
});

