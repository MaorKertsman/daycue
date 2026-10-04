import { describe, expect, it } from 'vitest';
import { FakeCompanion, FakePhone, jsonInit, makeHarness, ownerAuth } from './helpers.js';
import { verifyEcdsaP256, bytesToB64u } from '../src/util.js';

describe('owner API', () => {
  it('rejects a wrong owner secret and locks out after repeated failures', async () => {
    const h = makeHarness();
    for (let i = 0; i < 5; i++) {
      const r = await h.json('/v1/owner/pair-codes', { method: 'POST', headers: { authorization: 'Bearer wrong' } });
      expect(r.status).toBe(401);
    }
    const locked = await h.json('/v1/owner/pair-codes', { method: 'POST', headers: ownerAuth });
    expect(locked.status).toBe(429);
    h.clock.advance(61_000);
    expect((await h.json('/v1/owner/pair-codes', { method: 'POST', headers: ownerAuth })).status).toBe(200);
  });
});

describe('phone pairing', () => {
  it('pairs with a one-time code, code is single use and expires', async () => {
    const h = makeHarness();
    const phone = new FakePhone(h);
    await phone.pair();
    expect(phone.token).toMatch(/^dcd_/);
    // single use
    const code = (await h.json('/v1/owner/pair-codes', { method: 'POST', headers: ownerAuth })).body.code;
    const ok = await h.json('/v1/pair/phone', jsonInit('POST', { code, publicKey: phone.key.spki }));
    expect(ok.status).toBe(201);
    const again = await h.json('/v1/pair/phone', jsonInit('POST', { code, publicKey: phone.key.spki }));
    expect(again.status).toBe(403);
    // expiry
    const code2 = (await h.json('/v1/owner/pair-codes', { method: 'POST', headers: ownerAuth })).body.code;
    h.clock.advance(11 * 60_000);
    const late = await h.json('/v1/pair/phone', jsonInit('POST', { code: code2, publicKey: phone.key.spki }));
    expect(late.status).toBe(403);
  });

  it('rejects an invalid public key and a bad credential', async () => {
    const h = makeHarness();
    const code = (await h.json('/v1/owner/pair-codes', { method: 'POST', headers: ownerAuth })).body.code;
    const r = await h.json('/v1/pair/phone', jsonInit('POST', { code, publicKey: 'x'.repeat(100) }));
    expect(r.status).toBe(400);
    expect((await h.json('/v1/phone/commands', { headers: { authorization: 'Bearer dcd_nope' } })).status).toBe(401);
  });

  it('re-pairing replaces the phone, revokes the old credential and expires its pending commands', async () => {
    const h = makeHarness();
    const old = new FakePhone(h);
    await old.pair();
    const { token } = await h.relay.auth.createStaticToken('c', ['config:write']);
    const g = await h.relay.auth.verifyAccess(token);
    const { command } = await h.relay.enqueue(g, { type: 'config.apply', payload: { ops: [{ type: 'X' }] }, idempotencyKey: 'key-repair-1' });
    const neu = new FakePhone(h);
    await neu.pair();
    expect((await h.json('/v1/phone/commands', { headers: old.auth() })).status).toBe(401);
    expect((await h.relay.getCommand(command.id))!.state).toBe('expired');
    expect((await h.json('/v1/phone/commands', { headers: neu.auth() })).status).toBe(200);
  });

  it('companion pairing needs a code from the phone', async () => {
    const h = makeHarness();
    const phone = new FakePhone(h);
    await phone.pair();
    const co = new FakeCompanion(h);
    await co.pair(phone);
    expect(co.token).toMatch(/^dcd_/);
    // a companion credential cannot act as the phone and vice versa
    expect((await h.json('/v1/phone/commands', { headers: { authorization: `Bearer ${co.token}` } })).status).toBe(401);
    expect((await h.json('/v1/companion/signal', jsonInit('POST', {}, phone.auth()))).status).toBe(401);
    // an owner-issued phone code cannot pair a companion
    const phoneCode = (await h.json('/v1/owner/pair-codes', { method: 'POST', headers: ownerAuth })).body.code;
    const bad = await h.json('/v1/pair/companion', jsonInit('POST', { code: phoneCode, publicKey: co.key.spki }));
    expect(bad.status).toBe(403);
  });
});

describe('signatures', () => {
  it('verifies raw and DER ECDSA signatures (Android emits DER)', async () => {
    const kp = await crypto.subtle.generateKey({ name: 'ECDSA', namedCurve: 'P-256' }, true, ['sign', 'verify']);
    const spki = bytesToB64u(new Uint8Array(await crypto.subtle.exportKey('spki', kp.publicKey)));
    const raw = new Uint8Array(await crypto.subtle.sign({ name: 'ECDSA', hash: 'SHA-256' }, kp.privateKey, new TextEncoder().encode('hello')));
    expect(await verifyEcdsaP256(spki, 'hello', bytesToB64u(raw))).toBe(true);
    expect(await verifyEcdsaP256(spki, 'hellO', bytesToB64u(raw))).toBe(false);
    const int = (b: Uint8Array) => {
      let v = b;
      while (v.length > 1 && v[0] === 0) v = v.slice(1);
      if (v[0] & 0x80) v = Uint8Array.from([0, ...v]);
      return [0x02, v.length, ...v];
    };
    const body = [...int(raw.slice(0, 32)), ...int(raw.slice(32))];
    const der = Uint8Array.from([0x30, body.length, ...body]);
    expect(await verifyEcdsaP256(spki, 'hello', bytesToB64u(der))).toBe(true);
  });
});
