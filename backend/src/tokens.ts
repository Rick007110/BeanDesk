// Tokens for anonymous accounts: a JWT (HS256) with the account's id. They do not expire; an
// account that is gone (deleted, or empty for 30 days) is refused by the API.
import { createHmac, timingSafeEqual } from "node:crypto";

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;

const encode = (value: unknown) => Buffer.from(JSON.stringify(value)).toString("base64url");
const sign = (secret: string, data: string) => createHmac("sha256", secret).update(data).digest();

export function signToken(secret: string, accountId: string, now: Date): string {
  const data = `${encode({ alg: "HS256", typ: "JWT" })}.${encode({ sub: accountId, iat: Math.floor(now.getTime() / 1000) })}`;
  return `${data}.${sign(secret, data).toString("base64url")}`;
}

/** The account id in a token we signed, or null. */
export function verifyToken(secret: string, token: string): string | null {
  const parts = token.split(".");
  if (parts.length !== 3) return null;
  const given = Buffer.from(parts[2], "base64url");
  const expected = sign(secret, `${parts[0]}.${parts[1]}`);
  if (given.length !== expected.length || !timingSafeEqual(given, expected)) return null;
  try {
    const { sub } = JSON.parse(Buffer.from(parts[1], "base64url").toString("utf8"));
    return typeof sub === "string" && UUID.test(sub) ? sub : null;
  } catch {
    return null;
  }
}
