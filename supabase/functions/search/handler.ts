// The search flow, without Deno or Supabase imports: everything it needs comes in through Deps,
// so Node tests run it against fakes.
import {
  type Cell,
  type Geocoded,
  looksLikePlace,
  placeSplits,
  overpassQuery,
  parsePdok,
  parseRequest,
  pdokUrl,
  pickCells,
  RADIUS_M,
} from "./lib.ts";

/** The database side; supabase/functions/search/index.ts implements it with the service role. */
export interface Store {
  getGeocode(query: string): Promise<Geocoded | null>;
  putGeocode(query: string, hit: Geocoded): Promise<void>;
  missingCells(lat: number, lng: number, radiusM: number, maxAge: string): Promise<Cell[]>;
  fetchesSince(since: Date): Promise<number>;
  recordFetch(cells: number, elements: number): Promise<void>;
  importArea(cells: Cell[], elements: unknown[]): Promise<void>;
  areaExport(lat: number, lng: number, radiusM: number, label: string): Promise<{ places: unknown[] }>;
  /**
   * Places whose name, address, town or postcode holds every word (find_by_name); across the
   * country, or only within [near].
   */
  nameSearch(query: string, label: string, near?: { lat: number; lng: number; radiusM: number }): Promise<{ places: unknown[] }>;
  placeLocation(placeId: number): Promise<{ lat: number; lng: number } | null>;
}

/** Our own Overpass server (infra/overpass): its address and the key it wants. */
export interface OwnOverpass {
  url: string;
  key?: string;
}

export interface Deps {
  store: Store;
  fetch: typeof fetch;
  now: () => Date;
  // Asked first when set (secrets OVERPASS_URL and OVERPASS_KEY); the public servers after it.
  ownOverpass?: OwnOverpass;
}

/** One Overpass request covers at most this many cells; the rest follows with the next search. */
export const MAX_CELLS = 48;
/** All users together may start this many Overpass requests per window, to stay a good guest. */
export const MAX_FETCHES = 30;
export const FETCH_WINDOW_MS = 10 * 60 * 1000;
/** After a report, the surroundings of the place are fetched again unless that happened this recently. */
export const REFRESH_MAX_AGE = "1 hour";
/**
 * A search fetches cells older than this. The whole country is imported monthly
 * (import_osm_file), so this only catches a month whose import failed.
 */
export const SEARCH_MAX_AGE = "45 days";

// Tried in this order, each with its own time limit, so a search never waits much longer than
// half a minute: someone is looking at a spinner. The public servers are busy, and when they
// fail the search still answers with what the database has. overpass-api.de answers every
// request from Supabase's network with 406 (an IP block, also on /api/status), so it comes last.
const PUBLIC_OVERPASS = [
  { url: "https://maps.mail.ru/osm/tools/overpass/api/interpreter", timeoutMs: 25_000 },
  { url: "https://overpass.private.coffee/api/interpreter", timeoutMs: 8_000 },
  { url: "https://overpass-api.de/api/interpreter", timeoutMs: 3_000 },
];

interface OverpassEndpoint {
  url: string;
  timeoutMs: number;
  key?: string;
}

/**
 * Our own server first: it has only the Netherlands, is not shared and answers within seconds.
 * When it is down, the public servers still give an answer, with less time each so the whole
 * search stays within half a minute.
 */
export function overpassEndpoints(own?: OwnOverpass): OverpassEndpoint[] {
  if (!own) return PUBLIC_OVERPASS;
  return [
    { url: own.url, timeoutMs: 15_000, key: own.key },
    { url: PUBLIC_OVERPASS[0].url, timeoutMs: 10_000 },
    ...PUBLIC_OVERPASS.slice(1),
  ];
}
const USER_AGENT = "BeanDesk-search/0.1 (+https://github.com/Rick007110/BeanDesk)";

type SyncStatus = "cached" | "fetched" | "partial" | "rate_limited" | "failed";

interface Sync {
  status: SyncStatus;
  fetched: number;
  pending: number;
}

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
      return request.refreshPlaceId !== null
        ? await refresh(deps, request.refreshPlaceId)
        : await search(deps, request.query!, request.display!);
    } catch (e) {
      console.error("search failed", e);
      return json({ error: "internal" }, 500);
    }
  };
}

/** Around a town, a name is searched this far out: towns are larger than a search around them. */
export const NAME_NEAR_TOWN_M = 8000;

const byNameResponse = (found: { places: unknown[] }, label: string) =>
  json({ ...found, search: { kind: "naam", label, status: "cached", fetched_cells: 0, pending_cells: 0 } });

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

async function searchArea(deps: Deps, hit: Geocoded): Promise<Response> {
  const radius = RADIUS_M[hit.kind];
  const sync = await syncCells(deps, hit.lat, hit.lng, radius, SEARCH_MAX_AGE);
  const area = await deps.store.areaExport(hit.lat, hit.lng, radius, hit.label);

  // Nothing known and nothing fetched: say why, so the app does not claim there is no coffee here.
  if (area.places.length === 0 && (sync.status === "failed" || sync.status === "rate_limited")) {
    return sync.status === "failed" ? json({ error: "fetch_failed" }, 502) : json({ error: "busy" }, 429);
  }
  return json({
    ...area,
    search: { kind: hit.kind, label: hit.label, status: sync.status, fetched_cells: sync.fetched, pending_cells: sync.pending },
  });
}

/** A place was reported on: look at OpenStreetMap again for its surroundings, so corrections arrive. */
async function refresh(deps: Deps, placeId: number): Promise<Response> {
  const location = await deps.store.placeLocation(placeId);
  if (!location) return json({ error: "not_found" }, 404);
  const sync = await syncCells(deps, location.lat, location.lng, 50, REFRESH_MAX_AGE);
  return json({ status: sync.status, fetched_cells: sync.fetched });
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

/** Makes sure the cells around a point are in the database; fetches those that are not. */
async function syncCells(deps: Deps, lat: number, lng: number, radiusM: number, maxAge: string): Promise<Sync> {
  const missing = await deps.store.missingCells(lat, lng, radiusM, maxAge);
  if (missing.length === 0) return { status: "cached", fetched: 0, pending: 0 };

  const { chosen, left } = pickCells(missing, lat, lng, MAX_CELLS);
  const since = new Date(deps.now().getTime() - FETCH_WINDOW_MS);
  if ((await deps.store.fetchesSince(since)) >= MAX_FETCHES) {
    return { status: "rate_limited", fetched: 0, pending: missing.length };
  }

  const elements = await fetchOverpass(deps, chosen);
  // Failed attempts count too, so an Overpass that is down is not hammered.
  await deps.store.recordFetch(chosen.length, elements?.length ?? 0);
  if (!elements) return { status: "failed", fetched: 0, pending: missing.length };

  await deps.store.importArea(chosen, elements);
  return { status: left > 0 ? "partial" : "fetched", fetched: chosen.length, pending: left };
}

async function fetchOverpass(deps: Deps, cells: Cell[]): Promise<unknown[] | null> {
  const body = new URLSearchParams({ data: overpassQuery(cells) });
  for (const { url: endpoint, timeoutMs, key } of overpassEndpoints(deps.ownOverpass)) {
    try {
      const headers: Record<string, string> = { "user-agent": USER_AGENT };
      if (key) headers["x-beandesk-key"] = key;
      const response = await deps.fetch(endpoint, {
        method: "POST",
        body,
        headers,
        signal: AbortSignal.timeout(timeoutMs),
      });
      if (!response.ok) {
        console.warn(`Overpass ${endpoint} answered ${response.status}: ${(await response.text()).slice(0, 200)}`);
        continue;
      }
      const payload = await response.json();
      // Overpass reports timeouts and memory errors in a 200 answer.
      if (typeof payload?.remark === "string" && /error/i.test(payload.remark)) continue;
      if (Array.isArray(payload?.elements)) return payload.elements;
    } catch (e) {
      console.warn(`Overpass ${endpoint} failed`, e);
    }
  }
  return null;
}
