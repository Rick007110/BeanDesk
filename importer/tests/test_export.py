import json
from datetime import datetime, timezone
from pathlib import Path

from beandesk_import.export import build_export, to_json_bytes

# The sample the Kotlin parser is tested against. Same keys here means the app can read our output.
APP_SAMPLE = Path(__file__).resolve().parents[2] / "core" / "src" / "test" / "resources" / "city-export-sample.json"

NOW = datetime(2026, 10, 29, 3, 17, tzinfo=timezone.utc)


def row(**overrides) -> dict:
    base = {
        "id": 1,
        "osm_type": "node",
        "osm_id": 1001,
        "category": "cafe",
        "name": "Koffie & Werk",
        "address": "Oudegracht 1",
        "lat": 52.0911,
        "lng": 5.1201,
        "opening_hours": "Mo-Fr 08:00-18:00",
        "osm_tags": {"internet_access": "wlan"},
        "updated_at": NOW,
        "has_community": True,
        "community_wifi": "fast",
        "community_power": "many",
        "community_quiet": True,
        "community_laptop_policy": "not_on_weekends",
        "closed_reports": 0,
        "last_report_at": NOW,
    }
    return base | overrides


def test_export_has_the_same_shape_as_the_app_sample():
    sample = json.loads(APP_SAMPLE.read_text())
    export = build_export("utrecht", "Europe/Amsterdam", [row()], now=NOW)

    assert export.keys() == sample.keys()
    assert export["places"][0].keys() == sample["places"][0].keys()
    assert export["places"][0]["osm"].keys() == sample["places"][0]["osm"].keys()
    assert export["places"][0]["community"].keys() == sample["places"][0]["community"].keys()


def test_export_values():
    export = build_export(
        "utrecht",
        "Europe/Amsterdam",
        [row(), row(id=2, osm_id=2002, lat=52.0927, lng=5.1201, has_community=False)],
        now=NOW,
    )
    assert export["version"] == 1
    assert export["generated_at"] == "2026-10-29T03:17:00+00:00"
    assert export["center"] == {"lat": 52.0919, "lng": 5.1201}

    first, second = export["places"]
    assert first["osm"] == {"wifi": True, "wifi_free": None, "power": None}
    assert first["community"]["laptop_policy"] == "not_on_weekends"
    assert second["community"] is None

    # Non-ASCII stays readable in the file.
    assert "©" in to_json_bytes(export).decode("utf-8")


def test_empty_city_has_no_center():
    assert build_export("utrecht", "Europe/Amsterdam", [], now=NOW)["center"] is None
