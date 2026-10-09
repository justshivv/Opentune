# Free usage backend

This Worker + D1 database replaces Abacus increment counters. The website remains on GitHub Pages.
Deployed on Cloudflare's Free plan at `https://opentune-usage.create-a-new-site-with-sites.workers.dev`.
The public API URL and D1 binding are configured in this repository; credentials are kept outside it.

## Deploy

Use a free Cloudflare account, Node 22+ and Wrangler 4. Run from `usage-api`:

```sh
npx wrangler@4 login
npx wrangler@4 d1 create opentune-usage
# Copy the returned database_id into wrangler.jsonc. Keep the DB binding name.
npx wrangler@4 d1 execute opentune-usage --remote --file=schema.sql
npx wrangler@4 deploy
```

Put the returned HTTPS Workers URL in `site/usage-config.json` as `apiBase`, without a trailing path.
Publish the GitHub Pages site. Both Android and the website read this configuration; no API key is shipped in the APK.
If the website origin changes, update `ALLOWED_ORIGINS` (comma-separated exact origins) and redeploy.
Never put Cloudflare account tokens into the website or app.

Before enabling collection, check `GET /v1/summary` returns zero aggregates and that the
dashboard reads them. Test with a separate local database, not fabricated production installations.
Android debug builds do not upload. Release builds honor Settings > About > Count this phone in usage numbers.

## What the metrics actually mean

* **Unique installations reported:** distinct random UUIDs, generated once in private app storage.
  App upgrades preserve them. Reinstalling/clearing app data creates a new ID. Backups and transfers exclude IDs.
* **Daily/weekly/monthly active:** distinct installation IDs in the corresponding UTC period,
  across versions and days. Week starts Monday. Not a sum of daily counts.
* **Plays:** cumulative totals per installation/day/version, persisted before upload.
  Database upsert takes `MAX`, so repeat or reordered requests cannot add plays twice.
  Up to 30 days of pending reports retry on subsequent app use. Lost app storage loses undelivered reports.
* **Unique download browsers per release:** separate browser UUID, unique `(browser, release)` constraint.
  Counts requests through website APK links, not completed downloads. Direct GitHub downloads and
  blocked/disabled browser storage or tracking are not captured. Private windows/cleared storage yield new IDs.
* **GitHub APK download events:** original GitHub counters, including repeated downloads and updates.
  These are kept separate. Historical events cannot be converted to unique people.

There is no account-based identity, hardware identifier, listening history, song title or stored IP in these tables.
Hosting infrastructure still handles IPs for network requests. The public dashboard returns aggregate data only.
The API is an anonymous reporting endpoint: input validation and deduplication prevent accidental inflation,
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
