import type pg from "pg";
import { asRole } from "./db.ts";
import type { Store } from "./search/handler.ts";
import { isGeoKind } from "./search/lib.ts";

/** The database side of the search, with the service role: users may not call these directly. */
export function searchStore(pool: pg.Pool): Store {
  const one = <T>(sql: string, values: unknown[]) =>
    asRole(pool, "service_role", null, async (client) => (await client.query(sql, values)).rows[0] as T);

  return {
    async getGeocode(query) {
      const row = await one<{ label: string; kind: string; lat: number; lng: number } | undefined>(
        "select label, kind, lat, lng from public.geocode_cache where query = $1",
        [query],
      );
      return row && isGeoKind(row.kind) ? { label: row.label, kind: row.kind, lat: row.lat, lng: row.lng } : null;
    },
    async putGeocode(query, hit) {
      await one(
        `insert into public.geocode_cache (query, label, kind, lat, lng) values ($1, $2, $3, $4, $5)
         on conflict (query) do update set label = excluded.label, kind = excluded.kind, lat = excluded.lat, lng = excluded.lng`,
        [query, hit.label, hit.kind, hit.lat, hit.lng],
      );
    },
    areaExport: async (lat, lng, radiusM, label) =>
      (await one<{ area: { places: unknown[] } }>("select public.area_export($1, $2, $3, $4) as area", [lat, lng, radiusM, label])).area,
    nameSearch: async (query, label, near) =>
      (
        await one<{ found: { places: unknown[] } }>("select public.find_by_name($1, $2, $3, $4, $5) as found", [
          query,
          label,
          near?.lat ?? null,
          near?.lng ?? null,
          near?.radiusM ?? null,
        ])
      ).found,
  };
}
