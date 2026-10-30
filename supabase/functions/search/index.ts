// Edge function "search": finds the laptop spots around a place or address.
//
// POST {"q": "amersfoort"} → the places around it, in the city-export format (see README).
// The first search of an area fetches it from OpenStreetMap and stores it; after that it comes
// straight from the database. POST {"refresh_place_id": 123} fetches the surroundings of a
// place again after a report, so corrections in OpenStreetMap arrive too.
//
// Runs with the service role: the database functions it calls are not open to app users.
import { createClient } from "npm:@supabase/supabase-js@2.118.0";
import { createHandler, type Store } from "./handler.ts";
import { type Cell, isGeoKind } from "./lib.ts";

const db = createClient(Deno.env.get("SUPABASE_URL")!, Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!, {
  auth: { persistSession: false, autoRefreshToken: false },
});

async function rpc<T>(fn: string, args: Record<string, unknown>): Promise<T> {
  const { data, error } = await db.rpc(fn, args);
  if (error) throw new Error(`${fn}: ${error.message}`);
  return data as T;
}

const store: Store = {
  async getGeocode(query) {
    const { data, error } = await db.from("geocode_cache").select("label, kind, lat, lng").eq("query", query).maybeSingle();
    if (error) throw new Error(`geocode_cache: ${error.message}`);
    return data && isGeoKind(data.kind) ? { label: data.label, kind: data.kind, lat: data.lat, lng: data.lng } : null;
  },
  async putGeocode(query, hit) {
    const { error } = await db.from("geocode_cache").upsert({ query, ...hit });
    if (error) throw new Error(`geocode_cache: ${error.message}`);
  },
  missingCells: (lat, lng, radiusM, maxAge) =>
    rpc<Cell[]>("missing_osm_cells", {
      p_lat: lat,
      p_lng: lng,
      p_radius_m: radiusM,
      p_max_age: maxAge,
    }),
  async fetchesSince(since) {
    const { count, error } = await db
      .from("osm_fetches")
      .select("id", { count: "exact", head: true })
      .gte("fetched_at", since.toISOString());
    if (error) throw new Error(`osm_fetches: ${error.message}`);
    return count ?? 0;
  },
  async recordFetch(cells, elements) {
    const { error } = await db.from("osm_fetches").insert({ cells, elements });
    if (error) throw new Error(`osm_fetches: ${error.message}`);
  },
  async importArea(cells, elements) {
    const summary = await rpc("import_osm_area", {
      p_cells: cells.map(({ lat_index, lng_index }) => ({ lat_index, lng_index })),
      p_elements: elements,
    });
    console.log("import_osm_area", JSON.stringify(summary));
  },
  areaExport: (lat, lng, radiusM, label) =>
    rpc("area_export", { p_lat: lat, p_lng: lng, p_radius_m: radiusM, p_label: label }),
  nameSearch: (query, label, near) =>
    rpc("find_by_name", {
      p_query: query,
      p_label: label,
      p_lat: near?.lat ?? null,
      p_lng: near?.lng ?? null,
      p_radius_m: near?.radiusM ?? null,
    }),
  async placeLocation(placeId) {
    const rows = await rpc<{ lat: number; lng: number }[]>("place_location", { p_place_id: placeId });
    return rows[0] ?? null;
  },
};

Deno.serve(createHandler({ store, fetch, now: () => new Date() }));
