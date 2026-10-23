# Protected first-party add-on access

## Rollout status

The owner chose Android, TV and web support with verification required and unsupported devices blocked. The implementation is on `feat/first-party-lossless-addon`, alongside the first-party source in PR #29. It is not merged into main.

The Worker is temporarily deployed with **`VERIFICATION_MODE = "staged"`**. It can enroll devices and pair browsers, but existing unauthenticated catalogue requests still work during this compatibility window. **The production source is not yet OpenTune-only.** Rate limiting is live. Never describe staging as strict enforcement.

Before changing `VERIFICATION_MODE` to `strict`, install the official-signed preview, confirm an actual hardware-attestation enrollment and protected lookup, confirm browser approval, distribute the compatible Android/TV/web clients, then deploy the strict configuration. The deployment smoke test asserts unauthenticated searches return 401 in strict mode. The old clients cannot satisfy strict verification; there is deliberately no static-token or User-Agent fallback.

Builds are artifacts of the **Verified add-on clients** workflow. `OpenTune-verified-source-signed` contains the Android/TV APK; `OpenTune-verified-source-web` contains the web build. The web build is not automatically published over the existing site. Preview builds use the existing release signing identity and install over that identity's app. No GitHub release is created.

The [23 October client build](https://github.com/justshivv/Opentune/actions/runs/40027490120) passed for both platforms: [signed Android/TV APK](https://github.com/justshivv/Opentune/actions/runs/40027490120/artifacts/12534225713) and [web build](https://github.com/justshivv/Opentune/actions/runs/40027490120/artifacts/12534075246). The app identifies itself as `1.0.1-verification-preview`, following current main's version. GitHub artifact downloads require a GitHub login and expire; these links are test distribution, not a permanent release channel.

The [live staged deployment check](https://github.com/justshivv/Opentune/actions/runs/40028054552) confirmed challenge issuance, invalid enrollment → HTTP 403, reused challenge → HTTP 401, and unauthenticated browser approval lookup → HTTP 401. Existing Monochrome resolution still returned measured FLAC 16-bit/44.1 kHz. Genuine physical-device enrollment and browser approval remain pending; strict enforcement is not enabled.

## Android and TV

1. The server creates a random, two-minute, one-use challenge.
2. AndroidKeyStore generates a non-exportable P-256 signing key with that attestation challenge. Android and TV use the same `com.opentune` application identity.
3. The server verifies the X.509 chain against Google's official hardware-attestation roots, certificate validity and revocations. The first attestation extension encountered from the root must describe the enrollment key itself, preventing software key delegation.
4. The attestation must report TEE/StrongBox key security, a hardware-generated signing key, locked verified boot, exactly `com.opentune`, and the configured official signing-certificate SHA-256 digest. A proof signed by that key binds enrollment to the fresh challenge.
5. The server issues a seven-day opaque session bound to the attested public key. Tokens alone cannot authorize requests. Each request signs the token, timestamp, random nonce, HTTP method, path/query and body hash. Durable Objects serialize nonce consumption so concurrent replay fails. Proofs expire after 60 seconds; replay records last 120 seconds.

The official signer digest is derived from the existing release keystore in GitHub Actions. Only the public certificate is exported. No debug certificate, embedded APK password, usage installation UUID, caller-supplied certificate root, or User-Agent is accepted as app identity. Private signing material is removed after the build/export step. Native session metadata lives in `noBackupFilesDir`; the private key stays in AndroidKeyStore.

Google trust roots and revocation responses are fetched only from fixed official HTTPS endpoints and cached for one hour. If current trust material cannot be obtained, new enrollment fails closed. Already-issued native sessions can last up to seven days; this is not instantaneous device revocation. Hardware verification also cannot promise that every runtime exploit or automation by an authorized user is prevented.

Devices without suitable hardware attestation, unlocked/custom-verified boot, unofficial APK signatures and debug builds are refused. This intentionally excludes some TVs and modified devices. It does not depend on Google Play installation provenance; official sideloaded builds still have to pass hardware attestation.

## Web player

Browsers cannot prove that an Android APK is installed. A browser instead creates a non-exportable WebCrypto P-256 key and starts a five-minute pairing request from an allowed web origin. It keeps its private key and polling secret locally.

Settings → Audio sources → **Verify browser** displays an approval link and comparison code. Open that link on a verified native device. The native app fetches the actual website and code from the server, shows both, and requires an explicit **Allow** tap. Opening the deep link alone never approves anything. Only approve requests you started, after checking the code. On another device, copy the link and open it in the verified app.

The approved browser session lasts at most twelve hours and cannot outlive the native session that approved it. It is bound to the browser key, stored in IndexedDB, and signs every protected request. A browser session cannot approve other browsers. Origin is a pairing allowlist, **not proof of app identity**. Disconnect removes this browser's local key/session; sessions expire server-side. Clearing browser storage requires pairing again.

There are no secrets in the deep link. The link's identifier does not grant access: approval requires a verified native proof and polling requires a separate random secret. Authentication headers are only attached to the exact first-party origin and never forwarded to third-party add-ons or extension fetch fallbacks.

## Boundaries and operations

- Protects first-party catalogue and stream-resolution endpoints. Manifests and health remain public so the source can be installed.
- Does not alter third-party add-ons. Users may still add their own sources.
- Does **not** make Monochrome's public upstream or returned media URLs private. Playback currently goes directly to that upstream. Controlling access to every audio byte would require an authorized media proxy and separate bandwidth/cost planning.
- Approximately 240 edge requests per IP per minute per Cloudflare location, plus a bounded per-session replay window. Shared networks share the edge limit. Rate limiting is not authentication.
- No raw IP, bearer token, private key, certificate chain, grant or signed media URL is deliberately logged. Trust certificates and signing-certificate digests are public verification material.
- SQLite-backed Durable Objects hold expiring challenges, sessions and browser-pairing state; alarms remove expired state. This adds storage/operation usage to Cloudflare hosting, subject to its quotas.

Protocol version: `OpenTune-Addon-v1`. Enrollment proof: `OpenTune-Enrollment-v1\n<challenge>`. Request proof is the newline-separated protocol version, session, Unix milliseconds, base64url nonce, uppercase method, path plus original query, and SHA-256 hex of the exact UTF-8 body (empty for GET). Native signatures use ECDSA DER; browser signatures use 64-byte P1363. The stored session type determines the accepted encoding.

## Evidence and required device checks

Automated tests cover certificate parsing, app/signature binding, hardware and boot-policy rejection, chain signatures, root trust, certificate revocation and expiry, possession proofs, nonce replay, request tampering, browser approval, privilege separation, and rate-limit failure behavior. Test roots are injected into the test verifier only; the deployed service obtains roots from Google.

CI builds cannot prove enrollment on a physical supported Android/TV device, or prove audible playback on it. Before strict cutover, check a signed release build on a locked certified device, an unsupported/debug device's rejection, browser approval with a matching code, expiry/re-enrollment, and that signed searches work while unsigned searches return 401. A green build alone is not that evidence.

References: [Android key attestation verification](https://developer.android.com/privacy-and-security/security-key-attestation), [Android attestation schema](https://source.android.com/docs/security/features/keystore/attestation), [Workers crypto support](https://developers.cloudflare.com/workers/runtime-apis/nodejs/crypto/).

## Public add-on isolation regression checks

Verification is scoped to the exact HTTPS origin `https://opentune-lossless-addon.create-a-new-site-with-sites.workers.dev` on port 443. It applies to all OpenTune-hosted adapters, including Musopen, not external Monochrome/HiFi add-ons or user/admin-added public sources. Similar names, other Workers and URLs containing that origin as a query parameter do not activate it. Manifests and health stay public.

`FirstPartyAddonAuthTest` checks this without an Android Context, keystore or network. The HTTP client is lazy so public-origin checks do not initialize verification networking. The web regression test deliberately makes IndexedDB and fetch unavailable and confirms public-source requests still receive no verification headers. The browser pairing panel is hidden for lists containing only public sources. These tests passed in [the updated client workflow](https://github.com/justshivv/Opentune/actions/runs/40029960063).

The [expanded Worker deployment](https://github.com/justshivv/Opentune/actions/runs/40030304919) preserved staged policy, rejected invalid/replayed enrollment and unauthenticated approval, and resolved both Monochrome and Musopen samples. Strict cutover still requires the genuine-device checks above.
