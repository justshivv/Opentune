# Usage counts

What the app reports, and what the numbers on the README mean. Back to the [README](../README.md).

The new usage system uses a random installation ID, version, activity dates (India time) and
daily play totals. IDs survive app updates and are excluded from backups and device
transfers. Reinstalling or clearing app data creates a new ID. The server deduplicates
installations and merges cumulative play totals, so upload retries do not inflate counts.
No hardware identifiers, accounts, song names or listening history are sent. Turn it off
under Settings > About > Count this phone in usage numbers. Debug builds never count.

The badges on the README and `GET /v1/public` expose only aggregate counts (total installations
and active installations today, yesterday, this week and this month) with no sign-in; `GET /v1/badge`
returns the same as a [Shields endpoint](https://shields.io/badges/endpoint-badge), so
the README counts refresh themselves once a day, after 06:00 India time. The full breakdown — versions, plays, downloads and
per-day active numbers — stays behind the owner login on the usage dashboard. These are
measured anonymous reports, not fraud-proof head counts, and there is no "active right
now": the finest granularity is a calendar day.

The website separately reports unique browsers requesting APKs and GitHub's raw APK
download events. Neither means unique people or completed installs. Historical Abacus
counters cannot be converted into unique installations. The free-tier
[Worker and D1 backend](../usage-api/README.md) needs deployment; until its URL is configured,
the dashboard shows unique counts as unavailable. Up to 30 days of pending reports retry
during subsequent app use.
