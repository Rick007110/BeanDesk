// A fresh database for a test: database/platform.sql and every migration, the way
// deploy/migrate.sh sets up the server, plus the API's own database user. Needs
// BEANDESK_TEST_DATABASE_URL pointing at a PostGIS database that may be emptied.
import { readdirSync, readFileSync } from "node:fs";
import { join } from "node:path";
import pg from "pg";
import { parse } from "pg-connection-string";

const DATABASE_DIR = join(import.meta.dirname, "..", "..", "database");
export const FIXTURES = join(import.meta.dirname, "..", "..", "importer", "tests", "fixtures");

export const TEST_DATABASE_URL = process.env.BEANDESK_TEST_DATABASE_URL;

/** As on the server: may only switch to the roles anon, authenticated and service_role. */
export const API_USER = "beandesk_api";

export async function freshDatabase(): Promise<{ admin: pg.Pool; api: pg.Pool }> {
  const admin = new pg.Pool({ connectionString: TEST_DATABASE_URL, max: 2 });
  await admin.query("drop schema if exists public, auth, storage, extensions cascade");
  await admin.query("create schema public");
  await admin.query(readFileSync(join(DATABASE_DIR, "platform.sql"), "utf8"));
  for (const file of readdirSync(join(DATABASE_DIR, "migrations")).filter((f) => f.endsWith(".sql")).sort()) {
    await admin.query(readFileSync(join(DATABASE_DIR, "migrations", file), "utf8"));
  }
  await admin.query(`
    do $$ begin
        create role ${API_USER} login noinherit password 'test';
    exception when duplicate_object then null;
    end $$;
    grant anon, authenticated, service_role to ${API_USER};
  `);
  const api = new pg.Pool({ ...parse(TEST_DATABASE_URL!), user: API_USER, password: "test", max: 4 } as pg.PoolConfig);
  return { admin, api };
}

/** The places of the importer's test fixture, as the city Utrecht. */
export async function importFixture(admin: pg.Pool): Promise<void> {
  const elements = JSON.parse(readFileSync(join(FIXTURES, "overpass_sample.json"), "utf8")).elements;
  await admin.query("select public.import_osm_elements('utrecht', $1::jsonb)", [JSON.stringify(elements)]);
}
