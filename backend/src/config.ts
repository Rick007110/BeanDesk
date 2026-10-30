// Settings from the environment (deploy/.env on the server).

export interface Config {
  databaseUrl: string;
  // Signs the tokens of accounts; at least 32 characters, never in the repository.
  jwtSecret: string;
  port: number;
  host: string;
  // The nightly extract of the Netherlands (.github/workflows/osm-data.yml).
  extractUrl: string;
  // The nightly import and clean-up; off in tests.
  jobs: boolean;
}

export const DEFAULT_EXTRACT_URL = "https://github.com/Rick007110/BeanDesk/releases/download/osm-nl/nl-places.json";

export function readConfig(env: Record<string, string | undefined>): Config {
  const databaseUrl = env.DATABASE_URL;
  const jwtSecret = env.JWT_SECRET;
  if (!databaseUrl) throw new Error("DATABASE_URL is not set");
  if (!jwtSecret || jwtSecret.length < 32) throw new Error("JWT_SECRET must be at least 32 characters");
  return {
    databaseUrl,
    jwtSecret,
    port: Number(env.PORT ?? 8080),
    // Only Caddy, on the same machine, talks to the API.
    host: env.HOST ?? "127.0.0.1",
    extractUrl: env.EXTRACT_URL ?? DEFAULT_EXTRACT_URL,
    jobs: env.JOBS !== "off",
  };
}
