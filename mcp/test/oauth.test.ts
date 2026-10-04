import { describe, expect, it } from 'vitest';
import { BASE, REDIRECT, jsonInit, makeHarness, mcpClient, oauthLogin, ownerAuth, pkce, OWNER_SECRET } from './helpers.js';

const INIT = {
  jsonrpc: '2.0', id: 1, method: 'initialize',
  params: { protocolVersion: '2025-06-18', capabilities: {}, clientInfo: { name: 't', version: '1' } },
};
const mcpPost = (h: ReturnType<typeof makeHarness>, token: string | undefined, body: unknown = INIT) =>
  h.json('/mcp', {
    method: 'POST',
    headers: { 'content-type': 'application/json', accept: 'application/json, text/event-stream', ...(token ? { authorization: `Bearer ${token}` } : {}) },
    body: JSON.stringify(body),
  });

describe('discovery', () => {
  it('serves RFC 9728 protected resource metadata and RFC 8414 AS metadata', async () => {
    const h = makeHarness();
    const prm = (await h.json('/.well-known/oauth-protected-resource/mcp')).body;
    expect(prm.resource).toBe(`${BASE}/mcp`);
    expect(prm.authorization_servers).toEqual([BASE]);
    const as = (await h.json('/.well-known/oauth-authorization-server')).body;
    expect(as.issuer).toBe(BASE);
    expect(as.code_challenge_methods_supported).toEqual(['S256']);
    expect(as.authorization_response_iss_parameter_supported).toBe(true);
    expect(as.client_id_metadata_document_supported).toBe(true);
    expect(as.registration_endpoint).toBe(`${BASE}/register`);
  });

  it('401 carries WWW-Authenticate with resource_metadata and scope', async () => {
    const h = makeHarness();
    const r = await mcpPost(h, undefined);
    expect(r.status).toBe(401);
    const wa = r.headers.get('www-authenticate')!;
    expect(wa).toContain(`resource_metadata="${BASE}/.well-known/oauth-protected-resource/mcp"`);
    expect(wa).toContain('scope="config:read activity:read"');
  });
});

describe('OAuth 2.1 + PKCE happy path', () => {
  it('registers, gets consent, exchanges the code, and uses the token against /mcp', async () => {
    const h = makeHarness();
    const r = await oauthLogin(h, { scopes: ['config:read', 'activity:read'] });
    expect(r.tokenStatus).toBe(200);
    expect(r.redirect!.searchParams.get('iss')).toBe(BASE); // RFC 9207
    expect(r.redirect!.searchParams.get('state')).toBe('st123');
    expect(r.tokens.token_type).toBe('Bearer');
    expect(r.tokens.scope).toBe('config:read activity:read');
    expect(r.html).toContain('Authorize access to DayCue');
    const { client, connect } = mcpClient(h, r.tokens.access_token);
    await connect();
    const tools = (await client.listTools()).tools.map((t) => t.name).sort();
    expect(tools).toEqual(['get_activity_summary', 'get_command_status', 'get_config', 'get_status', 'list_recent_changes']);
    await client.close();
  });

  it('medication scope is unchecked by default on the consent page and only granted if ticked', async () => {
    const h = makeHarness();
    const r = await oauthLogin(h, { scopes: ['config:read', 'medication'], grant: ['config:read'] });
    expect(r.tokens.scope).toBe('config:read');
    expect(r.html).toMatch(/value="medication">/); // present but not "checked"
    expect(r.html).not.toMatch(/value="medication" checked/);
  });

  it('denies PKCE mismatch, code reuse, wrong redirect_uri, missing S256, wrong owner secret', async () => {
    const h = makeHarness();
    const reg = (await h.json('/register', jsonInit('POST', { client_name: 'c', redirect_uris: [REDIRECT] }))).body;
    const { verifier, challenge } = await pkce();
    const base = { response_type: 'code', client_id: reg.client_id, redirect_uri: REDIRECT, code_challenge: challenge, code_challenge_method: 'S256', scope: 'config:read' };
    // plain PKCE refused
    expect((await h.fetch(`/authorize?${new URLSearchParams({ ...base, code_challenge_method: 'plain' })}`)).status).toBe(400);
    // unknown redirect refused (no redirect to attacker)
    expect((await h.fetch(`/authorize?${new URLSearchParams({ ...base, redirect_uri: 'https://evil.example/cb' })}`)).status).toBe(400);
    // wrong resource refused
    expect((await h.fetch(`/authorize?${new URLSearchParams({ ...base, resource: 'https://other.example/mcp' })}`)).status).toBe(400);
    const page = await (await h.fetch(`/authorize?${new URLSearchParams(base)}`)).text();
    const txn = /name="txn" value="([^"]+)"/.exec(page)![1];
    const wrong = await h.fetch('/authorize/decision', { method: 'POST', headers: { 'content-type': 'application/x-www-form-urlencoded' }, body: new URLSearchParams({ txn, owner_secret: 'nope', decision: 'approve', scope: 'config:read' }) });
    expect(wrong.status).toBe(401);
    const dec = await h.fetch('/authorize/decision', { method: 'POST', headers: { 'content-type': 'application/x-www-form-urlencoded' }, body: new URLSearchParams({ txn, owner_secret: OWNER_SECRET, decision: 'approve', scope: 'config:read' }) });
    const code = new URL(dec.headers.get('location')!).searchParams.get('code')!;
    const tokenReq = (v: string, c = code) => h.json('/token', { method: 'POST', headers: { 'content-type': 'application/x-www-form-urlencoded' }, body: new URLSearchParams({ grant_type: 'authorization_code', code: c, code_verifier: v, client_id: reg.client_id, redirect_uri: REDIRECT }) });
    const badVerifier = await tokenReq('a'.repeat(43));
    expect(badVerifier.status).toBe(400); // code consumed by the failed attempt
    expect(badVerifier.body.error).toBe('invalid_grant');
    expect((await tokenReq(verifier)).status).toBe(400); // single use
  });

  it('owner can deny', async () => {
    const h = makeHarness();
    const reg = (await h.json('/register', jsonInit('POST', { client_name: 'c', redirect_uris: [REDIRECT] }))).body;
    const { challenge } = await pkce();
    const page = await (await h.fetch(`/authorize?${new URLSearchParams({ response_type: 'code', client_id: reg.client_id, redirect_uri: REDIRECT, code_challenge: challenge, code_challenge_method: 'S256' })}`)).text();
    const txn = /name="txn" value="([^"]+)"/.exec(page)![1];
    const dec = await h.fetch('/authorize/decision', { method: 'POST', headers: { 'content-type': 'application/x-www-form-urlencoded' }, body: new URLSearchParams({ txn, decision: 'deny' }) });
    const loc = new URL(dec.headers.get('location')!);
    expect(loc.searchParams.get('error')).toBe('access_denied');
    expect(loc.searchParams.get('iss')).toBe(BASE);
  });
});

describe('token validation', () => {
  it('rejects expired access tokens with 401 invalid_token', async () => {
    const h = makeHarness();
    const r = await oauthLogin(h, { scopes: ['config:read'] });
    expect((await mcpPost(h, r.tokens.access_token)).status).toBe(200);
    h.clock.advance(3600_001);
    const res = await mcpPost(h, r.tokens.access_token);
    expect(res.status).toBe(401);
    expect(res.headers.get('www-authenticate')).toContain('error="invalid_token"');
  });

  it('rejects a token whose audience is another resource (RFC 8707)', async () => {
    const h = makeHarness();
    const g = await h.relay.auth.createGrant('c', 'wrong audience', ['config:read'], 'oauth', 'https://other.example/mcp');
    const t = await h.relay.auth.issueTokens(g, { refresh: false });
    const res = await mcpPost(h, t.access_token);
    expect(res.status).toBe(401);
    expect(res.headers.get('www-authenticate')).toContain('invalid_token');
    // and a token minted for this resource but a grant bound to another audience also fails
    const g2 = await h.relay.auth.createGrant('c', 'ok grant', ['config:read'], 'oauth');
    const t2 = await h.relay.auth.issueTokens(g2, { refresh: false, resource: 'https://other.example/mcp' });
    expect((await mcpPost(h, t2.access_token)).status).toBe(401);
  });

  it('rejects garbage tokens and requests a resource other than this server at /token', async () => {
    const h = makeHarness();
    expect((await mcpPost(h, 'dca_garbage')).status).toBe(401);
    const r = await h.json('/token', { method: 'POST', headers: { 'content-type': 'application/x-www-form-urlencoded' }, body: new URLSearchParams({ grant_type: 'authorization_code', resource: 'https://other.example/mcp' }) });
    expect(r.body.error).toBe('invalid_target');
  });

  it('refresh tokens rotate; reuse revokes the grant', async () => {
    const h = makeHarness();
    const r = await oauthLogin(h, { scopes: ['config:read'] });
    const refresh = (rt: string) => h.json('/token', { method: 'POST', headers: { 'content-type': 'application/x-www-form-urlencoded' }, body: new URLSearchParams({ grant_type: 'refresh_token', refresh_token: rt, client_id: r.clientId }) });
    const r2 = await refresh(r.tokens.refresh_token);
    expect(r2.status).toBe(200);
    expect((await mcpPost(h, r2.body.access_token)).status).toBe(200);
    const replay = await refresh(r.tokens.refresh_token); // old token reused
    expect(replay.status).toBe(400);
    expect((await mcpPost(h, r2.body.access_token)).status).toBe(401); // grant revoked
  });
});

describe('client id metadata documents', () => {
  it('fetches and validates a CIMD, refuses mismatched or unsafe ones', async () => {
    const calls: string[] = [];
    const fetchImpl = (async (url: string) => {
      calls.push(url);
      if (url === 'https://app.example.com/client.json') {
        return new Response(JSON.stringify({ client_id: url, client_name: 'CIMD App', redirect_uris: [REDIRECT] }), { status: 200 });
      }
      return new Response(JSON.stringify({ client_id: 'https://different.example/x.json', redirect_uris: [REDIRECT] }), { status: 200 });
    }) as unknown as typeof fetch;
    const h = makeHarness({ fetchImpl });
    const { challenge } = await pkce();
    const q = (id: string) => new URLSearchParams({ response_type: 'code', client_id: id, redirect_uri: REDIRECT, code_challenge: challenge, code_challenge_method: 'S256' });
    const ok = await h.fetch(`/authorize?${q('https://app.example.com/client.json')}`);
    expect(ok.status).toBe(200);
    expect(await ok.text()).toContain('CIMD App');
    expect((await h.fetch(`/authorize?${q('https://other.example.com/client.json')}`)).status).toBe(400); // id mismatch
    const before = calls.length;
    expect((await h.fetch(`/authorize?${q('https://localhost/client.json')}`)).status).toBe(400);
    expect((await h.fetch(`/authorize?${q('https://127.0.0.1/client.json')}`)).status).toBe(400);
    expect((await h.fetch(`/authorize?${q('http://app.example.com/client.json')}`)).status).toBe(400);
    expect(calls.length).toBe(before); // never fetched unsafe URLs
  });
});

describe('revocation', () => {
  it('owner revoking a grant immediately invalidates its tokens; revoke-all works; audit records it', async () => {
    const h = makeHarness();
    const r = await oauthLogin(h, { scopes: ['config:read'] });
    expect((await mcpPost(h, r.tokens.access_token)).status).toBe(200);
    const grants = (await h.json('/v1/owner/grants', { headers: ownerAuth })).body.grants;
    expect(grants).toHaveLength(1);
    await h.json(`/v1/owner/grants/${grants[0].id}`, { method: 'DELETE', headers: ownerAuth });
    expect((await mcpPost(h, r.tokens.access_token)).status).toBe(401);
    const r2 = await oauthLogin(h, { scopes: ['config:read'] });
    await h.json('/v1/owner/revoke-all', { method: 'POST', headers: ownerAuth });
    expect((await mcpPost(h, r2.tokens.access_token)).status).toBe(401);
    const audit = (await h.json('/v1/owner/audit', { headers: ownerAuth })).body.entries.map((e: any) => e.action);
    expect(audit).toContain('grant.revoked');
    expect(audit).toContain('authorize.approved');
  });

  it('RFC 7009 /revoke revokes the grant', async () => {
    const h = makeHarness();
    const r = await oauthLogin(h, { scopes: ['config:read'] });
    await h.fetch('/revoke', { method: 'POST', headers: { 'content-type': 'application/x-www-form-urlencoded' }, body: new URLSearchParams({ token: r.tokens.refresh_token }) });
    expect((await mcpPost(h, r.tokens.access_token)).status).toBe(401);
  });
});
