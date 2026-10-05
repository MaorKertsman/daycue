import { describe, expect, it } from 'vitest';
import { FakePhone, makeHarness, mcpClient, toolJson } from './helpers.js';

// QA (independent): ACCEPTANCE scenario 16 - an offline phone never yields a false success.
async function setup() {
  const h = makeHarness();
  const phone = new FakePhone(h);
  await phone.pair();
  await phone.publish();
  const { token } = await h.relay.auth.createStaticToken('qa-client', ['config:read', 'config:write']);
  const { client, connect } = mcpClient(h, token);
  await connect();
  const call = async (name: string, args: Record<string, unknown> = {}) => client.callTool({ name, arguments: args });
  return { h, phone, call };
}
const OPS = [{ type: 'setHabitInterval', id: 'sunscreen', minutes: 75 }];

describe('QA scenario 16: offline commands stay pending and never report success', () => {
  it('a command sent while the phone is away is queued, applied=false, and still not applied after polling', async () => {
    const { call } = await setup();
    const res = toolJson(await call('apply_change', { ops: OPS, baseVersion: 1, idempotencyKey: 'qa-offline-001', waitSeconds: 0 }));
    expect(res.state).toBe('queued');
    expect(res.applied).toBe(false);
    expect(String(res.summary)).toMatch(/NOT APPLIED/);
    const again = toolJson(await call('get_command_status', { commandId: res.commandId, waitSeconds: 0 }));
    expect(again.applied).toBe(false);
    expect(again.state).toBe('queued');
  });

  it('a command the phone never fetches expires and is reported as expired, not applied', async () => {
    const { h, call } = await setup();
    const res = toolJson(await call('apply_change', { ops: OPS, baseVersion: 1, idempotencyKey: 'qa-offline-002', waitSeconds: 0, ttlSeconds: 60 }));
    h.clock.advance(120_000);
    const after = toolJson(await call('get_command_status', { commandId: res.commandId, waitSeconds: 0 }));
    expect(after.state).toBe('expired');
    expect(after.applied).toBe(false);
  });

  it('when the phone returns it applies the queued command once', async () => {
    const { phone, call } = await setup();
    const res = toolJson(await call('apply_change', { ops: OPS, baseVersion: 1, idempotencyKey: 'qa-offline-003', waitSeconds: 0 }));
    await phone.sync();
    await phone.sync();
    expect(phone.applyCount).toBe(1);
    const done = toolJson(await call('get_command_status', { commandId: res.commandId, waitSeconds: 0 }));
    expect(done.state).toBe('applied');
  });
});
