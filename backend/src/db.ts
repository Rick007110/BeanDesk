import pg from "pg";

// Place ids and counts are bigint; as JSON numbers they are what the app expects. They stay far
// below 2^53.
pg.types.setTypeParser(pg.types.builtins.INT8, (value) => Number(value));

/** The roles from database/platform.sql. The API's own database user may only switch to these. */
export type Role = "anon" | "authenticated" | "service_role";

/**
 * Runs [work] in one transaction as [role]. With [accountId], auth.uid() returns it, so the row
 * level security rules in database/migrations apply to that user, as they did on Supabase.
 */
export async function asRole<T>(
  pool: pg.Pool,
  role: Role,
  accountId: string | null,
  work: (client: pg.PoolClient) => Promise<T>,
): Promise<T> {
  const client = await pool.connect();
  try {
    await client.query("begin");
    // The role comes from the fixed list above, never from a request.
    await client.query(`set local role ${role}`);
    const claims = accountId ? { sub: accountId, role } : { role };
    await client.query("select set_config('request.jwt.claims', $1, true)", [JSON.stringify(claims)]);
    const result = await work(client);
    await client.query("commit");
    return result;
  } catch (e) {
    await client.query("rollback").catch(() => {});
    throw e;
  } finally {
    client.release();
  }
}
