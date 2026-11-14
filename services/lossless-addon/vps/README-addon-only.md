# OpenTune lossless add-on, add-on only

The add-on and nothing else: no owner dashboard (`/control/`), no usage dashboard,
and no app-configuration endpoint (`/v1/app-config`). It is Node.js behind Caddy for
HTTPS, with device sign-ins kept in a SQLite file in `/var/lib/opentune-addon`.

## Before installing

Point your domain at the server with an `A` record (on Cloudflare, set it to
**DNS only**), and let ports 80 and 443 through the server's firewall.

## Install

```sh
tar -xzf opentune-addon-only.tar.gz      # or: unzip opentune-addon-only.zip
cd opentune-addon
sudo DOMAIN=addon.example.com bash install.sh
```

It installs Node.js and Caddy if they're missing, puts the add-on in
`/opt/opentune-addon`, runs it as the `opentune-addon` service and gets the HTTPS
certificate. At the end it checks the manifest and prints the address.

Run it again from a newer package to update; settings and sign-ins stay. Run over an
earlier full install, it removes that install's dashboards and turns usage off.

## Settings

`/etc/opentune-addon.env`; after a change, `sudo systemctl restart opentune-addon`.

- `PUBLIC_HOST`: the domain apps use (set by `DOMAIN=` at install).
- `PAUSED=true` turns every search and stream away and tells apps to stay away for six hours.
- `APP_CERT_SHA256`: the official APK's signing certificate. Change it only if the app is
  signed with a different key.
- `VERIFICATION_MODE`, `WEB_ORIGINS`, `REQUESTS_PER_MINUTE`, `ADDON_WORKERS`: see the file.

## Day to day

```sh
journalctl -u opentune-addon -f
systemctl status opentune-addon
```

## Manifests

- `https://YOUR_DOMAIN/monochrome/manifest.json`
- `https://YOUR_DOMAIN/archive/manifest.json`
- `https://YOUR_DOMAIN/musopen/manifest.json`
- `https://YOUR_DOMAIN/netease/manifest.json`

Only app versions built to trust that domain can sign in to it. Without `/v1/app-config`,
an app that asks for its remote configuration there gets "not found" and uses its built-in
defaults.
