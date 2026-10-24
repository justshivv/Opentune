#!/usr/bin/env bash
# Installs the OpenTune lossless add-on on an Ubuntu server, behind Caddy
# for HTTPS, as a systemd service. Run it from the unpacked folder:
#   sudo bash install.sh
# Running it again updates the code and keeps the settings and sign-ins.
set -euo pipefail

DOMAIN="${DOMAIN:-addon-opentune.rethinknow.in}"
APP_DIR=/opt/opentune-addon
DATA_DIR=/var/lib/opentune-addon
ENV_FILE=/etc/opentune-addon.env
SERVICE=opentune-addon
PORT=8787
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

if [ "$(id -u)" -ne 0 ]; then echo "Run it as root: sudo bash install.sh"; exit 1; fi
for f in server.mjs worker.mjs auth.mjs security.mjs attestation.mjs; do
  [ -f "$HERE/$f" ] || { echo "Missing $f next to install.sh"; exit 1; }
done

echo "==> Packages"
export DEBIAN_FRONTEND=noninteractive
apt-get update -y
apt-get install -y curl ca-certificates gnupg debian-keyring debian-archive-keyring apt-transport-https

# Node 22.13 or newer has the built-in SQLite the server keeps sign-ins in.
need_node=1
if command -v node >/dev/null 2>&1; then
  major=$(node -p 'process.versions.node.split(".")[0]'); minor=$(node -p 'process.versions.node.split(".")[1]')
  if [ "$major" -gt 22 ] || { [ "$major" -eq 22 ] && [ "$minor" -ge 13 ]; }; then need_node=0; fi
fi
if [ "$need_node" -eq 1 ]; then
  echo "==> Node.js 24"
  curl -fsSL https://deb.nodesource.com/setup_24.x | bash -
  apt-get install -y nodejs
fi
echo "Node $(node -v)"

if ! command -v caddy >/dev/null 2>&1; then
  echo "==> Caddy"
  curl -1sLf 'https://dl.cloudsmith.io/public/caddy/stable/gpg.key' | gpg --dearmor --yes -o /usr/share/keyrings/caddy-stable-archive-keyring.gpg
  curl -1sLf 'https://dl.cloudsmith.io/public/caddy/stable/debian.deb.txt' > /etc/apt/sources.list.d/caddy-stable.list
  apt-get update -y
  apt-get install -y caddy
fi

echo "==> App"
id -u opentune >/dev/null 2>&1 || useradd --system --home-dir "$APP_DIR" --shell /usr/sbin/nologin opentune
mkdir -p "$APP_DIR" "$DATA_DIR"
install -m 0644 "$HERE"/server.mjs "$HERE"/worker.mjs "$HERE"/auth.mjs "$HERE"/security.mjs "$HERE"/attestation.mjs "$APP_DIR"/
printf '{ "type": "module", "private": true }\n' > "$APP_DIR/package.json"
chown -R opentune:opentune "$DATA_DIR"

if [ ! -f "$ENV_FILE" ]; then
  install -m 0640 -g opentune "$HERE/opentune-addon.env" "$ENV_FILE"
  sed -i "s/^PUBLIC_HOST=.*/PUBLIC_HOST=$DOMAIN/" "$ENV_FILE"
  echo "Settings written to $ENV_FILE"
else
  echo "Keeping the settings in $ENV_FILE"
fi

cat > /etc/systemd/system/$SERVICE.service <<UNIT
[Unit]
Description=OpenTune lossless add-on
After=network-online.target
Wants=network-online.target

[Service]
User=opentune
Group=opentune
WorkingDirectory=$APP_DIR
EnvironmentFile=$ENV_FILE
Environment=HOST=127.0.0.1
Environment=PORT=$PORT
Environment=DATA_DIR=$DATA_DIR
ExecStart=/usr/bin/env node --disable-warning=ExperimentalWarning $APP_DIR/server.mjs
Restart=always
RestartSec=3
NoNewPrivileges=true
ProtectSystem=strict
ProtectHome=true
PrivateTmp=true
ReadWritePaths=$DATA_DIR
MemoryMax=512M

[Install]
WantedBy=multi-user.target
UNIT
systemctl daemon-reload
systemctl enable --now $SERVICE
systemctl restart $SERVICE

echo "==> HTTPS for $DOMAIN"
mkdir -p /etc/caddy/sites
cat > /etc/caddy/sites/opentune-addon.caddy <<SITE
$DOMAIN {
	encode gzip
	reverse_proxy 127.0.0.1:$PORT
	log {
		output file /var/log/caddy/opentune-addon.log {
			roll_size 10MiB
			roll_keep 3
		}
	}
}
SITE
mkdir -p /var/log/caddy && chown caddy:caddy /var/log/caddy 2>/dev/null || true
CADDYFILE=/etc/caddy/Caddyfile
# A fresh Caddy ships a demo site on :80; it's replaced. Any other sites you have stay.
if [ ! -f "$CADDYFILE" ] || grep -q '^:80' "$CADDYFILE"; then
  printf 'import /etc/caddy/sites/*.caddy\n' > "$CADDYFILE"
elif ! grep -q 'import /etc/caddy/sites/' "$CADDYFILE"; then
  printf '\nimport /etc/caddy/sites/*.caddy\n' >> "$CADDYFILE"
fi
caddy validate --config "$CADDYFILE" --adapter caddyfile
systemctl enable caddy >/dev/null 2>&1 || true
systemctl reload caddy 2>/dev/null || systemctl restart caddy

if command -v ufw >/dev/null 2>&1 && ufw status | grep -q 'Status: active'; then
  ufw allow 80/tcp >/dev/null; ufw allow 443/tcp >/dev/null
  echo "Opened ports 80 and 443 in ufw"
fi

echo "==> Checking"
sleep 2
if curl -fsS "http://127.0.0.1:$PORT/monochrome/manifest.json" >/dev/null; then
  echo "The add-on answers on this server."
else
  echo "The add-on didn't answer. See: journalctl -u $SERVICE -n 50"; exit 1
fi
if curl -fsS --max-time 20 "https://$DOMAIN/monochrome/manifest.json" >/dev/null 2>&1; then
  echo "And on https://$DOMAIN"
else
  echo "Not reachable on https://$DOMAIN yet. Check that the domain's A record points to this server's IP"
  echo "and that ports 80 and 443 are open in Hostinger's firewall; Caddy keeps trying for the certificate."
fi
echo
echo "Manifest URLs:"
for s in monochrome archive musopen netease; do echo "  https://$DOMAIN/$s/manifest.json"; done
echo
echo "Logs:    journalctl -u $SERVICE -f"
echo "Pause:   set PAUSED=true in $ENV_FILE, then: systemctl restart $SERVICE"
