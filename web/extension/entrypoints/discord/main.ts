import '../popup/style.css';
import '../grant/style.css';
import { connectFromTab, DISCORD_ORIGINS } from '../../lib/discord';

// Opened by discord.connect: the browser only asks for discord.com after a
// click on one of the extension's own pages, and the token is only read on it.
const status = document.getElementById('status')!;
const button = document.getElementById('connect') as HTMLButtonElement;

// Opened by discord.allowAccess, for signing in with the phone: only discord.com is asked for
// here, as the phone hands the session over itself and no tab is read.
const forPhone = new URLSearchParams(location.search).get('for') === 'phone';
if (forPhone) {
  document.querySelector('main > p:not(.warning)')!.textContent =
    'To show what you play, the extension talks to Discord with the session your phone hands it. Allow it to reach discord.com, then go back to OpenTune and scan the code with your phone.';
  button.textContent = 'Allow discord.com';
}

button.addEventListener('click', async () => {
  const granted = await browser.permissions.request({ origins: DISCORD_ORIGINS }).catch(() => false);
  if (!granted) {
    status.textContent = 'Not allowed. Discord stays off.';
    return;
  }
  if (forPhone) {
    status.textContent = 'Allowed. Go back to OpenTune and make the code.';
    setTimeout(() => window.close(), 1500);
    return;
  }
  button.disabled = true;
  try {
    const state = await connectFromTab((message) => (status.textContent = message));
    status.textContent = `Connected as ${state.user?.name ?? 'your account'}. You can close this tab.`;
    setTimeout(() => window.close(), 1500);
  } catch (error) {
    status.textContent = error instanceof Error ? error.message : String(error);
    button.disabled = false;
  }
});

