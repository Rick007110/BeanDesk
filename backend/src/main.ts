// Starts the API on 127.0.0.1 (Caddy in front of it does HTTPS) and the nightly tasks.
import http from "node:http";
import pg from "pg";
import { createApi } from "./api.ts";
import { readConfig } from "./config.ts";
import { asRole } from "./db.ts";
import { deleteIdleAccounts, everyDayAt, importNetherlands } from "./jobs.ts";

const MAX_BODY_BYTES = 100_000;

const config = readConfig(process.env);
const pool = new pg.Pool({ connectionString: config.databaseUrl, max: 10 });
const api = createApi({ pool, jwtSecret: config.jwtSecret, fetch, now: () => new Date() });

const server = http.createServer(async (req, res) => {
  try {
    const chunks: Buffer[] = [];
    let size = 0;
    for await (const chunk of req) {
      size += chunk.length;
      if (size > MAX_BODY_BYTES) {
        res.writeHead(413, { "content-type": "application/json" }).end('{"error":"too_large"}');
        return;
      }
      chunks.push(chunk);
    }
    const headers = new Headers();
    for (const [name, value] of Object.entries(req.headers)) {
      if (typeof value === "string") headers.set(name, value);
    }
    const body = req.method === "GET" || req.method === "HEAD" ? undefined : Buffer.concat(chunks);
    // Caddy puts the client's address first in X-Forwarded-For and drops what the client sent.
    const ip = (req.headers["x-forwarded-for"] as string | undefined)?.split(",")[0].trim() || req.socket.remoteAddress || "?";
    const response = await api(new Request(`http://localhost${req.url}`, { method: req.method, headers, body }), ip);
    res.writeHead(response.status, Object.fromEntries(response.headers));
    res.end(Buffer.from(await response.arrayBuffer()));
  } catch (e) {
    console.error("request failed", e);
    if (!res.headersSent) res.writeHead(500, { "content-type": "application/json" });
    res.end('{"error":"internal"}');
  }
});

server.listen(config.port, config.host, () => console.log(`BeanDesk API on http://${config.host}:${config.port}`));

if (config.jobs) {
  const importNow = () => importNetherlands(pool, fetch, config.extractUrl);
  // The Action publishes a new extract at 02:23 UTC; import it at 05:23.
  everyDayAt(5, 23, "import_netherlands", importNow);
  everyDayAt(4, 43, "delete_idle_accounts", () => deleteIdleAccounts(pool));
  // A new, empty database gets its places right away instead of the next night.
  const { count } = await asRole(pool, "anon", null, async (client) =>
    (await client.query("select count(*)::int as count from public.places", [])).rows[0]
  );
  if (count === 0) {
    importNow().then(
      (result) => console.log("first import", JSON.stringify(result)),
      (e) => console.error("first import failed", e),
    );
  }
}
