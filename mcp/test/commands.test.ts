import { describe, expect, it } from 'vitest';
import { FakeCompanion, FakePhone, jsonInit, makeHarness, mcpClient, oauthLogin, ownerAuth, toolJson, toolText, type Harness } from './helpers.js';

async function setup(scopes = ['config:read', 'config:write', 'sessions:control', 'activity:read']) {
  const h = makeHarness();
  const phone = new FakePhone(h);
  await phone.pair();
  await phone.publish();
  const { token } = await h.relay.auth.createStaticToken('test-client', scopes);
  const { client, connect } = mcpClient(h, token);
  await connect();
  const call = async (name: string, args: Record<string, unknown> = {}) => client.callTool({ name, arguments: args });
  return { h, phone, client, call, token };
}
const OPS = [{ type: 'setHabitInterval', id: 'sunscreen', minutes: 90 }];

describe('scenario 15: validated, applied once, acknowledged by the phone', () => {
  it('apply -> phone applies -> tool reports applied with the new version', async () => {
    const { phone, call } = await setup();
    const p = phone.whileWaiting(call('apply_change', { ops: OPS, baseVersion: 1, idempotencyKey: 'apply-0001', waitSeconds: 5 }));
    const res = toolJson(await p);
    expect(res.state).toBe('applied');
    expect(res.applied).toBe(true);
    expect(res.result.newVersion).toBe(2);
    expect(res.summary).toMatch(/^APPLIED/);
    expect(phone.habits[0].intervalMinutes).toBe(90);
  });

  it('same idempotency key submitted twice (and redelivered) is applied exactly once', async () => {
    const { phone, call, h } = await setup();
    const a = toolJson(await call('apply_change', { ops: OPS, baseVersion: 1, idempotencyKey: 'dup-key-001', waitSeconds: 0 }));
    const b = toolJson(await call('apply_change', { ops: OPS, baseVersion: 1, idempotencyKey: 'dup-key-001', waitSeconds: 0 }));
    expect(b.commandId).toBe(a.commandId);
    expect(b.deduplicated).toBe(true);
    await phone.sync();
    await phone.sync(); // redelivery of anything not yet acked must not re-apply (acked ones are not redelivered)
    // simulate a lost ack: the relay still thinks it is delivered and redelivers
    await h.relay.store.update('command', a.commandId, (c: any) => ({ ...c, state: 'delivered', ackedAt: undefined, result: undefined }));
    await phone.sync();
    expect(phone.applyCount).toBe(1);
    expect(phone.version).toBe(2);
    const st = toolJson(await call('get_command_status', { commandId: a.commandId }));
    expect(st.state).toBe('applied');
  });

  it('same key with different content is a conflict', async () => {
    const { call } = await setup();
    await call('apply_change', { ops: OPS, idempotencyKey: 'same-key-01', waitSeconds: 0 });
    const r = await call('apply_change', { ops: [{ type: 'setHabitInterval', id: 'sunscreen', minutes: 30 }], idempotencyKey: 'same-key-01', waitSeconds: 0 });
    expect(r.isError).toBe(true);
    expect(toolText(r)).toContain('idempotency_conflict');
  });

  it('phone validation errors come back as rejected, never applied; stale baseVersion is a conflict', async () => {
    const { phone, call } = await setup();
    let p = phone.whileWaiting(call('apply_change', { ops: [{ type: 'Bogus' }], idempotencyKey: 'bad-ops-001', waitSeconds: 5 }));
    const rej = toolJson(await p);
    expect(rej.state).toBe('rejected');
    expect(rej.applied).toBe(false);
    expect(rej.result.errors[0].code).toBe('unknown_op');
    p = phone.whileWaiting(call('apply_change', { ops: OPS, baseVersion: 0, idempotencyKey: 'stale-base-1', waitSeconds: 5 }));
    const conflict = toolJson(await p);
    expect(conflict.state).toBe('rejected');
    expect(conflict.result.conflict.currentVersion).toBe(1);
    expect(phone.applyCount).toBe(0);
  });

  it('a forged ack (bad signature) cannot mark a command applied', async () => {
    const { phone, call, h } = await setup();
    const a = toolJson(await call('apply_change', { ops: OPS, idempotencyKey: 'forge-key-1', waitSeconds: 0 }));
    const pulled = (await h.json('/v1/phone/commands', { headers: phone.auth() })).body.commands[0];
    const forged = await phone.ack(pulled, 'applied', { newVersion: 99 }, { badSig: true });
    expect(forged.status).toBe(403);
    expect(toolJson(await call('get_command_status', { commandId: a.commandId })).state).toBe('delivered');
  });

  it('sensitive change waits for on-phone confirmation and is reported as awaiting_confirmation', async () => {
    const { phone, call } = await setup();
    const ops = [{ type: 'deleteMedication', id: 'med1' }];
    const p = phone.whileWaiting(call('apply_change', { ops, idempotencyKey: 'sensitive-01', waitSeconds: 5 }));
    const res = toolJson(await p);
    expect(res.state).toBe('awaiting_confirmation');
    expect(res.applied).toBe(false);
    expect(res.summary).toMatch(/confirm/i);
    await phone.confirm(res.commandId, true);
    const done = toolJson(await call('get_command_status', { commandId: res.commandId }));
    expect(done.state).toBe('applied');
    expect(done.result.newVersion).toBe(2);
  });

  it('propose_change is preview only and never changes the phone', async () => {
    const { phone, call } = await setup();
    const p = phone.whileWaiting(call('propose_change', { ops: OPS, baseVersion: 1, idempotencyKey: 'preview-001', waitSeconds: 5 }));
    const res = toolJson(await p);
    expect(res.previewed).toBe(true);
    expect(res.applied).toBe(false);
    expect(res.summary).toMatch(/NOTHING WAS CHANGED/);
    expect(res.result.preview.sensitivity).toBe('ordinary');
    expect(phone.version).toBe(1);
    expect(phone.applyCount).toBe(0);
  });
});

describe('scenario 16: offline phone leaves commands pending, never false success', () => {
  it('reports queued (not applied) with the reason, then applies after the phone comes back', async () => {
    const { phone, call, h } = await setup();
    phone.online = false;
    const t0 = h.clock.now();
    const res = toolJson(await call('apply_change', { ops: OPS, baseVersion: 1, idempotencyKey: 'offline-001', waitSeconds: 2 }));
    expect(res.state).toBe('queued');
    expect(res.applied).toBe(false);
    expect(res.summary).toMatch(/^NOT APPLIED/);
    expect(res.summary).toMatch(/No push wake is configured/);
    expect(h.clock.now()).toBeGreaterThan(t0); // it did wait
    expect(phone.version).toBe(1);
    const status = toolJson(await call('get_status'));
    expect(status.pendingCommands).toHaveLength(1);
    // phone returns
    phone.online = true;
    await phone.sync();
    const done = toolJson(await call('get_command_status', { commandId: res.commandId }));
    expect(done.state).toBe('applied');
    expect(done.applied).toBe(true);
  });

  it('with a registered push token the relay requests an FCM wake (no payload) and says so', async () => {
    const { phone, call, h } = await setup();
    await h.json('/v1/phone/push', { ...jsonInit('PUT', { fcmToken: 'fake-fcm-token' }, phone.auth()) });
    // the relay has a FakeWakeSender but it is not "configured" unless it is not the Noop one: FakeWakeSender counts as configured
    phone.online = false;
    const res = toolJson(await call('apply_change', { ops: OPS, idempotencyKey: 'push-key-01', waitSeconds: 0 }));
    expect(h.wake.sent).toEqual([{ token: 'fake-fcm-token', reason: 'command' }]);
    expect(res.summary).toMatch(/wake push was requested/);
    expect(res.state).toBe('queued');
  });

  it('commands expire; expired is reported as not applied and the phone will not apply it', async () => {
    const { phone, call, h } = await setup();
    phone.online = false;
    const res = toolJson(await call('control_session', { action: 'start', target: { kind: 'routine', id: 'morning' }, idempotencyKey: 'expire-key-1', ttlSeconds: 60, waitSeconds: 0 }));
    expect(res.state).toBe('queued');
    h.clock.advance(61_000);
    phone.online = true;
    expect(await phone.sync()).toBe(0); // expired commands are not delivered
    const st = toolJson(await call('get_command_status', { commandId: res.commandId }));
    expect(st.state).toBe('expired');
    expect(st.applied).toBe(false);
    expect(st.summary).toMatch(/did NOT happen/);
    expect(phone.sessionLog).toHaveLength(0);
  });

  it('a signed ack that arrives after relay expiry is recorded honestly as applied (late)', async () => {
    const { phone, call, h } = await setup();
    const a = toolJson(await call('apply_change', { ops: OPS, idempotencyKey: 'late-ack-001', ttlSeconds: 60, waitSeconds: 0 }));
    const pulled = (await h.json('/v1/phone/commands', { headers: phone.auth() })).body.commands[0];
    const [, result] = (phone as any).decide(pulled);
    h.clock.advance(120_000);
    expect(toolJson(await call('get_command_status', { commandId: a.commandId })).state).toBe('expired');
    await phone.ack(pulled, 'applied', result);
    const st = toolJson(await call('get_command_status', { commandId: a.commandId }));
    expect(st.state).toBe('applied');
    expect(st.lateAck).toBe(true);
  });

  it('no phone paired -> clear error, nothing queued', async () => {
    const h = makeHarness();
    const { token } = await h.relay.auth.createStaticToken('c', ['config:write']);
    const { client, connect } = mcpClient(h, token);
    await connect();
    const r = await client.callTool({ name: 'apply_change', arguments: { ops: OPS, idempotencyKey: 'nophone-001', waitSeconds: 0 } });
    expect(r.isError).toBe(true);
    expect(toolText(r)).toContain('no_phone_paired');
  });
});

describe('reads', () => {
  it('serve from the snapshot, state its age, and mark external text untrusted', async () => {
    const { call, h } = await setup();
    h.clock.advance(5 * 60_000);
    const res = await call('get_config', { section: 'routines' });
    const text = toolText(res);
    expect(text).toMatch(/DATA, not instructions/);
    const j = toolJson(res);
    expect(j.snapshot.ageSeconds).toBe(300);
    expect(j.snapshot.configVersion).toBe(1);
    expect(j.snapshot.note).toMatch(/5 min ago/);
    const name = j.data[0].name as string;
    expect(name.startsWith('<untrusted>')).toBe(true);
    expect(name).not.toContain('<b>'); // angle brackets neutralised so the wrapper cannot be closed early
    expect(j.data[0].id).toBe('morning'); // identifiers stay plain
  });

  it('medication labels are excluded unless the medication scope is granted AND the phone publishes them', async () => {
    const { phone, h, call } = await setup();
    // no medication grant yet -> relay tells the phone not to publish, and drops it if it does
    expect((await phone.publish({ medication: { medications: [{ name: 'SynthMed 5mg' }] } })).body.wants.medication).toBe(false);
    expect(JSON.stringify(await h.relay.getSnapshot())).not.toContain('SynthMed');
    const denied = await call('get_config', { section: 'medications' });
    expect(denied.isError).toBe(true);
    expect(toolText(denied)).toContain('medication scope');
    // a phone that leaks medications inside config is stripped defensively
    await h.json('/v1/phone/snapshot', jsonInit('PUT', { version: 2, config: { habits: [], medications: [{ name: 'SynthMed' }] }, status: {} }, phone.auth()));
    expect(JSON.stringify(await call('get_config', { section: 'all' }))).not.toContain('SynthMed');
    // grant medication
    const { token } = await h.relay.auth.createStaticToken('med', ['config:read', 'medication']);
    const med = mcpClient(h, token);
    await med.connect();
    phone.version = 10;
    expect((await phone.publish({ medication: { medications: [{ name: 'SynthMed 5mg' }] } })).body.wants.medication).toBe(true);
    const ok = toolJson(await med.client.callTool({ name: 'get_config', arguments: { section: 'medications' } }));
    expect(JSON.stringify(ok.data)).toContain('SynthMed');
  });

  it('no snapshot yet -> says so instead of inventing data', async () => {
    const h = makeHarness();
    const phone = new FakePhone(h);
    await phone.pair();
    const { token } = await h.relay.auth.createStaticToken('c', ['config:read']);
    const m = mcpClient(h, token);
    await m.connect();
    const j = toolJson(await m.client.callTool({ name: 'get_config', arguments: { section: 'all' } }));
    expect(j.snapshot.exists).toBe(false);
    expect(j.data).toBe(null);
  });

  it('rejects an older snapshot version', async () => {
    const { phone, h } = await setup();
    await h.json('/v1/phone/snapshot', jsonInit('PUT', { version: 5, config: {}, status: {} }, phone.auth()));
    const r = await h.json('/v1/phone/snapshot', jsonInit('PUT', { version: 3, config: {}, status: {} }, phone.auth()));
    expect(r.status).toBe(409);
  });
});

describe('scope enforcement', () => {
  it('read-only token sees only read tools, and calling a write tool over HTTP is 403 insufficient_scope', async () => {
    const h = makeHarness();
    const r = await oauthLogin(h, { scopes: ['config:read'] });
    const m = mcpClient(h, r.tokens.access_token);
    await m.connect();
    const names = (await m.client.listTools()).tools.map((t) => t.name);
    expect(names).toContain('get_config');
    expect(names).not.toContain('apply_change');
    expect(names).not.toContain('control_session');
    expect(names).not.toContain('get_activity_summary');
    const res = await h.json('/mcp', {
      method: 'POST',
      headers: { 'content-type': 'application/json', accept: 'application/json, text/event-stream', authorization: `Bearer ${r.tokens.access_token}` },
      body: JSON.stringify({ jsonrpc: '2.0', id: 2, method: 'tools/call', params: { name: 'apply_change', arguments: { ops: OPS, idempotencyKey: 'scope-test-1' } } }),
    });
    expect(res.status).toBe(403);
    const wa = res.headers.get('www-authenticate')!;
    expect(wa).toContain('error="insufficient_scope"');
    expect(wa).toContain('scope="config:write"');
  });

  it('sessions:control does not grant config:write and vice versa; command queue enforces it too', async () => {
    const { h } = await setup();
    const { token } = await h.relay.auth.createStaticToken('s', ['sessions:control']);
    const g = await h.relay.auth.verifyAccess(token);
    await expect(h.relay.enqueue(g, { type: 'config.apply', payload: { ops: [] }, idempotencyKey: 'scope-key-001' })).rejects.toMatchObject({ code: 'insufficient_scope' });
    const g2 = await h.relay.auth.verifyAccess((await h.relay.auth.createStaticToken('w', ['config:write'])).token);
    await expect(h.relay.enqueue(g2, { type: 'session.control', payload: {}, idempotencyKey: 'scope-key-002' })).rejects.toMatchObject({ code: 'insufficient_scope' });
    // config:write implies config:read
    const m = mcpClient(h, (await h.relay.auth.createStaticToken('w2', ['config:write'])).token);
    await m.connect();
    expect((await m.client.listTools()).tools.map((t) => t.name)).toContain('get_config');
  });
});

describe('undo and recent changes', () => {
  it('undo reverts through the phone, is only reported once the phone confirms, and shows in recent changes', async () => {
    const { phone, call } = await setup();
    let p = phone.whileWaiting(call('apply_change', { ops: OPS, baseVersion: 1, idempotencyKey: 'undo-apply-01', waitSeconds: 5 }));
    const applied = toolJson(await p);
    expect(applied.applied).toBe(true);
    p = phone.whileWaiting(call('undo_change', { commandId: applied.commandId, idempotencyKey: 'undo-undo-001', waitSeconds: 5 }));
    const undone = toolJson(await p);
    expect(undone.applied).toBe(true);
    expect(phone.habits[0].intervalMinutes).toBe(120);
    expect(phone.version).toBe(3);
    const changes = toolJson(await call('list_recent_changes', { limit: 10 }));
    expect(changes.remoteChanges.map((c: any) => c.type)).toEqual(expect.arrayContaining(['config.apply', 'config.undo']));
    // cannot undo something that never applied
    phone.online = false;
    const pending = toolJson(await call('apply_change', { ops: OPS, idempotencyKey: 'undo-pend-001', waitSeconds: 0 }));
    const bad = await call('undo_change', { commandId: pending.commandId, idempotencyKey: 'undo-pend-002', waitSeconds: 0 });
    expect(bad.isError).toBe(true);
    expect(toolText(bad)).toMatch(/not applied/);
  });
});

describe('revocation of an MCP client', () => {
  it('revoked client token stops working immediately', async () => {
    const { h, token } = await setup();
    const grants = (await h.json('/v1/owner/grants', { headers: ownerAuth })).body.grants;
    await h.json(`/v1/owner/grants/${grants[0].id}`, { method: 'DELETE', headers: ownerAuth });
    const res = await h.json('/v1/client/whoami', jsonInit('POST', {}, { authorization: `Bearer ${token}` }));
    expect(res.status).toBe(401);
  });
});

describe('companion activity signals', () => {
  async function withCompanion() {
    const s = await setup();
    const co = new FakeCompanion(s.h);
    await co.pair(s.phone);
    return { ...s, co };
  }

  it('fresh signal is reported; after ttl the state is unknown, not idle', async () => {
    const { co, call, h } = await withCompanion();
    expect((await co.signal('active', h.clock.now(), 180)).status).toBe(200);
    let a = toolJson(await call('get_activity_summary'));
    expect(a.state).toBe('active');
    expect(a.fresh).toBe(true);
    expect(a.companions[0]).toMatchObject({ companionId: co.id, state: 'active', fresh: true });
    h.clock.advance(181_000);
    a = toolJson(await call('get_activity_summary'));
    expect(a.state).toBe('unknown');
    expect(a.fresh).toBe(false);
    expect(a.note).toMatch(/UNKNOWN/);
  });

  it('rejects forged, replayed, already-expired and out-of-range signals', async () => {
    const { co, h } = await withCompanion();
    expect((await co.signal('idle', h.clock.now(), 180, true)).status).toBe(403); // wrong signature
    expect((await co.signal('active', h.clock.now(), 180)).status).toBe(200);
    expect((await co.signal('idle', h.clock.now(), 180)).status).toBe(409); // not newer
    expect((await co.signal('idle', h.clock.now() - 400_000, 180)).status).toBe(422); // already expired
    expect((await co.signal('idle', h.clock.now() + 10, 5)).status).toBe(400); // ttl too small
    expect((await co.signal('sleeping', h.clock.now() + 20, 60)).status).toBe(400);
  });

  it('phone can fetch the signed signal and companion key to verify independently; wake-on-activity requests a push', async () => {
    const { co, phone, h } = await withCompanion();
    await h.json('/v1/phone/push', jsonInit('PUT', { fcmToken: 'tok', wakeOnActivity: true }, phone.auth()));
    await co.signal('active', h.clock.now(), 180);
    expect(h.wake.sent.some((w) => w.reason === 'activity')).toBe(true);
    const r = await h.json('/v1/phone/activity', { headers: phone.auth() });
    expect(r.body.signal.state).toBe('active');
    expect(r.body.signal.sig).toBeTruthy(); // legacy shape
    expect(r.body.signals[0]).toMatchObject({ companionId: co.id, state: 'active', ttlSeconds: 180, signature: r.body.signal.sig });
    expect(typeof r.body.signals[0].receivedAt).toBe('number');
    expect(r.body.companions[0].publicKey).toBe(co.key.spki);
  });
});

describe('data minimisation / retention', () => {
  it('commands are purged after expiry + retention; audit never contains op payloads', async () => {
    const { call, h } = await setup();
    await call('apply_change', { ops: [{ type: 'setHabitInterval', id: 'secret-habit-id', minutes: 1 }], idempotencyKey: 'retention-01', ttlSeconds: 60, waitSeconds: 0 });
    const audit = JSON.stringify((await h.json('/v1/owner/audit', { headers: ownerAuth })).body);
    expect(audit).not.toContain('secret-habit-id');
    h.clock.advance(60_000 + 14 * 86400_000 + 1000);
    await h.relay.maintenance();
    expect(await h.store.list('command')).toHaveLength(0);
    expect(await h.store.list('idem')).toHaveLength(0);
  });
});
