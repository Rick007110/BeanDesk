// What the server does by itself every night: import the Netherlands, and remove anonymous
// accounts that never reported anything.
import type pg from "pg";
import { asRole } from "./db.ts";

const USER_AGENT = "BeanDesk-import/0.2 (+https://github.com/Rick007110/BeanDesk)";

/**
 * Imports the newest extract (see .github/workflows/osm-data.yml), which the Action publishes
 * every night at 02:23 UTC. Returns what import_osm_extract() counted.
 */
export async function importNetherlands(pool: pg.Pool, fetchFn: typeof fetch, url: string): Promise<unknown> {
  // GitHub answers a release download with a redirect, which fetch follows.
  const response = await fetchFn(url, { headers: { "user-agent": USER_AGENT }, signal: AbortSignal.timeout(180_000) });
  if (!response.ok) throw new Error(`extract download: HTTP ${response.status} for ${url}`);
  const extract = await response.text();
  return asRole(pool, "service_role", null, async (client) =>
    (await client.query("select public.import_osm_extract($1::jsonb) as result", [extract])).rows[0].result
  );
}

export async function deleteIdleAccounts(pool: pg.Pool): Promise<number> {
  return asRole(pool, "service_role", null, async (client) =>
    (await client.query("select public.delete_idle_anonymous_accounts() as deleted", [])).rows[0].deleted
  );
}

/** Runs [task] every day at [hour]:[minute] UTC; a failure is logged and tried again the next day. */
export function everyDayAt(hour: number, minute: number, name: string, task: () => Promise<unknown>): () => void {
  let timer: NodeJS.Timeout;
  const schedule = () => {
    const now = new Date();
    const next = new Date(Date.UTC(now.getUTCFullYear(), now.getUTCMonth(), now.getUTCDate(), hour, minute));
    if (next <= now) next.setUTCDate(next.getUTCDate() + 1);
    timer = setTimeout(async () => {
      try {
        console.log(name, JSON.stringify(await task()));
      } catch (e) {
        console.error(`${name} failed`, e);
      }
      schedule();
    }, next.getTime() - now.getTime());
  };
  schedule();
  return () => clearTimeout(timer);
}
