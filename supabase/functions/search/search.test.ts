// Run with: node --test supabase/functions/search/search.test.ts (Node 22.18 or newer)
import assert from "node:assert/strict";
import { describe, it } from "node:test";
import { createHandler, type Deps, MAX_CELLS, MAX_FETCHES, type Store } from "./handler.ts";
import {
  type Cell,
  foldText,
  type Geocoded,
  labelFor,
  looksLikePlace,
  normalizeQuery,
  placeSplits,
  overpassQuery,
  parsePdok,
  parseRequest,
  pickCells,
} from "./lib.ts";

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
    assert.deepEqual(parseRequest({ q: " Bonen  & Boeken " }), { query: "bonen & boeken", display: "Bonen & Boeken", refreshPlaceId: null });
    assert.deepEqual(parseRequest({ refresh_place_id: 12 }), { query: null, display: null, refreshPlaceId: 12 });
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

  it("tells a place from the name of a cafe", () => {
    const town: Geocoded = { label: "Amersfoort", kind: "woonplaats", lat: 52.15, lng: 5.38 };
    const street: Geocoded = { label: "Biltstraat, Utrecht", kind: "weg", lat: 52.09, lng: 5.13 };
    const address: Geocoded = { label: "Oudegracht 10, 3511AM Utrecht", kind: "adres", lat: 52.09, lng: 5.11 };
    assert.ok(looksLikePlace("amersfoort", town));
    assert.ok(looksLikePlace("biltstraat", street));
    assert.ok(looksLikePlace("oudegracht 10 utrecht", address));
    // PDOK finds something for almost anything; "bonen" is not "Bonenburgerweg".
    assert.ok(!looksLikePlace("bonen", { ...street, label: "Bonenburgerweg, Ergens" }));
    assert.ok(!looksLikePlace("starbucks", town));
    assert.ok(!looksLikePlace("coffeecompany breda", { ...town, label: "Breda" }));
    assert.equal(foldText(" Café Été "), "cafe ete");
  });

  it("reads a text as a name and a town in a few ways", () => {
    assert.deepEqual(placeSplits("coffee company breda"), [
      { name: "coffee company", place: "breda" },
      { name: "company breda", place: "coffee" },
      { name: "coffee", place: "company breda" },
      { name: "breda", place: "coffee company" },
    ]);
    assert.deepEqual(placeSplits("starbucks"), []);
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
function fakes(
  options: {
    missing?: Cell[];
    fetchesSince?: number;
    overpass?: "ok" | "down";
    pdok?: unknown;
    pdokDown?: boolean;
    places?: unknown[];
    byName?: unknown[];
    // What find_by_name returns when asked near a point (a name with a town).
    byNameNear?: unknown[];
    // PDOK answers per query; the default answers Amersfoort to everything.
    pdokByQuery?: Record<string, Record<string, unknown> | null>;
    ownOverpass?: { url: string; key?: string };
    // Our own server is down, the public ones are not.
    ownOverpassDown?: boolean;
  } = {},
) {
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
    nameSearch: async (query, label, near) => {
      log.push(near ? `name near ${query}` : `name ${query}`);
      return { version: 1, city: label, places: (near ? options.byNameNear : options.byName) ?? [] };
    },
    placeLocation: async (id) => (id === 7 ? { lat: 52.09, lng: 5.12 } : null),
  };
  const fetchFake = (async (input: string | URL | Request, init?: RequestInit) => {
    const url = String(input);
    if (url.includes("pdok")) {
      log.push(`fetch ${new URL(url).host}`);
      if (options.pdokDown) return new Response("down", { status: 503 });
      const q = new URL(url).searchParams.get("q") ?? "";
      if (options.pdokByQuery && q in options.pdokByQuery) return Response.json(pdokAnswer(options.pdokByQuery[q]));
      return Response.json(options.pdok ?? pdokAnswer(AMERSFOORT));
    }
    const key = new Headers(init?.headers).get("x-beandesk-key");
    log.push(`overpass ${new URL(url).host}${key ? ` key ${key}` : ""}`);
    if (options.overpass === "down") return new Response("busy", { status: 429 });
    if (options.ownOverpassDown && url === options.ownOverpass?.url) throw new TypeError("connection refused");
    return Response.json({ elements: [{ type: "node", id: 1 }, { type: "node", id: 2 }] });
  }) as typeof fetch;
  const deps: Deps = {
    store,
    fetch: fetchFake,
    now: () => new Date("2026-10-30T12:00:00Z"),
    ownOverpass: options.ownOverpass,
  };
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
      "name amersfoort",
      "fetch api.pdok.nl",
      "missing 4000 45 days",
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

  it("finds cafes by name when the text is not a place", async () => {
    const street = { type: "weg", weergavenaam: "Starbuckslaan, Ergens", centroide_ll: "POINT(5.1 52.1)" };
    const { call, log } = fakes({ pdok: pdokAnswer(street), byName: [{ id: 1 }, { id: 2 }] });
    const result = await call({ q: "Starbucks" });
    assert.equal(result.status, 200);
    assert.equal(result.body.city, "Starbucks");
    assert.equal(result.body.places.length, 2);
    assert.equal(result.body.search.kind, "naam");
    assert.ok(!log.some((line) => line.startsWith("missing")), "no area is fetched");
  });

  it("prefers a clear place over cafes with that word in their name", async () => {
    const { call } = fakes({ byName: [{ id: 1 }], places: [{ id: 5 }] });
    const result = await call({ q: "Amersfoort" });
    assert.equal(result.body.search.kind, "woonplaats");
  });

  it("finds a name around a town that OpenStreetMap does not record for the place", async () => {
    const zwolle = { type: "woonplaats", weergavenaam: "Zwolle, Zwolle, Overijssel", centroide_ll: "POINT(6.09 52.51)" };
    const { call, log } = fakes({
      pdokByQuery: { "anne&max zwolle": zwolle, zwolle },
      byNameNear: [{ id: 1 }],
    });
    const result = await call({ q: "Anne&Max Zwolle" });
    assert.equal(result.status, 200);
    assert.equal(result.body.search.kind, "naam");
    assert.ok(log.includes("name near anne&max"));
  });

  it("shows nothing rather than a whole town for a name it cannot find", async () => {
    // PDOK finds Breda for "coffeecompany breda", but there is no such cafe in Breda.
    const breda = { type: "woonplaats", weergavenaam: "Breda, Breda, Noord-Brabant", centroide_ll: "POINT(4.78 51.59)" };
    const { call } = fakes({ pdok: pdokAnswer(breda), places: [{ id: 5 }] });
    assert.deepEqual(await call({ q: "coffeecompany breda" }), { status: 404, body: { error: "not_found" } });
  });

  it("still finds by name when PDOK is down", async () => {
    assert.equal((await fakes({ pdokDown: true, byName: [{ id: 1 }] }).call({ q: "starbucks" })).status, 200);
    assert.deepEqual(await fakes({ pdokDown: true }).call({ q: "starbucks" }), { status: 502, body: { error: "geocode_failed" } });
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

  it("asks our own Overpass server first, with its key", async () => {
    const own = { url: "http://203.0.113.7:8090/api/interpreter", key: "geheim" };
    const { call, log } = fakes({ missing: [cell(2607, 179)], ownOverpass: own });
    const result = await call({ q: "amersfoort" });
    assert.equal(result.body.search.status, "fetched");
    assert.deepEqual(log.filter((line) => line.startsWith("overpass ")), ["overpass 203.0.113.7:8090 key geheim"]);
  });

  it("falls back to the public servers when our own is down, without sending them the key", async () => {
    const own = { url: "http://203.0.113.7:8090/api/interpreter", key: "geheim" };
    const { call, log } = fakes({ missing: [cell(2607, 179)], ownOverpass: own, ownOverpassDown: true });
    const result = await call({ q: "amersfoort" });
    assert.equal(result.body.search.status, "fetched");
    assert.deepEqual(log.filter((line) => line.startsWith("overpass ")), [
      "overpass 203.0.113.7:8090 key geheim",
      "overpass maps.mail.ru",
    ]);
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
