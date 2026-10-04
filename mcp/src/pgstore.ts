import type { Store } from './store.js';

/** Minimal subset of `pg`'s Pool used here (keeps this file free of a hard dependency on pg types). */
export interface PgPoolLike {
  query(text: string, params?: unknown[]): Promise<{ rows: any[]; rowCount?: number | null }>;
}

/**
 * Postgres-backed Store (e.g. Render Postgres). One table, `kv(ns, key, value jsonb, rev, expires_at)`.
 * `update` uses optimistic compare-and-swap on `rev` with retries, so it is safe across processes.
 *
 * STATUS: written against the Store contract but NOT run against a real Postgres in this repo
 * (no database was available). Treat as unverified until exercised with DATABASE_URL.
 */
export class PgStore implements Store {
  constructor(private pool: PgPoolLike, private now: () => number = Date.now) {}

  async init(): Promise<void> {
    await this.pool.query(
      `CREATE TABLE IF NOT EXISTS kv (
         ns text NOT NULL, key text NOT NULL, value jsonb NOT NULL,
         rev integer NOT NULL DEFAULT 1, expires_at bigint,
         PRIMARY KEY (ns, key))`,
    );
  }

  async get<T>(ns: string, key: string): Promise<T | undefined> {
    const r = await this.pool.query(
      'SELECT value FROM kv WHERE ns=$1 AND key=$2 AND (expires_at IS NULL OR expires_at > $3)',
      [ns, key, this.now()],
    );
    return r.rows[0]?.value as T | undefined;
  }

  async put<T>(ns: string, key: string, value: T, opts?: { expiresAt?: number }): Promise<void> {
    await this.pool.query(
      `INSERT INTO kv(ns,key,value,expires_at) VALUES($1,$2,$3::jsonb,$4)
       ON CONFLICT (ns,key) DO UPDATE SET value=EXCLUDED.value, expires_at=EXCLUDED.expires_at, rev=kv.rev+1`,
      [ns, key, JSON.stringify(value), opts?.expiresAt ?? null],
    );
  }

  async delete(ns: string, key: string): Promise<void> {
    await this.pool.query('DELETE FROM kv WHERE ns=$1 AND key=$2', [ns, key]);
  }

  async list<T>(ns: string, opts?: { prefix?: string }) {
    const r = await this.pool.query(
      `SELECT key, value FROM kv WHERE ns=$1 AND key LIKE $2 AND (expires_at IS NULL OR expires_at > $3) ORDER BY key`,
      [ns, `${(opts?.prefix ?? '').replace(/[\\%_]/g, '\\$&')}%`, this.now()],
    );
    return r.rows.map((x) => ({ key: x.key as string, value: x.value as T }));
  }

  async update<T>(
    ns: string,
    key: string,
    fn: (cur: T | undefined) => T | null | undefined,
    opts?: { expiresAt?: number },
  ): Promise<T | undefined> {
    for (let attempt = 0; attempt < 8; attempt++) {
      const r = await this.pool.query(
        'SELECT value, rev, expires_at FROM kv WHERE ns=$1 AND key=$2 AND (expires_at IS NULL OR expires_at > $3)',
        [ns, key, this.now()],
      );
      const row = r.rows[0];
      const cur = row?.value as T | undefined;
      const next = fn(cur === undefined ? undefined : structuredClone(cur));
      if (next === undefined) return cur;
      if (next === null) {
        await this.delete(ns, key);
        return undefined;
      }
      const exp = opts?.expiresAt ?? (row?.expires_at != null ? Number(row.expires_at) : null);
      if (!row) {
        const ins = await this.pool.query(
          `INSERT INTO kv(ns,key,value,expires_at) VALUES($1,$2,$3::jsonb,$4)
           ON CONFLICT (ns,key) DO UPDATE SET value=EXCLUDED.value, expires_at=EXCLUDED.expires_at, rev=kv.rev+1
           WHERE kv.expires_at IS NOT NULL AND kv.expires_at <= $5`,
          [ns, key, JSON.stringify(next), exp, this.now()],
        );
        if (ins.rowCount) return next;
      } else {
        const up = await this.pool.query(
          'UPDATE kv SET value=$3::jsonb, expires_at=$4, rev=rev+1 WHERE ns=$1 AND key=$2 AND rev=$5',
          [ns, key, JSON.stringify(next), exp, row.rev],
        );
        if (up.rowCount) return next;
      }
    }
    throw new Error('store update contention');
  }

  async purgeExpired(): Promise<number> {
    const r = await this.pool.query('DELETE FROM kv WHERE expires_at IS NOT NULL AND expires_at <= $1', [this.now()]);
    return r.rowCount ?? 0;
  }
}
