"""Command line entry point.

    python -m beandesk_import import utrecht
    python -m beandesk_import export utrecht --upload

Needs DATABASE_URL (the Supabase connection string); --upload also needs
SUPABASE_URL and SUPABASE_SERVICE_ROLE_KEY.
"""

import argparse
import json
import os
import sys
from pathlib import Path

import psycopg

from . import db, export, overpass
from .cities import get_city
from .normalize import normalize_all


def _env(name: str) -> str:
    value = os.environ.get(name)
    if not value:
        raise SystemExit(f"Environment variable {name} is not set")
    return value


def run_import(args: argparse.Namespace) -> None:
    city = get_city(args.city)
    if args.overpass_file:
        elements = json.loads(Path(args.overpass_file).read_text())["elements"]
    else:
        elements = overpass.fetch_elements(city)
    records = normalize_all(elements)
    print(f"{city.slug}: {len(elements)} OSM elements, {len(records)} places after filtering")

    if args.dry_run:
        return
    with psycopg.connect(_env("DATABASE_URL")) as conn:
        try:
            result = db.import_places(conn, city.slug, records, force=args.force)
        except db.ImportAborted as e:
            raise SystemExit(str(e))
    print(
        f"{city.slug}: {result.inserted} new, {result.updated} changed, "
        f"{result.unchanged} unchanged, {result.deleted} marked deleted"
    )


def run_export(args: argparse.Namespace) -> None:
    city = get_city(args.city)
    with psycopg.connect(_env("DATABASE_URL")) as conn:
        rows = db.export_rows(conn, city.slug)
    content = export.to_json_bytes(export.build_export(city.slug, city.timezone, rows))
    print(f"{city.slug}: {len(rows)} places, {len(content) / 1024:.0f} KB")

    if args.out_dir:
        out = Path(args.out_dir) / f"{city.slug}.json"
        out.parent.mkdir(parents=True, exist_ok=True)
        out.write_bytes(content)
        print(f"Wrote {out}")
    if args.upload:
        export.upload(content, city.slug, _env("SUPABASE_URL"), _env("SUPABASE_SERVICE_ROLE_KEY"))
        print(f"Uploaded {city.slug}.json to Supabase Storage")


def main(argv: list[str] | None = None) -> None:
    parser = argparse.ArgumentParser(prog="beandesk_import")
    commands = parser.add_subparsers(dest="command", required=True)

    imp = commands.add_parser("import", help="Fetch places from OpenStreetMap into the database")
    imp.add_argument("city")
    imp.add_argument("--overpass-file", help="Use a saved Overpass JSON response instead of the API")
    imp.add_argument("--dry-run", action="store_true", help="Fetch and filter, but do not write")
    imp.add_argument("--force", action="store_true", help="Also when far fewer places come back than before")
    imp.set_defaults(func=run_import)

    exp = commands.add_parser("export", help="Write the city export for the app")
    exp.add_argument("city")
    exp.add_argument("--out-dir", help="Also write <city>.json to this directory")
    exp.add_argument("--upload", action="store_true", help="Upload to Supabase Storage")
    exp.set_defaults(func=run_export)

    args = parser.parse_args(argv)
    args.func(args)


if __name__ == "__main__":
    sys.exit(main())
