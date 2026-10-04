import type { Store } from './store.js';
import { RelayError, SCOPES, DEFAULT_SCOPES, PHONE_GATED_SCOPES, type Grantee, type Scope } from './types.js';
import { blockedHostName } from './netguard.js';
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
  /** Phone approval state. Missing (legacy) means approved. Gated scopes are inactive while 'pending'. */
  approval?: GrantApproval;
  approvedAt?: number;
}
export type GrantApproval = 'pending' | 'approved' | 'declined' | 'not_required';

/** Scopes that are actually usable right now. */
export function activeScopes(g: Pick<Grant, 'scopes' | 'approval'>): string[] {
  return g.approval === 'pending' ? g.scopes.filter((s) => !(PHONE_GATED_SCOPES as readonly string[]).includes(s)) : g.scopes;
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
  /** Set when the owner approved a consent for this client (then it is never evicted while a grant exists). */
  approvedAt?: number;
}
interface Txn {
  clientId: string;
  clientName: string;
  redirectUri: string;
  state?: string;
  codeChallenge: string;
  requested: string[];
  resource: string;
  createdAt: number;
  csrfHash: string;
}
interface CodeRec {
  clientId: string;
  redirectUri: string;
  codeChallenge: string;
  scopes: string[];
  resource: string;
  exp: number;
  used?: boolean;
  grantId?: string;
  reused?: boolean;
}

export type RedirectTrust = 'trusted' | 'loopback' | 'untrusted';
/** Official callback hosts of the two big connector clients (see docs/architecture/RELAY.md 3.1 for sources). */
export const DEFAULT_TRUSTED_REDIRECT_HOSTS = ['claude.ai', 'claude.com', 'chatgpt.com'];

export const LIMITS = { dcrClients: 50, pendingTxns: 100, cimdCache: 100, cimdMaxBytes: 16_384, cimdTimeoutMs: 5000 };

export type CimdFetch = (url: string, o: { maxBytes: number; timeoutMs: number }) => Promise<{ status: number; text: string }>;

/** Default CIMD fetcher over a fetch implementation: no redirects, streaming byte cap, timeout. */
export function fetchWithCap(fetchImpl: typeof fetch): CimdFetch {
  return async (url, o) => {
    const res = await fetchImpl(url, { redirect: 'error', headers: { accept: 'application/json' }, signal: AbortSignal.timeout(o.timeoutMs) });
    const declared = Number(res.headers.get('content-length') ?? 0);
    if (declared > o.maxBytes) throw new Error('document too large');
    if (!res.body) {
      const t = await res.text();
      if (t.length > o.maxBytes) throw new Error('document too large');
      return { status: res.status, text: t };
    }
    const reader = res.body.getReader();
    const chunks: Uint8Array[] = [];
    let size = 0;
    for (;;) {
      const { done, value } = await reader.read();
      if (done) break;
      size += value.length;
      if (size > o.maxBytes) {
        await reader.cancel().catch(() => {});
        throw new Error('document too large');
      }
      chunks.push(value);
    }
    const all = new Uint8Array(size);
    let off = 0;
    for (const c of chunks) {
      all.set(c, off);
      off += c.length;
    }
    return { status: res.status, text: new TextDecoder().decode(all) };
  };
}

export function classifyRedirect(uri: string, trustedHosts: readonly string[]): RedirectTrust {
  try {
    const u = new URL(uri);
    if (u.protocol === 'http:' && LOOPBACK.has(u.hostname)) return 'loopback';
    if (u.protocol === 'https:' && trustedHosts.includes(u.hostname.toLowerCase())) return 'trusted';
  } catch {
    /* fall through */
  }
  return 'untrusted';
}

export interface AuthDeps {
  store: Store;
  clock: Clock;
  baseUrl: string;
  resource: string;
  audit: (actor: string, action: string, detail?: Record<string, unknown>) => Promise<void>;
  fetchImpl: typeof fetch;
  /** Hardened fetcher for client metadata documents (resolves and vets DNS). Defaults to a capped fetchImpl wrapper. */
  cimdFetch?: CimdFetch;
  accessTtlS: number;
  refreshTtlS: number;
  trustedRedirectHosts: string[];
  requirePhoneApproval: boolean;
  phonePaired: () => Promise<boolean>;
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
    return false; // MCP authorization: redirect URIs MUST be localhost or HTTPS (private-use schemes are refused)
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

function blockedCimdHost(u: URL): boolean {
  return blockedHostName(u.hostname) || (u.port !== '' && u.port !== '443');
}

const esc = (s: string) =>
  s.replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c]!);

export class AuthService {
  constructor(private d: AuthDeps) {}

  private get now() {
    return this.d.clock.now();
  }

  // ---------------------------------------------------------------- grants / tokens

  async createGrant(clientId: string, label: string, scopes: string[], kind: Grant['kind'], resource = this.d.resource, id = randomId('gr')) {
    const gated = scopes.some((s) => (PHONE_GATED_SCOPES as readonly string[]).includes(s));
    const needsPhone = this.d.requirePhoneApproval && gated && (await this.d.phonePaired());
    const g: Grant = { id, clientId, clientLabel: label.slice(0, 80), scopes, kind, resource, createdAt: this.now, approval: needsPhone ? 'pending' : 'not_required' };
    await this.d.store.put('grant', g.id, g);
    await this.bumpGrantsVersion();
    return g;
  }

  /** Monotonic counter the phone compares to learn that the grant list changed. */
  async bumpGrantsVersion(): Promise<void> {
    await this.d.store.update<number>('meta', 'grantsVersion', (c) => (c ?? 0) + 1);
  }
  async grantsVersion(): Promise<number> {
    return (await this.d.store.get<number>('meta', 'grantsVersion')) ?? 0;
  }

  /** Grants as shown to the phone (id, label, scopes, approval state). Revoked grants are omitted. */
  async grantsForPhone() {
    const items = (await this.listGrants())
      .filter((g) => !g.revokedAt)
      .map((g) => ({
        id: g.id,
        label: g.clientLabel,
        kind: g.kind,
        scopes: g.scopes,
        activeScopes: activeScopes(g),
        approval: g.approval ?? 'approved',
        createdAt: g.createdAt,
        lastUsedAt: g.lastUsedAt ?? null,
      }));
    return { version: await this.grantsVersion(), items };
  }

  /**
   * Phone decision on a grant (signature already verified by the relay).
   * approve: activates gated scopes (optionally narrowed to approvedScopes); decline: revokes; revoke: revokes any grant.
   */
  async phoneDecideGrant(id: string, decision: 'approve' | 'decline' | 'revoke', approvedScopes?: string[]): Promise<{ ok: boolean; approval?: GrantApproval; revoked?: boolean }> {
    const g = await this.d.store.get<Grant>('grant', id);
    if (!g || g.revokedAt) throw new RelayError('not_found', 'Unknown or already revoked grant', 404);
    if (decision === 'approve') {
      if (approvedScopes && (approvedScopes.length === 0 || !approvedScopes.every((x) => g.scopes.includes(x)))) {
        throw new RelayError('invalid_request', 'approvedScopes must be a non-empty subset of the grant scopes', 400);
      }
      await this.d.store.update<Grant>('grant', id, (c) => (c ? { ...c, scopes: approvedScopes ?? c.scopes, approval: 'approved', approvedAt: this.now } : undefined));
      await this.bumpGrantsVersion();
      await this.d.audit('phone', 'grant.phone_approved', { grantId: id, scopes: approvedScopes ?? g.scopes });
      return { ok: true, approval: 'approved' };
    }
    await this.d.store.update<Grant>('grant', id, (c) => (c ? { ...c, revokedAt: this.now, approval: decision === 'decline' ? 'declined' : c.approval } : undefined));
    await this.bumpGrantsVersion();
    await this.d.audit('phone', decision === 'decline' ? 'grant.phone_declined' : 'grant.phone_revoked', { grantId: id });
    return { ok: true, revoked: true, approval: decision === 'decline' ? 'declined' : g.approval };
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
    await this.d.audit('owner', 'client_token.created', { grantId: g.id, label: g.clientLabel, scopes, approval: g.approval });
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
    const eff = activeScopes(g);
    const pendingScopes = g.scopes.filter((x) => !eff.includes(x));
    return { grantId: g.id, clientId: g.clientId, clientLabel: g.clientLabel, scopes: eff, pendingScopes: pendingScopes.length ? pendingScopes : undefined, expiresAt: rec.exp };
  }

  async listGrants(): Promise<Grant[]> {
    return (await this.d.store.list<Grant>('grant')).map((x) => x.value).sort((a, b) => b.createdAt - a.createdAt);
  }

  async revokeGrant(id: string): Promise<boolean> {
    let found = false;
    let changed = false;
    await this.d.store.update<Grant>('grant', id, (c) => {
      if (!c) return undefined;
      found = true;
      if (c.revokedAt) return undefined;
      changed = true;
      return { ...c, revokedAt: this.now };
    });
    if (changed) {
      await this.bumpGrantsVersion();
      await this.d.audit('owner', 'grant.revoked', { grantId: id });
    }
    return found;
  }

  async revokeAll(): Promise<number> {
    let n = 0;
    for (const g of await this.listGrants()) if (!g.revokedAt && (await this.revokeGrant(g.id))) n++;
    return n;
  }

  /** True if any active grant holds an ACTIVE medication scope (drives whether the phone publishes medication labels). */
  async anyMedicationGrant(): Promise<boolean> {
    return (await this.listGrants()).some((g) => !g.revokedAt && activeScopes(g).includes('medication'));
  }

  // ---------------------------------------------------------------- clients (DCR + CIMD)

  async registerClient(meta: Record<string, unknown>): Promise<OAuthClient & { raw: Record<string, unknown> }> {
    const uris = meta.redirect_uris;
    if (!Array.isArray(uris) || uris.length === 0 || uris.length > 10 || !uris.every((u) => typeof u === 'string' && validRedirectUri(u))) {
      throw new RelayError('invalid_redirect_uri', 'redirect_uris must be 1-10 valid https or loopback http URIs', 400);
    }
    if (meta.token_endpoint_auth_method !== undefined && meta.token_endpoint_auth_method !== 'none') {
      throw new RelayError('invalid_client_metadata', 'Only public clients (token_endpoint_auth_method=none) are supported', 400);
    }
    const existing = await this.d.store.list<OAuthClient>('client', { prefix: 'dcr:' });
    if (existing.length >= LIMITS.dcrClients) {
      // Never evict a client that was approved or holds an active grant; spam can only displace unused registrations.
      const used = new Set((await this.listGrants()).filter((g) => !g.revokedAt).map((g) => g.clientId));
      const evictable = existing.filter((x) => !x.value.approvedAt && !used.has(x.value.clientId)).sort((a, b) => a.value.createdAt - b.value.createdAt);
      if (!evictable.length) throw new RelayError('too_many_clients', 'Client registration limit reached. Try again later.', 429, undefined, 3600);
      await this.d.store.delete('client', evictable[0].key);
    }
    const name = typeof meta.client_name === 'string' && meta.client_name.trim() ? meta.client_name.trim().slice(0, 80) : 'Unnamed client';
    const c: OAuthClient = { clientId: randomId('dcrc'), name, redirectUris: uris as string[], kind: 'dcr', createdAt: this.now };
    // Unused registrations expire after 7 days (90 days once the owner approved a consent). Not audited: unauthenticated and spammable.
    await this.d.store.put('client', `dcr:${c.clientId}`, c, { expiresAt: this.now + 7 * 86400_000 });
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
    if (u.protocol !== 'https:' || u.pathname === '/' || u.username || u.password || u.hash || blockedCimdHost(u)) {
      throw new RelayError('invalid_client', 'client_id URL is not acceptable for metadata fetch', 400);
    }
    let text: string;
    try {
      const fetcher = this.d.cimdFetch ?? fetchWithCap(this.d.fetchImpl);
      const res = await fetcher(url, { maxBytes: LIMITS.cimdMaxBytes, timeoutMs: LIMITS.cimdTimeoutMs });
      if (res.status < 200 || res.status >= 300) throw new Error(`status ${res.status}`);
      text = res.text;
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
    await this.evictOldest<OAuthClient>('cimd', LIMITS.cimdCache, (v) => v.createdAt);
    await this.d.store.put('cimd', url, c, { expiresAt: this.now + 3600_000 });
    return c;
  }

  /** Keeps a namespace below cap entries by deleting the oldest (called before inserting one more). */
  private async evictOldest<T>(ns: string, cap: number, createdAt: (v: T) => number) {
    const all = await this.d.store.list<T>(ns);
    if (all.length < cap) return;
    all.sort((a, b) => createdAt(a.value) - createdAt(b.value));
    for (const x of all.slice(0, all.length - cap + 1)) await this.d.store.delete(ns, x.key);
  }

  // ---------------------------------------------------------------- authorization endpoint

  /**
   * Validates an authorization request. Errors thrown here are shown to the owner (never redirected).
   * Returns a one-time CSRF token that the caller must set as a cookie AND embed in the consent form.
   */
  async beginAuthorize(q: URLSearchParams): Promise<{ txnId: string; client: OAuthClient; requested: string[]; redirectUri: string; csrf: string }> {
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
    const csrf = randomToken('csrf', 24);
    const txn: Txn = {
      clientId,
      clientName: client.name,
      redirectUri,
      state: (q.get('state') ?? undefined)?.slice(0, 2048),
      codeChallenge: challenge,
      requested: requested.length ? requested : [...DEFAULT_SCOPES],
      resource: this.d.resource,
      createdAt: this.now,
      csrfHash: await sha256Hex(csrf),
    };
    await this.evictOldest<Txn>('txn', LIMITS.pendingTxns, (v) => v.createdAt);
    const txnId = randomToken('txn', 24);
    await this.d.store.put('txn', await sha256Hex(txnId), txn, { expiresAt: this.now + 10 * 60_000 });
    return { txnId, client, requested: txn.requested, redirectUri, csrf };
  }

  redirectTrust(redirectUri: string): RedirectTrust {
    return classifyRedirect(redirectUri, this.d.trustedRedirectHosts);
  }

  /** True when the double-submit CSRF values (cookie and form field) match the token bound to the transaction. */
  async csrfValid(txn: { csrfHash: string }, cookie: string | undefined, field: string | undefined): Promise<boolean> {
    if (!cookie || !field || cookie !== field) return false;
    return (await sha256Hex(field)) === txn.csrfHash;
  }

  consentHtml(p: { txnId: string; client: OAuthClient; requested: string[]; redirectUri: string; csrf: string; error?: string }): string {
    const desc: Record<string, string> = {
      'config:read': 'Read your habits, routines, cue profiles and settings (redacted snapshot).',
      'config:write': 'Propose and apply configuration changes. Each change is validated on your phone and only counts once the phone confirms.',
      'sessions:control': 'Start, pause, resume or stop sessions and routines.',
      'activity:read': 'Read the coarse computer activity state (active / idle / locked / asleep).',
      medication: 'Include medication labels and schedules. Off unless you tick it.',
    };
    const gatedNote = this.d.requirePhoneApproval
      ? '<p class="note">Write, session-control and medication permissions stay inactive until you approve this client on your phone.</p>'
      : '';
    const boxes = SCOPES.map((s) => {
      const on = p.requested.includes(s) && s !== 'medication';
      return `<label class="sc"><input type="checkbox" name="scope" value="${s}"${on ? ' checked' : ''}> <b>${s}</b>${
        s === 'medication' && p.requested.includes(s) ? ' <em>(requested)</em>' : ''
      }<br><span>${esc(desc[s])}</span></label>`;
    }).join('');
    let host = p.redirectUri;
    try {
      host = new URL(p.redirectUri).host || p.redirectUri;
    } catch {
      /* keep raw */
    }
    const trust = this.redirectTrust(p.redirectUri);
    const trustBlock =
      trust === 'trusted'
        ? `<p class="host ok">After you decide, your browser returns to <b>${esc(host)}</b></p>`
        : trust === 'loopback'
          ? `<p class="host">After you decide, your browser returns to <b>${esc(host)}</b>: an app running on <em>this</em> device (for example Claude Code). Continue only if you just started that app.</p>`
          : `<div class="warn"><p class="host bad">UNRECOGNIZED REDIRECT ADDRESS: <b>${esc(host)}</b></p><p>This is not an official Claude or ChatGPT address. Anyone who can reach this address will receive access to your DayCue relay if you approve. If you did not start this connection yourself from a client you recognize, press Deny.</p><label><input type="checkbox" name="confirm_untrusted" value="yes"> I recognize <b>${esc(host)}</b> and want to send the approval there</label></div>`;
    const idLine =
      p.client.kind === 'cimd'
        ? `Client ID document: <code>${esc(p.client.clientId)}</code>`
        : `Self-registered client <code>${esc(p.client.clientId)}</code> (registered ${esc(new Date(p.client.createdAt).toISOString())}); the name below was typed by whoever registered it`;
    return `<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Authorize access to DayCue</title><style>
:root{color-scheme:light dark}body{font:16px system-ui,sans-serif;max-width:34rem;margin:2rem auto;padding:0 1rem}
.sc{display:block;margin:.6rem 0}.sc span{font-size:.85rem;opacity:.75}input[type=password]{width:100%;padding:.5rem;margin:.4rem 0}
button{padding:.6rem 1.2rem;margin-right:.5rem;font-size:1rem}.err{color:#b00020}.host{font-size:1.15rem;padding:.6rem;border:2px solid currentColor;border-radius:.4rem}
.host.ok{border-color:#2e7d32}.warn{border:3px solid #b00020;padding:.6rem;border-radius:.4rem;margin:1rem 0}.bad{color:#b00020;border-color:#b00020}
.note,.small{font-size:.85rem;opacity:.8}code{word-break:break-all}</style></head><body>
<h1>Authorize access to DayCue</h1>
${trustBlock}
<p><b>${esc(p.client.name)}</b> wants to connect to your DayCue relay. The name is supplied by the client and is <b>not verified</b>.</p>
<p class="small">${idLine}</p>
<p class="small">Full return address: <code>${esc(p.redirectUri)}</code></p>
${p.error ? `<p class="err">${esc(p.error)}</p>` : ''}
<form method="post" action="/authorize/decision" autocomplete="off"><input type="hidden" name="txn" value="${esc(p.txnId)}"><input type="hidden" name="csrf" value="${esc(p.csrf)}">
<h2>Permissions</h2>${boxes}${gatedNote}
<label>Owner secret<input type="password" name="owner_secret" autocomplete="off" required></label>
<button name="decision" value="approve">Approve</button><button name="decision" value="deny" formnovalidate>Deny</button>
</form></body></html>`;
  }

  /**
   * Returns what to do next: a redirect to the client, or (for denials to an unrecognized host) a local "denied" page,
   * never a redirect to an untrusted address. Throws RelayError for bad txn / missing untrusted confirmation.
   */
  async decide(txnId: string, approve: boolean, scopes: string[], opts: { confirmUntrusted?: boolean } = {}): Promise<{ redirect?: string; deniedLocally?: boolean }> {
    const key = await sha256Hex(txnId);
    const txn = await this.d.store.get<Txn>('txn', key);
    if (!txn) throw new RelayError('invalid_request', 'This authorization request expired. Start again from the client.', 400);
    const trust = this.redirectTrust(txn.redirectUri);
    if (approve && trust === 'untrusted' && !opts.confirmUntrusted) {
      throw new RelayError('confirmation_required', 'The redirect address is not recognized. Tick the confirmation box or press Deny.', 400);
    }
    await this.d.store.delete('txn', key);
    const u = new URL(txn.redirectUri);
    const iss = this.d.baseUrl;
    if (txn.state) u.searchParams.set('state', txn.state);
    u.searchParams.set('iss', iss);
    if (!approve) {
      u.searchParams.set('error', 'access_denied');
      await this.d.audit('owner', 'authorize.denied', { client: txn.clientName });
      // No open redirect: an unrecognized host does not get to receive the browser just because someone clicked Deny.
      return trust === 'untrusted' ? { deniedLocally: true } : { redirect: u.toString() };
    }
    const granted = [...new Set(scopes.filter((s) => (SCOPES as readonly string[]).includes(s)))];
    if (granted.length === 0) {
      u.searchParams.set('error', 'access_denied');
      return trust === 'untrusted' ? { deniedLocally: true } : { redirect: u.toString() };
    }
    const code = randomToken('code', 32);
    await this.d.store.put<CodeRec>(
      'code',
      await sha256Hex(code),
      { clientId: txn.clientId, redirectUri: txn.redirectUri, codeChallenge: txn.codeChallenge, scopes: granted, resource: txn.resource, exp: this.now + 60_000 },
      { expiresAt: this.now + 60_000 },
    );
    u.searchParams.set('code', code);
    await this.markClientApproved(txn.clientId);
    await this.d.audit('owner', 'authorize.approved', { client: txn.clientName, clientId: txn.clientId, redirectHost: u.host, scopes: granted });
    return { redirect: u.toString() };
  }

  private async markClientApproved(clientId: string) {
    if (clientId.startsWith('https://')) return;
    await this.d.store.update<OAuthClient>('client', `dcr:${clientId}`, (c) => (c ? { ...c, approvedAt: this.now } : undefined), { expiresAt: this.now + 90 * 86400_000 });
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
    const codeKey = await sha256Hex(code);
    let rec: CodeRec | undefined;
    let reuse = false;
    const grantId = randomId('gr');
    // Single use, with a tombstone kept for 10 minutes so that a replay can revoke what the first redemption issued.
    await this.d.store.update<CodeRec>(
      'code',
      codeKey,
      (c) => {
        rec = c;
        if (!c) return undefined;
        if (c.used) {
          reuse = true;
          return { ...c, reused: true };
        }
        return { ...c, used: true, grantId };
      },
      { expiresAt: this.now + 10 * 60_000 },
    );
    if (!rec) throw new RelayError('invalid_grant', 'Invalid, used or expired authorization code', 400);
    if (reuse) {
      if (rec.grantId) await this.revokeGrant(rec.grantId);
      await this.d.audit('client', 'oauth.code_reuse', { grantId: rec.grantId });
      throw new RelayError('invalid_grant', 'Authorization code already used; tokens issued from it were revoked', 400);
    }
    if (rec.exp <= this.now) throw new RelayError('invalid_grant', 'Invalid, used or expired authorization code', 400);
    if (rec.clientId !== form.get('client_id') || rec.redirectUri !== form.get('redirect_uri')) {
      throw new RelayError('invalid_grant', 'client_id or redirect_uri mismatch', 400);
    }
    if (!/^[A-Za-z0-9._~-]{43,128}$/.test(verifier)) throw new RelayError('invalid_grant', 'Bad code_verifier', 400);
    const digest = new Uint8Array(await crypto.subtle.digest('SHA-256', new TextEncoder().encode(verifier)));
    if (bytesToB64u(digest) !== rec.codeChallenge) throw new RelayError('invalid_grant', 'PKCE verification failed', 400);
    const client = await this.resolveClient(rec.clientId);
    const g = await this.createGrant(rec.clientId, client.name, rec.scopes, 'oauth', rec.resource, grantId);
    const tokens = await this.issueTokens(g, { refresh: true });
    // A replay that raced with this redemption could not revoke a grant that did not exist yet: re-check now.
    const after = await this.d.store.get<CodeRec>('code', codeKey);
    if (after?.reused) {
      await this.revokeGrant(g.id);
      throw new RelayError('invalid_grant', 'Authorization code already used; tokens issued from it were revoked', 400);
    }
    return tokens;
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
