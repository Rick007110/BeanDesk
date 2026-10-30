"""Tests the database functions that hold every import and export rule, against real PostGIS."""

import json
import uuid
from pathlib import Path

import psycopg
import pytest

from beandesk_import import db

# The sample the Kotlin parser is tested against. Same keys here means the app can read the export.
APP_SAMPLE = Path(__file__).resolve().parents[2] / "core" / "src" / "test" / "resources" / "city-export-sample.json"


def element(osm_id: int, osm_type: str = "node", lat: float = 52.09, lon: float = 5.12, **tags) -> dict:
    tags = {k.replace("__", ":"): v for k, v in tags.items()}
    return {"type": osm_type, "id": osm_id, "lat": lat, "lon": lon, "tags": tags}


def places(conn) -> dict[int, dict]:
    with conn.cursor(row_factory=psycopg.rows.dict_row) as cur:
        cur.execute(
            "select osm_id, category::text, name, address, opening_hours, deleted_at, updated_at,"
            " extensions.st_y(location::extensions.geometry) as lat,"
            " extensions.st_x(location::extensions.geometry) as lng"
            " from public.places order by osm_id"
        )
        return {r["osm_id"]: r for r in cur.fetchall()}


def place_id(conn, osm_id: int) -> int:
    return conn.execute("select id from public.places where osm_id = %s", (osm_id,)).fetchone()[0]


def create_user(conn, anonymous: bool = True) -> uuid.UUID:
    return conn.execute("insert into auth.users (is_anonymous) values (%s) returning id", (anonymous,)).fetchone()[0]


def report_as(conn, reporter: uuid.UUID, target_place: int, **columns):
    """Inserts a report the way the app does: as the authenticated role, with the user's JWT claims."""
    names = ["place_id", *columns]
    placeholders = ", ".join(["%s"] * len(names))
    with conn.transaction():
        conn.execute("set local role authenticated")
        conn.execute("select set_config('request.jwt.claims', %s, true)", (json.dumps({"sub": str(reporter)}),))
        conn.execute(
            f"insert into public.place_reports ({', '.join(names)}) values ({placeholders})",
            [target_place, *columns.values()],
        )


# --- Import ------------------------------------------------------------------------------

def test_filters_noise_and_keeps_laptop_spots(conn, overpass_elements):
    result = db.import_elements(conn, "utrecht", overpass_elements)
    assert result["elements"] == 8
    assert result["inserted"] == 4
    # Cannabis, no name, private and closed are left out.
    assert sorted(places(conn)) == [1001, 2002, 3003, 8008]


def test_maps_categories_addresses_and_coordinates(conn, overpass_elements):
    db.import_elements(conn, "utrecht", overpass_elements)
    rows = places(conn)

    assert rows[1001]["category"] == "cafe"
    assert rows[1001]["address"] == "Oudegracht 1"
    assert rows[1001]["opening_hours"] == "Mo-Fr 08:00-18:00; Sa 09:00-17:00"

    assert rows[2002]["category"] == "library"
    assert rows[2002]["address"] == "Neude"
    # Ways and relations take their coordinate from "center".
    assert (rows[2002]["lat"], rows[2002]["lng"]) == pytest.approx((52.0927, 5.1196))

    assert rows[3003]["category"] == "coworking"
    assert rows[3003]["address"] is None


def test_duplicate_elements_are_imported_once(conn, overpass_elements):
    result = db.import_elements(conn, "utrecht", overpass_elements + overpass_elements[:1])
    assert result["places"] == 4


def test_import_inserts_updates_and_soft_deletes(conn, overpass_elements):
    first = db.import_elements(conn, "utrecht", overpass_elements)
    assert (first["inserted"], first["updated"], first["unchanged"], first["deleted"]) == (4, 0, 0, 0)

    again = db.import_elements(conn, "utrecht", overpass_elements)
    assert (again["inserted"], again["updated"], again["unchanged"], again["deleted"]) == (0, 0, 4, 0)

    # The library got new opening hours and the coworking space is gone from OSM.
    changed = [e for e in overpass_elements if e["id"] != 3003]
    library = next(e for e in changed if e["id"] == 2002)
    library["tags"] = {**library["tags"], "opening_hours": "Mo-Sa 10:00-18:00"}
    before = places(conn)
    third = db.import_elements(conn, "utrecht", changed)
    assert (third["inserted"], third["updated"], third["unchanged"], third["deleted"]) == (0, 1, 2, 1)

    rows = places(conn)
    assert rows[3003]["deleted_at"] is not None
    assert rows[2002]["deleted_at"] is None
    # Unchanged rows keep their updated_at, so clients can tell what changed.
    assert rows[1001]["updated_at"] == before[1001]["updated_at"]
    assert rows[2002]["updated_at"] > before[2002]["updated_at"]


def test_import_refuses_to_delete_most_of_a_city(conn, overpass_elements):
    db.import_elements(conn, "utrecht", overpass_elements)

    with pytest.raises(psycopg.errors.RaiseException, match="import_aborted"):
        db.import_elements(conn, "utrecht", overpass_elements[:1])

    forced = db.import_elements(conn, "utrecht", overpass_elements[:1], force=True)
    assert forced["deleted"] == 3


def test_unknown_city_is_refused(conn, overpass_elements):
    with pytest.raises(psycopg.errors.RaiseException, match="unknown_city"):
        db.import_elements(conn, "atlantis", overpass_elements)


def test_overpass_query(conn):
    query = conn.execute("select public.overpass_query('utrecht')").fetchone()[0]
    assert '["admin_level"="8"]["name"="Utrecht"]' in query
    assert 'nwr["amenity"~"^(cafe|library|coworking_space)$"](area.city);' in query
    assert query.endswith("out center tags;")

    conn.execute("insert into public.cities (slug, name) values ('den-bosch', 'Den \"Bosch\"')")
    quoted = conn.execute("select public.overpass_query('den-bosch')").fetchone()[0]
    assert '["name"="Den \\"Bosch\\""]' in quoted


def test_users_cannot_run_imports(conn, overpass_elements):
    with pytest.raises(psycopg.errors.InsufficientPrivilege), conn.transaction():
        conn.execute("set local role anon")
        db.import_elements(conn, "utrecht", overpass_elements)


# --- Reports -----------------------------------------------------------------------------

def test_reports_update_community_facts(conn, overpass_elements):
    db.import_elements(conn, "utrecht", overpass_elements)
    cafe = place_id(conn, 1001)
    user = create_user(conn)

    report_as(conn, user, cafe, wifi="fast", power="few")
    report_as(conn, user, cafe, quiet=True)
    report_as(conn, user, cafe, permanently_closed=True)

    row = conn.execute(
        "select wifi::text, power::text, quiet, closed_reports from public.place_community where place_id = %s",
        (cafe,),
    ).fetchone()
    assert row == ("fast", "few", True, 1)


def test_new_users_get_a_profile(conn):
    user = create_user(conn)
    assert conn.execute("select count(*) from public.profiles where id = %s", (user,)).fetchone()[0] == 1


def test_users_cannot_forge_report_fields(conn, overpass_elements):
    db.import_elements(conn, "utrecht", overpass_elements)
    cafe = place_id(conn, 1001)
    user = create_user(conn)
    other = create_user(conn)

    with pytest.raises(psycopg.errors.InsufficientPrivilege):
        report_as(conn, user, cafe, wifi="yes", user_id=other)
    with pytest.raises(psycopg.errors.InsufficientPrivilege):
        report_as(conn, user, cafe, wifi="yes", reported_at="2020-01-01")


def test_users_cannot_raise_their_own_trust_score(conn):
    user = create_user(conn)
    with pytest.raises(psycopg.errors.InsufficientPrivilege), conn.transaction():
        conn.execute("set local role authenticated")
        conn.execute("select set_config('request.jwt.claims', %s, true)", (json.dumps({"sub": str(user)}),))
        conn.execute("update public.profiles set trust_score = 100 where id = %s", (user,))


def test_anonymous_accounts_hit_the_daily_limit(conn, overpass_elements):
    db.import_elements(conn, "utrecht", overpass_elements)
    cafe = place_id(conn, 1001)
    user = create_user(conn, anonymous=True)

    for _ in range(20):
        report_as(conn, user, cafe, quiet=True)
    with pytest.raises(psycopg.errors.RaiseException, match="rate_limit"):
        report_as(conn, user, cafe, quiet=True)


# --- Export ------------------------------------------------------------------------------

def test_export_has_the_same_shape_as_the_app_sample(conn, overpass_elements):
    db.import_elements(conn, "utrecht", overpass_elements)
    report_as(conn, create_user(conn), place_id(conn, 1001), wifi="fast")
    sample = json.loads(APP_SAMPLE.read_text())

    export = db.city_export(conn, "utrecht")
    cafe = next(p for p in export["places"] if p["osm_id"] == 1001)

    assert export.keys() == sample.keys()
    assert cafe.keys() == sample["places"][0].keys()
    assert cafe["osm"].keys() == sample["places"][0]["osm"].keys()
    assert cafe["community"].keys() == sample["places"][0]["community"].keys()


def test_export_includes_community_and_skips_hidden_and_deleted(conn, overpass_elements):
    db.import_elements(conn, "utrecht", overpass_elements)
    report_as(conn, create_user(conn), place_id(conn, 1001), laptop_policy="not_on_weekends")
    conn.execute("update public.places set hidden = true where osm_id = 8008")
    conn.execute("update public.places set deleted_at = now() where osm_id = 3003")

    export = db.city_export(conn, "utrecht")

    assert export["version"] == 1
    assert export["timezone"] == "Europe/Amsterdam"
    exported = {p["osm_id"]: p for p in export["places"]}
    assert sorted(exported) == [1001, 2002]
    assert exported[1001]["community"]["laptop_policy"] == "not_on_weekends"
    assert exported[2002]["community"] is None
    assert exported[1001]["lat"] == pytest.approx(52.0911)
    assert export["center"]["lat"] == pytest.approx((52.0911 + 52.0927) / 2, abs=1e-6)


def test_export_osm_facts(conn):
    db.import_elements(
        conn,
        "utrecht",
        [
            element(1, name="Wlan, gratis", amenity="cafe", internet_access="wlan", internet_access__fee="no"),
            element(2, name="Terminal en wlan", amenity="cafe", internet_access="terminal;wlan"),
            element(3, name="Alleen terminal", amenity="cafe", internet_access="terminal"),
            element(4, name="Geen wifi", amenity="cafe", internet_access="no", power_supply="no"),
            element(5, name="Klanten", amenity="cafe", internet_access="yes", internet_access__fee="customers"),
            element(6, name="Betaald", amenity="cafe", internet_access="wifi", internet_access__fee="yes", power_supply="yes"),
            element(7, name="Niets bekend", amenity="cafe"),
        ],
    )
    facts = {p["osm_id"]: p["osm"] for p in db.city_export(conn, "utrecht")["places"]}

    assert facts[1] == {"wifi": True, "wifi_free": True, "power": None}
    assert facts[2]["wifi"] is True
    assert facts[3]["wifi"] is None
    assert facts[4] == {"wifi": False, "wifi_free": None, "power": False}
    assert facts[5] == {"wifi": True, "wifi_free": True, "power": None}
    assert facts[6] == {"wifi": True, "wifi_free": False, "power": True}
    assert facts[7] == {"wifi": None, "wifi_free": None, "power": None}


def test_export_of_an_empty_and_an_unknown_city(conn):
    empty = db.city_export(conn, "groningen")
    assert empty["places"] == []
    assert empty["center"] is None
    assert db.city_export(conn, "atlantis") is None


def test_anyone_may_read_the_export(conn, overpass_elements):
    db.import_elements(conn, "utrecht", overpass_elements)
    with conn.transaction():
        conn.execute("set local role anon")
        assert len(db.city_export(conn, "utrecht")["places"]) == 4


# --- Search areas --------------------------------------------------------------------------

# The fixture's places lie in two cells: (2604, 170) and (2604, 171).
FIXTURE_CELLS = [{"lat_index": 2604, "lng_index": 170}, {"lat_index": 2604, "lng_index": 171}]


def import_area(conn, cells, elements) -> dict:
    return conn.execute(
        "select public.import_osm_area(%s, %s)", (psycopg.types.json.Jsonb(cells), psycopg.types.json.Jsonb(elements))
    ).fetchone()[0]


def missing_cells(conn, lat=52.09, lng=5.12, radius=2000, max_age="30 days") -> set[tuple[int, int]]:
    rows = conn.execute(
        "select lat_index, lng_index from public.missing_osm_cells(%s, %s, %s, %s::interval)", (lat, lng, radius, max_age)
    ).fetchall()
    return {(r[0], r[1]) for r in rows}


def area_export(conn, lat=52.0911, lng=5.1201, radius=2000, label="Utrecht") -> dict:
    return conn.execute(
        "select public.area_export(%s, %s, %s, %s)", (lat, lng, radius, label)
    ).fetchone()[0]


def test_missing_cells_cover_the_radius_and_skip_fresh_ones(conn):
    before = missing_cells(conn)
    # 2 km around (52.09, 5.12): 3 rows of cells (0.02° lat) by 3 columns (0.03° lng).
    assert len(before) == 9
    assert (2604, 170) in before

    conn.execute("insert into public.osm_cells (lat_index, lng_index) values (2604, 170)")
    conn.execute("insert into public.osm_cells (lat_index, lng_index, fetched_at) values (2604, 171, now() - interval '40 days')")
    after = missing_cells(conn)
    assert (2604, 170) not in after
    # Older than 30 days counts as missing again.
    assert (2604, 171) in after
    assert len(after) == 8


def test_area_import_adds_places_without_a_city_and_marks_cells(conn, overpass_elements):
    result = import_area(conn, FIXTURE_CELLS, overpass_elements)
    assert (result["inserted"], result["updated"], result["deleted"]) == (4, 0, 0)

    cities = conn.execute("select distinct city from public.places").fetchall()
    assert cities == [(None,)]
    assert missing_cells(conn).isdisjoint({(2604, 170), (2604, 171)})


def test_area_import_keeps_the_city_of_known_places(conn, overpass_elements):
    db.import_elements(conn, "utrecht", overpass_elements)
    result = import_area(conn, FIXTURE_CELLS, overpass_elements)
    assert (result["inserted"], result["updated"]) == (0, 0)
    assert conn.execute("select count(*) from public.places where city = 'utrecht'").fetchone()[0] == 4


def test_area_import_soft_deletes_only_inside_its_cells(conn, overpass_elements):
    import_area(conn, FIXTURE_CELLS, overpass_elements)
    # Re-fetch only cell (2604, 171), which holds the coworking space, now gone from OSM.
    result = import_area(conn, [{"lat_index": 2604, "lng_index": 171}], [])
    # Nothing came back where 1 place was: that looks like a bad answer, so nothing is deleted.
    assert result["deleted"] == 0

    without_coworking = [e for e in overpass_elements if e["id"] != 3003]
    result = import_area(conn, FIXTURE_CELLS, without_coworking)
    assert result["deleted"] == 1
    assert places(conn)[3003]["deleted_at"] is not None
    assert places(conn)[1001]["deleted_at"] is None


def test_area_export_is_nearest_first_within_the_radius(conn, overpass_elements):
    import_area(conn, FIXTURE_CELLS, overpass_elements)
    report_as(conn, create_user(conn), place_id(conn, 1001), wifi="fast")
    sample = json.loads(APP_SAMPLE.read_text())

    export = area_export(conn, lat=52.0911, lng=5.1201, radius=500, label="Oudegracht 1, Utrecht")

    assert export["city"] == "Oudegracht 1, Utrecht"
    assert export["center"] == {"lat": 52.0911, "lng": 5.1201}
    assert export["radius_m"] == 500
    # The app reads every key of its sample; extra keys such as radius_m are ignored.
    assert sample.keys() <= export.keys()
    assert export["places"][0].keys() == sample["places"][0].keys()
    # 1001 is at the point, 2002 about 200 m away; the others are further than 500 m.
    assert [p["osm_id"] for p in export["places"]] == [1001, 2002]
    assert export["places"][0]["community"]["wifi"] == "fast"


def test_area_export_writes_places_exactly_like_the_city_export(conn, overpass_elements):
    db.import_elements(conn, "utrecht", overpass_elements)
    report_as(conn, create_user(conn), place_id(conn, 1001), wifi="fast", quiet=True)

    by_city = {p["id"]: p for p in db.city_export(conn, "utrecht")["places"]}
    by_area = {p["id"]: p for p in area_export(conn, radius=5000)["places"]}
    assert by_area == by_city


def test_place_location(conn, overpass_elements):
    db.import_elements(conn, "utrecht", overpass_elements)
    library = place_id(conn, 2002)
    assert conn.execute("select * from public.place_location(%s)", (library,)).fetchone() == pytest.approx((52.0927, 5.1196))

    conn.execute("update public.places set deleted_at = now() where id = %s", (library,))
    assert conn.execute("select * from public.place_location(%s)", (library,)).fetchone() is None


def test_the_search_function_role_can_do_its_work(conn, overpass_elements):
    with conn.transaction():
        conn.execute("set local role service_role")
        import_area(conn, FIXTURE_CELLS, overpass_elements)
        conn.execute("insert into public.osm_fetches (cells, elements) values (2, 8)")
        conn.execute(
            "insert into public.geocode_cache (query, label, kind, lat, lng) values ('utrecht', 'Utrecht', 'woonplaats', 52.09, 5.12)"
            " on conflict (query) do update set label = excluded.label"
        )
        assert missing_cells(conn).isdisjoint({(2604, 170), (2604, 171)})
        assert len(area_export(conn)["places"]) == 4
        assert conn.execute("select * from public.place_location(%s)", (place_id(conn, 1001),)).fetchone() is not None


@pytest.mark.parametrize("role", ["anon", "authenticated"])
@pytest.mark.parametrize(
    "statement",
    [
        "select public.area_export(52.09, 5.12, 1000, 'x')",
        "select * from public.missing_osm_cells(52.09, 5.12, 1000)",
        "select public.import_osm_area('[]', '[]')",
        "select * from public.place_location(1)",
        "select * from public.geocode_cache",
        "insert into public.osm_cells (lat_index, lng_index) values (1, 1)",
    ],
)
def test_users_cannot_use_search_internals(conn, role, statement):
    with pytest.raises(psycopg.errors.InsufficientPrivilege), conn.transaction():
        conn.execute(f"set local role {role}")
        conn.execute(statement)


# --- Import from an OpenStreetMap extract (osm_extract.py) ---------------------------------

def test_extract_import_adds_places_and_marks_its_cells(conn, overpass_elements):
    data = {
        "generated_at": "2026-11-01T02:30:00+00:00",
        "cells": [[c["lat_index"], c["lng_index"]] for c in FIXTURE_CELLS],
        "elements": overpass_elements,
    }
    result = conn.execute("select public.import_osm_extract(%s)", (psycopg.types.json.Jsonb(data),)).fetchone()[0]

    assert (result["cells"], result["inserted"], result["generated_at"]) == (2, 4, "2026-11-01T02:30:00+00:00")
    assert missing_cells(conn).isdisjoint({(2604, 170), (2604, 171)})
    assert len(area_export(conn)["places"]) == 4


def test_users_cannot_run_the_extract_import(conn):
    with pytest.raises(psycopg.errors.InsufficientPrivilege), conn.transaction():
        conn.execute("set local role authenticated")
        conn.execute("select public.import_osm_file('https://example.com')")
