// Edge function "search": finds the laptop spots around a place or address.
//
// POST {"q": "amersfoort"} → the places around it, or the places with that name, in the
// city-export format (see README). All of the Netherlands is in the database (the daily extract
// import), so the function only looks the text up at PDOK and asks the database.
//
// Runs with the service role: the database functions it calls are not open to app users.
import { createClient } from "npm:@supabase/supabase-js@2.118.0";
import { createHandler, type Store } from "./handler.ts";
import { isGeoKind } from "./lib.ts";

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
};

Deno.serve(createHandler({ store, fetch }));
