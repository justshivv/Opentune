# Protected first-party add-on access

## Rollout status

The owner chose Android, TV and web support with verification required and unsupported devices blocked. The implementation is on `feat/first-party-lossless-addon`, alongside the first-party source in PR #29. It is not merged into main.

The production configuration now requires **`VERIFICATION_MODE = "strict"`**. Search and stream-resolution requests require a session bound to a verified installation and a fresh signed request. Changing User-Agent, copying a bearer token, or sending the official website's Origin does not grant access. Manifests remain public so sources can be installed; successful import alone does not authorize playback.

The owner confirmed that the signed Android verification preview switches to FLAC after the trust-loading fix. Strict cutover follows the owner's requirement to block unverified clients, including older OpenTune builds. Public third-party add-ons remain unaffected. TV hardware enrollment and browser approval still need physical-device confirmation; the compatible web build is an artifact and has not replaced the production web player. A client without verification support cannot use these first-party sources.

Builds are artifacts of the **Verified add-on clients** workflow. `OpenTune-verified-source-signed` contains the Android/TV APK; `OpenTune-verified-source-web` contains the web build. The web build is not automatically published over the existing site. Preview builds use the existing release signing identity and install over that identity's app. No GitHub release is created.

The [23 October client build](https://github.com/justshivv/Opentune/actions/runs/40032575069) passed for both platforms: [signed Android/TV APK](https://github.com/justshivv/Opentune/actions/runs/40032575069/artifacts/12534759667) and [web build](https://github.com/justshivv/Opentune/actions/runs/40032575069/artifacts/12535167840). The updated artifact identifies itself as `1.0.1-verification-preview.2`; the original `1.0.1-verification-preview` also supports verification. GitHub artifact downloads require a GitHub login and expire; these links are test distribution, not a permanent release channel.

The [live staged deployment check](https://github.com/justshivv/Opentune/actions/runs/40028054552) confirmed challenge issuance, invalid enrollment → HTTP 403, reused challenge → HTTP 401, and unauthenticated browser approval lookup → HTTP 401. Existing Monochrome resolution still returned measured FLAC 16-bit/44.1 kHz. This was historical staging evidence, not strict enforcement; Android playback was subsequently confirmed as described below.

The [strict production check](https://github.com/justshivv/Opentune/actions/runs/40034330368) passed on 23 October at 22:34 UTC. All three public manifests returned 200. All twelve search/stream probes with BitChord or spoofed OpenTune User-Agent, an official Origin and a fake bearer returned 401. Invalid enrollment returned 403, challenge replay and unauthorized browser approval returned 401. The initial immediate post-deploy check still saw staging and failed; the rerun confirmed strict policy. Saved evidence: `services/lossless-addon/reports/strict-deployment-2026-10-23.json`. The owner subsequently reported 401 in the official preview too; the signed-request runtime failure and correction are documented below.

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

### Signed requests rejected after strict cutover

The owner reported HTTP 401 for Monochrome and Musopen in the official preview after strict cutover. Enrollment succeeded, but Workers rejected a `PublicKeyObject` nested in `crypto.verify` options. Ordinary Node accepts that form, so the Node signature tests passed; staging had also concealed the failed request check by allowing unsigned catalogue access. The original runtime test exercised attestation only, and the live checks only demonstrated rejection of unauthorized requests.

The verifier now validates the stored P-256 public key and passes its PEM string with the explicit DER (Android/TV) or P1363 (web) signature encoding. It does not remove proof validation, change trusted roots, clear sessions or enable staging. The existing signed preview and enrolled keys remain compatible.

The isolated Workers test now uses actual SQLite Durable Objects to create test-only native/browser sessions, reject changed requests and invalid signatures, accept valid signed requests, and reject replay. The test failed with the old code ([reproduction](https://github.com/justshivv/Opentune/actions/runs/40035044715)) and passed with the PEM correction ([fixed runtime check](https://github.com/justshivv/Opentune/actions/runs/40035319775)). The test harness and session-creation route are never bundled into production. The deployment workflow now requires this runtime check before publishing. A fresh physical-device playback check is still needed after deployment.

### Verification-preview incident fixed on 23 October

The first signed preview could show `OpenTune Monochrome: unavailable or invalid response` before searching. Google trust-data requests used `redirect: 'error'`, which the Workers fetch runtime rejects. The previous catch mapped that infrastructure failure to a device rejection, while Android's resolver hid the underlying verification exception. Node-only cryptography tests and a smoke test expecting any 403 did not detect this.

Trust requests now use `redirect: 'manual'` and reject non-success statuses, including redirects. No redirected trust host is followed and no verification policy is relaxed. Trust-service failures return 503 with a fixed diagnostic code, separately from 403 device-policy rejection. The preview.2 client preserves that code and reports the source stage for DNS, timeout, HTTP and JSON failures without exposing private source URLs or credentials. It retains current main's concurrent resolution, late upgrades, fresh links and per-song rate-limit handling.

The isolated Workers-runtime test reproduces trust fetching through a mocked fixed Google origin, verifies a generated test certificate fixture and rejects the wrong signer/proof. This failed before the redirect fix and passed afterwards. Test roots are never deployed or accepted by the production service. Thirty Node server tests and this runtime test passed in [run 40032775374](https://github.com/justshivv/Opentune/actions/runs/40032775374). The local workerd package currently supports compatibility dates through 2026-08-06; production remains configured separately.

[Production verification checks](https://github.com/justshivv/Opentune/actions/runs/40032775446) passed after the fix, including trust loading, challenge/replay rejection and browser approval gating. The deployed catalogue/media probe returned 295 at 24-bit/44.1 kHz and So High at 16-bit/44.1 kHz; Same Beef had no exact match in that search. Wo Noor timed out during the separate deployment probe, so upstream availability is not guaranteed. Saved reports are `verification-fix-deployment-2026-10-23.json` and `sidhu-client-path-2026-10-23.json` in `services/lossless-addon/reports`.

The existing verification-preview client can retry after the server fix without reinstalling; its one-minute failed-enrollment hold can be cleared by restarting the app. The owner retried the installed 1.0.1-verification-preview and confirmed that 295 now switches to FLAC. This confirms the repaired path on that Android device. TV verification and browser approval still need physical-device checks. The production configuration now requires strict verification, and deployment checks exercise unsigned search and stream requests for all three first-party sources using both BitChord and OpenTune User-Agent values.

Automated tests cover certificate parsing, app/signature binding, hardware and boot-policy rejection, chain signatures, root trust, certificate revocation and expiry, possession proofs, nonce replay, request tampering, browser approval, privilege separation, and rate-limit failure behavior. Test roots are injected into the test verifier only; the deployed service obtains roots from Google.

CI builds cannot prove enrollment on a physical supported Android/TV device, or prove audible playback on it. Remaining device checks include TV hardware enrollment, unsupported/debug device rejection, browser approval with a matching code, expiry/re-enrollment, and signed searches after strict cutover. A green build alone is not that evidence.

References: [Android key attestation verification](https://developer.android.com/privacy-and-security/security-key-attestation), [Android attestation schema](https://source.android.com/docs/security/features/keystore/attestation), [Workers crypto support](https://developers.cloudflare.com/workers/runtime-apis/nodejs/crypto/).

## Public add-on isolation regression checks

Verification is scoped to the exact HTTPS origin `https://opentune-lossless-addon.create-a-new-site-with-sites.workers.dev` on port 443. It applies to all OpenTune-hosted adapters, including Musopen, not external Monochrome/HiFi add-ons or user/admin-added public sources. Similar names, other Workers and URLs containing that origin as a query parameter do not activate it. Manifests and health stay public.

`FirstPartyAddonAuthTest` checks this without an Android Context, keystore or network. The HTTP client is lazy so public-origin checks do not initialize verification networking. The web regression test deliberately makes IndexedDB and fetch unavailable and confirms public-source requests still receive no verification headers. The browser pairing panel is hidden for lists containing only public sources. These tests passed in [the updated client workflow](https://github.com/justshivv/Opentune/actions/runs/40029960063).

The [expanded Worker deployment](https://github.com/justshivv/Opentune/actions/runs/40030304919) preserved staged policy, rejected invalid/replayed enrollment and unauthenticated approval, and resolved both Monochrome and Musopen samples. That deployment was staged; see the current rollout status above.
