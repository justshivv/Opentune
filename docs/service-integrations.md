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

BitChord's Android presence uses a Discord user-token gateway implementation and its
own Discord application identity. It is not a registration-free standard OAuth
integration. OpenTune needs its own application identity and an implemented, tested
presence transport before this can be offered as a working Android setting. That
Android integration is not included in this build. No BitChord credentials or
application identity are used.

## Browser extension updates

The popup can check the published extension version and open its download and the
browser's extension manager. Signed browser-managed installations can request the
browser's update service. A development ZIP installed with **Load unpacked** cannot
replace its own files or silently reinstall itself. It still needs the downloaded
folder replaced and Reload selected in the extension manager. Store publication is
required for the usual automatic consumer update experience.
