"""Runs the import, the report triggers and the export against a real PostGIS database."""

import dataclasses
import json
import uuid

import psycopg
import pytest

from beandesk_import import db
from beandesk_import.export import build_export
from beandesk_import.normalize import normalize_all


def active_places(conn) -> dict[int, dict]:
    with conn.cursor(row_factory=psycopg.rows.dict_row) as cur:
        cur.execute("select osm_id, name, deleted_at, updated_at from public.places order by osm_id")
        return {r["osm_id"]: r for r in cur.fetchall()}


def create_user(conn, anonymous: bool = True) -> uuid.UUID:
    with conn.transaction():
        return conn.execute(
            "insert into auth.users (is_anonymous) values (%s) returning id", (anonymous,)
        ).fetchone()[0]


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


def place_id(conn, osm_id: int) -> int:
    return conn.execute("select id from public.places where osm_id = %s", (osm_id,)).fetchone()[0]


def test_import_inserts_updates_and_soft_deletes(conn, overpass_elements):
    records = normalize_all(overpass_elements)

    first = db.import_places(conn, "utrecht", records)
    assert (first.inserted, first.updated, first.unchanged, first.deleted) == (4, 0, 0, 0)

    again = db.import_places(conn, "utrecht", records)
    assert (again.inserted, again.updated, again.unchanged, again.deleted) == (0, 0, 4, 0)

    # The library got new opening hours and the coworking space is gone from OSM.
    changed = [
        dataclasses.replace(r, opening_hours="Mo-Sa 10:00-18:00") if r.osm_id == 2002 else r
        for r in records
        if r.osm_id != 3003
    ]
    before = active_places(conn)
    third = db.import_places(conn, "utrecht", changed)
    assert (third.inserted, third.updated, third.unchanged, third.deleted) == (0, 1, 2, 1)

    places = active_places(conn)
    assert places[3003]["deleted_at"] is not None
    assert places[2002]["deleted_at"] is None
    # Unchanged rows keep their updated_at, so the app's delta sync skips them.
    assert places[1001]["updated_at"] == before[1001]["updated_at"]
    assert places[2002]["updated_at"] > before[2002]["updated_at"]

    # When the coworking space comes back in OSM, its old row is used again.
    back = db.import_places(conn, "utrecht", records)
    assert (back.inserted, back.updated, back.unchanged) == (0, 2, 2)
    assert active_places(conn)[3003]["deleted_at"] is None


def test_import_refuses_to_delete_most_of_a_city(conn, overpass_elements):
    records = normalize_all(overpass_elements)
    db.import_places(conn, "utrecht", records)

    with pytest.raises(db.ImportAborted):
        db.import_places(conn, "utrecht", records[:1])

    forced = db.import_places(conn, "utrecht", records[:1], force=True)
    assert forced.deleted == 3


def test_reports_update_community_facts(conn, overpass_elements):
    db.import_places(conn, "utrecht", normalize_all(overpass_elements))
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
    db.import_places(conn, "utrecht", normalize_all(overpass_elements))
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
    db.import_places(conn, "utrecht", normalize_all(overpass_elements))
    cafe = place_id(conn, 1001)
    user = create_user(conn, anonymous=True)

    for _ in range(20):
        report_as(conn, user, cafe, quiet=True)
    with pytest.raises(psycopg.errors.RaiseException, match="rate_limit"):
        report_as(conn, user, cafe, quiet=True)


def test_export_includes_community_and_skips_hidden_and_deleted(conn, overpass_elements):
    db.import_places(conn, "utrecht", normalize_all(overpass_elements))
    report_as(conn, create_user(conn), place_id(conn, 1001), laptop_policy="not_on_weekends")
    with conn.transaction():
        conn.execute("update public.places set hidden = true where osm_id = 8008")

    export = build_export("utrecht", "Europe/Amsterdam", db.export_rows(conn, "utrecht"))

    places = {p["osm_id"]: p for p in export["places"]}
    assert sorted(places) == [1001, 2002, 3003]
    assert places[1001]["community"]["laptop_policy"] == "not_on_weekends"
    assert places[1001]["osm"]["wifi"] is True
    assert places[2002]["community"] is None
    assert places[1001]["lat"] == pytest.approx(52.0911)
