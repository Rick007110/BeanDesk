"""The seeding checklist: which places it lists, and reading back what was seen."""

import csv
from datetime import datetime
from pathlib import Path

from beandesk_import import db, seeding


def element(osm_id: int, lat: float, lon: float, **tags) -> dict:
    return {"type": "node", "id": osm_id, "lat": lat, "lon": lon, "tags": tags}


def write(path: Path, rows: list[dict]) -> Path:
    with path.open("w", newline="", encoding="utf-8") as file:
        writer = csv.DictWriter(file, fieldnames=seeding.FIELDS)
        writer.writeheader()
        for row in rows:
            writer.writerow({"place_id": "1", "naam": "Café", **row})
    return path


def test_visits_become_reports_at_the_time_of_the_visit(tmp_path):
    visits = seeding.read_visits(write(tmp_path / "list.csv", [
        {"place_id": "7", "bezocht": "2026-11-02 10:30", "wifi": "Snel", "stopcontacten": "veel", "rustig": "ja",
         "laptop": "niet in het weekend"},
        {"place_id": "8", "bezocht": "2026-07-01 16:00", "rustig": "druk"},
        {"place_id": "9", "bezocht": "2026-11-03 09:00", "gesloten": "ja"},
        {"place_id": "10"},
    ]))
    assert visits.errors == []
    assert visits.skipped == 1
    assert visits.rows == [
        {"place_id": 7, "visited_at": "2026-11-02T10:30:00+01:00", "wifi": "fast", "power": "many", "quiet": True,
         "laptop_policy": "not_on_weekends"},
        # Summer time in July.
        {"place_id": 8, "visited_at": "2026-07-01T16:00:00+02:00", "quiet": False},
        {"place_id": 9, "visited_at": "2026-11-03T09:00:00+01:00", "permanently_closed": True},
    ]


def test_mistakes_are_named_with_their_line(tmp_path):
    visits = seeding.read_visits(write(tmp_path / "list.csv", [
        {"bezocht": "2 november", "wifi": "snel"},
        {"bezocht": "2026-11-02 10:30", "wifi": "supersnel"},
        {"bezocht": "2026-11-02 10:30"},
    ]))
    assert len(visits.errors) == 3
    assert visits.errors[0].startswith("regel 2 (Café): bezocht '2 november'")
    assert "wifi 'supersnel' is niet een van: geen, ja, traag, snel" in visits.errors[1]
    assert visits.errors[2].startswith("regel 4") and "leeg" in visits.errors[2]
    assert visits.rows == []


def test_the_checklist_lists_likely_laptop_spots_in_the_area_first(conn, tmp_path):
    dam = {"lat": 52.3731, "lon": 4.8926}
    db.import_elements(conn, "amsterdam", [
        element(1, name="Gewoon café", amenity="cafe", **dam),
        element(2, name="Wifi-café", amenity="cafe", internet_access="wlan", opening_hours="Mo-Su 08:00-18:00", **dam),
        element(3, name="OBA", amenity="library", **dam),
        element(4, name="Café in Utrecht", amenity="cafe", internet_access="wlan", lat=52.09, lon=5.12),
        element(5, name="Café zonder wifi", amenity="cafe", internet_access="no", **dam),
    ], force=True)

    places = seeding.checklist(conn, "amsterdam-binnenstad")
    assert [p["name"] for p in places] == ["OBA", "Wifi-café", "Café zonder wifi", "Gewoon café"]

    out = tmp_path / "checklist.csv"
    seeding.write_checklist(places, out)
    rows = list(csv.DictReader(out.open(encoding="utf-8")))
    assert list(rows[0]) == seeding.FIELDS
    assert rows[1]["naam"] == "Wifi-café" and rows[1]["wifi_volgens_osm"] == "wlan"
    assert rows[1]["kaart"].startswith("https://www.openstreetmap.org/?mlat=52.3731&mlon=4.8926")

    # A filled-in row goes in as a report of that place.
    rows[1]["bezocht"] = "2026-11-02 10:30"
    rows[1]["wifi"] = "snel"
    filled = write(tmp_path / "filled.csv", rows)
    assert db.seed_reports(conn, seeding.read_visits(filled).rows) == 1
    reported = conn.execute("select wifi::text from public.place_community where place_id = %s", (places[1]["id"],)).fetchone()
    assert reported == ("fast",)
