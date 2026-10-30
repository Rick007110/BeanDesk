// Run with: node --test supabase/functions/search/search.test.ts (Node 22.18 or newer)
import assert from "node:assert/strict";
import { describe, it } from "node:test";
import { createHandler, type Deps, MAX_CELLS, MAX_FETCHES, type Store } from "./handler.ts";
import { type Cell, type Geocoded, labelFor, normalizeQuery, overpassQuery, parsePdok, parseRequest, pickCells } from "./lib.ts";

const cell = (lat_index: number, lng_index: number): Cell => ({
  lat_index,
  lng_index,
  south: lat_index * 0.02,
  west: lng_index * 0.03,
  north: (lat_index + 1) * 0.02,
  east: (lng_index + 1) * 0.03,
});

const pdokAnswer = (doc: Record<string, unknown> | null) => ({ response: { numFound: doc ? 1 : 0, docs: doc ? [doc] : [] } });

const AMERSFOORT = { type: "woonplaats", weergavenaam: "Amersfoort, Amersfoort, Utrecht", centroide_ll: "POINT(5.387 52.156)" };

describe("lib", () => {
  it("normalizes queries to one cache key", () => {
    assert.equal(normalizeQuery("  Oudegracht   10\tUTRECHT "), "oudegracht 10 utrecht");
    assert.equal(normalizeQuery("a"), null);
    assert.equal(normalizeQuery("x".repeat(101)), null);
    assert.equal(normalizeQuery(42), null);
  });

  it("accepts a query or a place to refresh, nothing else", () => {
    assert.deepEqual(parseRequest({ q: "Utrecht" }), { query: "utrecht", refreshPlaceId: null });
    assert.deepEqual(parseRequest({ refresh_place_id: 12 }), { query: null, refreshPlaceId: 12 });
    assert.equal(parseRequest({ refresh_place_id: -1 }), null);
    assert.equal(parseRequest({ lat: 52, lng: 5 }), null);
    assert.equal(parseRequest(null), null);
  });

  it("reads PDOK answers", () => {
    assert.deepEqual(parsePdok(pdokAnswer(AMERSFOORT)), { label: "Amersfoort", kind: "woonplaats", lat: 52.156, lng: 5.387 });
    assert.deepEqual(
      parsePdok(pdokAnswer({ type: "adres", weergavenaam: "Oudegracht 10, 3511AM Utrecht", centroide_ll: "POINT(5.1165 52.0960)" })),
      { label: "Oudegracht 10, 3511AM Utrecht", kind: "adres", lat: 52.096, lng: 5.1165 },
    );
    assert.equal(parsePdok(pdokAnswer(null)), null);
    assert.equal(parsePdok(pdokAnswer({ ...AMERSFOORT, type: "gemeente" })), null);
    assert.equal(parsePdok(pdokAnswer({ ...AMERSFOORT, centroide_ll: "POINT(2.35 48.85)" })), null);
    assert.equal(parsePdok({ error: "x" }), null);
  });

  it("keeps the town of a woonplaats label", () => {
    assert.equal(labelFor("woonplaats", "Utrecht, Utrecht, Utrecht"), "Utrecht");
    assert.equal(labelFor("weg", "Biltstraat, Utrecht"), "Biltstraat, Utrecht");
  });

  it("picks the nearest cells first", () => {
    const far = cell(2604, 175);
    const near = cell(2604, 170);
    const { chosen, left } = pickCells([far, near], 52.09, 5.12, 1);
    assert.deepEqual(chosen, [near]);
    assert.equal(left, 1);
  });

  it("asks Overpass for every cell with the import's filter", () => {
    const query = overpassQuery([cell(2604, 170), cell(2604, 171)]);
    assert.ok(query.startsWith("[out:json]"));
    assert.ok(query.includes('nwr["amenity"~"^(cafe|library|coworking_space)$"](52.08000,5.10000,52.10000,5.13000);'));
    assert.ok(query.includes("(52.08000,5.13000,52.10000,5.16000);"));
    assert.ok(query.endsWith("out center tags;"));
  });
});

/** An in-memory Store and fetch, recording what the handler does. */
function fakes(options: { missing?: Cell[]; fetchesSince?: number; overpass?: "ok" | "down"; pdok?: unknown; places?: unknown[] } = {}) {
  const log: string[] = [];
  const cache = new Map<string, Geocoded>();
  let places = options.places ?? [];
  const store: Store = {
    getGeocode: async (q) => cache.get(q) ?? null,
    putGeocode: async (q, hit) => void cache.set(q, hit),
    missingCells: async (_lat, _lng, radius, maxAge) => {
      log.push(`missing ${radius} ${maxAge ?? "default"}`);
      return options.missing ?? [];
    },
    fetchesSince: async () => options.fetchesSince ?? 0,
    recordFetch: async (cells, elements) => void log.push(`record ${cells} ${elements}`),
    importArea: async (cells, elements) => {
      log.push(`import ${cells.length} ${elements.length}`);
      places = elements;
    },
    areaExport: async (_lat, _lng, radius, label) => ({ version: 1, city: label, radius_m: radius, places }),
    placeLocation: async (id) => (id === 7 ? { lat: 52.09, lng: 5.12 } : null),
  };
  const fetchFake = (async (input: string | URL | Request) => {
    const url = String(input);
    if (url.includes("pdok")) {
      log.push(`fetch ${new URL(url).host}`);
      return Response.json(options.pdok ?? pdokAnswer(AMERSFOORT));
    }
    log.push(`overpass ${new URL(url).host}`);
    if (options.overpass === "down") return new Response("busy", { status: 429 });
    return Response.json({ elements: [{ type: "node", id: 1 }, { type: "node", id: 2 }] });
  }) as typeof fetch;
  const deps: Deps = { store, fetch: fetchFake, now: () => new Date("2026-10-30T12:00:00Z") };
  const call = async (body: unknown) => {
    const response = await createHandler(deps)(new Request("http://x/search", { method: "POST", body: JSON.stringify(body) }));
    return { status: response.status, body: await response.json() };
  };
  return { call, log, cache };
}

describe("search", () => {
  it("geocodes once, fetches missing cells and returns the area", async () => {
    const { call, log, cache } = fakes({ missing: [cell(2607, 179), cell(2608, 179)] });

    const first = await call({ q: " Amersfoort " });
    assert.equal(first.status, 200);
    assert.equal(first.body.city, "Amersfoort");
    assert.equal(first.body.radius_m, 4000);
    assert.equal(first.body.places.length, 2);
    assert.deepEqual(first.body.search, { kind: "woonplaats", label: "Amersfoort", status: "fetched", fetched_cells: 2, pending_cells: 0 });
    assert.ok(cache.has("amersfoort"));
    assert.deepEqual(log, [
      "fetch api.pdok.nl",
      "missing 4000 default",
      "overpass maps.mail.ru",
      "record 2 2",
      "import 2 2",
    ]);

    log.length = 0;
    await call({ q: "amersfoort" });
    assert.ok(!log.includes("fetch api.pdok.nl"), "the second search uses the cache");
  });

  it("serves known areas without Overpass", async () => {
    const { call, log } = fakes({ missing: [], places: [{ id: 1 }] });
    const result = await call({ q: "amersfoort" });
    assert.equal(result.body.search.status, "cached");
    assert.ok(!log.some((line) => line.startsWith("overpass ")));
  });

  it("fetches at most MAX_CELLS cells at a time", async () => {
    const many = Array.from({ length: MAX_CELLS + 5 }, (_, i) => cell(2600 + Math.floor(i / 10), 170 + (i % 10)));
    const { call } = fakes({ missing: many });
    const result = await call({ q: "amersfoort" });
    assert.equal(result.body.search.status, "partial");
    assert.equal(result.body.search.fetched_cells, MAX_CELLS);
    assert.equal(result.body.search.pending_cells, 5);
  });

  it("says nothing was found", async () => {
    const { call } = fakes({ pdok: pdokAnswer(null) });
    assert.deepEqual(await call({ q: "qwxz" }), { status: 404, body: { error: "not_found" } });
  });

  it("tries the next Overpass server and then gives up honestly", async () => {
    const { call, log } = fakes({ missing: [cell(2607, 179)], overpass: "down" });
    const result = await call({ q: "amersfoort" });
    assert.deepEqual(result, { status: 502, body: { error: "fetch_failed" } });
    assert.deepEqual(
      log.filter((line) => line.startsWith("overpass ")),
      ["overpass maps.mail.ru", "overpass overpass.private.coffee", "overpass overpass-api.de"],
    );
    assert.ok(log.includes("record 1 0"), "failed attempts count towards the limit");
  });

  it("still answers from the database when Overpass is down", async () => {
    const { call } = fakes({ missing: [cell(2607, 179)], overpass: "down", places: [{ id: 1 }] });
    const result = await call({ q: "amersfoort" });
    assert.equal(result.status, 200);
    assert.equal(result.body.search.status, "failed");
  });

  it("stops fetching when everyone together asked too much", async () => {
    const { call, log } = fakes({ missing: [cell(2607, 179)], fetchesSince: MAX_FETCHES });
    assert.deepEqual(await call({ q: "amersfoort" }), { status: 429, body: { error: "busy" } });
    assert.ok(!log.some((line) => line.startsWith("overpass ")));
  });

  it("refreshes the cell of a reported place, at most hourly", async () => {
    const { call, log } = fakes({ missing: [cell(2604, 170)] });
    assert.deepEqual(await call({ refresh_place_id: 7 }), { status: 200, body: { status: "fetched", fetched_cells: 1 } });
    assert.ok(log.includes("missing 50 1 hour"));
    assert.equal((await call({ refresh_place_id: 8 })).status, 404);
  });

  it("refuses bad requests", async () => {
    const { call } = fakes();
    assert.equal((await call({ q: "" })).status, 400);
    const get = await createHandler({ store: {} as Store, fetch, now: () => new Date() })(new Request("http://x/search"));
    assert.equal(get.status, 405);
  });
});
