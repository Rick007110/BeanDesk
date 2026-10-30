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

/** Lowercase without accents, so "cafe" and "Café" compare equal. */
export function foldText(text: string): string {
  return text.normalize("NFD").replace(/\p{Mn}/gu, "").toLowerCase().trim();
}

/** The words of a text, folded, without punctuation: "Oudegracht 10, 3511AM" → oudegracht, 10, 3511am. */
export function wordsOf(text: string): string[] {
  return foldText(text).split(/[^\p{L}\p{N}]+/u).filter((word) => word.length > 0);
}

/**
 * Whether the text was meant as this place or address rather than as (part of) the name of a
 * cafe: every word typed is a word of what PDOK found. "Amersfoort", "biltstraat" for
 * "Biltstraat, Utrecht" and "oudegracht 10 utrecht" are places; "coffeecompany breda" is not,
 * although PDOK finds Breda for it.
 */
export function looksLikePlace(query: string, hit: Geocoded): boolean {
  const label = new Set(wordsOf(hit.label));
  const words = wordsOf(query);
  return words.length > 0 && words.every((word) => label.has(word));
}

/**
 * The ways to read a text as a name followed or preceded by a town, such as "coffeecompany |
 * breda" or "breda | coffee company": up to two words at the end or the start as the town.
 */
export function placeSplits(query: string): { name: string; place: string }[] {
  const words = query.split(" ").filter((word) => word.length > 0);
  const splits: { name: string; place: string }[] = [];
  for (const size of [1, 2]) {
    if (words.length <= size) break;
    splits.push({ name: words.slice(0, -size).join(" "), place: words.slice(-size).join(" ") });
    splits.push({ name: words.slice(size).join(" "), place: words.slice(0, size).join(" ") });
  }
  return splits;
}

export interface SearchRequest {
  /** A name, place or address to look up, normalized (see normalizeQuery). */
  query: string;
  /** The same text as typed, for showing the results under. */
  display: string;
}

/** Accepts {q}; nothing else is trusted from the app. */
export function parseRequest(body: unknown): SearchRequest | null {
  if (typeof body !== "object" || body === null) return null;
  const { q } = body as Record<string, unknown>;
  const query = normalizeQuery(q);
  if (!query) return null;
  // deno-lint-ignore no-control-regex
  const display = (q as string).replace(/[\u0000-\u001f\u007f\s]+/g, " ").trim();
  return { query, display };
}
