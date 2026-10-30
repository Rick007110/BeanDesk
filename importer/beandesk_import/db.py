"""Thin wrappers around the database functions in database/migrations, which hold every
import and export rule. The nightly import calls the same functions via pg_cron."""

import psycopg
from psycopg.types.json import Jsonb


def import_extract(conn: psycopg.Connection, data: dict) -> dict:
    """Imports a file made by osm_extract.py, as import_osm_file() does every night."""
    return conn.execute("select public.import_osm_extract(%s)", (Jsonb(data),)).fetchone()[0]


def import_elements(conn: psycopg.Connection, city: str, elements: list[dict], force: bool = False) -> dict:
    """Imports OpenStreetMap elements (in the Overpass format) as the places of one city."""
    return conn.execute(
        "select public.import_osm_elements(%s, %s, %s)", (city, Jsonb(elements), force)
    ).fetchone()[0]


def city_export(conn: psycopg.Connection, city: str) -> dict | None:
    """The JSON the app downloads, or None for an unknown city."""
    return conn.execute("select public.city_export(%s)", (city,)).fetchone()[0]


def city_changes(conn: psycopg.Connection, city: str, since) -> dict | None:
    """What changed in a city since an earlier export's generated_at, as the app downloads it."""
    return conn.execute("select public.city_changes(%s, %s)", (city, since)).fetchone()[0]


def seed_reports(conn: psycopg.Connection, rows: list[dict]) -> int:
    """Enters what was seen on visits as reports of the seed account; returns how many went in."""
    return conn.execute("select public.seed_reports(%s)", (Jsonb(rows),)).fetchone()[0]
