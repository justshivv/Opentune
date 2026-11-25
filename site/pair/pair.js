'use strict';
// Fragment stays on the device; no analytics, storage, network fetch or third-party scripts.
const payload = location.hash.slice(1);
// The code's hints (what else the device takes, its other addresses) ride in the query; only
// well-formed ones are passed on to the app, which checks them again.
const params = new URLSearchParams(location.search);
const kept = [];
const take = params.get('take');
if (take && /^[a-z,]{1,40}$/.test(take)) kept.push(`take=${take}`);
const also = params.get('also');
if (also && /^[0-9A-Fa-f.:,]{1,300}$/.test(also)) kept.push(`also=${also}`);
history.replaceState(null, '', location.pathname);
if (/^[A-Za-z0-9_-]{40,512}$/.test(payload)) {
  const open = document.getElementById('open');
  open.href = `opentune://pair/${kept.length ? '?' + kept.join('&') : ''}#${payload}`;
  open.hidden = false;
} else {
  document.getElementById('status').textContent = 'Scan a fresh QR code from NepTune → Settings → Account → Sign in with your phone.';
}
