import { mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { describe, expect, it } from 'vitest';
import { createApp } from '../src/app.js';
import { Relay } from '../src/relay.js';
import { FileStore } from '../src/store.js';
import { FcmHttpV1Sender } from '../src/wake.js';
import { bytesToB64u } from '../src/util.js';
import { BASE, FakeClock, FakePhone, OWNER_SECRET, type Harness } from './helpers.js';

describe('FileStore durability (Render persistent disk path)', () => {
  it('pairing, grants and the queued command survive a process restart', async () => {
    const dir = mkdtempSync(join(tmpdir(), 'daycue-'));
    const file = join(dir, 'relay.json');
    const clock = new FakeClock();
    const stores: FileStore[] = [];
    const mk = () => {
      const store = new FileStore(file, () => clock.now());
      stores.push(store);
      const relay = new Relay({ baseUrl: BASE, ownerSecret: OWNER_SECRET, store, clock, pollIntervalMs: 10, requirePhoneApprovalForNewGrants: false });
      const app = createApp(relay);
      const fetchFn = (p: string, i?: RequestInit) => Promise.resolve(app.request(`${BASE}${p}`, i));
      const h: Harness = {
        clock, relay, app, fetch: fetchFn, store: undefined as any, wake: undefined as any,
        json: async (p, i) => { const r = await fetchFn(p, i); return { status: r.status, body: await r.json().catch(() => ({})), headers: r.headers }; },
      };
      return h;
    };
    try {
      const h1 = mk();
      const phone = new FakePhone(h1);
      await phone.pair();
      await phone.publish();
      const { token } = await h1.relay.auth.createStaticToken('c', ['config:write']);
      const g = await h1.relay.auth.verifyAccess(token);
      const { command } = await h1.relay.enqueue(g, { type: 'config.apply', payload: { ops: [{ type: 'setHabitInterval', id: 'sunscreen', minutes: 60 }] }, baseVersion: 1, idempotencyKey: 'durable-key-1' });
      // "restart": a clean shutdown flushes the coalesced write
      await stores[0].flush();
      const h2 = mk();
      expect((await h2.relay.auth.verifyAccess(token)).clientLabel).toBe('c'); // grant survived
      expect((await h2.relay.getCommand(command.id))!.state).toBe('queued'); // queue survived
      expect(await h2.relay.getSnapshot()).toMatchObject({ version: 1 });
      const pulled = await h2.json('/v1/phone/commands', { headers: phone.auth() });
      expect(pulled.body.commands).toHaveLength(1);
    } finally {
      rmSync(dir, { recursive: true, force: true });
    }
  });
});

describe('FCM HTTP v1 sender (request shape only; live credentials not available)', () => {
  it('exchanges a JWT for a token and posts a high-priority data-only message', async () => {
    const kp = await crypto.subtle.generateKey({ name: 'RSASSA-PKCS1-v1_5', modulusLength: 2048, publicExponent: new Uint8Array([1, 0, 1]), hash: 'SHA-256' }, true, ['sign', 'verify']);
    const pkcs8 = new Uint8Array(await crypto.subtle.exportKey('pkcs8', kp.privateKey));
    const pem = `-----BEGIN PRIVATE KEY-----\n${btoa(String.fromCharCode(...pkcs8))}\n-----END PRIVATE KEY-----\n`;
    const calls: Array<{ url: string; init: any }> = [];
    const fetchImpl = (async (url: string, init: any) => {
      calls.push({ url, init });
      if (url.includes('oauth2')) return new Response(JSON.stringify({ access_token: 'at', expires_in: 3600 }), { status: 200 });
      return new Response('{}', { status: 200 });
    }) as unknown as typeof fetch;
    const s = new FcmHttpV1Sender({ project_id: 'demo-project', client_email: 'x@demo.iam.gserviceaccount.com', private_key: pem }, fetchImpl);
    expect(await s.wake('device-token', 'command')).toEqual({ ok: true });
    expect(calls[1].url).toBe('https://fcm.googleapis.com/v1/projects/demo-project/messages:send');
    expect(calls[1].init.headers.authorization).toBe('Bearer at');
    const msg = JSON.parse(calls[1].init.body).message;
    expect(msg.token).toBe('device-token');
    expect(msg.android.priority).toBe('HIGH');
    expect(msg.data).toEqual({ type: 'sync', reason: 'command' }); // no config content in the push
    expect(msg.notification).toBeUndefined();
    void bytesToB64u;
    await s.wake('device-token', 'command'); // token cached
    expect(calls.filter((c) => c.url.includes('oauth2'))).toHaveLength(1);
  });
});
