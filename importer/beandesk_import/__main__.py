"""Command line entry point. Needs DATABASE_URL.

    python -m beandesk_import import utrecht
    python -m beandesk_import import utrecht --overpass-file saved-response.json
    python -m beandesk_import export utrecht --out-dir out
"""

import argparse
import json
import os
import sys
from pathlib import Path

import psycopg

from . import db


def _connect() -> psycopg.Connection:
    url = os.environ.get("DATABASE_URL")
    if not url:
        raise SystemExit("Environment variable DATABASE_URL is not set")
    return psycopg.connect(url)


def run_import(args: argparse.Namespace) -> None:
    with _connect() as conn:
        if args.overpass_file:
            elements = json.loads(Path(args.overpass_file).read_text())["elements"]
            result = db.import_elements(conn, args.city, elements, force=args.force)
        else:
            result = db.import_city(conn, args.city, force=args.force)
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


def main(argv: list[str] | None = None) -> None:
    parser = argparse.ArgumentParser(prog="beandesk_import")
    commands = parser.add_subparsers(dest="command", required=True)

    imp = commands.add_parser("import", help="Import a city from OpenStreetMap into the database")
    imp.add_argument("city")
    imp.add_argument("--overpass-file", help="Use a saved Overpass JSON response instead of the API")
    imp.add_argument("--force", action="store_true", help="Also when far fewer places come back than before")
    imp.set_defaults(func=run_import)

    exp = commands.add_parser("export", help="Write the city export the app downloads to a file")
    exp.add_argument("city")
    exp.add_argument("--out-dir", default="out")
    exp.set_defaults(func=run_export)

    args = parser.parse_args(argv)
    args.func(args)


if __name__ == "__main__":
    sys.exit(main())
