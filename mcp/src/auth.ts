import type { Store } from './store.js';
import { RelayError, SCOPES, DEFAULT_SCOPES, type Grantee, type Scope } from './types.js';
import { bytesToB64u, randomId, randomToken, sha256Hex, type Clock } from './util.js';

export interface Grant {
  id: string;
  clientId: string;
  clientLabel: string;
  scopes: string[];
  kind: 'oauth' | 'static';
  resource: string;
  createdAt: number;
  lastUsedAt?: number;
  revokedAt?: number;
}

interface AccessRec {
  grantId: string;
  resource: string;
  exp: number;
}
interface RefreshRec {
  grantId: string;
  exp: number;
  used?: boolean;
}
export interface OAuthClient {
  clientId: string;
  name: string;
  redirectUris: string[];
  kind: 'dcr' | 'cimd';
  createdAt: number;
}
interface Txn {
  clientId: string;
  clientName: string;
  redirectUri: string;
  state?: string;
  codeChallenge: string;
  requested: string[];
  resource: string;
}
interface CodeRec {
  clientId: string;
  redirectUri: string;
  codeChallenge: string;
  scopes: string[];
  resource: string;
}

export interface AuthDeps {
  store: Store;
  clock: Clock;
  baseUrl: string;
  resource: string;
  audit: (actor: string, action: string, detail?: Record<string, unknown>) => Promise<void>;
  fetchImpl: typeof fetch;
  accessTtlS: number;
  refreshTtlS: number;
}

const FORBIDDEN_SCHEMES = new Set(['javascript', 'data', 'file', 'vbscript', 'blob', 'about']);
const LOOPBACK = new Set(['localhost', '127.0.0.1', '[::1]']);

export function validRedirectUri(u: string): boolean {
  try {
    const url = new URL(u);
    if (url.hash) return false;
    const scheme = url.protocol.slice(0, -1);
    if (FORBIDDEN_SCHEMES.has(scheme)) return false;
    if (scheme === 'https') return !url.username && !url.password;
    if (scheme === 'http') return LOOPBACK.has(url.hostname);
    return true; // private-use scheme (native apps)
  } catch {
    return false;
  }
}

function redirectMatches(registered: string[], requested: string): boolean {
  if (registered.includes(requested)) return true;
  // RFC 8252 7.3: loopback redirects may vary the port.
  try {
    const r = new URL(requested);
    if (r.protocol !== 'http:' || !LOOPBACK.has(r.hostname)) return false;
    return registered.some((x) => {
      const u = new URL(x);
      return u.protocol === 'http:' && u.hostname === r.hostname && u.pathname === r.pathname && u.search === r.search;
    });
  } catch {
    return false;
  }
}

function blockedCimdHost(host: string): boolean {
  if (host === 'localhost' || host.endsWith('.localhost') || host.endsWith('.local') || host.endsWith('.internal')) return true;
  if (!host.includes('.')) return true;
  if (host.startsWith('[') || /^\d+\.\d+\.\d+\.\d+$/.test(host)) return true; // IP literals
  return false;
}

const esc = (s: string) =>
  s.replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c]!);

export class AuthService {
  constructor(private d: AuthDeps) {}

  private get now() {
    return this.d.clock.now();
  }

  // ---------------------------------------------------------------- grants / tokens

  async createGrant(clientId: string, label: string, scopes: string[], kind: Grant['kind'], resource = this.d.resource) {
    const g: Grant = { id: randomId('gr'), clientId, clientLabel: label.slice(0, 80), scopes, kind, resource, createdAt: this.now };
    await this.d.store.put('grant', g.id, g);
    return g;
  }

  async issueTokens(g: Grant, opts: { refresh: boolean; accessTtlS?: number; resource?: string }) {
    const ttl = opts.accessTtlS ?? this.d.accessTtlS;
    const access = randomToken('dca');
    const exp = this.now + ttl * 1000;
    await this.d.store.put<AccessRec>('at', await sha256Hex(access), { grantId: g.id, resource: opts.resource ?? g.resource, exp }, { expiresAt: exp });
    const out: { access_token: string; token_type: 'Bearer'; expires_in: number; scope: string; refresh_token?: string } = {
      access_token: access,
      token_type: 'Bearer',
      expires_in: ttl,
      scope: g.scopes.join(' '),
    };
    if (opts.refresh) {
      const rt = randomToken('dcr');
      const rexp = this.now + this.d.refreshTtlS * 1000;
      await this.d.store.put<RefreshRec>('rt', await sha256Hex(rt), { grantId: g.id, exp: rexp }, { expiresAt: rexp });
      out.refresh_token = rt;
    }
    return out;
  }

  /** Owner-created token for the local stdio server (or any non-OAuth client). */
  async createStaticToken(label: string, scopes: string[], expiresInDays = 90) {
    const g = await this.createGrant(`static:${label}`, label, scopes, 'static');
    const t = await this.issueTokens(g, { refresh: false, accessTtlS: expiresInDays * 86400 });
    await this.d.audit('owner', 'client_token.created', { grantId: g.id, label: g.clientLabel, scopes });
    return { grant: g, token: t.access_token, expiresAt: this.now + expiresInDays * 86400_000 };
  }

  /** Validates an access token, including audience (RFC 8707). Throws invalid_token. */
  async verifyAccess(token: string): Promise<Grantee & { expiresAt: number }> {
    const rec = await this.d.store.get<AccessRec>('at', await sha256Hex(token));
    if (!rec || rec.exp <= this.now) throw new RelayError('invalid_token', 'The access token is invalid or expired', 401);
    if (rec.resource !== this.d.resource) throw new RelayError('invalid_token', 'The access token audience does not match this server', 401);
    const g = await this.d.store.get<Grant>('grant', rec.grantId);
    if (!g || g.revokedAt || g.resource !== this.d.resource) throw new RelayError('invalid_token', 'The grant was revoked', 401);
    if (!g.lastUsedAt || this.now - g.lastUsedAt > 60_000) {
      await this.d.store.update<Grant>('grant', g.id, (c) => (c ? { ...c, lastUsedAt: this.now } : undefined));
    }
    return { grantId: g.id, clientId: g.clientId, clientLabel: g.clientLabel, scopes: g.scopes, expiresAt: rec.exp };
  }

  async listGrants(): Promise<Grant[]> {
    return (await this.d.store.list<Grant>('grant')).map((x) => x.value).sort((a, b) => b.createdAt - a.createdAt);
  }

  async revokeGrant(id: string): Promise<boolean> {
    let found = false;
    await this.d.store.update<Grant>('grant', id, (c) => {
      if (!c) return undefined;
      found = true;
      return c.revokedAt ? undefined : { ...c, revokedAt: this.now };
    });
    if (found) await this.d.audit('owner', 'grant.revoked', { grantId: id });
    return found;
  }

  async revokeAll(): Promise<number> {
    let n = 0;
    for (const g of await this.listGrants()) if (!g.revokedAt && (await this.revokeGrant(g.id))) n++;
    return n;
  }

  /** True if any active grant holds the medication scope (drives whether the phone publishes medication labels). */
  async anyMedicationGrant(): Promise<boolean> {
    return (await this.listGrants()).some((g) => !g.revokedAt && g.scopes.includes('medication'));
  }

  // ---------------------------------------------------------------- clients (DCR + CIMD)

  async registerClient(meta: Record<string, unknown>): Promise<OAuthClient & { raw: Record<string, unknown> }> {
    const uris = meta.redirect_uris;
    if (!Array.isArray(uris) || uris.length === 0 || uris.length > 10 || !uris.every((u) => typeof u === 'string' && validRedirectUri(u))) {
      throw new RelayError('invalid_redirect_uri', 'redirect_uris must be 1-10 valid https, loopback http, or private-use scheme URIs', 400);
    }
    if (meta.token_endpoint_auth_method !== undefined && meta.token_endpoint_auth_method !== 'none') {
      throw new RelayError('invalid_client_metadata', 'Only public clients (token_endpoint_auth_method=none) are supported', 400);
    }
    const existing = await this.d.store.list<OAuthClient>('client', { prefix: 'dcr:' });
    if (existing.length >= 50) {
      // Bound storage: evict the oldest unused registration instead of refusing (open DCR can be spammed).
      const oldest = existing.sort((a, b) => a.value.createdAt - b.value.createdAt)[0];
      await this.d.store.delete('client', oldest.key);
    }
    const name = typeof meta.client_name === 'string' && meta.client_name.trim() ? meta.client_name.trim().slice(0, 80) : 'Unnamed client';
    const c: OAuthClient = { clientId: randomId('dcrc'), name, redirectUris: uris as string[], kind: 'dcr', createdAt: this.now };
    // DCR registrations expire if never used to authorize (90 days) to bound storage.
    await this.d.store.put('client', `dcr:${c.clientId}`, c, { expiresAt: this.now + 90 * 86400_000 });
    await this.d.audit('client', 'client.registered', { clientId: c.clientId, name });
    return { ...c, raw: meta };
  }

  async resolveClient(clientId: string): Promise<OAuthClient> {
    if (clientId.startsWith('https://')) return this.fetchCimd(clientId);
    const c = await this.d.store.get<OAuthClient>('client', `dcr:${clientId}`);
    if (!c) throw new RelayError('invalid_client', 'Unknown client_id', 400);
    return c;
  }

  private async fetchCimd(url: string): Promise<OAuthClient> {
    const cached = await this.d.store.get<OAuthClient>('cimd', url);
    if (cached) return cached;
    let u: URL;
    try {
      u = new URL(url);
    } catch {
      throw new RelayError('invalid_client', 'Bad client_id URL', 400);
    }
    if (u.protocol !== 'https:' || u.pathname === '/' || u.username || u.password || u.hash || blockedCimdHost(u.hostname)) {
      throw new RelayError('invalid_client', 'client_id URL is not acceptable for metadata fetch', 400);
    }
    let text: string;
    try {
      const res = await this.d.fetchImpl(url, {
        redirect: 'error',
        headers: { accept: 'application/json' },
        signal: AbortSignal.timeout(5000),
      });
      if (!res.ok) throw new Error(`status ${res.status}`);
      text = await res.text();
      if (text.length > 16_384) throw new Error('document too large');
    } catch (e) {
      throw new RelayError('invalid_client', `Could not fetch client metadata: ${(e as Error).message}`, 400);
    }
    let doc: Record<string, unknown>;
    try {
      doc = JSON.parse(text);
    } catch {
      throw new RelayError('invalid_client', 'Client metadata is not JSON', 400);
    }
    const uris = doc.redirect_uris;
    if (doc.client_id !== url || !Array.isArray(uris) || uris.length === 0 || !uris.every((x) => typeof x === 'string' && validRedirectUri(x))) {
      throw new RelayError('invalid_client', 'Client metadata document is invalid (client_id must equal its URL; redirect_uris required)', 400);
    }
    if (doc.token_endpoint_auth_method !== undefined && doc.token_endpoint_auth_method !== 'none') {
      throw new RelayError('invalid_client', 'Only public clients are supported', 400);
    }
    const c: OAuthClient = {
      clientId: url,
      name: typeof doc.client_name === 'string' ? doc.client_name.slice(0, 80) : u.hostname,
      redirectUris: uris as string[],
      kind: 'cimd',
      createdAt: this.now,
    };
    await this.d.store.put('cimd', url, c, { expiresAt: this.now + 3600_000 });
    return c;
  }

  // ---------------------------------------------------------------- authorization endpoint

  /** Validates an authorization request. Errors thrown here are shown to the owner (never redirected). */
  async beginAuthorize(q: URLSearchParams): Promise<{ txnId: string; client: OAuthClient; requested: string[]; redirectUri: string }> {
    const clientId = q.get('client_id') ?? '';
    const redirectUri = q.get('redirect_uri') ?? '';
    if (!clientId) throw new RelayError('invalid_request', 'client_id is required', 400);
    const client = await this.resolveClient(clientId);
    if (!redirectUri || !redirectMatches(client.redirectUris, redirectUri)) {
      throw new RelayError('invalid_request', 'redirect_uri does not match the client registration', 400);
    }
    if (q.get('response_type') !== 'code') throw new RelayError('invalid_request', 'response_type must be code', 400);
    const challenge = q.get('code_challenge') ?? '';
    if (q.get('code_challenge_method') !== 'S256' || !/^[A-Za-z0-9_-]{43}$/.test(challenge)) {
      throw new RelayError('invalid_request', 'PKCE with code_challenge_method=S256 is required', 400);
    }
    const resource = q.get('resource');
    if (resource && resource.replace(/\/$/, '') !== this.d.resource) {
      throw new RelayError('invalid_target', `resource must be ${this.d.resource}`, 400);
    }
    const asked = (q.get('scope') ?? '').split(/\s+/).filter(Boolean);
    const requested = asked.filter((s): s is Scope => (SCOPES as readonly string[]).includes(s));
    const txn: Txn = {
      clientId,
      clientName: client.name,
      redirectUri,
      state: q.get('state') ?? undefined,
      codeChallenge: challenge,
      requested: requested.length ? requested : [...DEFAULT_SCOPES],
      resource: this.d.resource,
    };
    const txnId = randomToken('txn', 24);
    await this.d.store.put('txn', await sha256Hex(txnId), txn, { expiresAt: this.now + 10 * 60_000 });
    return { txnId, client, requested: txn.requested, redirectUri };
  }

  consentHtml(p: { txnId: string; client: OAuthClient; requested: string[]; redirectUri: string; error?: string }): string {
    const desc: Record<string, string> = {
      'config:read': 'Read your habits, routines, cue profiles and settings (redacted snapshot).',
      'config:write': 'Propose and apply configuration changes. Each change is validated on your phone and only counts once the phone confirms.',
      'sessions:control': 'Start, pause, resume or stop sessions and routines.',
      'activity:read': 'Read the coarse computer activity state (active / idle / locked / asleep).',
      medication: 'Include medication labels and schedules. Off unless you tick it.',
    };
    const boxes = SCOPES.map((s) => {
      const on = p.requested.includes(s) && s !== 'medication';
      return `<label class="sc"><input type="checkbox" name="scope" value="${s}"${on ? ' checked' : ''}> <b>${s}</b>${
        s === 'medication' && p.requested.includes(s) ? ' <em>(requested)</em>' : ''
      }<br><span>${esc(desc[s])}</span></label>`;
    }).join('');
    let host = '';
    try {
      host = new URL(p.redirectUri).host || p.redirectUri;
    } catch {
      host = p.redirectUri;
    }
    return `<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Authorize access to DayCue</title><style>
:root{color-scheme:light dark}body{font:16px system-ui,sans-serif;max-width:34rem;margin:2rem auto;padding:0 1rem}
.sc{display:block;margin:.6rem 0}.sc span{font-size:.85rem;opacity:.75}input[type=password]{width:100%;padding:.5rem;margin:.4rem 0}
button{padding:.6rem 1.2rem;margin-right:.5rem;font-size:1rem}.err{color:#b00020}</style></head><body>
<h1>Authorize access to DayCue</h1>
<p><b>${esc(p.client.name)}</b> (redirects to <code>${esc(host)}</code>) wants to connect to your DayCue relay. The name is supplied by the client and is not verified.</p>
${p.error ? `<p class="err">${esc(p.error)}</p>` : ''}
<form method="post" action="/authorize/decision"><input type="hidden" name="txn" value="${esc(p.txnId)}">
<h2>Permissions</h2>${boxes}
<label>Owner secret<input type="password" name="owner_secret" autocomplete="current-password" required></label>
<button name="decision" value="approve">Approve</button><button name="decision" value="deny" formnovalidate>Deny</button>
</form></body></html>`;
  }

  /** Returns the redirect URL to send the browser to. Throws RelayError for bad txn / owner secret (re-render). */
  async decide(txnId: string, approve: boolean, scopes: string[]): Promise<{ redirect: string }> {
    const key = await sha256Hex(txnId);
    const txn = await this.d.store.get<Txn>('txn', key);
    if (!txn) throw new RelayError('invalid_request', 'This authorization request expired. Start again from the client.', 400);
    await this.d.store.delete('txn', key);
    const u = new URL(txn.redirectUri);
    const iss = this.d.baseUrl;
    if (txn.state) u.searchParams.set('state', txn.state);
    u.searchParams.set('iss', iss);
    if (!approve) {
      u.searchParams.set('error', 'access_denied');
      await this.d.audit('owner', 'authorize.denied', { client: txn.clientName });
      return { redirect: u.toString() };
    }
    const granted = [...new Set(scopes.filter((s) => (SCOPES as readonly string[]).includes(s)))];
    if (granted.length === 0) {
      u.searchParams.set('error', 'access_denied');
      return { redirect: u.toString() };
    }
    const code = randomToken('code', 32);
    await this.d.store.put<CodeRec>(
      'code',
      await sha256Hex(code),
      { clientId: txn.clientId, redirectUri: txn.redirectUri, codeChallenge: txn.codeChallenge, scopes: granted, resource: txn.resource },
      { expiresAt: this.now + 60_000 },
    );
    u.searchParams.set('code', code);
    await this.d.audit('owner', 'authorize.approved', { client: txn.clientName, scopes: granted });
    return { redirect: u.toString() };
  }

  /** Peek a pending txn (used to re-render consent after a wrong owner secret without consuming it). */
  async peekTxn(txnId: string): Promise<Txn | undefined> {
    return this.d.store.get<Txn>('txn', await sha256Hex(txnId));
  }

  // ---------------------------------------------------------------- token endpoint

  async token(form: URLSearchParams) {
    const grantType = form.get('grant_type');
    const resource = form.get('resource');
    if (resource && resource.replace(/\/$/, '') !== this.d.resource) {
      throw new RelayError('invalid_target', `resource must be ${this.d.resource}`, 400);
    }
    if (grantType === 'authorization_code') return this.exchangeCode(form);
    if (grantType === 'refresh_token') return this.refresh(form);
    throw new RelayError('unsupported_grant_type', 'grant_type must be authorization_code or refresh_token', 400);
  }

  private async exchangeCode(form: URLSearchParams) {
    const code = form.get('code') ?? '';
    const verifier = form.get('code_verifier') ?? '';
    let rec: CodeRec | undefined;
    await this.d.store.update<CodeRec>('code', await sha256Hex(code), (c) => {
      rec = c;
      return c ? null : undefined; // single use
    });
    if (!rec) throw new RelayError('invalid_grant', 'Invalid, used or expired authorization code', 400);
    if (rec.clientId !== form.get('client_id') || rec.redirectUri !== form.get('redirect_uri')) {
      throw new RelayError('invalid_grant', 'client_id or redirect_uri mismatch', 400);
    }
    if (!/^[A-Za-z0-9._~-]{43,128}$/.test(verifier)) throw new RelayError('invalid_grant', 'Bad code_verifier', 400);
    const digest = new Uint8Array(await crypto.subtle.digest('SHA-256', new TextEncoder().encode(verifier)));
    if (bytesToB64u(digest) !== rec.codeChallenge) throw new RelayError('invalid_grant', 'PKCE verification failed', 400);
    const client = await this.resolveClient(rec.clientId);
    const g = await this.createGrant(rec.clientId, client.name, rec.scopes, 'oauth', rec.resource);
    return this.issueTokens(g, { refresh: true });
  }

  private async refresh(form: URLSearchParams) {
    const rt = form.get('refresh_token') ?? '';
    const key = await sha256Hex(rt);
    let reuse = false;
    let rec: RefreshRec | undefined;
    await this.d.store.update<RefreshRec>('rt', key, (c) => {
      rec = c;
      if (!c) return undefined;
      if (c.used) {
        reuse = true;
        return undefined;
      }
      return { ...c, used: true };
    });
    if (!rec || rec.exp <= this.now) throw new RelayError('invalid_grant', 'Invalid or expired refresh token', 400);
    if (reuse) {
      await this.revokeGrant(rec.grantId); // reuse detection
      throw new RelayError('invalid_grant', 'Refresh token reuse detected; grant revoked', 400);
    }
    const g = await this.d.store.get<Grant>('grant', rec.grantId);
    if (!g || g.revokedAt) throw new RelayError('invalid_grant', 'Grant revoked', 400);
    if (form.get('client_id') !== g.clientId) throw new RelayError('invalid_grant', 'client_id mismatch', 400);
    const asked = (form.get('scope') ?? '').split(/\s+/).filter(Boolean);
    if (asked.length && !asked.every((s) => g.scopes.includes(s))) throw new RelayError('invalid_scope', 'Cannot widen scope on refresh', 400);
    // Scope is a property of the grant (checked at use time); refresh never changes it.
    return this.issueTokens(g, { refresh: true });
  }

  async revokeToken(token: string): Promise<void> {
    const h = await sha256Hex(token);
    const at = await this.d.store.get<AccessRec>('at', h);
    const rt = await this.d.store.get<RefreshRec>('rt', h);
    const grantId = at?.grantId ?? rt?.grantId;
    if (grantId) await this.revokeGrant(grantId);
  }
}
