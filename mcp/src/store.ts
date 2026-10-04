import { readFileSync, writeFileSync, renameSync, existsSync, mkdirSync } from 'node:fs';
import { dirname } from 'node:path';

/**
 * Minimal durable KV abstraction. Everything the relay persists goes through this, so the core is
 * platform-neutral. Implementations: MemoryStore (tests), FileStore (single process + persistent disk,
 * e.g. a Render disk), PostgresStore (src/pgstore.ts, e.g. Render Postgres).
 *
 * `update` is an atomic read-modify-write: `fn` MUST be synchronous. Return the new value, `null` to
 * delete, `undefined` to leave unchanged.
 */
export interface Store {
  get<T>(ns: string, key: string): Promise<T | undefined>;
  put<T>(ns: string, key: string, value: T, opts?: { expiresAt?: number }): Promise<void>;
  delete(ns: string, key: string): Promise<void>;
  list<T>(ns: string, opts?: { prefix?: string }): Promise<Array<{ key: string; value: T }>>;
  update<T>(
    ns: string,
    key: string,
    fn: (cur: T | undefined) => T | null | undefined,
    opts?: { expiresAt?: number },
  ): Promise<T | undefined>;
  /** Remove entries whose expiresAt has passed. */
  purgeExpired(): Promise<number>;
}

interface Entry {
  v: unknown;
  e?: number;
}

export class MemoryStore implements Store {
  protected data = new Map<string, Entry>();
  constructor(protected now: () => number = Date.now) {}
  private k(ns: string, key: string) {
    return `${ns}\u0000${key}`;
  }
  protected changed(): void {}
  private live(k: string): Entry | undefined {
    const e = this.data.get(k);
    if (e && e.e !== undefined && e.e <= this.now()) {
      this.data.delete(k);
      return undefined;
    }
    return e;
  }
  async get<T>(ns: string, key: string) {
    const e = this.live(this.k(ns, key));
    return e ? (structuredClone(e.v) as T) : undefined;
  }
  async put<T>(ns: string, key: string, value: T, opts?: { expiresAt?: number }) {
    this.data.set(this.k(ns, key), { v: structuredClone(value), e: opts?.expiresAt });
    this.changed();
  }
  async delete(ns: string, key: string) {
    this.data.delete(this.k(ns, key));
    this.changed();
  }
  async list<T>(ns: string, opts?: { prefix?: string }) {
    const out: Array<{ key: string; value: T }> = [];
    const p = `${ns}\u0000`;
    for (const k of [...this.data.keys()]) {
      if (!k.startsWith(p)) continue;
      const key = k.slice(p.length);
      if (opts?.prefix && !key.startsWith(opts.prefix)) continue;
      const e = this.live(k);
      if (e) out.push({ key, value: structuredClone(e.v) as T });
    }
    return out.sort((a, b) => (a.key < b.key ? -1 : 1));
  }
  async update<T>(
    ns: string,
    key: string,
    fn: (cur: T | undefined) => T | null | undefined,
    opts?: { expiresAt?: number },
  ) {
    const k = this.k(ns, key);
    const cur = this.live(k);
    const curV = cur ? (structuredClone(cur.v) as T) : undefined;
    const next = fn(curV);
    if (next === undefined) return curV;
    if (next === null) {
      this.data.delete(k);
      this.changed();
      return undefined;
    }
    this.data.set(k, { v: structuredClone(next), e: opts?.expiresAt ?? cur?.e });
    this.changed();
    return structuredClone(next);
  }
  async purgeExpired() {
    let n = 0;
    for (const k of [...this.data.keys()]) {
      const before = this.data.has(k);
      if (before && !this.live(k)) n++;
    }
    if (n) this.changed();
    return n;
  }
}

/**
 * Single-process JSON file store. Writes the whole (small) document atomically (tmp file + rename)
 * after each mutation. Use with a persistent disk. Not safe for multiple processes.
 */
export class FileStore extends MemoryStore {
  constructor(private path: string, now: () => number = Date.now) {
    super(now);
    if (existsSync(path)) {
      const raw = JSON.parse(readFileSync(path, 'utf8')) as Array<[string, Entry]>;
      for (const [k, e] of raw) this.data.set(k, e);
    }
  }
  protected override changed(): void {
    mkdirSync(dirname(this.path), { recursive: true });
    const tmp = `${this.path}.tmp`;
    writeFileSync(tmp, JSON.stringify([...this.data.entries()]), { mode: 0o600 });
    renameSync(tmp, this.path);
  }
}
