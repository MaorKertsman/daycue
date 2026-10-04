import { existsSync, readFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';
import { CONFIG_OP_TYPES, OPS_EXAMPLES_TEXT, OP_EXAMPLES } from '../src/tools.js';
import { FakeCompanion, FakePhone, makeHarness, mcpClient, ownerAuth, toolJson } from './helpers.js';

async function setup(scopes = ['config:read', 'config:write', 'sessions:control', 'activity:read']) {
  const h = makeHarness();
  const phone = new FakePhone(h);
  await phone.pair();
  await phone.publish();
  const { token } = await h.relay.auth.createStaticToken('test-client', scopes);
  const { client, connect } = mcpClient(h, token);
  await connect();
  const call = async (name: string, args: Record<string, unknown> = {}) => client.callTool({ name, arguments: args });
  return { h, phone, client, call };
}

describe('several companions', () => {
  async function two() {
    const s = await setup();
    const a = new FakeCompanion(s.h);
    const b = new FakeCompanion(s.h);
    await a.pair(s.phone);
    await b.pair(s.phone);
    return { ...s, a, b };
  }

  it('replay rule is independent per companion', async () => {
    const { a, b, h } = await two();
    const t = h.clock.now();
    expect((await a.signal('active', t, 180)).status).toBe(200);
    expect((await b.signal('idle', t, 180)).status).toBe(200); // same observedAt, other companion: fine
    expect((await a.signal('idle', t, 180)).status).toBe(409);
    expect((await b.signal('active', t - 1000, 180)).status).toBe(409);
    expect((await b.signal('active', t + 1, 180)).status).toBe(200);
  });

  it('combined state: active > idle > locked > asleep; an expired companion is ignored', async () => {
    const { a, b, call, h } = await two();
    await a.signal('locked', h.clock.now(), 60);
    await b.signal('asleep', h.clock.now(), 600);
    let r = toolJson(await call('get_activity_summary'));
    expect(r.state).toBe('locked');
    expect(r.companions).toHaveLength(2);
    h.clock.advance(1000);
    await b.signal('idle', h.clock.now(), 600);
    expect(toolJson(await call('get_activity_summary')).state).toBe('idle');
    h.clock.advance(1000);
    await a.signal('active', h.clock.now(), 60);
    r = toolJson(await call('get_activity_summary'));
    expect(r.state).toBe('active');
    expect(r.fresh).toBe(true);
    expect(r.companions.every((c: any) => c.fresh)).toBe(true);
    h.clock.advance(61_000); // a (60 s) expires, b (600 s) still holds
    r = toolJson(await call('get_activity_summary'));
    expect(r.state).toBe('idle');
    expect(r.companions.find((c: any) => c.companionId === a.id)).toMatchObject({ fresh: false, state: 'unknown' });
    expect(r.companions.find((c: any) => c.companionId === b.id)).toMatchObject({ fresh: true, state: 'idle' });
    h.clock.advance(600_000);
    r = toolJson(await call('get_activity_summary'));
    expect(r.state).toBe('unknown');
    expect(r.fresh).toBe(false);
  });

  it('phone activity lists one slot per companion; the legacy signal is the most recent', async () => {
    const { a, b, phone, h } = await two();
    await a.signal('active', h.clock.now(), 180);
    h.clock.advance(5000);
    await b.signal('locked', h.clock.now(), 180);
    const r = await h.json('/v1/phone/activity', { headers: phone.auth() });
    expect(r.body.signals.map((s: any) => s.companionId).sort()).toEqual([a.id, b.id].sort());
    expect(r.body.signal.companionId).toBe(b.id);
    expect(r.body.signal.state).toBe('locked');
    expect(r.body.companions).toHaveLength(2);
  });

  it('transition log keeps entries from both companions', async () => {
    const { a, b, call, h } = await two();
    await a.signal('active', h.clock.now(), 180);
    await b.signal('active', h.clock.now(), 180);
    const r = toolJson(await call('get_activity_summary'));
    expect(r.recentTransitions.map((e: any) => e.companionId).sort()).toEqual([a.id, b.id].sort());
  });

  it('phone activity with no signals: empty list and null legacy signal', async () => {
    const { phone, h, a } = await two();
    const r = await h.json('/v1/phone/activity', { headers: phone.auth() });
    expect(r.body.signals).toEqual([]);
    expect(r.body.signal).toBeNull();
    expect(r.body.companions.map((c: any) => c.id)).toContain(a.id);
  });
});

describe('companion self-revoke', () => {
  it('revokes only that companion: credential 401, slot removed, audited, others unaffected', async () => {
    const { phone, h, call } = await setup();
    const a = new FakeCompanion(h);
    const b = new FakeCompanion(h);
    await a.pair(phone);
    await b.pair(phone);
    await a.signal('active', h.clock.now(), 180);
    await b.signal('idle', h.clock.now(), 180);
    const r = await h.json('/v1/companion/self', { method: 'DELETE', headers: { authorization: `Bearer ${a.token}` } });
    expect(r.status).toBe(200);
    expect(r.body.ok).toBe(true);
    expect((await a.signal('active', h.clock.now() + 1000, 180)).status).toBe(401);
    expect((await h.json('/v1/companion/self', { method: 'DELETE', headers: { authorization: `Bearer ${a.token}` } })).status).toBe(401);
    const dev = await h.store.get<any>('device', a.id);
    expect(dev).toMatchObject({ id: a.id, label: 'pc', publicKey: a.key.spki });
    expect(dev.revokedAt).toBeTruthy();
    expect(dev.tokenHash).toBeUndefined();
    expect(await h.store.get('signal', `latest:${a.id}`)).toBeUndefined();
    const act = await h.json('/v1/phone/activity', { headers: phone.auth() });
    expect(act.body.signals.map((s: any) => s.companionId)).toEqual([b.id]);
    expect(act.body.companions.map((c: any) => c.id)).toEqual([b.id]);
    expect((await b.signal('active', h.clock.now() + 1000, 180)).status).toBe(200);
    expect(toolJson(await call('get_activity_summary')).state).toBe('active');
    const audit = (await h.json('/v1/owner/audit', { headers: ownerAuth })).body.entries;
    expect(audit.some((e: any) => e.action === 'companion.self_revoked' && e.detail.deviceId === a.id)).toBe(true);
  });

  it('a phone credential, the owner secret or no credential cannot call it', async () => {
    const { phone, h } = await setup();
    const a = new FakeCompanion(h);
    await a.pair(phone);
    expect((await h.json('/v1/companion/self', { method: 'DELETE', headers: phone.auth() })).status).toBe(401);
    expect((await h.json('/v1/companion/self', { method: 'DELETE', headers: ownerAuth })).status).toBe(401);
    expect((await h.json('/v1/companion/self', { method: 'DELETE' })).status).toBe(401);
    expect((await a.signal('active', h.clock.now(), 180)).status).toBe(200); // still valid
  });

  it('owner revoke also drops the signal slot', async () => {
    const { phone, h } = await setup();
    const a = new FakeCompanion(h);
    await a.pair(phone);
    await a.signal('active', h.clock.now(), 180);
    await h.json(`/v1/owner/devices/${a.id}`, { method: 'DELETE', headers: ownerAuth });
    expect(await h.store.get('signal', `latest:${a.id}`)).toBeUndefined();
  });
});

describe('update_calendar_rules uses the real ConfigOp names', () => {
  const rule = OP_EXAMPLES.upsertCalendarRule.rule;

  it('builds upsertCalendarRule {rule,index?}, deleteCalendarRule {id}, reorderCalendarRules {ruleIds}', async () => {
    const s = await setup();
    const ops: any[] = [];
    const orig = s.h.relay.enqueue.bind(s.h.relay);
    s.h.relay.enqueue = (async (g: any, i: any) => { ops.push(i.payload.ops[0]); return orig(g, i); }) as any;
    await s.call('update_calendar_rules', { action: 'set', rule, index: 1, idempotencyKey: 'cal-key-0001', waitSeconds: 0 });
    await s.call('update_calendar_rules', { action: 'set', rule, idempotencyKey: 'cal-key-0002', waitSeconds: 0 });
    await s.call('update_calendar_rules', { action: 'delete', ruleId: 'meetings', idempotencyKey: 'cal-key-0003', waitSeconds: 0 });
    await s.call('update_calendar_rules', { action: 'reorder', order: ['b', 'a'], idempotencyKey: 'cal-key-0004', waitSeconds: 0 });
    expect(ops).toEqual([
      { type: 'upsertCalendarRule', rule, index: 1 },
      { type: 'upsertCalendarRule', rule },
      { type: 'deleteCalendarRule', id: 'meetings' },
      { type: 'reorderCalendarRules', ruleIds: ['b', 'a'] },
    ]);
  });

  it('rejects missing arguments', async () => {
    const { call } = await setup();
    expect((await call('update_calendar_rules', { action: 'delete', idempotencyKey: 'cal-key-0005' })).isError).toBe(true);
  });
});

describe('tool descriptions carry correct op examples', () => {
  it('every example op type is a real ConfigOp name with the right fields', () => {
    for (const [name, ex] of Object.entries(OP_EXAMPLES)) {
      expect(ex.type).toBe(name);
      expect(CONFIG_OP_TYPES as readonly string[]).toContain(ex.type);
    }
    expect(Object.keys(OP_EXAMPLES.setHabitInterval).sort()).toEqual(['id', 'minutes', 'type']);
    expect(Object.keys(OP_EXAMPLES.upsertCalendarRule).sort()).toEqual(['rule', 'type']);
    expect(OP_EXAMPLES.setPause.target.type).toBe('habit');
    expect(['until', 'indefinite']).toContain(OP_EXAMPLES.setPause.pause.type);
    expect(OP_EXAMPLES.setPostureModes.modes.every((m) => ['Sitting', 'Standing', 'Walking', 'Custom'].includes(m.kind))).toBe(true);
    expect(JSON.parse(JSON.stringify(OP_EXAMPLES))).toEqual(OP_EXAMPLES);
  });

  it('op name list matches android ConfigOp.kt when the Kotlin source is present', () => {
    const f = new URL('../../android/domain/src/main/kotlin/app/daycue/domain/edit/ConfigOp.kt', import.meta.url);
    if (!existsSync(f)) return;
    const kt = [...readFileSync(f, 'utf8').matchAll(/@SerialName\("(\w+)"\)/g)].map((m) => m[1]);
    expect([...CONFIG_OP_TYPES].sort()).toEqual(kt.sort());
  });

  it('propose_change, apply_change and the ops schema contain the examples; no "provisional" wording', async () => {
    const { client } = await setup();
    const tools = (await client.listTools()).tools;
    for (const n of ['propose_change', 'apply_change']) {
      const t = tools.find((x) => x.name === n)!;
      expect(t.description).toContain('setHabitInterval');
      expect(t.description).toContain('setPause');
      const opsDesc = (t.inputSchema as any).properties.ops.description as string;
      expect(opsDesc).toContain(OPS_EXAMPLES_TEXT);
      for (const k of Object.keys(OP_EXAMPLES)) expect(opsDesc).toContain(k);
      expect(opsDesc).toContain('deleteCalendarRule');
    }
    const cal = tools.find((x) => x.name === 'update_calendar_rules')!;
    expect(cal.description).not.toMatch(/provisional/i);
  });
});
