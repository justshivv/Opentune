# Private usage analytics with username and password

Open https://justshivv.github.io/Opentune/usage/ to sign in. Login, analytics and sign-out all stay at this URL.
Google sign-in and Cloudflare Access are no longer used. Both the dashboard and read APIs
require an owner session; anonymous app reporting continues to work.

## Set your username and password

1. Open [GitHub repository secrets](https://github.com/justshivv/Opentune/settings/secrets/actions).
2. Add two **Repository secrets**:
   - `USAGE_ADMIN_USERNAME`: your chosen username (for example `owner`; 3–128 letters, numbers or ._@+- characters; email addresses work too).
   - `USAGE_ADMIN_PASSWORD`: a unique password or passphrase, 16–256 characters long.
3. Open **Actions → Usage API → Run workflow**, select **main**, and run it.
4. Open the dashboard and enter those credentials.

The workflow stores credentials in Cloudflare Worker secrets and creates the signing key automatically.
Do not put passwords in source code or chat. Missing credentials keep data locked.
If you already created a Cloudflare Access application for this dashboard, remove that application
so it does not intercept the normal login page. The Worker now handles authentication itself.
The old `ACCESS_*` secrets are unused and may be deleted.

## Sessions and password changes

Sessions expire after eight hours. GitHub Pages keeps the signed session token in sessionStorage for the current tab, so refresh works without third-party cookies. Passwords are never stored. The interface is static/public; the Worker authenticates every request for private data. A strict content security policy limits scripts and connections. The legacy Worker URL still uses Secure, HttpOnly, SameSite=Strict cookies. Login attempts are limited to five per minute per IP.
Use **Sign out** to clear the session from this browser. A copied session token remains valid until
expiry or credential rotation, so rotate credentials if a session is compromised.
To change or reset credentials, update the two repository secrets and rerun **Usage API**.
Each run that configures credentials rotates the session key, requiring browsers to sign in again.
Existing data is preserved. The workflow applies additive CREATE TABLE IF NOT EXISTS statements; it never recreates or clears the database.

The workflow checks login and logout when credentials are supplied, and always checks that anonymous
requests cannot read analytics. Build with `python scripts/prepare-usage-assets.py` and run
`node --test usage-api/worker.test.mjs usage-api/dashboard/model.test.mjs`.

## Reporting and aggregation reference

# Free usage backend

This Worker + D1 database replaces Abacus increment counters. The website remains on GitHub Pages.
Deployed on Cloudflare's Free plan at `https://opentune-usage.create-a-new-site-with-sites.workers.dev`.
The public API URL and D1 binding are configured in this repository; credentials are kept outside it.

## Deploy the existing backend

Use **Actions → Usage API → Run workflow** on main. It builds private assets and deploys the existing
Worker and D1 binding, applying the additive schema first, using `CLOUDFLARE_API_TOKEN` and `CLOUDFLARE_ACCOUNT_ID`. Do not recreate the
production database. For local tests, use a separate SQLite database rather than fabricated production reports.
Android release builds honor Settings → About → Count this phone in usage numbers; debug builds do not upload.
The public ingestion URL stays in `site/usage-config.json`; no authentication credential is shipped in the APK.

## What the metrics actually mean

* **Unique installations reported:** distinct random UUIDs, generated once in private app storage.
  App upgrades preserve them. Reinstalling/clearing app data creates a new ID. Backups and transfers exclude IDs.
* **Daily/weekly/monthly active:** distinct installation IDs in the selected reporting calendar (IST by default; legacy UTC history is separate),
  across versions and days. Week starts Monday. Not a sum of daily counts.
* **Current versions:** each installation appears once under its latest reported version. New clients send a persisted version revision, so updates/downgrades move one count and delayed retries cannot undo the move. Older clients are inferred from their latest activity day, using numeric version order for same-day upgrades. These estimates cannot identify same-day legacy downgrades. Offline or uninstalled apps remain at their last report. Historical version activity is retained separately.
* **Plays:** cumulative totals per installation/day/version, persisted before upload.
  Database upsert takes `MAX`, so repeat or reordered requests cannot add plays twice.
  Up to 30 days of pending reports retry on subsequent app use. Lost app storage loses undelivered reports.
* **Unique download browsers per release:** separate browser UUID, unique `(browser, release)` constraint.
  Counts requests through website APK links, not completed downloads. Direct GitHub downloads and
  blocked/disabled browser storage or tracking are not captured. Private windows/cleared storage yield new IDs.
* **GitHub APK download events:** original GitHub counters, including repeated downloads and updates.
  These are kept separate. Historical events cannot be converted to unique people.

There is no account-based identity, hardware identifier, listening history, song title or stored IP in these tables.
Hosting infrastructure still handles IPs for network requests. The private owner dashboard returns aggregate data only.
The write API is an anonymous reporting endpoint: input validation and deduplication prevent accidental inflation,
but a modified client can invent IDs or reports. These are measured reports, not fraud-proof human counts.
Cloudflare WAF/rate limiting can mitigate abuse if needed; never claim precise unique people without sign-in.
Do not combine legacy Abacus data with these new unique metrics.

## Free-tier limits and operations

Cloudflare currently documents 100,000 Worker requests/day, 5 million D1 rows read/day,
100,000 rows written/day and 5 GB total D1 storage on Free. Index updates count toward writes.
Stay on Free; do not enable a paid plan automatically. A limit/error is shown as unavailable, never as zero;
Android retains pending aggregates and retries during later use. This is not unlimited hosting.
Database data is retained until the owner deletes it; monitor storage as the app grows.

Sources: https://developers.cloudflare.com/workers/platform/pricing/
and https://developers.cloudflare.com/d1/platform/pricing/

## Validation

```sh
node --test worker.test.mjs
```

Tests run the actual schema and queries against SQLite, covering retries, out-of-order reports,
updates, unique periods, repeated downloads, invalid input and request-size/origin checks.


## Date-range dashboard

`GET /v1/analytics?from=2026-10-01&to=2026-10-09&timeZone=Asia%2FKolkata` returns inclusive IST
activity totals, daily counts, version counts and the first recorded activity date.
Ranges may cover up to 366 days and cannot end in the future. Unique installations
are deduplicated across the entire period; they are not the sum of daily counts.
Read endpoints require owner authentication. Omitting `timeZone` preserves UTC API compatibility. The dashboard defaults to IST; the UTC history selector preserves older reports. Existing daily totals have no event times, so they are never relabelled as IST. New app daily rows send `timeZone: "Asia/Kolkata"` and are stored separately; absent/UTC rows keep their original table. All-time totals combine both and deduplicate installations. The public counts and the README badge (`/v1/public`, `/v1/badge`) also combine both tables, on the IST calendar by default, so installations still on older UTC-reporting versions are counted along with current ones, each once.

New activity payloads also include `currentVersion` and `versionRevision` (a persisted increasing integer). Current version updates and daily aggregates commit atomically. `/v1/summary` adds `currentVersions`, with `version`, `installations` and `inferred` counts; identifiers are never returned. A version revision changes only when the installed version changes, so retries and an offline backlog cannot move a device back to an older version.

Build assets first with `python scripts/prepare-usage-assets.py` from the repository root, then deploy
with `npx wrangler@4 d1 execute opentune-usage --remote --file=schema.sql` followed by `npx wrangler@4 deploy` from this directory; do not
recreate the production D1 database. The Usage API workflow can do this with repository
secrets `CLOUDFLARE_API_TOKEN` and `CLOUDFLARE_ACCOUNT_ID`. The token needs permission
to deploy this Worker and access its existing D1 binding. It must not be committed.
Until the Worker update is deployed, the UTC history view can use the existing last-30-day
summary; IST stays unavailable until the updated API is deployed. Older ranges and period-wide unique counts are explicitly unavailable in
that mode. Download totals remain all-time because GitHub provides no daily history.

## Community audio sources

The owner dashboard can publish a catalog under `GET /v1/addons`. Only the authenticated owner can POST updates to that endpoint. Run the updated `schema.sql` migration when deploying; it creates `addon_catalog` without changing usage data. Load the current revision, add/edit the draft, and publish it. Concurrent edits use a revision check.

Supported Android previews refresh at launch and when Sources opens. Existing enabled choices and order are preserved, withdrawn community entries disappear, and personal URLs remain untouched. Community sources display “Added by admin” and an alias, with no URL in the app UI. **Endpoints are public network data, not hidden credentials.** Real URL secrecy would require a separately operated proxy; no audio relay is introduced here. Worker deployment is separate from the APK/site build.
