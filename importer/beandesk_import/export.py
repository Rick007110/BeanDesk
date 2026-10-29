"""Builds the city export that the app downloads. The format is parsed by
core/src/main/kotlin/nl/beandesk/core/export/CityExport.kt; keep both in sync."""

import json
from datetime import datetime, timezone

import requests

from .normalize import osm_facts

EXPORT_VERSION = 1
ATTRIBUTION = "© OpenStreetMap-bijdragers"
BUCKET = "city-exports"


def _iso(value: datetime | None) -> str | None:
    return value.astimezone(timezone.utc).isoformat() if value else None


def build_export(city: str, tz: str, rows: list[dict], now: datetime | None = None) -> dict:
    places = []
    for row in rows:
        community = None
        if row["has_community"]:
            community = {
                "wifi": row["community_wifi"],
                "power": row["community_power"],
                "quiet": row["community_quiet"],
                "laptop_policy": row["community_laptop_policy"],
                "closed_reports": row["closed_reports"],
                "last_report_at": _iso(row["last_report_at"]),
            }
        places.append(
            {
                "id": row["id"],
                "osm_type": row["osm_type"],
                "osm_id": row["osm_id"],
                "category": row["category"],
                "name": row["name"],
                "address": row["address"],
                "lat": round(row["lat"], 7),
                "lng": round(row["lng"], 7),
                "opening_hours": row["opening_hours"],
                "osm": osm_facts(row["osm_tags"]),
                "community": community,
                "updated_at": _iso(row["updated_at"]),
            }
        )

    center = None
    if places:
        center = {
            "lat": round(sum(p["lat"] for p in places) / len(places), 6),
            "lng": round(sum(p["lng"] for p in places) / len(places), 6),
        }

    return {
        "version": EXPORT_VERSION,
        "city": city,
        "timezone": tz,
        "generated_at": _iso(now or datetime.now(timezone.utc)),
        "attribution": ATTRIBUTION,
        "center": center,
        "places": places,
    }


def to_json_bytes(export: dict) -> bytes:
    return json.dumps(export, ensure_ascii=False, separators=(",", ":")).encode("utf-8")


def upload(content: bytes, city: str, supabase_url: str, service_role_key: str) -> None:
    """Uploads (or replaces) <city>.json in the public city-exports bucket."""
    response = requests.post(
        f"{supabase_url.rstrip('/')}/storage/v1/object/{BUCKET}/{city}.json",
        data=content,
        headers={
            "Authorization": f"Bearer {service_role_key}",
            "apikey": service_role_key,
            "Content-Type": "application/json",
            "x-upsert": "true",
            # The app refreshes at most every few hours; the CDN may cache this long.
            "cache-control": "max-age=3600",
        },
        timeout=60,
    )
    response.raise_for_status()
