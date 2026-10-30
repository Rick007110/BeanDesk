"""Tests the database functions that hold every import and export rule, against real PostGIS."""

import json
import uuid
from datetime import datetime
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


# --- Stars ---------------------------------------------------------------------------------

def rate_as(conn, reporter: uuid.UUID, target_place: int, rating: int):
    """Rates the way the app does: an upsert on (place_id, user_id) as the authenticated role."""
    with conn.transaction():
        conn.execute("set local role authenticated")
        conn.execute("select set_config('request.jwt.claims', %s, true)", (json.dumps({"sub": str(reporter)}),))
        conn.execute(
            "insert into public.place_ratings (place_id, rating) values (%s, %s)"
            " on conflict (place_id, user_id) do update set place_id = excluded.place_id, rating = excluded.rating",
            (target_place, rating),
        )


def test_ratings_show_as_an_average_in_the_export(conn, overpass_elements):
    db.import_elements(conn, "utrecht", overpass_elements)
    cafe = place_id(conn, 1001)
    first, second = create_user(conn), create_user(conn)

    rate_as(conn, first, cafe, 5)
    rate_as(conn, second, cafe, 4)
    # A new rating by the same user replaces the old one.
    rate_as(conn, second, cafe, 3)

    exported = {p["osm_id"]: p for p in db.city_export(conn, "utrecht")["places"]}
    assert exported[1001]["rating"] == {"average": 4.0, "count": 2}
    assert exported[2002]["rating"] is None
    assert exported[1001] == {p["osm_id"]: p for p in area_export(conn, radius=5000)["places"]}[1001]


def test_users_cannot_rate_for_someone_else_or_read_others(conn, overpass_elements):
    db.import_elements(conn, "utrecht", overpass_elements)
    cafe = place_id(conn, 1001)
    user, other = create_user(conn), create_user(conn)
    rate_as(conn, other, cafe, 2)

    with pytest.raises(psycopg.errors.InsufficientPrivilege), conn.transaction():
        conn.execute("set local role authenticated")
        conn.execute("select set_config('request.jwt.claims', %s, true)", (json.dumps({"sub": str(user)}),))
        conn.execute("insert into public.place_ratings (place_id, user_id, rating) values (%s, %s, 5)", (cafe, other))

    with conn.transaction():
        conn.execute("set local role authenticated")
        conn.execute("select set_config('request.jwt.claims', %s, true)", (json.dumps({"sub": str(user)}),))
        assert conn.execute("select count(*) from public.place_ratings").fetchone()[0] == 0
        # The summary is public.
        assert conn.execute("select rating_count from public.place_rating_summary").fetchone()[0] == 1


def test_ratings_outside_one_to_five_are_refused(conn, overpass_elements):
    db.import_elements(conn, "utrecht", overpass_elements)
    with pytest.raises(psycopg.errors.CheckViolation):
        rate_as(conn, create_user(conn), place_id(conn, 1001), 6)


# --- Search by name ------------------------------------------------------------------------

def name_search(conn, query: str, label: str = "label") -> dict:
    return conn.execute("select public.name_search(%s, %s)", (query, label)).fetchone()[0]


def test_name_search_finds_every_word_in_name_or_address(conn, overpass_elements):
    import_area(conn, FIXTURE_CELLS, overpass_elements)

    found = name_search(conn, "  KOFFIE   oudegracht ", label="Koffie oudegracht")
    assert [p["osm_id"] for p in found["places"]] == [1001]
    assert found["city"] == "Koffie oudegracht"
    assert found["center"] == {"lat": 52.0911, "lng": 5.1201}
    assert found["places"][0].keys() == json.loads(APP_SAMPLE.read_text())["places"][0].keys()

    assert name_search(conn, "koffie neude")["places"] == []
    assert name_search(conn, "   ")["places"] == []


def test_name_search_ignores_accents_and_wildcards(conn):
    import_area(
        conn,
        FIXTURE_CELLS,
        [
            element(1, name="Café de Zaak", amenity="cafe"),
            element(2, name="100% Koffie", amenity="cafe"),
            element(3, name="Koffie_Bar", amenity="cafe"),
        ],
    )
    assert [p["osm_id"] for p in name_search(conn, "cafe zaak")["places"]] == [1]
    assert [p["osm_id"] for p in name_search(conn, "100%")["places"]] == [2]
    # "_" and "%" are not wildcards: "koffie_" does not match "100% Koffie".
    assert [p["osm_id"] for p in name_search(conn, "koffie_")["places"]] == [3]


def test_users_cannot_run_name_search(conn):
    with pytest.raises(psycopg.errors.InsufficientPrivilege), conn.transaction():
        conn.execute("set local role anon")
        name_search(conn, "koffie")


def find_by_name(conn, query: str, near: tuple[float, float, int] | None = None) -> dict:
    lat, lng, radius = near or (None, None, None)
    return conn.execute(
        "select public.find_by_name(%s, 'label', %s, %s, %s)", (query, lat, lng, radius)
    ).fetchone()[0]


def test_find_by_name_ignores_spaces_and_uses_town_and_postcode(conn):
    import_area(
        conn,
        FIXTURE_CELLS,
        [
            element(1, name="Coffee Company", amenity="cafe", addr__city="Utrecht", addr__postcode="3511 AB"),
            element(2, name="Coffee Company", amenity="cafe", addr__city="Breda", lat=52.091, lon=5.125),
            element(3, name="Anne&Max", amenity="cafe"),
        ],
    )
    assert sorted(p["osm_id"] for p in find_by_name(conn, "coffeecompany")["places"]) == [1, 2]
    assert [p["osm_id"] for p in find_by_name(conn, "coffeecompany breda")["places"]] == [2]
    assert [p["osm_id"] for p in find_by_name(conn, "coffee company 3511ab")["places"]] == [1]
    assert [p["osm_id"] for p in find_by_name(conn, "annemax")["places"]] == [3]


def test_find_by_name_near_a_point_is_nearest_first(conn):
    import_area(
        conn,
        FIXTURE_CELLS,
        [
            element(1, name="Coffee Company", amenity="cafe", lat=52.09, lon=5.12),
            element(2, name="Coffee Company", amenity="cafe", lat=52.095, lon=5.12),
            element(3, name="Coffee Company", amenity="cafe", lat=52.2, lon=5.12),
        ],
    )
    found = find_by_name(conn, "coffee company", near=(52.096, 5.12, 2000))
    assert [p["osm_id"] for p in found["places"]] == [2, 1]
    assert found["center"] == {"lat": 52.096, "lng": 5.12}


# --- Parts of the day, removed reports, deleting an account -------------------------------

def report_at(conn, reporter: uuid.UUID, target_place: int, moment_sql: str, **columns):
    """A report at a given moment; only the database itself may set reported_at."""
    names = ["place_id", "user_id", "reported_at", *columns]
    conn.execute(
        f"insert into public.place_reports ({', '.join(names)}) values (%s, %s, {moment_sql}, "
        + ", ".join(["%s"] * len(columns)) + ")",
        [target_place, reporter, *columns.values()],
    )


# Last week's Monday, Dutch time; the parts of the day are counted in Dutch time.
LAST_MONDAY = "(date_trunc('week', now() at time zone 'Europe/Amsterdam') - interval '7 days')"


def dutch(offset: str) -> str:
    return f"(({LAST_MONDAY} + interval '{offset}') at time zone 'Europe/Amsterdam')"


def test_quiet_reports_count_per_part_of_the_day(conn, overpass_elements):
    db.import_elements(conn, "utrecht", overpass_elements)
    cafe = place_id(conn, 1001)
    user = create_user(conn)

    report_at(conn, user, cafe, dutch("1 day 08:30"), quiet=True)  # Tuesday morning
    report_at(conn, user, cafe, dutch("1 day 09:45"), quiet=True)
    report_at(conn, user, cafe, dutch("1 day 13:30"), quiet=False)  # Tuesday afternoon
    report_at(conn, user, cafe, dutch("2 days 17:00"), quiet=True)  # Wednesday evening
    report_at(conn, user, cafe, dutch("5 days 11:00"), quiet=False)  # Saturday
    report_at(conn, user, cafe, dutch("6 days 20:00"), quiet=False)  # Sunday evening: weekend
    report_at(conn, user, cafe, dutch("1 day 08:00"), wifi="fast")  # no quiet: not counted
    report_at(conn, user, cafe, "now() - interval '13 months'", quiet=True)  # too old

    expected = {
        "morning": {"quiet": 2, "busy": 0},
        "afternoon": {"quiet": 0, "busy": 1},
        "evening": {"quiet": 1, "busy": 0},
        "weekend": {"quiet": 0, "busy": 2},
    }
    stored = conn.execute("select quiet_by_daypart from public.place_community where place_id = %s", (cafe,)).fetchone()[0]
    assert stored == expected
    exported = {p["osm_id"]: p for p in db.city_export(conn, "utrecht")["places"]}
    assert exported[1001]["community"]["quiet_by_daypart"] == expected


def test_a_place_without_quiet_reports_has_no_parts_of_the_day(conn, overpass_elements):
    db.import_elements(conn, "utrecht", overpass_elements)
    report_as(conn, create_user(conn), place_id(conn, 1001), wifi="fast")
    exported = {p["osm_id"]: p for p in db.city_export(conn, "utrecht")["places"]}
    assert exported[1001]["community"]["quiet_by_daypart"] is None


def test_removing_reports_recomputes_what_users_say(conn, overpass_elements):
    db.import_elements(conn, "utrecht", overpass_elements)
    cafe = place_id(conn, 1001)
    first, second = create_user(conn), create_user(conn)
    report_at(conn, first, cafe, "now() - interval '2 days'", wifi="fast", quiet=True)
    report_at(conn, second, cafe, "now() - interval '1 day'", wifi="slow", permanently_closed=True)

    def community():
        return conn.execute(
            "select wifi::text, quiet, closed_reports from public.place_community where place_id = %s", (cafe,)
        ).fetchone()

    assert community() == ("slow", True, 1)
    conn.execute("delete from public.place_reports where user_id = %s", (second,))
    assert community() == ("fast", True, 0)
    conn.execute("delete from public.place_reports where user_id = %s", (first,))
    assert community() is None
    exported = {p["osm_id"]: p for p in db.city_export(conn, "utrecht")["places"]}
    assert exported[1001]["community"] is None


def as_user(conn, user: uuid.UUID | None):
    conn.execute("set local role authenticated")
    claims = {"sub": str(user)} if user else {}
    conn.execute("select set_config('request.jwt.claims', %s, true)", (json.dumps(claims),))


def test_users_delete_their_own_account_with_its_reports_and_stars(conn, overpass_elements):
    db.import_elements(conn, "utrecht", overpass_elements)
    cafe = place_id(conn, 1001)
    leaving, staying = create_user(conn), create_user(conn)
    report_at(conn, staying, cafe, "now() - interval '2 days'", wifi="fast")
    report_at(conn, leaving, cafe, "now() - interval '1 day'", wifi="none")
    rate_as(conn, staying, cafe, 4)
    rate_as(conn, leaving, cafe, 1)

    with conn.transaction():
        as_user(conn, leaving)
        conn.execute("select public.delete_my_account()")

    assert conn.execute("select count(*) from auth.users where id = %s", (leaving,)).fetchone()[0] == 0
    assert conn.execute("select count(*) from public.place_reports where user_id = %s", (leaving,)).fetchone()[0] == 0
    assert conn.execute("select count(*) from public.profiles where id = %s", (leaving,)).fetchone()[0] == 0
    exported = {p["osm_id"]: p for p in db.city_export(conn, "utrecht")["places"]}
    assert exported[1001]["community"]["wifi"] == "fast"
    assert exported[1001]["rating"] == {"average": 4.0, "count": 1}
    assert conn.execute("select count(*) from auth.users where id = %s", (staying,)).fetchone()[0] == 1


def test_only_a_signed_in_user_can_delete_an_account_and_only_their_own(conn):
    with pytest.raises(psycopg.errors.InsufficientPrivilege), conn.transaction():
        conn.execute("set local role anon")
        conn.execute("select public.delete_my_account()")

    with pytest.raises(psycopg.errors.InvalidAuthorizationSpecification), conn.transaction():
        as_user(conn, None)
        conn.execute("select public.delete_my_account()")

    with pytest.raises(psycopg.errors.InsufficientPrivilege), conn.transaction():
        as_user(conn, create_user(conn))
        conn.execute("select public.recompute_place_community(1)")


# --- Changes since the last download, idle accounts, seeding --------------------------------

def generated_at(conn) -> str:
    """An export's generated_at a while ago, past the five minutes of overlap."""
    return conn.execute("select now() - interval '10 minutes'").fetchone()[0]


def age_everything(conn):
    """Makes every row look ten minutes older than the last download, so nothing counts as changed."""
    conn.execute("update public.places set updated_at = now() - interval '1 hour'")
    conn.execute("update public.place_community set updated_at = now() - interval '1 hour'")
    conn.execute("update public.place_rating_summary set updated_at = now() - interval '1 hour'")


def test_changes_hold_only_what_changed_since_the_last_download(conn, overpass_elements):
    db.import_elements(conn, "utrecht", overpass_elements)
    reported, rated = place_id(conn, 1001), place_id(conn, 2002)
    age_everything(conn)
    since = generated_at(conn)

    unchanged = db.city_changes(conn, "utrecht", since)
    assert unchanged["places"] == [] and unchanged["removed"] == []

    report_as(conn, create_user(conn), reported, wifi="fast")
    rate_as(conn, create_user(conn), rated, 5)
    conn.execute("update public.places set deleted_at = now(), updated_at = now() where osm_id = 3003")
    conn.execute("update public.places set hidden = true where osm_id = 8008")

    changes = db.city_changes(conn, "utrecht", since)
    assert {p["osm_id"] for p in changes["places"]} == {1001, 2002}
    assert sorted(changes["removed"]) == sorted([place_id(conn, 3003), place_id(conn, 8008)])
    changed = {p["osm_id"]: p for p in changes["places"]}
    assert changed[1001]["community"]["wifi"] == "fast"
    assert changed[2002]["rating"] == {"average": 5.0, "count": 1}
    # Same format as the full export, plus what it is relative to.
    full = db.city_export(conn, "utrecht")
    assert changed[1001] == next(p for p in full["places"] if p["osm_id"] == 1001)
    assert changes["since"] is not None and changes["generated_at"] is not None


def test_a_place_whose_last_report_went_counts_as_changed(conn, overpass_elements):
    db.import_elements(conn, "utrecht", overpass_elements)
    cafe = place_id(conn, 1001)
    user = create_user(conn)
    report_as(conn, user, cafe, wifi="fast")
    age_everything(conn)
    since = generated_at(conn)

    conn.execute("delete from public.place_reports where user_id = %s", (user,))

    changed = {p["osm_id"]: p for p in db.city_changes(conn, "utrecht", since)["places"]}
    assert changed[1001]["community"] is None


def test_anyone_may_read_the_changes(conn, overpass_elements):
    db.import_elements(conn, "utrecht", overpass_elements)
    since = generated_at(conn)
    with conn.transaction():
        conn.execute("set local role anon")
        assert len(db.city_changes(conn, "utrecht", since)["places"]) == 4
    assert db.city_changes(conn, "atlantis", since) is None


def test_idle_anonymous_accounts_go_after_thirty_days(conn, overpass_elements):
    db.import_elements(conn, "utrecht", overpass_elements)
    cafe = place_id(conn, 1001)
    idle, recent, reporter, rater = (create_user(conn) for _ in range(4))
    person = create_user(conn, anonymous=False)
    conn.execute(
        "update auth.users set created_at = now() - interval '31 days' where id = any(%s)",
        ([idle, reporter, rater, person],),
    )
    report_as(conn, reporter, cafe, wifi="fast")
    rate_as(conn, rater, cafe, 4)

    with conn.transaction():
        conn.execute("set local role service_role")
        assert conn.execute("select public.delete_idle_anonymous_accounts()").fetchone()[0] == 1

    left = {r[0] for r in conn.execute("select id from auth.users").fetchall()}
    assert idle not in left
    assert {recent, reporter, rater, person} <= left


def test_users_cannot_delete_other_accounts(conn):
    for role in ("anon", "authenticated"):
        with pytest.raises(psycopg.errors.InsufficientPrivilege), conn.transaction():
            conn.execute(f"set local role {role}")
            conn.execute("select public.delete_idle_anonymous_accounts('0 seconds')")


def test_seed_reports_count_at_the_time_of_the_visit(conn, overpass_elements):
    db.import_elements(conn, "utrecht", overpass_elements)
    cafe = place_id(conn, 1001)
    morning, afternoon = (conn.execute(f"select {dutch(at)}").fetchone()[0] for at in ("1 day 10:30", "1 day 15:00"))
    # More than a person may report in a day, all in one batch.
    rows = [
        {"place_id": cafe, "visited_at": morning.isoformat(), "wifi": "fast", "power": "many", "quiet": True},
        *({"place_id": cafe, "visited_at": afternoon.isoformat(), "quiet": False} for _ in range(70)),
    ]
    with conn.transaction():
        conn.execute("set local role service_role")
        assert db.seed_reports(conn, rows) == 71

    community = next(p for p in db.city_export(conn, "utrecht")["places"] if p["osm_id"] == 1001)["community"]
    assert community["wifi"] == "fast"
    assert community["power"] == "many"
    assert community["quiet"] is False
    assert datetime.fromisoformat(community["last_report_at"]) == afternoon
    assert community["quiet_by_daypart"] == {"morning": {"quiet": 1, "busy": 0}, "afternoon": {"quiet": 0, "busy": 70}}


def test_users_cannot_seed_or_report_as_the_seed_account(conn, overpass_elements):
    db.import_elements(conn, "utrecht", overpass_elements)
    cafe = place_id(conn, 1001)
    with pytest.raises(psycopg.errors.InsufficientPrivilege), conn.transaction():
        conn.execute("set local role authenticated")
        db.seed_reports(conn, [{"place_id": cafe, "visited_at": "2026-10-27T10:30:00+01:00", "wifi": "fast"}])

    user = create_user(conn)
    seeder = conn.execute("select public.seed_account_id()").fetchone()[0]
    with pytest.raises(psycopg.errors.InsufficientPrivilege), conn.transaction():
        as_user(conn, user)
        conn.execute("insert into public.place_reports (place_id, user_id, wifi) values (%s, %s, 'fast')", (cafe, seeder))
