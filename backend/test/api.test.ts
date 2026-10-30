// The API against a real database, as the app uses it.
import assert from "node:assert/strict";
import { after, before, beforeEach, describe, it } from "node:test";
import type pg from "pg";
import { createApi, type Limits } from "../src/api.ts";
import { deleteIdleAccounts, importNetherlands } from "../src/jobs.ts";
import { freshDatabase, importFixture, TEST_DATABASE_URL } from "./database.ts";

const SECRET = "a-test-secret-that-is-long-enough-for-hs256";
const UTRECHT = { type: "woonplaats", weergavenaam: "Utrecht, Utrecht, Utrecht", centroide_ll: "POINT(5.1201 52.0911)" };

describe("api", { skip: !TEST_DATABASE_URL && "BEANDESK_TEST_DATABASE_URL is not set" }, () => {
  let admin: pg.Pool;
  let pool: pg.Pool;

  before(async () => {
    ({ admin, api: pool } = await freshDatabase());
  });

  after(async () => {
    await pool?.end();
    await admin?.end();
  });

  beforeEach(async () => {
    await admin.query("truncate public.places, public.geocode_cache, public.osm_cells restart identity cascade");
    await admin.query("delete from auth.users");
    await importFixture(admin);
  });

  const pdok = (async () => Response.json({ response: { numFound: 1, docs: [UTRECHT] } })) as typeof fetch;

  function client(limits: Partial<Limits> = {}) {
    const api = createApi({ pool, jwtSecret: SECRET, fetch: pdok, now: () => new Date(), limits });
    return async (method: string, path: string, options: { body?: unknown; token?: string; ip?: string } = {}) => {
      const headers: Record<string, string> = { "content-type": "application/json" };
      if (options.token) headers.authorization = `Bearer ${options.token}`;
      const response = await api(
        new Request(`http://localhost${path}`, {
          method,
          headers,
          body: options.body === undefined ? undefined : JSON.stringify(options.body),
        }),
        options.ip ?? "203.0.113.1",
      );
      const text = await response.text();
      return { status: response.status, body: text ? JSON.parse(text) : null };
    };
  }

  const placeId = async (osmId: number) =>
    Number((await admin.query("select id from public.places where osm_id = $1", [osmId])).rows[0].id);

  it("serves the city export and what changed since", async () => {
    const call = client();
    const city = await call("GET", "/v1/cities/utrecht");
    assert.equal(city.status, 200);
    assert.equal(city.body.city, "utrecht");
    assert.equal(city.body.places.length, 4);
    assert.equal((await call("GET", "/v1/cities/atlantis")).status, 404);

    await admin.query("update public.places set updated_at = now() - interval '1 hour'");
    const since = new Date(Date.now() - 10 * 60_000).toISOString();
    const changes = await call("GET", `/v1/cities/utrecht/changes?since=${encodeURIComponent(since)}`);
    assert.equal(changes.status, 200);
    assert.deepEqual([changes.body.places, changes.body.removed], [[], []]);
    assert.equal((await call("GET", "/v1/cities/utrecht/changes?since=gisteren")).status, 400);
  });

  it("makes an anonymous account that reports, sees its own reports and deletes itself", async () => {
    const call = client();
    const cafe = await placeId(1001);
    const created = await call("POST", "/v1/accounts");
    assert.equal(created.status, 201);
    const token = created.body.token as string;

    const report = await call("POST", "/v1/reports", { token, body: { place_id: cafe, wifi: "fast", quiet: true, stars: 4 } });
    assert.equal(report.status, 201);
    // Stars again: the new rating replaces the old.
    assert.equal((await call("POST", "/v1/reports", { token, body: { place_id: cafe, stars: 5 } })).status, 201);

    const exported = (await call("GET", "/v1/cities/utrecht")).body.places.find((p: { id: number }) => p.id === cafe);
    assert.equal(exported.community.wifi, "fast");
    assert.deepEqual(exported.rating, { average: 5, count: 1 });

    const mine = await call("GET", "/v1/me/contributions", { token });
    assert.equal(mine.status, 200);
    assert.equal(mine.body.reports.length, 1);
    assert.equal(mine.body.reports[0].place_id, cafe);
    assert.equal(mine.body.reports[0].place_name, "Koffie & Werk");
    assert.equal(mine.body.reports[0].wifi, "fast");
    assert.deepEqual(mine.body.ratings.map((r: { rating: number }) => r.rating), [5]);

    assert.equal((await call("DELETE", "/v1/me", { token })).status, 204);
    assert.deepEqual(await call("GET", "/v1/me/contributions", { token }), { status: 401, body: { error: "account_gone" } });
    const after = (await call("GET", "/v1/cities/utrecht")).body.places.find((p: { id: number }) => p.id === cafe);
    assert.equal(after.community, null);
    assert.equal(after.rating, null);
  });

  it("shows nobody else's reports", async () => {
    const call = client();
    const cafe = await placeId(1001);
    const first = (await call("POST", "/v1/accounts")).body.token;
    const second = (await call("POST", "/v1/accounts")).body.token;
    await call("POST", "/v1/reports", { token: first, body: { place_id: cafe, wifi: "slow" } });
    const theirs = await call("GET", "/v1/me/contributions", { token: second });
    assert.deepEqual(theirs.body, { reports: [], ratings: [] });
  });

  it("refuses reports without a valid token or with wrong values", async () => {
    const call = client();
    const cafe = await placeId(1001);
    const token = (await call("POST", "/v1/accounts")).body.token as string;

    assert.deepEqual(await call("POST", "/v1/reports", { body: { place_id: cafe, wifi: "fast" } }), {
      status: 401,
      body: { error: "no_account" },
    });
    const forged = `${token.slice(0, -4)}AAAA`;
    assert.equal((await call("POST", "/v1/reports", { token: forged, body: { place_id: cafe, wifi: "fast" } })).status, 401);

    for (const body of [
      { place_id: cafe },
      { place_id: cafe, wifi: "supersnel" },
      { place_id: cafe, stars: 6 },
      { place_id: "1", wifi: "fast" },
      { place_id: cafe, quiet: "ja" },
    ]) {
      assert.equal((await call("POST", "/v1/reports", { token, body })).status, 400, JSON.stringify(body));
    }
    assert.deepEqual(await call("POST", "/v1/reports", { token, body: { place_id: 999999, wifi: "fast" } }), {
      status: 404,
      body: { error: "unknown_place" },
    });
  });

  it("keeps the daily limit of the database", async () => {
    const call = client();
    const cafe = await placeId(1001);
    const token = (await call("POST", "/v1/accounts")).body.token as string;
    for (let i = 0; i < 20; i++) {
      assert.equal((await call("POST", "/v1/reports", { token, body: { place_id: cafe, quiet: i % 2 === 0 } })).status, 201);
    }
    assert.deepEqual(await call("POST", "/v1/reports", { token, body: { place_id: cafe, quiet: true } }), {
      status: 429,
      body: { error: "rate_limit" },
    });
  });

  it("limits new accounts and searches per IP address", async () => {
    const call = client({ accountsPerHour: 2, searchesPerMinute: 1 });
    assert.equal((await call("POST", "/v1/accounts")).status, 201);
    assert.equal((await call("POST", "/v1/accounts")).status, 201);
    assert.equal((await call("POST", "/v1/accounts")).status, 429);
    assert.equal((await call("POST", "/v1/accounts", { ip: "203.0.113.2" })).status, 201);

    assert.equal((await call("POST", "/v1/search", { body: { q: "utrecht" } })).status, 200);
    assert.equal((await call("POST", "/v1/search", { body: { q: "utrecht" } })).status, 429);
  });

  it("searches around a town and by name", async () => {
    const call = client();
    const town = await call("POST", "/v1/search", { body: { q: "Utrecht" } });
    assert.equal(town.status, 200);
    assert.deepEqual(town.body.search, { kind: "woonplaats", label: "Utrecht" });
    assert.equal(town.body.places.length, 4);
    const cached = await admin.query("select label from public.geocode_cache where query = 'utrecht'");
    assert.deepEqual(cached.rows, [{ label: "Utrecht" }]);

    const byName = await call("POST", "/v1/search", { body: { q: "koffie werk" } });
    assert.equal(byName.status, 200);
    assert.equal(byName.body.search.kind, "naam");
    assert.deepEqual(byName.body.places.map((p: { name: string }) => p.name), ["Koffie & Werk"]);
  });

  it("answers what it does not know with 404", async () => {
    assert.deepEqual(await client()("GET", "/v1/nothing"), { status: 404, body: { error: "not_found" } });
    assert.deepEqual(await client()("GET", "/health"), { status: 200, body: { ok: true } });
  });

  it("runs as a database user that can only switch to the API roles", async () => {
    await assert.rejects(pool.query("select count(*) from public.place_reports"), /permission denied/);
    await assert.rejects(pool.query("select public.seed_reports('[]')"), /permission denied/);
  });

  it("imports the nightly extract, with the municipality as the city", async () => {
    const extract = {
      generated_at: "2026-10-31T02:30:00+00:00",
      cells: [[2604, 170], [2604, 171]],
      elements: [
        { type: "node", id: 9001, lat: 52.0912, lon: 5.1202, tags: { amenity: "cafe", name: "Nieuw Café" }, gemeente: "Utrecht" },
      ],
    };
    const fetchExtract = (async () => new Response(JSON.stringify(extract))) as typeof fetch;
    // Far fewer places than before: the import keeps the others rather than closing them all.
    const result = (await importNetherlands(pool, fetchExtract, "https://example.com/nl-places.json")) as { inserted: number };
    assert.equal(result.inserted, 1);
    const city = await admin.query("select city from public.places where osm_id = 9001");
    assert.deepEqual(city.rows, [{ city: "utrecht" }]);

    await admin.query("insert into auth.users (is_anonymous, created_at) values (true, now() - interval '31 days')");
    assert.equal(await deleteIdleAccounts(pool), 1);
  });
});
