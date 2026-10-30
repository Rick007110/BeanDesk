#!/usr/bin/env bash
# Sets up BeanDesk's own Overpass server on this Linux VPS (Debian or Ubuntu), next to whatever
# else runs here: two Docker containers and nothing outside /opt/beandesk-overpass.
#
#   sudo bash infra/overpass/install.sh            (from a clone of the repository)
#   curl -fsSL https://raw.githubusercontent.com/Rick007110/BeanDesk/main/infra/overpass/install.sh | sudo bash
#
# Running it again keeps the key and the database; it only updates the configuration.
set -euo pipefail

DIR=/opt/beandesk-overpass
RAW=https://raw.githubusercontent.com/Rick007110/BeanDesk/main/infra/overpass
HERE="$(cd "$(dirname "${BASH_SOURCE[0]:-$0}")" 2>/dev/null && pwd || true)"

say() { printf '\n== %s\n' "$*"; }
port_free() { ! ss -ltnH "( sport = :$1 )" 2>/dev/null | grep -q .; }

if [ "$(id -u)" -ne 0 ]; then
  echo "Run this as root (sudo)." >&2
  exit 1
fi

say "Checking the machine"
memory_gb=$(awk '/MemTotal/ { printf "%d", $2 / 1024 / 1024 }' /proc/meminfo)
mkdir -p "$DIR"
disk_gb=$(df -BG --output=avail "$DIR" | tail -1 | tr -dc '0-9')
echo "Memory: ${memory_gb} GB, free disk: ${disk_gb} GB"
if [ "$memory_gb" -lt 2 ] || [ "$disk_gb" -lt 30 ]; then
  echo "Overpass for the Netherlands needs at least 2 GB of memory (4 GB is better) and 30 GB of free disk." >&2
  exit 1
fi

if ! command -v docker >/dev/null 2>&1; then
  say "Installing Docker"
  curl -fsSL https://get.docker.com | sh
fi
docker compose version >/dev/null

say "Writing the configuration to $DIR"
# From the clone this script is in, or else from GitHub.
for file in docker-compose.yml Caddyfile check.sh; do
  if [ -n "$HERE" ] && [ -f "$HERE/install.sh" ] && [ -f "$HERE/$file" ]; then
    cp "$HERE/$file" "$DIR/$file"
  else
    curl -fsSL "$RAW/$file" -o "$DIR/$file"
  fi
done
chmod +x "$DIR/check.sh"

if [ -f "$DIR/.env" ]; then
  echo "Keeping the key and address from $DIR/.env"
else
  key=$(openssl rand -hex 24)
  ip=$(curl -fsS https://api.ipify.org)
  if port_free 80 && port_free 443; then
    # A host name that points at this IP, so Caddy can get an HTTPS certificate without a domain.
    site="${ip//./-}.sslip.io"
    url="https://$site/api/interpreter"
    ports="HTTP_PORT=80\nHTTPS_PORT=443\nPLAIN_PORT=127.0.0.1:8090"
  else
    echo "Ports 80 or 443 are in use here: Overpass gets plain HTTP on port 8090."
    site=":8090"
    url="http://$ip:8090/api/interpreter"
    ports="HTTP_PORT=127.0.0.1:8080\nHTTPS_PORT=127.0.0.1:8443\nPLAIN_PORT=8090"
  fi
  printf 'OVERPASS_KEY=%s\nOVERPASS_SITE=%s\nOVERPASS_URL=%s\n%b\n' "$key" "$site" "$url" "$ports" > "$DIR/.env"
  chmod 600 "$DIR/.env"
fi

if command -v ufw >/dev/null 2>&1 && ufw status | grep -q "Status: active"; then
  say "Opening the firewall"
  if grep -q '^OVERPASS_SITE=:8090' "$DIR/.env"; then ufw allow 8090/tcp; else ufw allow 80/tcp && ufw allow 443/tcp; fi
fi

say "Starting Overpass and Caddy"
cd "$DIR"
docker compose --env-file .env up -d

# shellcheck disable=SC1091
. "$DIR/.env"
cat <<DONE

== Done. Overpass is now downloading the Netherlands and building its database. That takes
   one to two hours; follow it with:   docker compose -f $DIR/docker-compose.yml logs -f overpass

   Then check it with:   bash $DIR/check.sh

   Put these two values in Supabase (Edge Functions → Secrets, or "supabase secrets set"):
     OVERPASS_URL=$OVERPASS_URL
     OVERPASS_KEY=$OVERPASS_KEY
DONE
