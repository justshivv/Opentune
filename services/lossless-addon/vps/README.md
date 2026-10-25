# OpenTune lossless add-on on a VPS

The same add-on as the Cloudflare Worker, run by Node.js on an Ubuntu
server with Caddy in front for HTTPS. Sign-ins are kept in a SQLite file
in `/var/lib/opentune-addon`, and there is no daily request limit.

## Before installing

Point the domain at the server: an `A` record for
`addon-opentune.rethinknow.in` with the VPS's IP address. If the domain is
on Cloudflare, set that record to **DNS only** (grey cloud), so Caddy can get
its certificate and the add-on sees each phone's own address.

In Hostinger's panel, make sure the VPS firewall lets in ports 80 and 443.

## Install

Copy the archive to the server and run the installer as root:

```sh
scp opentune-addon-vps.tar.gz root@YOUR_VPS_IP:/root/
ssh root@YOUR_VPS_IP
tar -xzf opentune-addon-vps.tar.gz
cd opentune-addon
sudo bash install.sh
```

It installs Node.js 24 and Caddy if they're missing, puts the add-on in
`/opt/opentune-addon`, starts it as the `opentune-addon` service and asks
Caddy for the HTTPS certificate. At the end it prints the manifest URLs.

To update later, unpack a newer archive and run `sudo bash install.sh`
again. Settings and sign-ins stay.

## Settings

`/etc/opentune-addon.env`. After a change:

```sh
sudo systemctl restart opentune-addon
```

- `PAUSED=true` turns every search and stream away and tells apps to stay
  away for six hours.
- `APP_CERT_SHA256` is the official APK's signing certificate. Change it
  only if the app is ever signed with a different key.

## Day to day

```sh
journalctl -u opentune-addon -f          # the add-on's log
systemctl status opentune-addon          # running or not
tail -f /var/log/caddy/opentune-addon.log  # every request, from Caddy
```

## Apps

Only app versions that trust `https://addon-opentune.rethinknow.in` can
sign in to it; older ones were built for the workers.dev address. In the
owner dashboard, change the four add-on URLs to:

- `https://addon-opentune.rethinknow.in/monochrome/manifest.json`
- `https://addon-opentune.rethinknow.in/archive/manifest.json`
- `https://addon-opentune.rethinknow.in/musopen/manifest.json`
- `https://addon-opentune.rethinknow.in/netease/manifest.json`

## Owner dashboard and remote configuration

This package also includes `/control/`. Set `CONTROL_ADMIN_USERNAME` and `CONTROL_ADMIN_PASSWORD` (16+ characters) in `/etc/opentune-addon.env`, restart the service, and open `https://addon-opentune.rethinknow.in/control/`. Existing installations must add these two settings manually; the installer preserves the old environment file.

Back up `control.sqlite` alongside `auth.sqlite`; the configuration signing key must survive server migrations. See [the complete configuration guide](../../../docs/remote-configuration.md) for supported controls, publication, rollback and client compatibility.
