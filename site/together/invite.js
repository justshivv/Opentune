'use strict';
// The invite stays in the fragment: it is never sent to the website server.
const code = new URLSearchParams(location.hash.slice(1)).get('code') || '';
if (/^[23456789ABCDEFGHJKLMNPQRSTUVWXYZ]{12}$/.test(code)) {
  const custom = `opentune://together/${code}`;
  const link = document.getElementById('open');
  link.href = /Android/i.test(navigator.userAgent)
    ? `intent://together/${code}#Intent;scheme=opentune;package=com.opentune;end`
    : custom;
  link.hidden = false;
  // The web player joins the same rooms, from a computer or a phone's browser.
  const web = document.getElementById('web');
  web.href = `../player/#/invite/${code}`;
  web.hidden = false;
  document.getElementById('help').hidden = false;
  document.getElementById('code').textContent = code.match(/.{4}/g).join('–');
} else {
  document.getElementById('message').textContent = 'This invite is incomplete or invalid. Ask your friend to share the room link again.';
}
