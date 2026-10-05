import { describe, expect, it } from 'vitest';
import { companionRevokeMessage } from '../src/relay.js';
import { FakeCompanion, FakePhone, makeHarness, ownerAuth, sign, type Harness } from './helpers.js';

async function setup() {
  const h = makeHarness();
  const phone = new FakePhone(h);
  await phone.pair();
  const a = new FakeCompanion(h);
  const b = new FakeCompanion(h);
  await a.pair(phone);
  await b.pair(phone);
  await a.signal('active');
  await b.signal('idle');
  return { h, phone, a, b };
}

async function revoke(h: Harness, phone: FakePhone, id: string, o: { signedAt?: number; signWith?: CryptoKeyPair; signId?: string; omitSig?: boolean; omitTime?: boolean; auth?: Record<string, string> } = {}) {
  const signedAt = o.signedAt ?? h.clock.now();
  const headers: Record<string, string> = { ...(o.auth ?? phone.auth()) };
  if (!o.omitTime) headers['x-daycue-signed-at'] = String(signedAt);
  if (!o.omitSig) headers['x-daycue-signature'] = await sign(o.signWith ?? phone.key.kp, companionRevokeMessage(o.signId ?? id, signedAt));
  return h.json(`/v1/phone/companions/${encodeURIComponent(id)}`, { method: 'DELETE', headers });
}

describe('phone revokes a companion', () => {
  it('succeeds: 204, credential dead, slot gone, listing updated, audited, others unaffected', async () => {
    const { h, phone, a, b } = await setup();
    const r = await revoke(h, phone, a.id);
    expect(r.status).toBe(204);
    expect((await a.signal('active', h.clock.now() + 1000)).status).toBe(401);
    expect(await h.store.get('signal', `latest:${a.id}`)).toBeUndefined();
    const act = await h.json('/v1/phone/activity', { headers: phone.auth() });
    expect(act.body.companions.map((c: any) => c.id)).toEqual([b.id]);
    expect(act.body.signals.map((s: any) => s.companionId)).toEqual([b.id]);
    expect((await b.signal('active', h.clock.now() + 1000)).status).toBe(200);
    const audit = (await h.json('/v1/owner/audit', { headers: ownerAuth })).body.entries;
    expect(audit.some((e: any) => e.action === 'companion.phone_revoked' && e.detail.deviceId === a.id)).toBe(true);
  });

  it('rejects an absent or wrong signature and a missing timestamp, changing nothing', async () => {
    const { h, phone, a } = await setup();
    expect((await revoke(h, phone, a.id, { omitSig: true })).status).toBe(403);
    expect((await revoke(h, phone, a.id, { omitTime: true })).status).toBe(400);
    const wrongKey = await (await import('./helpers.js')).genKey();
    expect((await revoke(h, phone, a.id, { signWith: wrongKey.kp })).status).toBe(403);
    expect((await revoke(h, phone, a.id, { signWith: a.key.kp })).status).toBe(403); // the companion's own key is not the phone's
    const other = await revoke(h, phone, a.id, { signId: 'co_other' }); // signature for another companion id
    expect(other.status).toBe(403);
    expect(other.body.error).toBe('bad_signature');
    expect((await a.signal('active', h.clock.now() + 1000)).status).toBe(200);
    const audit = (await h.json('/v1/owner/audit', { headers: ownerAuth })).body.entries;
    expect(audit.some((e: any) => e.action === 'companion.revoke_bad_signature')).toBe(true);
  });

  it('rejects a stale or future timestamp (replayed signature outside the 10 minute window)', async () => {
    const { h, phone, a } = await setup();
    const old = h.clock.now();
    h.clock.t += 11 * 60_000;
    expect((await revoke(h, phone, a.id, { signedAt: old })).status).toBe(400);
    expect((await revoke(h, phone, a.id, { signedAt: h.clock.now() + 11 * 60_000 })).status).toBe(400);
    expect((await a.signal('active', h.clock.now(), 180)).status).toBe(200);
    expect((await revoke(h, phone, a.id)).status).toBe(204);
  });

  it('is idempotent: repeat is 204 with one audit entry; a never-existing id is 404 unknown_companion', async () => {
    const { h, phone, a } = await setup();
    expect((await revoke(h, phone, a.id)).status).toBe(204);
    expect((await revoke(h, phone, a.id)).status).toBe(204);
    const audit = (await h.json('/v1/owner/audit', { headers: ownerAuth })).body.entries;
    expect(audit.filter((e: any) => e.action === 'companion.phone_revoked').length).toBe(1);
    const u = await revoke(h, phone, 'co_doesnotexist');
    expect(u.status).toBe(404);
    expect(u.body.error).toBe('unknown_companion');
    // the phone itself is not a companion
    expect((await revoke(h, phone, phone.deviceId)).status).toBe(404);
    expect((await h.json('/v1/phone/activity', { headers: phone.auth() })).status).toBe(200);
  });

  it('requires the phone credential: none, a companion, the owner secret all get 401', async () => {
    const { h, phone, a, b } = await setup();
    for (const auth of [{}, { authorization: `Bearer ${b.token}` }, ownerAuth]) {
      expect((await revoke(h, phone, a.id, { auth })).status).toBe(401);
    }
    expect((await a.signal('active', h.clock.now() + 1000)).status).toBe(200);
  });

  it("a different phone cannot revoke: an old phone's credential 401, a new phone's credential with the old key 403", async () => {
    const { h, phone, a } = await setup();
    const oldPhone = Object.assign(Object.create(Object.getPrototypeOf(phone)), phone) as FakePhone; // keeps the old token and key
    const next = new FakePhone(h);
    await next.pair('second phone'); // re-pairing revokes the first phone
    expect((await revoke(h, oldPhone, a.id)).status).toBe(401);
    expect((await revoke(h, next, a.id, { signWith: oldPhone.key.kp })).status).toBe(403);
    expect((await a.signal('active', h.clock.now() + 1000)).status).toBe(200);
    expect((await revoke(h, next, a.id)).status).toBe(204);
  });
});
