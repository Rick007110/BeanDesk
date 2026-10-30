"""Seeding: before launch, someone visits 30-50 places in a neighbourhood and writes down what
they saw, so the app is not empty there. This makes the checklist to take along, and reads it
back for public.seed_reports().

The checklist is a CSV with Dutch columns and answers, to fill in on a phone or laptop.
"""

import csv
from dataclasses import dataclass
from datetime import datetime
from pathlib import Path
from zoneinfo import ZoneInfo

import psycopg

# Neighbourhoods as (west, south, east, north) in degrees.
AREAS = {
    # Within the Singelgracht: the canal ring, the old centre, the Jordaan and the Plantage.
    "amsterdam-binnenstad": (4.876, 52.360, 4.918, 52.384),
}

# Libraries and coworking spaces first, then places OpenStreetMap says have wifi, then places
# with opening hours: the likeliest laptop spots.
CHECKLIST_SQL = """
select
    p.id,
    p.name,
    coalesce(p.address, '') as address,
    p.category::text as category,
    coalesce(p.osm_tags ->> 'internet_access', '') as osm_wifi,
    coalesce(p.opening_hours, '') as opening_hours,
    round(extensions.st_y(p.location::extensions.geometry)::numeric, 6)::float as lat,
    round(extensions.st_x(p.location::extensions.geometry)::numeric, 6)::float as lng
from public.places p
where p.deleted_at is null
    and not p.hidden
    and extensions.st_intersects(
        p.location,
        extensions.st_makeenvelope(%(west)s, %(south)s, %(east)s, %(north)s, 4326)::extensions.geography
    )
order by
    p.category = 'cafe',
    coalesce(p.osm_tags ->> 'internet_access', '') not in ('yes', 'wlan', 'wifi'),
    p.opening_hours is null,
    p.name
limit %(limit)s
"""

FIELDS = [
    "place_id", "naam", "adres", "soort", "wifi_volgens_osm", "openingstijden", "kaart",
    # Filled in on the visit:
    "bezocht", "wifi", "stopcontacten", "rustig", "laptop", "gesloten", "opmerking",
]

# The Dutch answers in the checklist, and what the database calls them.
WIFI = {"geen": "none", "ja": "yes", "traag": "slow", "snel": "fast"}
POWER = {"geen": "none", "weinig": "few", "veel": "many"}
QUIET = {"ja": True, "rustig": True, "nee": False, "druk": False}
LAPTOP = {"welkom": "allowed", "niet in het weekend": "not_on_weekends", "niet toegestaan": "not_allowed"}
YES = {"ja": True, "": False, "nee": False}

DUTCH_TIME = ZoneInfo("Europe/Amsterdam")


def checklist(conn: psycopg.Connection, area: str, limit: int = 50) -> list[dict]:
    """The places to visit in an area, likeliest laptop spots first."""
    west, south, east, north = AREAS[area]
    with conn.cursor(row_factory=psycopg.rows.dict_row) as cur:
        cur.execute(CHECKLIST_SQL, {"west": west, "south": south, "east": east, "north": north, "limit": limit})
        return cur.fetchall()


def write_checklist(places: list[dict], path: Path) -> None:
    """A CSV with one row per place and empty columns for the visit."""
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", newline="", encoding="utf-8") as file:
        writer = csv.DictWriter(file, fieldnames=FIELDS)
        writer.writeheader()
        for place in places:
            writer.writerow({
                "place_id": place["id"],
                "naam": place["name"],
                "adres": place["address"],
                "soort": place["category"],
                "wifi_volgens_osm": place["osm_wifi"],
                "openingstijden": place["opening_hours"],
                "kaart": f"https://www.openstreetmap.org/?mlat={place['lat']}&mlon={place['lng']}#map=19/{place['lat']}/{place['lng']}",
            })


@dataclass
class Visits:
    """What a filled-in checklist says, ready for public.seed_reports(), or why it cannot go in."""

    rows: list[dict]
    errors: list[str]
    skipped: int


def read_visits(path: Path) -> Visits:
    """Reads the visited rows of a checklist; rows without "bezocht" are skipped."""
    rows, errors, skipped = [], [], 0
    with path.open(newline="", encoding="utf-8-sig") as file:
        for line, record in enumerate(csv.DictReader(file), start=2):
            visited = (record.get("bezocht") or "").strip()
            if not visited:
                skipped += 1
                continue
            row, problems = _visit(record, visited)
            if problems:
                name = record.get("naam") or record.get("place_id")
                errors.extend(f"regel {line} ({name}): {problem}" for problem in problems)
            else:
                rows.append(row)
    return Visits(rows=rows, errors=errors, skipped=skipped)


def _visit(record: dict, visited: str) -> tuple[dict, list[str]]:
    problems = []
    row: dict = {}
    try:
        row["place_id"] = int(record["place_id"])
    except (KeyError, TypeError, ValueError):
        problems.append(f"place_id '{record.get('place_id')}' is geen getal")
    try:
        moment = datetime.strptime(visited, "%Y-%m-%d %H:%M").replace(tzinfo=DUTCH_TIME)
        row["visited_at"] = moment.isoformat()
    except ValueError:
        problems.append(f"bezocht '{visited}' is niet in de vorm 2026-11-02 10:30")

    for column, key, answers in (
        ("wifi", "wifi", WIFI),
        ("stopcontacten", "power", POWER),
        ("rustig", "quiet", QUIET),
        ("laptop", "laptop_policy", LAPTOP),
        ("gesloten", "permanently_closed", YES),
    ):
        answer = (record.get(column) or "").strip().lower()
        if not answer:
            continue
        if answer not in answers:
            problems.append(f"{column} '{answer}' is niet een van: {', '.join(a for a in answers if a)}")
        else:
            row[key] = answers[answer]

    if not problems and not any(key in row for key in ("wifi", "power", "quiet", "laptop_policy")) and not row.get("permanently_closed"):
        problems.append("bezocht, maar wifi, stopcontacten, rustig, laptop en gesloten zijn leeg")
    return row, problems
