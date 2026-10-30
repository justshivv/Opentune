# Service integrations in OpenTune builds

## Last.fm

Listeners sign in using a username and password. The app exchanges those directly
with Last.fm for a session key and does not store the password. Existing connected
accounts retain their signing credentials and queued scrobbles across updates.

The build owner must register **OpenTune's own application** at
https://www.last.fm/api/account/create and configure the repository Actions secrets
`LASTFM_API_KEY` and `LASTFM_API_SECRET`. The preview and release workflows pass
these into the Android core module. Local builds accept the same environment names.
Without a configured application, new sign-in is disabled with an explanation;
there is no pretend connection and users are not asked to register their own API app.

BitChord supplies application credentials in its code. That does not make Last.fm
registration optional. OpenTune does not reuse another app's credentials. Credentials
embedded in mobile binaries can be recovered; Actions secrets keep them out of Git
and build logs, not out of distributed binaries.

## Discord presence

Discord titles a presence card with the registered name of the Discord application
it comes from, so OpenTune needs its own application, named OpenTune. Earlier
desktop and extension builds borrowed BitChord's application id, which is why the
card read "Listening to BitChord". That id is gone; no BitChord application
identity is used now.

To set it up once:

1. At [discord.com/developers/applications](https://discord.com/developers/applications),
   create an application named **OpenTune** and give it the OpenTune icon
   (`docs/logo.png`).
2. Copy its **Application ID** (a 17 to 20 digit number; it isn't a secret).
3. In the repository's **Settings → Secrets and variables → Actions → Variables**,
   add a variable `DISCORD_APP_ID` with that number. CI, release and the website
   build read it, so the desktop app and the browser extension carry it.

A desktop build without one asks for the id in **Settings → Discord and Last.fm**
and shows nothing on Discord until it has one. The extension without one still
posts its card named OpenTune, but with the cover as a plain link and no
application behind its buttons.

The Android app has no Discord presence yet. BitChord's Android presence uses a
Discord user-token gateway, not a registration-free OAuth integration; OpenTune
would need a tested transport of its own before offering it there.

## Browser extension updates

The popup can check the published extension version and open its download and the
browser's extension manager. Signed browser-managed installations can request the
browser's update service. A development ZIP installed with **Load unpacked** cannot
replace its own files or silently reinstall itself. It still needs the downloaded
folder replaced and Reload selected in the extension manager. Store publication is
required for the usual automatic consumer update experience.

## Spotify saves

Playlist additions send a real account write and require Spotify's returned snapshot
before updating OpenTune's copy. They need an editable playlist and a verified matching
recording. This does not submit listening history to Spotify.

Spotify requests share a paced request lane. HTTP 429 responses honor `Retry-After`
(seconds or an HTTP date); short cooldowns get at most two retries within a 90-second
wait budget. Longer cooldowns produce a readable wait message and prevent subsequent
requests from ignoring that cooldown. Repeated taps are coalesced while a save is pending;
multiple songs are sent in playlist batches of up to 100. Connection failures and
unconfirmed successful responses are not automatically replayed, because the previous
write might already have reached Spotify. Refresh before manually retrying those cases.

Spotify still controls account permissions, availability and rate limits. Automated
response tests cannot establish that a particular account's web session permits writes.
