import { newDb } from 'pg-mem';
import { describe, expect, it } from 'vitest';
import { PgStore } from '../src/pgstore.js';
import { Relay } from '../src/relay.js';
import { createApp } from '../src/app.js';
import { BASE, FakeClock, FakePhone, OWNER_SECRET, type Harness } from './helpers.js';

/**
 * PgStore against pg-mem, an in-process emulation of PostgreSQL (no external service). This verifies the SQL and the
 * Store contract as far as pg-mem emulates Postgres; it is NOT a substitute for a run against a real Postgres
 * (real concurrency, the LIKE ESCAPE default and TLS are not exercised).
 */
async function makeStore(clock = new FakeClock()) {
  const { Pool } = newDb().adapters.createPg();
  const store = new PgStore(new Pool(), () => clock.now());
  await store.init();
  return { store, clock };
}

describe('PgStore (pg-mem)', () => {
  it('get / put / delete / list with prefix (including LIKE metacharacters)', async () => {
    const { store } = await makeStore();
    await store.put('ns', 'dcr:a', { v: 1 });
    await store.put('ns', 'dcr:b', { v: 2 });
    await store.put('ns', 'other', { v: 3 });
    await store.put('ns', 'we%ird_key', { v: 4 });
    await store.put('ns', 'weXirdXkey', { v: 5 });
    expect(await store.get('ns', 'dcr:a')).toEqual({ v: 1 });
    expect((await store.list('ns', { prefix: 'dcr:' })).map((x) => x.key)).toEqual(['dcr:a', 'dcr:b']);
    expect((await store.list('ns', { prefix: 'we%ird' })).map((x) => x.key)).toEqual(['we%ird_key']);
    await store.put('ns', 'dcr:a', { v: 9 });
    expect(await store.get('ns', 'dcr:a')).toEqual({ v: 9 });
    await store.delete('ns', 'dcr:a');
    expect(await store.get('ns', 'dcr:a')).toBeUndefined();
  });

  it('expiry hides entries and purgeExpired removes them', async () => {
    const { store, clock } = await makeStore();
    await store.put('ns', 'k', { v: 1 }, { expiresAt: clock.now() + 1000 });
    await store.put('ns', 'keep', { v: 2 });
    expect(await store.get('ns', 'k')).toEqual({ v: 1 });
    clock.advance(1001);
    expect(await store.get('ns', 'k')).toBeUndefined();
    expect(await store.list('ns')).toHaveLength(1);
    expect(await store.purgeExpired()).toBe(1);
  });

  it('update is an atomic read-modify-write: create, change, leave unchanged, delete, re-create over an expired row', async () => {
    const { store, clock } = await makeStore();
    expect(await store.update<number>('c', 'n', (c) => (c ?? 0) + 1)).toBe(1);
    expect(await store.update<number>('c', 'n', (c) => (c ?? 0) + 1)).toBe(2);
    expect(await store.update<number>('c', 'n', () => undefined)).toBe(2);
    expect(await store.get('c', 'n')).toBe(2);
    expect(await store.update<number>('c', 'n', () => null)).toBeUndefined();
    expect(await store.get('c', 'n')).toBeUndefined();
    await store.put('c', 'e', 1, { expiresAt: clock.now() + 10 });
    clock.advance(11);
    expect(await store.update<number>('c', 'e', (c) => (c ?? 0) + 100, { expiresAt: clock.now() + 1000 })).toBe(100);
    expect(await store.get('c', 'e')).toBe(100);
  });

  it('parallel updates do not lose increments (compare-and-swap on rev)', async () => {
    const { store } = await makeStore();
    await Promise.all(Array.from({ length: 20 }, () => store.update<number>('c', 'n', (c) => (c ?? 0) + 1)));
    expect(await store.get('c', 'n')).toBe(20);
  });

  it('runs the relay end to end on PgStore: pairing, grant, command, signed ack', async () => {
    const { store, clock } = await makeStore();
    const relay = new Relay({ baseUrl: BASE, ownerSecret: OWNER_SECRET, store, clock, pollIntervalMs: 10, requirePhoneApprovalForNewGrants: false });
    const app = createApp(relay);
    const fetchFn = (p: string, i?: RequestInit) => Promise.resolve(app.request(`${BASE}${p}`, i));
    const h: Harness = {
      clock, relay, app, fetch: fetchFn, store: undefined as any, wake: undefined as any,
      json: async (p, i) => { const r = await fetchFn(p, i); return { status: r.status, body: await r.json().catch(() => ({})), headers: r.headers }; },
    };
    const phone = new FakePhone(h);
    await phone.pair();
    await phone.publish();
    const { token } = await relay.auth.createStaticToken('c', ['config:write']);
    const g = await relay.auth.verifyAccess(token);
    const { command } = await relay.enqueue(g, { type: 'config.apply', payload: { ops: [{ type: 'setHabitInterval', id: 'sunscreen', minutes: 60 }] }, baseVersion: 1, idempotencyKey: 'pg-key-0001' });
    expect(await phone.sync()).toBe(1);
    expect((await relay.getCommand(command.id))!.state).toBe('applied');
  });
});
