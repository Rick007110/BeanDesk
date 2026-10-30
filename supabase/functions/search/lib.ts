// Pure helpers for the search function: no Deno or network, so Node can test them too.

/** Searches work in the Netherlands for now (PDOK only knows Dutch places); a margin covers the border. */
const COVERAGE = { south: 50.6, north: 53.7, west: 3.0, east: 7.4 };

export function inCoverage(lat: number, lng: number): boolean {
  return lat >= COVERAGE.south && lat <= COVERAGE.north && lng >= COVERAGE.west && lng <= COVERAGE.east;
}

export type GeoKind = "woonplaats" | "adres" | "postcode" | "weg";

/** How far around a found point places are shown; towns get more than a single address. */
export const RADIUS_M: Record<GeoKind, number> = {
  woonplaats: 4000,
  weg: 2000,
  adres: 1500,
  postcode: 1500,
};

export const GEO_KINDS = Object.keys(RADIUS_M) as GeoKind[];

export function isGeoKind(value: unknown): value is GeoKind {
  return typeof value === "string" && (GEO_KINDS as string[]).includes(value);
}

export interface Geocoded {
  label: string;
  kind: GeoKind;
  lat: number;
  lng: number;
}

export interface Cell {
  lat_index: number;
  lng_index: number;
  south: number;
  west: number;
  north: number;
  east: number;
}

const MAX_QUERY_LENGTH = 100;

/** The cache key of a search: lowercase, single spaces. Null when there is nothing to search for. */
export function normalizeQuery(input: unknown): string | null {
  if (typeof input !== "string") return null;
  const query = input
    .normalize("NFKC")
    // deno-lint-ignore no-control-regex
    .replace(/[\u0000-\u001f\u007f]/g, " ")
    .replace(/\s+/g, " ")
    .trim()
    .toLowerCase();
  if (query.length < 2 || query.length > MAX_QUERY_LENGTH) return null;
  return query;
}

/** PDOK writes points as "POINT(5.09 52.08)", longitude first. */
export function parsePoint(wkt: unknown): { lat: number; lng: number } | null {
  if (typeof wkt !== "string") return null;
  const match = /^POINT\(\s*(-?\d+(?:\.\d+)?)\s+(-?\d+(?:\.\d+)?)\s*\)$/.exec(wkt.trim());
  if (!match) return null;
  return { lat: Number(match[2]), lng: Number(match[1]) };
}

/** A town is called "Utrecht, Utrecht, Utrecht" (town, municipality, province); keep the town. */
export function labelFor(kind: GeoKind, displayName: string): string {
  const name = displayName.trim();
  return kind === "woonplaats" ? name.split(",")[0].trim() : name;
}

/** The URL for the best match of a free-text query. Municipalities are left out: the town is the better centre. */
export function pdokUrl(query: string): string {
  const url = new URL("https://api.pdok.nl/bzk/locatieserver/search/v3_1/free");
  url.searchParams.set("q", query);
  url.searchParams.set("rows", "1");
  url.searchParams.set("fq", `type:(${GEO_KINDS.join(" OR ")})`);
  url.searchParams.set("fl", "weergavenaam,type,centroide_ll");
  return url.toString();
}

/** Reads the first hit of a PDOK answer; null when nothing usable was found. */
export function parsePdok(body: unknown): Geocoded | null {
  // deno-lint-ignore no-explicit-any
  const doc = (body as any)?.response?.docs?.[0];
  if (!doc || !isGeoKind(doc.type) || typeof doc.weergavenaam !== "string") return null;
  const point = parsePoint(doc.centroide_ll);
  if (!point || !inCoverage(point.lat, point.lng)) return null;
  const label = labelFor(doc.type, doc.weergavenaam);
  if (!label) return null;
  return { label, kind: doc.type, lat: point.lat, lng: point.lng };
}

const METERS_PER_DEGREE = 111_320;

function distanceToCellMeters(cell: Cell, lat: number, lng: number): number {
  const dLat = (lat - (cell.south + cell.north) / 2) * METERS_PER_DEGREE;
  const dLng = (lng - (cell.west + cell.east) / 2) * METERS_PER_DEGREE * Math.cos((lat * Math.PI) / 180);
  return Math.hypot(dLat, dLng);
}

/** Keeps the [max] cells nearest to the point; the rest follow with the next search. */
export function pickCells(cells: Cell[], lat: number, lng: number, max: number): { chosen: Cell[]; left: number } {
  const sorted = [...cells].sort((a, b) => distanceToCellMeters(a, lat, lng) - distanceToCellMeters(b, lat, lng));
  return { chosen: sorted.slice(0, max), left: Math.max(0, sorted.length - max) };
}

const bound = (value: number) => value.toFixed(5);

/** One Overpass request for all cells: the same places as the city import, per bounding box. */
export function overpassQuery(cells: Cell[]): string {
  const boxes = cells
    .map((c) => `nwr["amenity"~"^(cafe|library|coworking_space)$"](${bound(c.south)},${bound(c.west)},${bound(c.north)},${bound(c.east)});`)
    .join("");
  return `[out:json][timeout:40];(${boxes});out center tags;`;
}

export interface SearchRequest {
  /** A place or address to look up. */
  query: string | null;
  /** A place that was reported on: its surroundings are fetched again. */
  refreshPlaceId: number | null;
}

/** Accepts {q} or {refresh_place_id}; nothing else is trusted from the app. */
export function parseRequest(body: unknown): SearchRequest | null {
  if (typeof body !== "object" || body === null) return null;
  const { q, refresh_place_id: refresh } = body as Record<string, unknown>;
  if (typeof refresh === "number" && Number.isSafeInteger(refresh) && refresh > 0) {
    return { query: null, refreshPlaceId: refresh };
  }
  const query = normalizeQuery(q);
  return query ? { query, refreshPlaceId: null } : null;
}
