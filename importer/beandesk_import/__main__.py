"""Command line entry point. Needs DATABASE_URL.

    python -m beandesk_import import-extract nl-places.json
    python -m beandesk_import export utrecht --out-dir out
    python -m beandesk_import seed-checklist amsterdam-binnenstad --out docs/seeding/amsterdam-binnenstad.csv
    python -m beandesk_import seed-import docs/seeding/amsterdam-binnenstad.csv --dry-run
"""

import argparse
import json
import os
import sys
from pathlib import Path

import psycopg

from . import db, seeding


def _connect() -> psycopg.Connection:
    url = os.environ.get("DATABASE_URL")
    if not url:
        raise SystemExit("Environment variable DATABASE_URL is not set")
    return psycopg.connect(url)


def run_import_extract(args: argparse.Namespace) -> None:
    data = json.loads(Path(args.file).read_text())
    with _connect() as conn:
        result = db.import_extract(conn, data)
    print(json.dumps(result, indent=2))


def run_export(args: argparse.Namespace) -> None:
    with _connect() as conn:
        export = db.city_export(conn, args.city)
    if export is None:
        raise SystemExit(f"Unknown city '{args.city}'")
    content = json.dumps(export, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
    out = Path(args.out_dir) / f"{args.city}.json"
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_bytes(content)
    print(f"{args.city}: {len(export['places'])} places, {len(content) / 1024:.0f} KB, written to {out}")


def run_seed_checklist(args: argparse.Namespace) -> None:
    with _connect() as conn:
        places = seeding.checklist(conn, args.area, args.limit)
    out = Path(args.out)
    seeding.write_checklist(places, out)
    print(f"{args.area}: {len(places)} places written to {out}")


def run_seed_import(args: argparse.Namespace) -> None:
    visits = seeding.read_visits(Path(args.file))
    if visits.errors:
        raise SystemExit("Nothing imported; fix these first:\n" + "\n".join(visits.errors))
    print(f"{len(visits.rows)} visits to import, {visits.skipped} rows not visited yet")
    if args.dry_run or not visits.rows:
        return
    with _connect() as conn:
        inserted = db.seed_reports(conn, visits.rows)
    print(f"{inserted} reports added")


def main(argv: list[str] | None = None) -> None:
    parser = argparse.ArgumentParser(prog="beandesk_import")
    commands = parser.add_subparsers(dest="command", required=True)

    imp = commands.add_parser(
        "import-extract",
        help="Import a file made by osm_extract.py (nl-places.json), as the database does every night",
    )
    imp.add_argument("file")
    imp.set_defaults(func=run_import_extract)

    exp = commands.add_parser("export", help="Write the city export the app downloads to a file")
    exp.add_argument("city")
    exp.add_argument("--out-dir", default="out")
    exp.set_defaults(func=run_export)

    check = commands.add_parser("seed-checklist", help="Write the places to visit in a neighbourhood to a CSV")
    check.add_argument("area", choices=sorted(seeding.AREAS))
    check.add_argument("--limit", type=int, default=50)
    check.add_argument("--out", required=True)
    check.set_defaults(func=run_seed_checklist)

    seed = commands.add_parser("seed-import", help="Add the visits in a filled-in checklist as reports")
    seed.add_argument("file")
    seed.add_argument("--dry-run", action="store_true", help="Only check the file")
    seed.set_defaults(func=run_seed_import)

    args = parser.parse_args(argv)
    args.func(args)


if __name__ == "__main__":
    sys.exit(main())
