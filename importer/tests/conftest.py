import json
import os
from pathlib import Path

import psycopg
import pytest

TESTS_DIR = Path(__file__).resolve().parent
MIGRATIONS_DIR = TESTS_DIR.parent.parent / "supabase" / "migrations"


@pytest.fixture
def overpass_elements() -> list[dict]:
    return json.loads((TESTS_DIR / "fixtures" / "overpass_sample.json").read_text())["elements"]


@pytest.fixture
def conn():
    """A database with the Supabase stubs and all migrations applied, reset for every test.

    Needs BEANDESK_TEST_DATABASE_URL pointing at a throwaway PostGIS database; skipped otherwise.
    """
    url = os.environ.get("BEANDESK_TEST_DATABASE_URL")
    if not url:
        pytest.skip("BEANDESK_TEST_DATABASE_URL is not set")

    with psycopg.connect(url, autocommit=True) as setup:
        setup.execute("drop schema if exists public, auth, storage, extensions cascade")
        setup.execute("create schema public")
        setup.execute((TESTS_DIR / "sql" / "supabase_stubs.sql").read_text())
        for migration in sorted(MIGRATIONS_DIR.glob("*.sql")):
            setup.execute(migration.read_text())

    # Autocommit, so every `conn.transaction()` in the tests is a real transaction
    # and `set local role` ends with it.
    with psycopg.connect(url, autocommit=True) as connection:
        yield connection
