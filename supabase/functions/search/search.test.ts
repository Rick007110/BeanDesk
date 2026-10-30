// Run with: node --test supabase/functions/search/search.test.ts (Node 22.18 or newer)
import assert from "node:assert/strict";
import { describe, it } from "node:test";
import { createHandler, type Deps, type Store } from "./handler.ts";
import {
  foldText,
  type Geocoded,
  labelFor,
  looksLikePlace,
  normalizeQuery,
  placeSplits,
  parsePdok,
  parseRequest,
} from "./lib.ts";

const pdokAnswer = (doc: Record<string, unknown> | null) => ({ response: { numFound: doc ? 1 : 0, docs: doc ? [doc] : [] } });

const AMERSFOORT = { type: "woonplaats", weergavenaam: "Amersfoort, Amersfoort, Utrecht", centroide_ll: "POINT(5.387 52.156)" };

describe("lib", () => {
  it("normalizes queries to one cache key", () => {
    assert.equal(normalizeQuery("  Oudegracht   10\tUTRECHT "), "oudegracht 10 utrecht");
    assert.equal(normalizeQuery("a"), null);
    assert.equal(normalizeQuery("x".repeat(101)), null);
    assert.equal(normalizeQuery(42), null);
  });

  it("accepts a query, nothing else", () => {
    assert.deepEqual(parseRequest({ q: " Bonen  & Boeken " }), { query: "bonen & boeken", display: "Bonen & Boeken" });
    assert.equal(parseRequest({ refresh_place_id: 12 }), null);
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
});

/** An in-memory Store and fetch, recording what the handler does. */
function fakes(
  options: {
    pdok?: unknown;
    pdokDown?: boolean;
    places?: unknown[];
    byName?: unknown[];
    // What find_by_name returns when asked near a point (a name with a town).
    byNameNear?: unknown[];
    // PDOK answers per query; the default answers Amersfoort to everything.
    pdokByQuery?: Record<string, Record<string, unknown> | null>;
  } = {},
) {
  const log: string[] = [];
  const cache = new Map<string, Geocoded>();
  const places = options.places ?? [];
  const store: Store = {
    getGeocode: async (q) => cache.get(q) ?? null,
    putGeocode: async (q, hit) => void cache.set(q, hit),
    areaExport: async (_lat, _lng, radius, label) => ({ version: 1, city: label, radius_m: radius, places }),
    nameSearch: async (query, label, near) => {
      log.push(near ? `name near ${query}` : `name ${query}`);
      return { version: 1, city: label, places: (near ? options.byNameNear : options.byName) ?? [] };
    },
  };
  // Only PDOK is asked over the network.
  const fetchFake = (async (input: string | URL | Request) => {
    const url = String(input);
    log.push(`fetch ${new URL(url).host}`);
    if (options.pdokDown) return new Response("down", { status: 503 });
    const q = new URL(url).searchParams.get("q") ?? "";
    if (options.pdokByQuery && q in options.pdokByQuery) return Response.json(pdokAnswer(options.pdokByQuery[q]));
    return Response.json(options.pdok ?? pdokAnswer(AMERSFOORT));
  }) as typeof fetch;
  const deps: Deps = { store, fetch: fetchFake };
  const call = async (body: unknown) => {
    const response = await createHandler(deps)(new Request("http://x/search", { method: "POST", body: JSON.stringify(body) }));
    return { status: response.status, body: await response.json() };
  };
  return { call, log, cache };
}

describe("search", () => {
  it("geocodes once and returns the area from the database", async () => {
    const { call, log, cache } = fakes({ places: [{ id: 1 }, { id: 2 }] });

    const first = await call({ q: " Amersfoort " });
    assert.equal(first.status, 200);
    assert.equal(first.body.city, "Amersfoort");
    assert.equal(first.body.radius_m, 4000);
    assert.equal(first.body.places.length, 2);
    assert.deepEqual(first.body.search, { kind: "woonplaats", label: "Amersfoort" });
    assert.ok(cache.has("amersfoort"));
    assert.deepEqual(log, ["name amersfoort", "fetch api.pdok.nl"]);

    log.length = 0;
    await call({ q: "amersfoort" });
    assert.ok(!log.includes("fetch api.pdok.nl"), "the second search uses the cache");
  });

  it("finds cafes by name when the text is not a place", async () => {
    const street = { type: "weg", weergavenaam: "Starbuckslaan, Ergens", centroide_ll: "POINT(5.1 52.1)" };
    const { call, log } = fakes({ pdok: pdokAnswer(street), byName: [{ id: 1 }, { id: 2 }] });
    const result = await call({ q: "Starbucks" });
    assert.equal(result.status, 200);
    assert.equal(result.body.city, "Starbucks");
    assert.equal(result.body.places.length, 2);
    assert.deepEqual(result.body.search, { kind: "naam", label: "Starbucks" });
    assert.deepEqual(log, ["name starbucks", "fetch api.pdok.nl"]);
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

  it("refuses bad requests", async () => {
    const { call } = fakes();
    assert.equal((await call({ q: "" })).status, 400);
    assert.equal((await call({ refresh_place_id: 7 })).status, 400);
    const get = await createHandler({ store: {} as Store, fetch })(new Request("http://x/search"));
    assert.equal(get.status, 405);
  });
});
