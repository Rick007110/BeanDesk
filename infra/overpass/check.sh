#!/usr/bin/env bash
# Asks our Overpass server for the cafés around the Dam, the way the search function does.
set -euo pipefail
DIR=/opt/beandesk-overpass
# shellcheck disable=SC1091
. "$DIR/.env"
curl -fsS --max-time 60 "$OVERPASS_URL" \
  -H "X-BeanDesk-Key: $OVERPASS_KEY" \
  --data-urlencode 'data=[out:json][timeout:25];node["amenity"="cafe"](52.370,4.888,52.376,4.898);out center;' \
  | python3 -c 'import json, sys; elements = json.load(sys.stdin)["elements"]; print(f"OK: {len(elements)} cafés around the Dam")'
# Without the key: refused.
status=$(curl -s -o /dev/null -w '%{http_code}' --max-time 10 "$OVERPASS_URL" --data-urlencode 'data=[out:json];node(1);out;')
[ "$status" = "403" ] && echo "OK: requests without the key are refused" || { echo "Requests without the key get $status, not 403" >&2; exit 1; }
