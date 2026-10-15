'use strict';
(() => {
  const input = new URLSearchParams(location.search);
  // Clear the one-time code from browser history before any further navigation.
  history.replaceState(null, '', location.pathname);
  const state = input.getAll('state');
  const code = input.getAll('code');
  const error = input.getAll('error');
  if (state.length !== 1 || !/^[A-Za-z0-9_-]{43}$/.test(state[0]) ||
      !((code.length === 1 && code[0].length > 0 && code[0].length <= 4096 && error.length === 0) ||
        (error.length === 1 && code.length === 0))) {
    document.getElementById('message').textContent = 'This sign-in link is incomplete. Start Spotify sign-in again in OpenTune.';
    return;
  }
  const output = new URLSearchParams({state: state[0]});
  output.set(code.length ? 'code' : 'error', code.length ? code[0] : 'access_denied');
  const button = document.getElementById('open');
  button.href = 'opentune://spotify-callback?' + output;
  button.hidden = false;
})();
