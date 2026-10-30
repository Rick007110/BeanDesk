// BeanDesk's API. Every rule about data lives in the database (database/migrations); this
// switches to the right role per request, checks tokens and keeps clients within limits.
//
//   GET    /v1/cities/:city                  the city export
//   GET    /v1/cities/:city/changes?since=   only what changed since an earlier export
//   POST   /v1/search          {"q": ...}    places around a town or address, or by name
//   POST   /v1/accounts                      a new anonymous account: {"token": ...}
//   POST   /v1/reports         (token)       what someone saw, and/or their stars
//   GET    /v1/me/contributions (token)      their own reports and stars
//   DELETE /v1/me              (token)       the account, with its reports and stars
import type pg from "pg";
import { asRole } from "./db.ts";
import { RateLimit } from "./rateLimit.ts";
import { createHandler as createSearch } from "./search/handler.ts";
import { searchStore } from "./searchStore.ts";
import { signToken, verifyToken } from "./tokens.ts";

export interface ApiDeps {
  pool: pg.Pool;
  jwtSecret: string;
  // For PDOK, which the search asks.
  fetch: typeof fetch;
  now: () => Date;
  limits?: Partial<Limits>;
}

/** Per IP address. Reports also have a daily limit per account, in the database. */
export interface Limits {
  requestsPerMinute: number;
  accountsPerHour: number;
  searchesPerMinute: number;
}

const DEFAULT_LIMITS: Limits = { requestsPerMinute: 300, accountsPerHour: 30, searchesPerMinute: 60 };

const WIFI = ["none", "yes", "slow", "fast"];
const POWER = ["none", "few", "many"];
const LAPTOP_POLICY = ["allowed", "not_on_weekends", "not_allowed"];

const json = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), { status, headers: { "content-type": "application/json" } });

const error = (code: string, status: number) => json({ error: code }, status);

/** JSON that the database already wrote, passed on as it is. */
const rawJson = (text: string) => new Response(text, { headers: { "content-type": "application/json" } });

class HttpError extends Error {
  readonly status: number;
  readonly code: string;

  constructor(status: number, code: string) {
    super(code);
    this.status = status;
    this.code = code;
  }
}

export function createApi(deps: ApiDeps): (request: Request, ip: string) => Promise<Response> {
  const limits = { ...DEFAULT_LIMITS, ...deps.limits };
  const requests = new RateLimit(limits.requestsPerMinute, 60_000);
  const accounts = new RateLimit(limits.accountsPerHour, 3_600_000);
  const searches = new RateLimit(limits.searchesPerMinute, 60_000);
  const search = createSearch({ store: searchStore(deps.pool), fetch: deps.fetch });
  const { pool } = deps;

  /** The account behind the request's token; refuses a missing or bad token and a gone account. */
  async function account(request: Request): Promise<string> {
    const header = request.headers.get("authorization") ?? "";
    const id = header.startsWith("Bearer ") ? verifyToken(deps.jwtSecret, header.slice(7)) : null;
    if (!id) throw new HttpError(401, "no_account");
    const exists = await asRole(pool, "service_role", null, async (client) =>
      (await client.query("select public.account_exists($1) as exists", [id])).rows[0].exists
    );
    // Deleted, or removed after 30 days without reports: the app then starts a new account.
    if (!exists) throw new HttpError(401, "account_gone");
    return id;
  }

  async function route(request: Request, ip: string): Promise<Response> {
    const now = deps.now().getTime();
    if (!requests.allow(ip, now)) return error("too_many_requests", 429);
    const { pathname, searchParams } = new URL(request.url);
    const method = request.method;

    if (method === "GET" && pathname === "/health") return json({ ok: true });

    const city = /^\/v1\/cities\/([a-z0-9-]{1,60})(\/changes)?$/.exec(pathname);
    if (method === "GET" && city && !city[2]) {
      const { body } = await asRole(pool, "anon", null, async (client) =>
        (await client.query("select public.city_export($1)::text as body", [city[1]])).rows[0]
      );
      return body ? rawJson(body) : error("unknown_city", 404);
    }
    if (method === "GET" && city && city[2]) {
      const since = searchParams.get("since") ?? "";
      if (Number.isNaN(Date.parse(since))) return error("bad_since", 400);
      const { body } = await asRole(pool, "anon", null, async (client) =>
        (await client.query("select public.city_changes($1, $2::timestamptz)::text as body", [city[1], since])).rows[0]
      );
      return body ? rawJson(body) : error("unknown_city", 404);
    }

    if (method === "POST" && pathname === "/v1/search") {
      if (!searches.allow(ip, now)) return error("too_many_requests", 429);
      return search(request);
    }

    if (method === "POST" && pathname === "/v1/accounts") {
      if (!accounts.allow(ip, now)) return error("too_many_requests", 429);
      const id = await asRole(pool, "service_role", null, async (client) =>
        (await client.query("select public.create_anonymous_account() as id", [])).rows[0].id
      );
      return json({ token: signToken(deps.jwtSecret, id, deps.now()) }, 201);
    }

    if (method === "POST" && pathname === "/v1/reports") {
      const id = await account(request);
      const report = parseReport(await request.json().catch(() => null));
      if (!report) return error("bad_report", 400);
      await asRole(pool, "authenticated", id, async (client) => {
        if (report.hasFacts) {
          // user_id and reported_at come from the database.
          await client.query(
            `insert into public.place_reports (place_id, wifi, power, quiet, laptop_policy, permanently_closed)
             values ($1, $2, $3, $4, $5, $6)`,
            [report.placeId, report.wifi, report.power, report.quiet, report.laptopPolicy, report.permanentlyClosed],
          );
        }
        // One rating per user per place: a new one replaces the old.
        if (report.stars !== null) {
          await client.query(
            `insert into public.place_ratings (place_id, rating) values ($1, $2)
             on conflict (place_id, user_id) do update set rating = excluded.rating`,
            [report.placeId, report.stars],
          );
        }
      });
      return json({ ok: true }, 201);
    }

    if (method === "GET" && pathname === "/v1/me/contributions") {
      const id = await account(request);
      // Row level security shows a user only their own reports and stars.
      const result = await asRole(pool, "authenticated", id, async (client) => ({
        reports: (
          await client.query(
            `select r.place_id, r.reported_at, r.wifi, r.power, r.quiet, r.laptop_policy, r.permanently_closed,
                    p.name as place_name
             from public.place_reports r left join public.places p on p.id = r.place_id
             order by r.reported_at desc limit 100`,
          )
        ).rows,
        ratings: (
          await client.query(
            `select r.place_id, r.rating, r.rated_at, p.name as place_name
             from public.place_ratings r left join public.places p on p.id = r.place_id
             order by r.rated_at desc limit 100`,
          )
        ).rows,
      }));
      return json(result);
    }

    if (method === "DELETE" && pathname === "/v1/me") {
      const id = await account(request);
      await asRole(pool, "authenticated", id, (client) => client.query("select public.delete_my_account()"));
      return new Response(null, { status: 204 });
    }

    return error("not_found", 404);
  }

  return async (request, ip) => {
    try {
      return await route(request, ip);
    } catch (e) {
      if (e instanceof HttpError) return error(e.code, e.status);
      const pgError = e as { code?: string; message?: string; constraint?: string };
      // The daily report limit (enforce_report_rate_limit).
      if (pgError.code === "P0001" && pgError.message?.startsWith("rate_limit")) return error("rate_limit", 429);
      if (pgError.code === "23503" && pgError.constraint?.includes("place_id")) return error("unknown_place", 404);
      console.error(request.method, new URL(request.url).pathname, e);
      return error("internal", 500);
    }
  };
}

interface ParsedReport {
  placeId: number;
  wifi: string | null;
  power: string | null;
  quiet: boolean | null;
  laptopPolicy: string | null;
  permanentlyClosed: boolean;
  stars: number | null;
  hasFacts: boolean;
}

/** A report as the app sends it; null when anything in it is not what the database accepts. */
export function parseReport(body: unknown): ParsedReport | null {
  if (typeof body !== "object" || body === null) return null;
  const b = body as Record<string, unknown>;
  const oneOf = (value: unknown, allowed: string[]) =>
    value === undefined || value === null ? null : typeof value === "string" && allowed.includes(value) ? value : undefined;
  const placeId = b.place_id;
  if (typeof placeId !== "number" || !Number.isSafeInteger(placeId) || placeId <= 0) return null;
  const wifi = oneOf(b.wifi, WIFI);
  const power = oneOf(b.power, POWER);
  const laptopPolicy = oneOf(b.laptop_policy, LAPTOP_POLICY);
  if (wifi === undefined || power === undefined || laptopPolicy === undefined) return null;
  const quiet = b.quiet === undefined || b.quiet === null ? null : b.quiet;
  if (quiet !== null && typeof quiet !== "boolean") return null;
  const closed = b.permanently_closed ?? false;
  if (typeof closed !== "boolean") return null;
  const stars = b.stars === undefined || b.stars === null ? null : b.stars;
  if (stars !== null && !(typeof stars === "number" && Number.isInteger(stars) && stars >= 1 && stars <= 5)) return null;
  const hasFacts = wifi !== null || power !== null || quiet !== null || laptopPolicy !== null || closed;
  if (!hasFacts && stars === null) return null;
  return { placeId, wifi, power, quiet, laptopPolicy, permanentlyClosed: closed, stars, hasFacts };
}
