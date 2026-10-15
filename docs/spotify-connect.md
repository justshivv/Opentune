# Connect Spotify to OpenTune

Spotify sign-in imports the playlists and Liked Songs the account permits OpenTune to read. It does not stream Spotify audio or unlock Spotify Premium/lossless. Audio still resolves through OpenTune's existing sources. The connection is read-only: no likes, playlists or history are written back to Spotify.

1. Create an application in the [Spotify Developer Dashboard](https://developer.spotify.com/dashboard). Select Web API.
2. Register this exact redirect URI, including the trailing slash:

   `https://justshivv.github.io/Opentune/spotify/callback/`

3. Copy its **client ID**, never its client secret. Development-mode account limits and eligibility apply; add the intended test account in the dashboard if required.
4. In OpenTune, open **Import a playlist → Connect Spotify** (also under Connected services). Enter that client ID and continue in your browser.
5. Approve read access, then tap **Open OpenTune** on the callback page if Android does not open the app automatically.
6. Import Liked Songs or choose a playlist. OpenTune shows matching/import progress in the existing import screen. Unavailable/local Spotify entries cannot be imported through this API.

The website callback must be deployed before this flow works. The implementation uses authorization-code PKCE (S256), a random one-time state, and a ten-minute attempt expiry. Tokens and pending verifiers are encrypted with Android Keystore and excluded from backups/device transfer. Tokens are sent only to Spotify's token/API hosts; HTTP redirects are disabled for credentialed requests. Disconnect removes the local connection. Permissions can also be revoked from your Spotify account's Apps page.

No working Spotify client ID is bundled yet. Spotify may restrict API access for developer-mode apps and certain playlists; the app reports denied access instead of silently importing an incomplete playlist. A developer application/approved account is required for an end-to-end sign-in test. Public-link/CSV import continues to work without this connection.

References: [PKCE](https://developer.spotify.com/documentation/web-api/tutorials/code-pkce-flow), [redirect URIs](https://developer.spotify.com/documentation/web-api/concepts/redirect_uri).
