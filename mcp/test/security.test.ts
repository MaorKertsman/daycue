import { mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { describe, expect, it } from 'vitest';
import { fetchWithCap, classifyRedirect, LIMITS } from '../src/auth.js';
import { HttpClientApi } from '../src/clientApi.js';
import { blockedHostName, isPublicIp } from '../src/netguard.js';
import { safeHttpsGet } from '../src/node/safefetch.js';
import { ackMessageV2, Relay, assertOwnerSecretStrength } from '../src/relay.js';
import { FileStore, MemoryStore } from '../src/store.js';
import { canonicalJson, secretEquals, sha256Hex } from '../src/util.js';
import {
  BASE, FORM, FakePhone, OWNER_SECRET, REDIRECT, jsonInit, makeHarness, mcpClient, oauthLogin, openConsent, ownerAuth, pkce, postDecision, sign, toolJson, type Harness,
} from './helpers.js';

const from = (ip: string, extra: Record<string, string> = {}) => ({ 'x-forwarded-for': ip, ...extra });
const badOwner = (ip: string) => ({ authorization: 'Bearer wrong-secret', 'x-forwarded-for': ip });
const goodOwner = (ip: string) => ({ ...ownerAuth, 'x-forwarded-for': ip });
const pairCodes = (h: Harness, headers: Record<string, string>) => h.json('/v1/owner/pair-codes', { method: 'POST', headers });

// ------------------------------------------------------------------ M-3 size, rate, caps
describe('M-3 request size limits, rate limits, caps', () => {
  it('rejects oversized bodies with 413 before parsing (default 64 KiB, snapshot 300 KiB)', async () => {
    const h = makeHarness();
    const big = JSON.stringify({ client_name: 'x'.repeat(70_000), redirect_uris: [REDIRECT] });
    expect((await h.json('/register', { method: 'POST', headers: { 'content-type': 'application/json' }, body: big })).status).toBe(413);
    const phone = new FakePhone(h);
    await phone.pair();
    const snap = (n: number) => h.json('/v1/phone/snapshot', jsonInit('PUT', { version: 1, config: { pad: 'y'.repeat(n) } }, phone.auth()));
    expect((await snap(200_000)).status).toBe(200);
    expect((await snap(400_000)).status).toBe(413);
  });

  it('rate limits registration per source (Retry-After) without affecting other sources', async () => {
    const h = makeHarness();
    const reg = (ip: string) => h.json('/register', jsonInit('POST', { client_name: 'c', redirect_uris: [REDIRECT] }, from(ip)));
    for (let i = 0; i < 5; i++) expect((await reg('1.1.1.1')).status).toBe(201);
    const limited = await reg('1.1.1.1');
    expect(limited.status).toBe(429);
    expect(Number(limited.headers.get('retry-after'))).toBeGreaterThan(0);
    expect((await reg('2.2.2.2')).status).toBe(201);
  });

  it('takes the source from the proxy-appended end of X-Forwarded-For (client-supplied prefixes cannot dodge limits)', async () => {
    const h = makeHarness();
    for (let i = 0; i < 6; i++) await h.json('/register', jsonInit('POST', { redirect_uris: [REDIRECT] }, from(`10.0.0.${i}, 1.1.1.1`)));
    expect((await h.json('/register', jsonInit('POST', { redirect_uris: [REDIRECT] }, from('99.9.9.9, 1.1.1.1')))).status).toBe(429);
  });

  it('caps DCR registrations and never evicts a client that holds a grant', async () => {
    const h = makeHarness();
    const first = await oauthLogin(h, { scopes: ['config:read'] });
    for (let i = 0; i < LIMITS.dcrClients + 30; i++) await h.relay.auth.registerClient({ client_name: `spam${i}`, redirect_uris: [REDIRECT] });
    const n = (await h.store.list('client', { prefix: 'dcr:' })).length;
    expect(n).toBeLessThanOrEqual(LIMITS.dcrClients);
    await expect(h.relay.auth.resolveClient(first.clientId)).resolves.toMatchObject({ clientId: first.clientId });
    // unauthenticated registrations are not written to the audit log
    expect((await h.relay.listAudit(500)).filter((e) => e.action === 'client.registered')).toHaveLength(0);
  });

  it('caps pending authorization requests and the CIMD cache', async () => {
    const fetchImpl = (async (url: string) => new Response(JSON.stringify({ client_id: url, client_name: 'n', redirect_uris: [REDIRECT] }), { status: 200 })) as unknown as typeof fetch;
    const h = makeHarness({ fetchImpl });
    const { challenge } = await pkce();
    const reg = (await h.json('/register', jsonInit('POST', { redirect_uris: [REDIRECT] }))).body;
    const q = { response_type: 'code', client_id: reg.client_id, redirect_uri: REDIRECT, code_challenge: challenge, code_challenge_method: 'S256' };
    for (let i = 0; i < LIMITS.pendingTxns + 20; i++) expect((await h.fetch(`/authorize?${new URLSearchParams(q)}`, { headers: from(`7.7.${i >> 8}.${i & 255}`) })).status).toBe(200);
    expect((await h.store.list('txn')).length).toBeLessThanOrEqual(LIMITS.pendingTxns);
    for (let i = 0; i < LIMITS.cimdCache + 10; i++) await h.relay.auth.resolveClient(`https://app${i}.example.com/c.json`);
    expect((await h.store.list('cimd')).length).toBeLessThanOrEqual(LIMITS.cimdCache);
  });

  it('bounds audit growth', async () => {
    const h = makeHarness();
    for (let i = 0; i < 2100; i++) await h.relay.audit('t', 'x');
    expect((await h.store.list('audit')).length).toBeLessThanOrEqual(2000);
    expect((await h.relay.listAudit(100000)).length).toBeLessThanOrEqual(500);
  });

  it('caps queued commands', async () => {
    const h = makeHarness();
    const phone = new FakePhone(h);
    await phone.pair();
    const { token } = await h.relay.auth.createStaticToken('c', ['config:write']);
    const g = await h.relay.auth.verifyAccess(token);
    for (let i = 0; i < 100; i++) await h.relay.enqueue(g, { type: 'config.apply', payload: { ops: [{ type: 'X' }] }, idempotencyKey: `cap-key-${i}-xx` });
    await expect(h.relay.enqueue(g, { type: 'config.apply', payload: { ops: [{ type: 'X' }] }, idempotencyKey: 'cap-key-over-xx' })).rejects.toMatchObject({ code: 'too_many_pending', status: 429 });
  });

  it('FileStore coalesces writes (cost does not grow per mutation) and persists on flush', async () => {
    const dir = mkdtempSync(join(tmpdir(), 'daycue-'));
    try {
      const file = join(dir, 'relay.json');
      const store = new FileStore(file, Date.now, 10_000);
      for (let i = 0; i < 500; i++) await store.put('ns', `k${i}`, { i, pad: 'z'.repeat(200) });
      expect(store.writes).toBe(0); // nothing written synchronously per mutation
      await store.flush();
      expect(store.writes).toBe(1);
      expect((await new FileStore(file).list('ns')).length).toBe(500);
    } finally {
      rmSync(dir, { recursive: true, force: true });
    }
  });
});

// ------------------------------------------------------------------ M-1 / M-2 owner secret protection
describe('M-1 / M-2 owner secret throttling', () => {
  it('locks out only the offending source, not the owner', async () => {
    const h = makeHarness();
    for (let i = 0; i < 5; i++) expect((await pairCodes(h, badOwner('6.6.6.6'))).status).toBe(401);
    const locked = await pairCodes(h, badOwner('6.6.6.6'));
    expect(locked.status).toBe(429);
    expect(Number(locked.headers.get('retry-after'))).toBeGreaterThan(0);
    expect((await pairCodes(h, goodOwner('6.6.6.6'))).status).toBe(429); // that source stays locked
    expect((await pairCodes(h, goodOwner('203.0.113.9'))).status).toBe(200); // the owner is unaffected
    h.clock.advance(31_000);
    expect((await pairCodes(h, goodOwner('6.6.6.6'))).status).toBe(200);
  });

  it('counts attempts atomically: 40 parallel guesses from one source reach the comparison at most 5 times', async () => {
    const h = makeHarness();
    const rs = await Promise.all(Array.from({ length: 40 }, () => pairCodes(h, badOwner('6.6.6.6'))));
    expect(rs.filter((r) => r.status === 401).length).toBeLessThanOrEqual(5);
    expect(rs.filter((r) => r.status === 429).length).toBeGreaterThanOrEqual(35);
  });

  it('revocation and the kill path work while the source is locked out', async () => {
    const h = makeHarness();
    const login = await oauthLogin(h, { scopes: ['config:read'] });
    for (let i = 0; i < 6; i++) await pairCodes(h, badOwner('unknown'));
    expect((await pairCodes(h, { ...ownerAuth })).status).toBe(429); // locked (same source "unknown")
    const grants = (await h.json('/v1/owner/grants', { headers: from('203.0.113.9', ownerAuth) })).body.grants;
    const del = await h.json(`/v1/owner/grants/${grants[0].id}`, { method: 'DELETE', headers: ownerAuth });
    expect(del.status).toBe(200);
    expect((await h.json('/v1/owner/revoke-all', { method: 'POST', headers: ownerAuth })).status).toBe(200);
    expect(login.tokens.access_token).toBeTruthy();
    // wrong secrets on the kill path are 401 and rate limited, never a lockout of the real owner
    for (let i = 0; i < 20; i++) expect((await h.json('/v1/owner/revoke-all', { method: 'POST', headers: { authorization: 'Bearer nope' } })).status).toBe(401);
    expect((await h.json('/v1/owner/revoke-all', { method: 'POST', headers: ownerAuth })).status).toBe(200);
  });

  it('the consent page has its own bucket: a locked owner API does not block approving a connector', async () => {
    const h = makeHarness();
    for (let i = 0; i < 6; i++) await pairCodes(h, badOwner('unknown'));
    const r = await oauthLogin(h, { scopes: ['config:read'] });
    expect(r.tokenStatus).toBe(200);
  });

  it('a distributed flood cannot lock out a source that has authenticated before; new sources are throttled', async () => {
    const h = makeHarness();
    expect((await pairCodes(h, goodOwner('203.0.113.9'))).status).toBe(200); // owner becomes a known source
    for (let i = 0; i < 130; i++) await pairCodes(h, badOwner(`10.1.${i >> 8}.${i & 255}`));
    expect((await pairCodes(h, goodOwner('203.0.113.9'))).status).toBe(200);
    expect((await pairCodes(h, goodOwner('198.51.100.7'))).status).toBe(429); // unknown source during a flood
    expect((await h.json('/v1/owner/revoke-all', { method: 'POST', headers: from('198.51.100.7', ownerAuth) })).status).toBe(200); // kill path unaffected
    h.clock.advance(61_000);
    expect((await pairCodes(h, goodOwner('198.51.100.7'))).status).toBe(200);
  });

  it('pairing attempts are throttled per source too', async () => {
    const h = makeHarness();
    const phone = new FakePhone(h);
    await phone.pair();
    const attempt = (ip: string, code: string) => h.json('/v1/pair/phone', jsonInit('POST', { code, publicKey: phone.key.spki }, from(ip)));
    for (let i = 0; i < 5; i++) expect((await attempt('6.6.6.6', 'AAAAA-BBBBB')).status).toBe(403);
    expect((await attempt('6.6.6.6', 'AAAAA-BBBBB')).status).toBe(429);
    const code = (await pairCodes(h, ownerAuth)).body.code;
    expect((await attempt('203.0.113.9', code)).status).toBe(201);
  });

  it('refuses weak owner secrets, accepts generated ones, compares in constant-time style', async () => {
    expect(() => assertOwnerSecretStrength('correct horse battery staple 9!')).toThrow(/too weak/);
    expect(() => assertOwnerSecretStrength('a'.repeat(40))).toThrow(/too weak/);
    expect(() => assertOwnerSecretStrength('password1234password1234password1234')).toThrow(/too weak/);
    const gen = Buffer.from(crypto.getRandomValues(new Uint8Array(32))).toString('base64url');
    expect(() => assertOwnerSecretStrength(gen)).not.toThrow();
    expect(() => new Relay({ baseUrl: BASE, ownerSecret: 'short-but-24-characters-ok!', store: new MemoryStore() })).toThrow(/too weak/);
    expect(await secretEquals(gen, gen)).toBe(true);
    expect(await secretEquals(gen, gen.slice(0, -1))).toBe(false);
    expect(await secretEquals('', gen)).toBe(false);
  });
});

// ------------------------------------------------------------------ M-6 consent page
describe('M-6 consent page', () => {
  const params = async (redirect: string, redirectUris = [redirect]) => {
    const h = makeHarness();
    const reg = (await h.json('/register', jsonInit('POST', { client_name: 'Claude', redirect_uris: redirectUris }))).body;
    const { challenge } = await pkce();
    return { h, q: { response_type: 'code', client_id: reg.client_id, redirect_uri: redirect, code_challenge: challenge, code_challenge_method: 'S256', scope: 'config:read' } };
  };

  it('shows the redirect host prominently; official hosts and loopback need no extra confirmation', async () => {
    for (const uri of ['https://claude.ai/api/mcp/auth_callback', 'https://claude.com/api/mcp/auth_callback', 'https://chatgpt.com/connector/oauth/abc123', 'https://chatgpt.com/connector_platform_oauth_redirect']) {
      const { h, q } = await params(uri);
      const c = await openConsent(h, q);
      expect(c.html).toContain(`returns to <b>${new URL(uri).host}</b>`);
      expect(c.html).not.toContain('UNRECOGNIZED');
      expect(c.html).not.toContain('confirm_untrusted');
    }
    const { h, q } = await params('http://127.0.0.1:53124/callback');
    const c = await openConsent(h, q);
    expect(c.html).toContain('running on <em>this</em> device');
    expect(c.html).not.toContain('UNRECOGNIZED');
  });

  it('warns loudly about other hosts, needs an explicit tick, and never redirects an untrusted host on deny', async () => {
    const { h, q } = await params('https://evil.example/cb');
    const c = await openConsent(h, q);
    expect(c.html).toContain('UNRECOGNIZED REDIRECT ADDRESS');
    expect(c.html).toContain('<b>evil.example</b>');
    expect(c.html).toContain('name="confirm_untrusted"');
    const noTick = await postDecision(h, c, { owner_secret: OWNER_SECRET, decision: 'approve', scope: 'config:read' });
    expect(noTick.status).toBe(400);
    expect(noTick.headers.get('location')).toBeNull();
    const deny = await postDecision(h, c, { decision: 'deny' });
    expect(deny.status).toBe(200); // local "denied" page
    expect(deny.headers.get('location')).toBeNull();
    const c2 = await openConsent(h, q);
    const ok = await postDecision(h, c2, { owner_secret: OWNER_SECRET, decision: 'approve', scope: 'config:read', confirm_untrusted: 'yes' });
    expect(ok.status).toBe(302);
    expect(new URL(ok.headers.get('location')!).host).toBe('evil.example');
  });

  it('honours a custom trusted host list', async () => {
    const h = makeHarness({ relay: { trustedRedirectHosts: ['my.example'] } });
    expect(classifyRedirect('https://my.example/cb', ['my.example'])).toBe('trusted');
    const reg = (await h.json('/register', jsonInit('POST', { redirect_uris: ['https://claude.ai/cb'] }))).body;
    const { challenge } = await pkce();
    const c = await openConsent(h, { response_type: 'code', client_id: reg.client_id, redirect_uri: 'https://claude.ai/cb', code_challenge: challenge, code_challenge_method: 'S256' });
    expect(c.html).toContain('UNRECOGNIZED'); // no longer in the list
  });

  it('refuses private-use schemes, plain http to non-loopback and userinfo redirect URIs at registration', async () => {
    const h = makeHarness();
    for (const uri of ['myapp://callback', 'http://example.com/cb', 'https://u:p@claude.ai/cb', 'https://claude.ai/cb#frag']) {
      expect((await h.json('/register', jsonInit('POST', { redirect_uris: [uri] }))).status).toBe(400);
    }
  });

  it('is CSRF protected: needs the matching cookie and token, same-origin, and forged posts never reach the secret check', async () => {
    const { h, q } = await params(REDIRECT);
    const c = await openConsent(h, q);
    const good = { owner_secret: OWNER_SECRET, decision: 'approve', scope: 'config:read' };
    expect((await postDecision(h, { ...c, cookie: '' }, good)).status).toBe(403); // no cookie
    expect((await postDecision(h, { ...c, cookie: 'daycue_csrf=other' }, good)).status).toBe(403); // wrong cookie
    expect((await postDecision(h, { ...c, csrf: 'forged' }, good)).status).toBe(403); // wrong token
    expect((await postDecision(h, c, good, { origin: 'https://evil.example' })).status).toBe(403); // cross-site origin
    expect((await postDecision(h, c, good, { 'sec-fetch-site': 'cross-site' })).status).toBe(403);
    for (let i = 0; i < 20; i++) await postDecision(h, { ...c, cookie: '' }, { ...good, owner_secret: 'guess' });
    expect((await postDecision(h, c, good)).status).toBe(302); // owner attempt budget untouched by forged posts
  });

  it('sets anti-clickjacking headers and a Strict, HttpOnly, Secure CSRF cookie', async () => {
    const { h, q } = await params(REDIRECT);
    const c = await openConsent(h, q);
    expect(c.headers.get('content-security-policy')).toContain("frame-ancestors 'none'");
    expect(c.headers.get('x-frame-options')).toBe('DENY');
    expect(c.headers.get('cache-control')).toBe('no-store');
    const sc = c.headers.get('set-cookie')!;
    expect(sc).toMatch(/HttpOnly/i);
    expect(sc).toMatch(/SameSite=Strict/i);
    expect(sc).toMatch(/Secure/i);
    const bad = await h.fetch(`/authorize?${new URLSearchParams({ ...q, redirect_uri: 'https://evil.example/cb' })}`);
    expect(bad.status).toBe(400); // unregistered redirect: error page, no redirect
    expect(bad.headers.get('x-frame-options')).toBe('DENY');
  });
});

// ------------------------------------------------------------------ L-1 CIMD hardening
describe('L-1 client metadata fetch hardening', () => {
  it('blocks trailing-dot and internal names before fetching anything', async () => {
    const calls: string[] = [];
    const fetchImpl = (async (u: string) => { calls.push(u); return new Response('{}'); }) as unknown as typeof fetch;
    const h = makeHarness({ fetchImpl });
    const { challenge } = await pkce();
    for (const host of ['localhost.', 'foo.localhost.', 'metadata.google.internal.', 'router.local.', 'intranet', '0x7f.1', '2130706433', '[::1]', 'a.example.com:8443']) {
      const r = await h.fetch(`/authorize?${new URLSearchParams({ response_type: 'code', client_id: `https://${host}/c.json`, redirect_uri: REDIRECT, code_challenge: challenge, code_challenge_method: 'S256' })}`);
      expect(r.status, host).toBe(400);
    }
    expect(calls).toEqual([]);
    expect(blockedHostName('LOCALHOST..')).toBe(true);
    expect(blockedHostName('app.example.com')).toBe(false);
  });

  it('classifies private, loopback, link-local, CGNAT, ULA and mapped addresses as non-public', () => {
    for (const ip of ['127.0.0.1', '10.1.2.3', '172.16.0.1', '192.168.1.1', '169.254.169.254', '100.64.0.1', '0.0.0.0', '::1', '::', 'fe80::1', 'fc00::1', 'fd12::1', '::ffff:127.0.0.1', '::ffff:7f00:1', '::ffff:169.254.169.254', 'not-an-ip', '224.0.0.1']) {
      expect(isPublicIp(ip), ip).toBe(false);
    }
    for (const ip of ['8.8.8.8', '1.1.1.1', '93.184.216.34', '2606:4700:4700::1111', '::ffff:8.8.8.8']) expect(isPublicIp(ip), ip).toBe(true);
  });

  it('refuses names that RESOLVE to private addresses (nip.io style) and connects only to vetted addresses', async () => {
    const seen: string[] = [];
    const lookupImpl = (host: string, _o: unknown, cb: (e: Error | null, a: Array<{ address: string; family: number }>) => void) => {
      seen.push(host);
      cb(null, host.includes('rebind') ? [{ address: '8.8.8.8', family: 4 }, { address: '169.254.169.254', family: 4 }] : [{ address: '127.0.0.1', family: 4 }]);
    };
    const opts = { maxBytes: 16384, timeoutMs: 2000, lookupImpl };
    await expect(safeHttpsGet('https://127.0.0.1.nip.io/c.json', opts)).rejects.toThrow(/non-public/);
    await expect(safeHttpsGet('https://169.254.169.254.nip.io/c.json', opts)).rejects.toThrow(/non-public/);
    await expect(safeHttpsGet('https://rebind.example.com/c.json', opts)).rejects.toThrow(/non-public/); // any bad answer in the set rejects
    await expect(safeHttpsGet('https://localhost./c.json', opts)).rejects.toThrow(/not allowed/);
    await expect(safeHttpsGet('http://app.example.com/c.json', opts)).rejects.toThrow(/https only/);
    expect(seen).toEqual(['127.0.0.1.nip.io', '169.254.169.254.nip.io', 'rebind.example.com']);
  });

  it('caps the body while streaming, refuses redirects and sets a timeout', async () => {
    let pulled = 0;
    const stream = new ReadableStream<Uint8Array>({ pull(c) { pulled += 4096; c.enqueue(new Uint8Array(4096)); } });
    let init: RequestInit | undefined;
    const fetchImpl = (async (_u: string, i: RequestInit) => { init = i; return new Response(stream, { status: 200 }); }) as unknown as typeof fetch;
    await expect(fetchWithCap(fetchImpl)('https://app.example.com/c.json', { maxBytes: 16384, timeoutMs: 1000 })).rejects.toThrow(/too large/);
    expect(pulled).toBeLessThan(16384 * 3); // stopped early, did not read an unbounded body
    expect(init?.redirect).toBe('error');
    expect(init?.signal).toBeDefined();
  });
});

// ------------------------------------------------------------------ L-2 code reuse
describe('L-2 authorization code reuse', () => {
  it('a replayed code revokes the grant and tokens that the first redemption issued', async () => {
    const h = makeHarness();
    const reg = (await h.json('/register', jsonInit('POST', { redirect_uris: [REDIRECT] }))).body;
    const { verifier, challenge } = await pkce();
    const c = await openConsent(h, { response_type: 'code', client_id: reg.client_id, redirect_uri: REDIRECT, code_challenge: challenge, code_challenge_method: 'S256', scope: 'config:read' });
    const dec = await postDecision(h, c, { owner_secret: OWNER_SECRET, decision: 'approve', scope: 'config:read' });
    const code = new URL(dec.headers.get('location')!).searchParams.get('code')!;
    const redeem = () => h.json('/token', { method: 'POST', headers: FORM, body: new URLSearchParams({ grant_type: 'authorization_code', code, code_verifier: verifier, client_id: reg.client_id, redirect_uri: REDIRECT }) });
    const first = await redeem();
    expect(first.status).toBe(200);
    const status = (t: string) => h.json('/mcp', { method: 'POST', headers: { 'content-type': 'application/json', accept: 'application/json, text/event-stream', authorization: `Bearer ${t}` }, body: '{}' });
    expect((await status(first.body.access_token)).status).not.toBe(401);
    const replay = await redeem();
    expect(replay.status).toBe(400);
    expect((await status(first.body.access_token)).status).toBe(401); // grant revoked
    const refresh = await h.json('/token', { method: 'POST', headers: FORM, body: new URLSearchParams({ grant_type: 'refresh_token', refresh_token: first.body.refresh_token, client_id: reg.client_id }) });
    expect(refresh.status).toBe(400);
    expect((await h.relay.listAudit(50)).map((e) => e.action)).toContain('oauth.code_reuse');
  });
});

// ------------------------------------------------------------------ M-8 isolation and medication redaction
describe('M-8 per-client isolation and medication redaction', () => {
  async function world() {
    const h = makeHarness();
    const phone = new FakePhone(h);
    await phone.pair();
    await h.json('/v1/phone/snapshot', jsonInit('PUT', {
      version: 1, config: { habits: [] }, medication: { medications: [] },
      status: { recentChanges: [{ version: 1, summary: 'habits[a].name changed' }, { version: 2, summary: 'medications[m1].label: Example vitamin' }], nextCues: [{ kind: 'medication', at: '09:00' }, { kind: 'habit', at: '10:00' }] },
    }, phone.auth()));
    const mk = async (label: string, scopes: string[]) => {
      const { token } = await h.relay.auth.createStaticToken(label, scopes);
      const g = await h.relay.auth.verifyAccess(token);
      const { client, connect } = mcpClient(h, token);
      await connect();
      return { g, call: (name: string, args: Record<string, unknown> = {}) => client.callTool({ name, arguments: args }) };
    };
    return { h, phone, mk };
  }
  const ackApplied = async (phone: FakePhone, c: { id: string; payloadHash: string }, summary: string) => phone.ack(c, 'applied', { newVersion: 2, sensitivity: 'ordinary', summary });

  it('a client sees only its own command results unless it holds the needed scope', async () => {
    const { h, phone, mk } = await world();
    const a = await mk('a', ['config:read', 'config:write']);
    const reader = await mk('reader', ['config:read']);
    const writer = await mk('writer', ['config:write']);
    const { command } = await h.relay.enqueue(a.g, { type: 'config.apply', payload: { ops: [{ type: 'setHabitInterval' }] }, idempotencyKey: 'iso-key-0001' });
    await ackApplied(phone, command, 'Sunscreen interval 120 -> 90 min');
    const own = toolJson(await a.call('get_command_status', { commandId: command.id }));
    expect(JSON.stringify(own.result)).toContain('Sunscreen');
    const other = toolJson(await reader.call('get_command_status', { commandId: command.id }));
    expect(other.state).toBe('applied');
    expect(other.result).toBeUndefined();
    expect(other.resultWithheld).toBe(true);
    expect(JSON.stringify(other)).not.toContain('Sunscreen');
    expect(JSON.stringify(toolJson(await writer.call('get_command_status', { commandId: command.id })).result)).toContain('Sunscreen'); // holds config:write
    const list = toolJson(await reader.call('list_recent_changes', {}));
    expect(list.remoteChanges[0].commandId).toBe(command.id);
    expect(JSON.stringify(list.remoteChanges)).not.toContain('Sunscreen');
    const st = toolJson(await reader.call('get_status', {}));
    expect(st.pendingCommands).toHaveLength(0);
  });

  it('medication-related results are redacted per client scope in get_command_status, list_recent_changes and status lists', async () => {
    const { h, phone, mk } = await world();
    const med = await mk('med', ['config:read', 'config:write', 'medication']);
    const plain = await mk('plain', ['config:read', 'config:write']);
    const { command } = await h.relay.enqueue(med.g, { type: 'config.apply', payload: { ops: [{ type: 'upsertMedication', medication: { id: 'm1' } }] }, idempotencyKey: 'med-key-0001' });
    await ackApplied(phone, command, 'medications[m1].label: Example vitamin');
    const ownerView = toolJson(await med.call('get_command_status', { commandId: command.id }));
    expect(JSON.stringify(ownerView)).toContain('Example vitamin');
    const plainView = toolJson(await plain.call('get_command_status', { commandId: command.id }));
    expect(JSON.stringify(plainView)).not.toContain('vitamin');
    expect(plainView.result.message).toMatch(/withheld/);
    expect(plainView.result.newVersion).toBe(2); // non-sensitive facts survive
    const list = toolJson(await plain.call('list_recent_changes', {}));
    expect(JSON.stringify(list)).not.toContain('vitamin'); // relay-issued summary and the phone's recentChanges
    expect(JSON.stringify(list.phoneChanges)).toContain('habits[a]');
    const medList = toolJson(await med.call('list_recent_changes', {}));
    expect(JSON.stringify(medList)).toContain('Example vitamin');
    const status = toolJson(await plain.call('get_status', {}));
    expect(JSON.stringify(status.status)).not.toContain('09:00');
    expect(JSON.stringify(status.status)).toContain('withheld');
    expect(JSON.stringify(status.status)).toContain('habit');
  });

  it('get_command_status is available to a sessions:control-only grant', async () => {
    const { mk } = await world();
    const s = await mk('s', ['sessions:control']);
    const r = await s.call('get_command_status', { commandId: 'cmd_unknown' });
    expect(JSON.stringify(r)).toContain('not_found');
  });
});

// ------------------------------------------------------------------ M-7 phone visibility / approval
describe('M-7 grants visible to the phone; phone approval of new grants', () => {
  async function approvalWorld() {
    const h = makeHarness({ relay: { requirePhoneApprovalForNewGrants: true } });
    const phone = new FakePhone(h);
    await phone.pair();
    await phone.publish();
    return { h, phone };
  }

  it('write scopes stay inactive until the phone approves; read-only grants are active immediately', async () => {
    const { h, phone } = await approvalWorld();
    const { token, grant } = await h.relay.auth.createStaticToken('claude-code', ['config:read', 'config:write', 'medication']);
    const g = await h.relay.auth.verifyAccess(token);
    expect(g.scopes).toEqual(['config:read']);
    expect(g.pendingScopes).toEqual(['config:write', 'medication']);
    await expect(h.relay.enqueue(g, { type: 'config.apply', payload: { ops: [{ type: 'X' }] }, idempotencyKey: 'pending-key-1' })).rejects.toMatchObject({ code: 'insufficient_scope' });
    const { client, connect } = mcpClient(h, token);
    await connect();
    expect((await client.listTools()).tools.map((t) => t.name)).not.toContain('apply_change');
    const read = await h.relay.auth.createStaticToken('reader', ['config:read']);
    expect((await h.relay.auth.verifyAccess(read.token)).pendingScopes).toBeUndefined();
    expect(read.grant.approval).toBe('not_required');
    // medication stays off for the phone until approved
    expect((await phone.publish()).body.wants.medication).toBe(false);

    const pull = (await h.json('/v1/phone/commands', { headers: phone.auth() })).body;
    const item = pull.grants.items.find((x: any) => x.id === grant.id);
    expect(item).toMatchObject({ label: 'claude-code', approval: 'pending', activeScopes: ['config:read'], scopes: ['config:read', 'config:write', 'medication'] });
    const v0 = pull.grants.version;
    expect((await phone.publish()).body.grantsVersion).toBe(v0);

    const ok = await phone.decideGrant(grant.id, 'approve');
    expect(ok.status).toBe(200);
    expect(ok.body.grants.version).toBeGreaterThan(v0);
    const g2 = await h.relay.auth.verifyAccess(token);
    expect(g2.scopes).toEqual(['config:read', 'config:write', 'medication']);
    expect((await phone.publish()).body.wants.medication).toBe(true);
    expect((await (await mcpClient(h, token).connect()).listTools()).tools.map((t) => t.name)).toContain('apply_change');
    await client.close();
  });

  it('decisions need the phone signature and a fresh timestamp; the phone can narrow, decline and revoke', async () => {
    const { h, phone } = await approvalWorld();
    const a = await h.relay.auth.createStaticToken('a', ['config:read', 'config:write', 'sessions:control']);
    expect((await phone.decideGrant(a.grant.id, 'approve', [], { badSig: true })).status).toBe(403);
    expect((await phone.decideGrant(a.grant.id, 'approve', [], { decidedAt: h.clock.now() - 3600_000 })).status).toBe(400);
    // a stolen device TOKEN without the key cannot approve
    const forged = await h.json(`/v1/phone/grants/${a.grant.id}/decision`, jsonInit('POST', { decision: 'approve', decidedAt: h.clock.now(), signature: 'AAAA' }, phone.auth()));
    expect(forged.status).toBe(403);
    expect((await h.relay.auth.verifyAccess(a.token)).scopes).toEqual(['config:read']);
    expect((await phone.decideGrant(a.grant.id, 'approve', ['config:read', 'config:write'])).status).toBe(200); // narrowed
    expect((await h.relay.auth.verifyAccess(a.token)).scopes).toEqual(['config:read', 'config:write']);
    expect((await phone.decideGrant(a.grant.id, 'approve', ['medication'])).status).toBe(400); // cannot widen
    const b = await h.relay.auth.createStaticToken('b', ['config:read', 'config:write']);
    expect((await phone.decideGrant(b.grant.id, 'decline')).status).toBe(200);
    await expect(h.relay.auth.verifyAccess(b.token)).rejects.toMatchObject({ status: 401 });
    expect((await phone.decideGrant(a.grant.id, 'revoke')).status).toBe(200);
    await expect(h.relay.auth.verifyAccess(a.token)).rejects.toMatchObject({ status: 401 });
    expect((await h.json('/v1/phone/grants', { headers: phone.auth() })).body.grants.items).toHaveLength(0);
    const audit = (await h.relay.listAudit(100)).map((e) => e.action);
    expect(audit).toEqual(expect.arrayContaining(['grant.phone_approved', 'grant.phone_declined', 'grant.phone_revoked', 'grant.bad_signature']));
  });

  it('does not gate while no phone is paired, and the option can be turned off', async () => {
    const h = makeHarness({ relay: { requirePhoneApprovalForNewGrants: true } });
    const t = await h.relay.auth.createStaticToken('early', ['config:write']);
    expect((await h.relay.auth.verifyAccess(t.token)).scopes).toEqual(['config:write']);
    const off = makeHarness();
    await new FakePhone(off).pair();
    const t2 = await off.relay.auth.createStaticToken('x', ['config:write']);
    expect((await off.relay.auth.verifyAccess(t2.token)).scopes).toEqual(['config:write']);
  });

  it('an OAuth grant created through the consent page is gated too', async () => {
    const { h } = await approvalWorld();
    const r = await oauthLogin(h, { scopes: ['config:read', 'config:write'] });
    expect(r.tokens.scope).toBe('config:read config:write'); // what the owner granted
    const g = await h.relay.auth.verifyAccess(r.tokens.access_token);
    expect(g.scopes).toEqual(['config:read']);
    const { client, connect } = mcpClient(h, r.tokens.access_token);
    await connect();
    const denied = await h.json('/mcp', { method: 'POST', headers: { 'content-type': 'application/json', accept: 'application/json, text/event-stream', authorization: `Bearer ${r.tokens.access_token}` }, body: JSON.stringify({ jsonrpc: '2.0', id: 2, method: 'tools/call', params: { name: 'apply_change', arguments: {} } }) });
    expect(denied.status).toBe(403);
    expect(JSON.stringify(denied.body)).toContain('approves this client');
  });
});

// ------------------------------------------------------------------ L-5 self revoke, L-3 ack v2, I-5, L-6
describe('phone self-revoke, snapshot version cap, ack v2, http relay URL', () => {
  it('DELETE /v1/phone/self revokes the credential, expires pending commands and unpairs', async () => {
    const h = makeHarness();
    const phone = new FakePhone(h);
    await phone.pair();
    const { token } = await h.relay.auth.createStaticToken('c', ['config:write']);
    const g = await h.relay.auth.verifyAccess(token);
    const { command } = await h.relay.enqueue(g, { type: 'config.apply', payload: { ops: [{ type: 'X' }] }, idempotencyKey: 'self-key-0001' });
    expect((await h.json('/v1/phone/self', { method: 'DELETE', headers: ownerAuth })).status).toBe(401); // owner secret is not a phone credential
    expect((await h.json('/v1/phone/self', { method: 'DELETE', headers: phone.auth() })).status).toBe(200);
    expect((await h.json('/v1/phone/commands', { headers: phone.auth() })).status).toBe(401);
    expect((await h.json('/v1/phone/snapshot', jsonInit('PUT', { version: 5, config: {} }, phone.auth()))).status).toBe(401);
    expect((await h.relay.getCommand(command.id))!.state).toBe('expired');
    await expect(h.relay.enqueue(g, { type: 'config.apply', payload: { ops: [{ type: 'X' }] }, idempotencyKey: 'self-key-0002' })).rejects.toMatchObject({ code: 'no_phone_paired' });
    expect((await h.relay.listAudit(20)).map((e) => e.action)).toContain('phone.self_revoked');
  });

  it('rejects absurd snapshot version jumps', async () => {
    const h = makeHarness();
    const phone = new FakePhone(h);
    await phone.pair();
    await phone.publish();
    const r = await h.json('/v1/phone/snapshot', jsonInit('PUT', { version: 2 ** 53 - 1, config: {} }, phone.auth()));
    expect(r.status).toBe(400);
    expect(r.body.error).toBe('version_jump');
    expect((await phone.publish()).status).toBe(200);
  });

  it('accepts an ack v2 that binds the canonical result and rejects a tampered result; keeps v1 working', async () => {
    const h = makeHarness();
    const phone = new FakePhone(h);
    await phone.pair();
    const { token } = await h.relay.auth.createStaticToken('c', ['config:write']);
    const g = await h.relay.auth.verifyAccess(token);
    const mkCmd = async (k: string) => (await h.relay.enqueue(g, { type: 'config.apply', payload: { ops: [{ type: 'X' }] }, idempotencyKey: k })).command;
    const c1 = await mkCmd('ackv2-key-001');
    const result = { newVersion: 3, summary: 'honest summary' };
    const ackedAt = h.clock.now();
    const sig = await sign(phone.key.kp, ackMessageV2(c1, 'applied', 3, ackedAt, await sha256Hex(canonicalJson(result))));
    const post = (c: { id: string; payloadHash: string }, res: unknown, signature: string) =>
      h.json(`/v1/phone/commands/${c.id}/ack`, jsonInit('POST', { outcome: 'applied', result: res, ackedAt, payloadHash: c.payloadHash, signature, signatureVersion: 2 }, phone.auth()));
    expect((await post(c1, { ...result, summary: 'changed by a malicious relay' }, sig)).status).toBe(403);
    expect((await post(c1, result, sig)).status).toBe(200);
    expect((await h.relay.getCommand(c1.id))!.ackVersion).toBe(2);
    const c2 = await mkCmd('ackv2-key-002');
    expect((await phone.ack(c2, 'applied', { newVersion: 4 })).status).toBe(200); // v1
  });

  it('an expired command cannot be moved back to awaiting_confirmation', async () => {
    const h = makeHarness();
    const phone = new FakePhone(h);
    await phone.pair();
    const { token } = await h.relay.auth.createStaticToken('c', ['config:write']);
    const g = await h.relay.auth.verifyAccess(token);
    const { command } = await h.relay.enqueue(g, { type: 'config.apply', payload: { ops: [{ type: 'X' }] }, idempotencyKey: 'exp-key-00001', ttlSeconds: 30 });
    h.clock.advance(60_000);
    expect((await h.relay.getCommand(command.id))!.state).toBe('expired');
    const r = await phone.ack(command, 'awaiting_confirmation', { sensitivity: 'sensitive' });
    expect(r.status).toBe(200);
    expect((await h.relay.getCommand(command.id))!.state).toBe('expired');
  });

  it('the HTTP client refuses plain http to non-loopback relays', () => {
    expect(() => new HttpClientApi('http://relay.example.com', 't')).toThrow(/https/);
    expect(() => new HttpClientApi('http://localhost:8787', 't')).not.toThrow();
    expect(() => new HttpClientApi('http://127.0.0.1:8787', 't')).not.toThrow();
    expect(() => new HttpClientApi('https://relay.example.com', 't')).not.toThrow();
  });
});
