// The search flow: everything it needs comes in through Deps, so the tests run it against fakes.
import { type Geocoded, looksLikePlace, parsePdok, parseRequest, pdokUrl, placeSplits, RADIUS_M } from "./lib.ts";

/** The database side; searchStore.ts implements it with the service role. */
export interface Store {
  getGeocode(query: string): Promise<Geocoded | null>;
  putGeocode(query: string, hit: Geocoded): Promise<void>;
  areaExport(lat: number, lng: number, radiusM: number, label: string): Promise<{ places: unknown[] }>;
  /**
   * Places whose name, address, town or postcode holds every word (find_by_name); across the
   * country, or only within [near].
   */
  nameSearch(query: string, label: string, near?: { lat: number; lng: number; radiusM: number }): Promise<{ places: unknown[] }>;
}

export interface Deps {
  store: Store;
  fetch: typeof fetch;
}

const USER_AGENT = "BeanDesk-search/0.1 (+https://github.com/Rick007110/BeanDesk)";

class UpstreamError extends Error {}

const json = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), { status, headers: { "content-type": "application/json" } });

export function createHandler(deps: Deps) {
  return async (req: Request): Promise<Response> => {
    if (req.method !== "POST") return json({ error: "method_not_allowed" }, 405);
    let body: unknown;
    try {
      body = await req.json();
    } catch {
      return json({ error: "bad_request" }, 400);
    }
    const request = parseRequest(body);
    if (!request) return json({ error: "bad_query" }, 400);

    try {
      return await search(deps, request.query, request.display);
    } catch (e) {
      console.error("search failed", e);
      return json({ error: "internal" }, 500);
    }
  };
}

/** Around a town, a name is searched this far out: towns are larger than a search around them. */
export const NAME_NEAR_TOWN_M = 8000;

const byNameResponse = (found: { places: unknown[] }, label: string) =>
  json({ ...found, search: { kind: "naam", label } });

/**
 * One text, several meanings, tried in this order:
 * 1. a place or address ("Amersfoort", "Oudegracht 10 Utrecht"): the places around it;
 * 2. a name, perhaps with the town or postcode ("Starbucks", "coffee company breda"): the places
 *    with that name, town and postcode as OpenStreetMap has them;
 * 3. a name and a town OpenStreetMap does not record for the place ("anne&max zwolle"): the
 *    places with that name around the town;
 * 4. a single word that PDOK only roughly matched, such as a misspelled town: the places around it.
 * Otherwise nothing: better no results than a list that has nothing to do with the text.
 */
async function search(deps: Deps, query: string, display: string): Promise<Response> {
  const [found, byName] = await Promise.all([lookUp(deps, query), deps.store.nameSearch(query, display)]);
  if (found.hit && looksLikePlace(query, found.hit)) return searchArea(deps, found.hit);
  if (byName.places.length > 0) return byNameResponse(byName, display);

  for (const split of placeSplits(query)) {
    const town = (await lookUp(deps, split.place)).hit;
    if (!town || town.kind !== "woonplaats" || !looksLikePlace(split.place, town)) continue;
    const near = await deps.store.nameSearch(split.name, display, { lat: town.lat, lng: town.lng, radiusM: NAME_NEAR_TOWN_M });
    if (near.places.length > 0) return byNameResponse(near, display);
  }

  if (found.hit && !query.includes(" ")) return searchArea(deps, found.hit);
  return found.failed ? json({ error: "geocode_failed" }, 502) : json({ error: "not_found" }, 404);
}

/** The place or address the text stands for: from the cache, else from PDOK (then cached). */
async function lookUp(deps: Deps, query: string): Promise<{ hit: Geocoded | null; failed: boolean }> {
  const cached = await deps.store.getGeocode(query);
  if (cached) return { hit: cached, failed: false };
  try {
    const hit = await geocode(deps, query);
    if (hit) await deps.store.putGeocode(query, hit);
    return { hit, failed: false };
  } catch (e) {
    // The search by name can still answer.
    console.warn("geocoding failed", e);
    return { hit: null, failed: true };
  }
}

/**
 * The places around a found place or address. All of the Netherlands is in the database (the
 * daily extract import), so nothing needs fetching.
 */
async function searchArea(deps: Deps, hit: Geocoded): Promise<Response> {
  const area = await deps.store.areaExport(hit.lat, hit.lng, RADIUS_M[hit.kind], hit.label);
  return json({ ...area, search: { kind: hit.kind, label: hit.label } });
}

async function geocode(deps: Deps, query: string): Promise<Geocoded | null> {
  let response: Response;
  try {
    response = await deps.fetch(pdokUrl(query), {
      headers: { "user-agent": USER_AGENT, accept: "application/json" },
      signal: AbortSignal.timeout(10_000),
    });
  } catch (e) {
    throw new UpstreamError(`PDOK unreachable: ${e}`);
  }
  if (!response.ok) throw new UpstreamError(`PDOK answered ${response.status}`);
  return parsePdok(await response.json());
}
