"""Thin wrappers around the database functions in supabase/migrations, which hold every
import and export rule. The weekly import on Supabase calls the same functions via pg_cron."""

import psycopg
from psycopg.types.json import Jsonb


def import_city(conn: psycopg.Connection, city: str, force: bool = False) -> dict:
    """Lets the database fetch the city from Overpass itself (needs the http extension)."""
    return conn.execute("select public.import_city(%s, %s)", (city, force)).fetchone()[0]


def import_elements(conn: psycopg.Connection, city: str, elements: list[dict], force: bool = False) -> dict:
    """Imports Overpass elements fetched elsewhere, such as a saved response."""
    return conn.execute(
        "select public.import_osm_elements(%s, %s, %s)", (city, Jsonb(elements), force)
    ).fetchone()[0]


def city_export(conn: psycopg.Connection, city: str) -> dict | None:
    """The JSON the app downloads, or None for an unknown city."""
    return conn.execute("select public.city_export(%s)", (city,)).fetchone()[0]
