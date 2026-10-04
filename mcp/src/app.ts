import { Hono, type Context } from 'hono';
import { cors } from 'hono/cors';
import { bodyLimit } from 'hono/body-limit';
import { getCookie, setCookie } from 'hono/cookie';
import { createMcpHandler } from '@modelcontextprotocol/server';
import { Relay } from './relay.js';
import { LocalClientApi, CLIENT_OPS } from './clientApi.js';
import { buildMcpServer, TOOL_SCOPE } from './tools.js';
import { RelayError, SCOPES, DEFAULT_SCOPES, hasScope } from './types.js';

export interface AppOptions {
  /**
   * Number of reverse proxies in front of the relay that append to X-Forwarded-For (Render: 1). The client address is the
   * entry that many hops from the RIGHT (entries further left are client-controlled and spoofable). 0 = do not trust the header.
   */
  trustProxyHops?: number;
  /** Override how the source address is determined (Node server uses the socket address when no proxy is trusted). */
  sourceOf?: (c: Context) => string | undefined;
}

const KiB = 1024;
const BODY_LIMITS = { default: 64 * KiB, snapshot: 300 * KiB, mcp: 128 * KiB };

/** Platform-neutral HTTP app (Hono, web-standard Request/Response): runs under Node, Workers, Deno, Bun. */
export function createApp(relay: Relay, opts: AppOptions = {}): Hono {
  const app = new Hono();
  const hops = opts.trustProxyHops ?? 0;
  const source = (c: Context): string => {
    const custom = opts.sourceOf?.(c);
    if (custom) return custom;
    if (hops > 0) {
      const parts = (c.req.header('x-forwarded-for') ?? '').split(',').map((x) => x.trim()).filter(Boolean);
      const ip = parts[parts.length - hops];
      if (ip) return ip.slice(0, 64);
    }
    return 'unknown';
  };
  const secureCookie = relay.issuer.startsWith('https://');

  // Request size limits (checked on Content-Length and while streaming), before any body is parsed.
  const limiters = new Map<number, ReturnType<typeof bodyLimit>>();
  const limitFor = (n: number) => {
    let l = limiters.get(n);
    if (!l) {
      l = bodyLimit({ maxSize: n, onError: (c) => c.json({ error: 'too_large', error_description: `Request body exceeds ${n} bytes`, message: `Request body exceeds ${n} bytes` }, 413) });
      limiters.set(n, l);
    }
    return l;
  };
  app.use('*', async (c, next) => {
    const p = c.req.path;
    const max = p === '/v1/phone/snapshot' ? BODY_LIMITS.snapshot : p === '/mcp' || p.startsWith('/v1/client/') ? BODY_LIMITS.mcp : BODY_LIMITS.default;
    return limitFor(max)(c, next);
  });

  // Per-source rate limits (in memory; see src/limits.ts). Health checks are exempt.
  const rate = (c: Context, name: string, limit: number, windowMs: number) => relay.limiter.hit(`${name}:${source(c)}`, limit, windowMs);
  app.use('*', async (c, next) => {
    if (c.req.path !== '/healthz') rate(c, 'all', 600, 60_000);
    await next();
  });
  app.use('/register', async (c, next) => {
    if (c.req.method === 'POST') rate(c, 'register', 5, 3600_000);
    await next();
  });
  app.use('/authorize', async (c, next) => {
    if (c.req.method === 'GET') rate(c, 'authorize', 20, 600_000);
    await next();
  });
  app.use('/token', async (c, next) => {
    rate(c, 'token', 60, 60_000);
    await next();
  });
  app.use('/revoke', async (c, next) => {
    rate(c, 'revoke', 30, 60_000);
    await next();
  });
  app.use('/v1/pair/*', async (c, next) => {
    rate(c, 'pair', 30, 600_000);
    await next();
  });
  const prmUrl = `${relay.issuer}/.well-known/oauth-protected-resource/mcp`;
  const baseScopes = DEFAULT_SCOPES.join(' ');

  // DNS-rebinding protection for local development.
  const base = new URL(relay.issuer);
  if (['localhost', '127.0.0.1', '[::1]'].includes(base.hostname)) {
    app.use('*', async (c, next) => {
      const host = (c.req.header('host') ?? '').replace(/:\d+$/, '');
      if (!['localhost', '127.0.0.1', '[::1]'].includes(host)) return c.text('Invalid Host header', 421);
      await next();
    });
  }

  const open = cors({ origin: '*', allowHeaders: ['*'], exposeHeaders: ['WWW-Authenticate', 'Mcp-Session-Id'], allowMethods: ['GET', 'POST', 'DELETE', 'OPTIONS'] });
  for (const p of ['/.well-known/*', '/token', '/register', '/revoke', '/mcp', '/v1/client/*']) app.use(p, open);

  const challenge = (parts: { error?: string; description?: string; scope?: string }) =>
    ['Bearer', [
      parts.error ? `error="${parts.error}"` : '',
      parts.description ? `error_description="${parts.description.replace(/"/g, "'")}"` : '',
      parts.scope ? `scope="${parts.scope}"` : '',
      `resource_metadata="${prmUrl}"`,
    ].filter(Boolean).join(', ')].join(' ');

  app.onError((err, c) => {
    if (err instanceof RelayError) {
      const headers: Record<string, string> = {};
      if (err.code === 'missing_token') headers['WWW-Authenticate'] = challenge({ scope: baseScopes });
      else if (err.code === 'invalid_token') headers['WWW-Authenticate'] = challenge({ error: 'invalid_token', description: err.message });
      else if (err.code === 'insufficient_scope') headers['WWW-Authenticate'] = challenge({ error: 'insufficient_scope', scope: (err.requiredScopes ?? []).join(' '), description: err.message });
      if (err.retryAfterS) headers['Retry-After'] = String(err.retryAfterS);
      return c.json(
        { error: err.code === 'missing_token' ? 'unauthorized' : err.code, error_description: err.message, message: err.message, requiredScopes: err.requiredScopes },
        { status: (err.code === 'missing_token' ? 401 : err.status) as any, headers },
      );
    }
    console.error('unhandled error', err);
    return c.json({ error: 'server_error', message: 'Internal error' }, 500);
  });

  const bearer = (c: Context) => {
    const h = c.req.header('authorization') ?? '';
    const m = /^Bearer\s+(\S+)$/i.exec(h);
    return m?.[1];
  };
  const json = async (c: Context): Promise<any> => {
    try {
      return await c.req.json();
    } catch {
      throw new RelayError('invalid_request', 'Body must be JSON', 400);
    }
  };
  const owner = async (c: Context) => relay.assertOwner(bearer(c), source(c), 'api');
  /** Revocation / kill path: not subject to failure lockouts, only to a per-source rate limit. */
  const ownerEmergency = async (c: Context) => relay.assertOwnerEmergency(bearer(c), source(c));
  const phone = async (c: Context) => relay.authDevice(bearer(c), 'phone');
  const companion = async (c: Context) => relay.authDevice(bearer(c), 'companion');
  const client = async (c: Context) => {
    const t = bearer(c);
    if (!t) throw new RelayError('missing_token', 'Authorization required', 401);
    return relay.auth.verifyAccess(t);
  };

  app.get('/healthz', (c) => c.json({ ok: true }));
  app.get('/', (c) => c.text('DayCue relay. MCP endpoint: /mcp\n'));

  // ------------------------------------------------------------ discovery
  const prm = (c: Context) =>
    c.json({
      resource: relay.resource,
      authorization_servers: [relay.issuer],
      scopes_supported: [...SCOPES],
      bearer_methods_supported: ['header'],
      resource_name: 'DayCue relay',
    });
  app.get('/.well-known/oauth-protected-resource', prm);
  app.get('/.well-known/oauth-protected-resource/mcp', prm);
  const asMeta = (c: Context) =>
    c.json({
      issuer: relay.issuer,
      authorization_endpoint: `${relay.issuer}/authorize`,
      token_endpoint: `${relay.issuer}/token`,
      registration_endpoint: `${relay.issuer}/register`,
      revocation_endpoint: `${relay.issuer}/revoke`,
      scopes_supported: [...SCOPES],
      response_types_supported: ['code'],
      response_modes_supported: ['query'],
      grant_types_supported: ['authorization_code', 'refresh_token'],
      code_challenge_methods_supported: ['S256'],
      token_endpoint_auth_methods_supported: ['none'],
      authorization_response_iss_parameter_supported: true,
      client_id_metadata_document_supported: true,
    });
  app.get('/.well-known/oauth-authorization-server', asMeta);
  app.get('/.well-known/openid-configuration', asMeta); // clients may probe OIDC discovery too

  // ------------------------------------------------------------ OAuth authorization server
  const html = (c: Context, body: string, status = 200) => {
    c.header('Content-Security-Policy', "default-src 'none'; style-src 'unsafe-inline'; frame-ancestors 'none'; base-uri 'none'");
    c.header('X-Frame-Options', 'DENY');
    c.header('Cache-Control', 'no-store');
    c.header('Referrer-Policy', 'no-referrer');
    return c.html(body, status as any);
  };
  const errPage = (c: Context, msg: string, status = 400) =>
    html(c, `<!doctype html><meta charset="utf-8"><title>DayCue</title><p>${msg.replace(/[<>&]/g, '')}</p>`, status);

  app.post('/register', async (c) => {
    const meta = await json(c);
    const reg = await relay.auth.registerClient(meta);
    return c.json(
      {
        client_id: reg.clientId,
        client_name: reg.name,
        redirect_uris: reg.redirectUris,
        grant_types: ['authorization_code', 'refresh_token'],
        response_types: ['code'],
        token_endpoint_auth_method: 'none',
      },
      201,
    );
  });

  app.get('/authorize', async (c) => {
    try {
      const r = await relay.auth.beginAuthorize(new URL(c.req.url).searchParams);
      // Double-submit CSRF token: cookie here, same value in the form, hash bound to the transaction.
      setCookie(c, 'daycue_csrf', r.csrf, { path: '/authorize', httpOnly: true, sameSite: 'Strict', secure: secureCookie, maxAge: 600 });
      return html(c, relay.auth.consentHtml(r));
    } catch (e) {
      if (e instanceof RelayError) return errPage(c, `Authorization request rejected: ${e.message}`, 400);
      throw e;
    }
  });

  app.post('/authorize/decision', async (c) => {
    rate(c, 'decision', 30, 600_000);
    // Anti-CSRF layer 1: browsers state where a form post came from.
    const origin = c.req.header('origin');
    const sfs = c.req.header('sec-fetch-site');
    if ((origin && origin !== relay.issuer) || (sfs && sfs !== 'same-origin')) return errPage(c, 'Cross-site request refused.', 403);
    const f = await c.req.parseBody({ all: true });
    const txn = String(f.txn ?? '');
    const t = await relay.auth.peekTxn(txn);
    if (!t) return errPage(c, 'This authorization request expired. Start again from the client.', 400);
    // Anti-CSRF layer 2: cookie must equal the form token and match the transaction (checked before any secret is compared).
    if (!(await relay.auth.csrfValid(t, getCookie(c, 'daycue_csrf'), String(f.csrf ?? '')))) {
      return errPage(c, 'Security check failed. Open the authorization link again from the client.', 403);
    }
    const approve = f.decision === 'approve';
    const scopes = ([] as unknown[]).concat(f.scope ?? []).map(String);
    const rerender = async (msg: string, status: number) => {
      const client = await relay.auth.resolveClient(t.clientId);
      return html(c, relay.auth.consentHtml({ txnId: txn, client, requested: t.requested, redirectUri: t.redirectUri, csrf: String(f.csrf), error: msg }), status);
    };
    const confirmUntrusted = f.confirm_untrusted === 'yes';
    if (approve) {
      if (relay.auth.redirectTrust(t.redirectUri) === 'untrusted' && !confirmUntrusted) {
        return rerender('This redirect address is not recognized. Tick the confirmation box to continue, or press Deny.', 400);
      }
      try {
        await relay.assertOwner(String(f.owner_secret ?? ''), source(c), 'consent');
      } catch (e) {
        if (!(e instanceof RelayError)) throw e;
        if (e.retryAfterS) c.header('Retry-After', String(e.retryAfterS));
        if (e.code === 'unauthorized') return rerender('Wrong owner secret.', 401);
        return errPage(c, e.message, e.status);
      }
    }
    try {
      const r = await relay.auth.decide(txn, approve, scopes, { confirmUntrusted });
      if (r.deniedLocally) return html(c, '<!doctype html><meta charset="utf-8"><title>DayCue</title><p>Request denied. Nothing was shared and you were not redirected. You can close this page.</p>');
      return c.redirect(r.redirect!, 302);
    } catch (e) {
      if (e instanceof RelayError) return errPage(c, e.message, e.status);
      throw e;
    }
  });

  const oauthJson = (c: Context, body: unknown, status = 200) => {
    c.header('Cache-Control', 'no-store');
    c.header('Pragma', 'no-cache');
    return c.json(body as any, status as any);
  };
  const oauthErr = (c: Context, e: unknown) => {
    if (!(e instanceof RelayError)) throw e;
    return oauthJson(c, { error: e.code, error_description: e.message }, e.status === 401 ? 400 : e.status);
  };
  app.post('/token', async (c) => {
    try {
      const body = await c.req.parseBody();
      const form = new URLSearchParams(Object.entries(body).map(([k, v]) => [k, String(v)] as [string, string]));
      return oauthJson(c, await relay.auth.token(form));
    } catch (e) {
      return oauthErr(c, e);
    }
  });
  app.post('/revoke', async (c) => {
    const body = await c.req.parseBody();
    if (body.token) await relay.auth.revokeToken(String(body.token));
    return oauthJson(c, {});
  });

  // ------------------------------------------------------------ owner API (Bearer <owner secret>)
  app.post('/v1/owner/pair-codes', async (c) => {
    await owner(c);
    return c.json(await relay.createPairCode('phone', 'owner'));
  });
  app.post('/v1/owner/client-tokens', async (c) => {
    await owner(c);
    const b = await json(c);
    const scopes = (Array.isArray(b.scopes) ? b.scopes : [...DEFAULT_SCOPES]).map(String);
    if (!scopes.every((s: string) => (SCOPES as readonly string[]).includes(s))) throw new RelayError('invalid_scope', `scopes must be among ${SCOPES.join(', ')}`, 400);
    const days = Math.min(Math.max(Number(b.expiresInDays ?? 90), 1), 365);
    const r = await relay.auth.createStaticToken(String(b.label ?? 'claude-code').slice(0, 60), scopes, days);
    return c.json({ token: r.token, grantId: r.grant.id, scopes, expiresAt: new Date(r.expiresAt).toISOString(), note: 'Shown once. Store it in an environment variable, never in the repo.' }, 201);
  });
  app.get('/v1/owner/grants', async (c) => {
    await owner(c);
    return c.json({ grants: (await relay.auth.listGrants()).map((g) => ({ id: g.id, client: g.clientLabel, kind: g.kind, scopes: g.scopes, createdAt: new Date(g.createdAt).toISOString(), lastUsedAt: g.lastUsedAt ? new Date(g.lastUsedAt).toISOString() : null, revoked: !!g.revokedAt })) });
  });
  app.delete('/v1/owner/grants/:id', async (c) => {
    await ownerEmergency(c);
    return c.json({ revoked: await relay.auth.revokeGrant(c.req.param('id')) });
  });
  app.post('/v1/owner/revoke-all', async (c) => {
    await ownerEmergency(c);
    return c.json({ revoked: await relay.auth.revokeAll() });
  });
  app.get('/v1/owner/devices', async (c) => {
    await owner(c);
    return c.json({ devices: await relay.listDevices() });
  });
  app.delete('/v1/owner/devices/:id', async (c) => {
    await ownerEmergency(c);
    await relay.revokeDeviceById(c.req.param('id'));
    return c.json({ ok: true });
  });
  app.get('/v1/owner/audit', async (c) => {
    await owner(c);
    return c.json({ entries: await relay.listAudit(Number(c.req.query('limit') ?? 100)) });
  });

  // ------------------------------------------------------------ phone
  app.post('/v1/pair/phone', async (c) => c.json(await relay.pairPhone(await json(c), source(c)), 201));
  app.put('/v1/phone/snapshot', async (c) => c.json(await relay.putSnapshot(await phone(c), await json(c))));
  app.get('/v1/phone/commands', async (c) => c.json(await relay.pullCommands(await phone(c))));
  app.post('/v1/phone/commands/:id/ack', async (c) => c.json(await relay.ackCommand(await phone(c), c.req.param('id'), await json(c))));
  app.post('/v1/phone/companion-codes', async (c) => c.json(await relay.createCompanionCode(await phone(c))));
  app.put('/v1/phone/push', async (c) => {
    await relay.setPush(await phone(c), await json(c));
    return c.json({ ok: true });
  });
  app.delete('/v1/phone/self', async (c) => c.json(await relay.revokePhoneSelf(await phone(c))));
  app.get('/v1/phone/grants', async (c) => {
    await phone(c);
    return c.json({ grants: await relay.auth.grantsForPhone(), serverTime: relay.clock.now() });
  });
  app.post('/v1/phone/grants/:id/decision', async (c) => c.json(await relay.phoneGrantDecision(await phone(c), c.req.param('id'), await json(c))));
  app.get('/v1/phone/activity', async (c) => {
    await phone(c);
    return c.json(await relay.activityForPhone());
  });

  // ------------------------------------------------------------ companion
  app.post('/v1/pair/companion', async (c) => c.json(await relay.pairCompanion(await json(c), source(c)), 201));
  app.post('/v1/companion/signal', async (c) => c.json(await relay.postSignal(await companion(c), await json(c))));
  app.delete('/v1/companion/self', async (c) => c.json(await relay.revokeCompanionSelf(await companion(c))));

  // ------------------------------------------------------------ client ops (used by the stdio server)
  app.post('/v1/client/:op', async (c) => {
    const g = await client(c);
    const op = c.req.param('op');
    if (!(CLIENT_OPS as readonly string[]).includes(op)) throw new RelayError('not_found', 'Unknown op', 404);
    const api = new LocalClientApi(relay, g) as any;
    const body = await c.req.json().catch(() => ({}));
    return c.json(await api[op](body));
  });

  // ------------------------------------------------------------ MCP (Streamable HTTP)
  app.all('/mcp', async (c) => {
    const token = bearer(c);
    if (!token) throw new RelayError('missing_token', 'Authorization required', 401);
    const g = await relay.auth.verifyAccess(token);
    if (c.req.method === 'POST') {
      const body = await c.req.raw.clone().json().catch(() => undefined);
      for (const m of Array.isArray(body) ? body : [body]) {
        if (m && m.method === 'tools/call') {
          const raw = TOOL_SCOPE[String(m.params?.name)];
          const needs = raw === undefined ? [] : Array.isArray(raw) ? raw : [raw];
          if (needs.length && !needs.some((n) => hasScope(g.scopes, n))) {
            const waiting = needs.some((n) => g.pendingScopes?.includes(n));
            throw new RelayError(
              'insufficient_scope',
              waiting
                ? `Tool ${m.params.name} needs the ${needs[0]} scope, which the owner granted but which is inactive until the phone approves this client. Ask the owner to approve it in the DayCue app`
                : `Tool ${m.params.name} requires the ${needs[0]} scope`,
              403,
              [needs[0]],
            );
          }
        }
      }
    }
    const handler = createMcpHandler(() => buildMcpServer(new LocalClientApi(relay, g), g.scopes), { responseMode: 'json' });
    return handler.fetch(c.req.raw, {
      authInfo: { token, clientId: g.clientId, scopes: g.scopes, expiresAt: Math.floor(g.expiresAt / 1000), resource: new URL(relay.resource) },
    });
  });

  return app;
}
