import { AuthService, DEFAULT_TRUSTED_REDIRECT_HOSTS, type CimdFetch } from './auth.js';
import { AttemptGuard, RateLimiter } from './limits.js';
import type { Store } from './store.js';
import {
  COMMAND_SCOPE,
  RelayError,
  TERMINAL,
  hasScope,
  type AckOutcome,
  type ActivitySignal,
  type ActivityState,
  type Command,
  type CommandResult,
  type CommandState,
  type CommandType,
  type Device,
  type Grantee,
  type Snapshot,
} from './types.js';
import { NoopWakeSender, type WakeSender } from './wake.js';
import {
  MIN_OWNER_SECRET_BITS,
  b64ToBytes,
  canonicalJson,
  estimateEntropyBits,
  normalizePairCode,
  randomId,
  randomPairCode,
  randomToken,
  secretEquals,
  sha256Hex,
  systemClock,
  verifyEcdsaP256,
  type Clock,
} from './util.js';

export interface RelayOptions {
  /** Public origin, no trailing slash, e.g. https://daycue-relay.onrender.com or http://localhost:8787 */
  baseUrl: string;
  ownerSecret: string;
  store: Store;
  clock?: Clock;
  wake?: WakeSender;
  fetchImpl?: typeof fetch;
  pollIntervalMs?: number;
  accessTtlS?: number;
  refreshTtlS?: number;
  /** Hardened fetcher for client metadata documents (Node: src/node/safefetch.ts). Default: capped fetchImpl wrapper (no DNS vetting). */
  cimdFetch?: CimdFetch;
  /** HTTPS hosts treated as official connector callback hosts on the consent page. Default: claude.ai, claude.com, chatgpt.com (loopback is always recognized). */
  trustedRedirectHosts?: string[];
  /** Default true: write-capable scopes (config:write, sessions:control, medication) of a NEW grant stay inactive until the phone approves it. Only applies while a phone is paired. */
  requirePhoneApprovalForNewGrants?: boolean;
}

/** Throws when the owner secret is too weak to be a root of trust on a public URL. */
export function assertOwnerSecretStrength(secret: string) {
  if (secret.length < 32 || estimateEntropyBits(secret) < MIN_OWNER_SECRET_BITS) {
    throw new Error(
      'DAYCUE_OWNER_SECRET is too weak: use at least 32 characters with about ' + MIN_OWNER_SECRET_BITS + ' bits of randomness. ' +
        'Generate one with: npm run gen-secret',
    );
  }
}

const DAY = 86400_000;
export const RETENTION = {
  pairCodeMs: 10 * 60_000,
  commandAfterExpiryMs: 14 * DAY,
  auditMs: 90 * DAY,
  signalLogMs: 24 * 3600_000,
};
const TTL_DEFAULT_S: Record<CommandType, number> = { 'config.preview': 600, 'config.apply': 6 * 3600, 'config.undo': 6 * 3600, 'session.control': 600 };
const TTL_MAX_S: Record<CommandType, number> = { 'config.preview': 3600, 'config.apply': 7 * 86400, 'config.undo': 7 * 86400, 'session.control': 3600 };

export function ackMessage(c: { id: string; payloadHash: string }, outcome: string, newVersion: number | undefined, ackedAt: number) {
  return `daycue.ack.v1\n${c.id}\n${c.payloadHash}\n${outcome}\n${newVersion ?? ''}\n${ackedAt}`;
}
/** v2 additionally binds a hash of the canonical result (summary, errors, preview) so the relay cannot alter what the phone reported. */
export function ackMessageV2(c: { id: string; payloadHash: string }, outcome: string, newVersion: number | undefined, ackedAt: number, resultHash: string) {
  return `daycue.ack.v2\n${c.id}\n${c.payloadHash}\n${outcome}\n${newVersion ?? ''}\n${ackedAt}\n${resultHash}`;
}
/** Phone decision on a grant. Signed with the phone key so a stolen phone credential alone cannot approve grants. */
export function grantDecisionMessage(grantId: string, decision: string, scopes: string[], decidedAt: number) {
  return `daycue.grant.v1\n${grantId}\n${decision}\n${[...scopes].sort().join(' ')}\n${decidedAt}`;
}
/** Phone revokes one paired companion. Signed with the phone key (a stolen phone credential alone cannot unpair companions). */
export function companionRevokeMessage(companionId: string, signedAt: number) {
  return `daycue.companion.revoke.v1\n${companionId}\n${signedAt}`;
}
export function signalMessage(companionId: string, state: string, observedAt: number, ttlSeconds: number) {
  return `daycue.signal.v1\n${companionId}\n${state}\n${observedAt}\n${ttlSeconds}`;
}

const AUDIT_MAX = 2000;
const AUDIT_KEEP = 1500;
const MAX_PENDING_COMMANDS = 100;
const MAX_SNAPSHOT_VERSION_JUMP = 10_000;
const signalKey = (companionId: string) => `latest:${companionId}`;
const SIGNAL_PREFIX = 'latest:';
const STATE_RANK: Record<ActivityState, number> = { active: 0, idle: 1, locked: 2, asleep: 3 };

export class Relay {
  readonly issuer: string;
  readonly resource: string;
  readonly store: Store;
  readonly clock: Clock;
  readonly auth: AuthService;
  readonly wakeSender: WakeSender;
  readonly pollIntervalMs: number;
  readonly limiter: RateLimiter;
  private ownerSecret: string;
  private ownerGuard: AttemptGuard;
  private consentGuard: AttemptGuard;
  private pairGuard: AttemptGuard;
  private auditCount?: number;

  constructor(o: RelayOptions) {
    assertOwnerSecretStrength(o.ownerSecret);
    this.issuer = o.baseUrl.replace(/\/$/, '');
    this.resource = `${this.issuer}/mcp`;
    this.store = o.store;
    this.clock = o.clock ?? systemClock;
    this.ownerSecret = o.ownerSecret;
    this.wakeSender = o.wake ?? new NoopWakeSender();
    this.pollIntervalMs = o.pollIntervalMs ?? 500;
    this.limiter = new RateLimiter(this.clock);
    this.ownerGuard = new AttemptGuard(this.clock);
    this.consentGuard = new AttemptGuard(this.clock);
    this.pairGuard = new AttemptGuard(this.clock);
    this.auth = new AuthService({
      store: o.store,
      clock: this.clock,
      baseUrl: this.issuer,
      resource: this.resource,
      audit: (a, b, c) => this.audit(a, b, c),
      fetchImpl: o.fetchImpl ?? fetch,
      accessTtlS: o.accessTtlS ?? 3600,
      refreshTtlS: o.refreshTtlS ?? 30 * 86400,
      cimdFetch: o.cimdFetch,
      trustedRedirectHosts: (o.trustedRedirectHosts ?? DEFAULT_TRUSTED_REDIRECT_HOSTS).map((h) => h.toLowerCase()),
      requirePhoneApproval: o.requirePhoneApprovalForNewGrants ?? true,
      phonePaired: async () => !!(await this.currentPhone()),
    });
  }

  private get now() {
    return this.clock.now();
  }

  // ------------------------------------------------------------------ audit & lockout

  async audit(actor: string, action: string, detail: Record<string, unknown> = {}) {
    const key = `${String(this.now).padStart(15, '0')}_${randomId('a')}`;
    await this.store.put('audit', key, { at: this.now, actor, action, detail }, { expiresAt: this.now + RETENTION.auditMs });
    // Bounded growth: keep at most AUDIT_MAX entries (oldest dropped first).
    this.auditCount = (this.auditCount ?? (await this.store.list('audit')).length - 1) + 1;
    if (this.auditCount > AUDIT_MAX) {
      const all = await this.store.list('audit');
      for (const x of all.slice(0, all.length - AUDIT_KEEP)) await this.store.delete('audit', x.key);
      this.auditCount = AUDIT_KEEP;
    }
  }

  async listAudit(limit = 100) {
    const n = Math.min(Math.max(Math.trunc(Number.isFinite(limit) ? limit : 100), 1), 500);
    const all = await this.store.list<{ at: number; actor: string; action: string; detail: unknown }>('audit');
    return all.slice(-n).reverse().map((x) => ({ ...x.value, at: new Date(x.value.at).toISOString() }));
  }

  /** Audit of failed owner attempts is itself rate limited so unauthenticated callers cannot grow the log. */
  private async auditAuthFailure() {
    try {
      this.limiter.hit('audit:auth_failed', 12, 3600_000);
    } catch {
      return;
    }
    await this.audit('unknown', 'owner.auth_failed');
  }

  // ------------------------------------------------------------------ owner

  /**
   * Checks the owner secret. The attempt is counted BEFORE the comparison (atomic with respect to parallel requests),
   * per source address, in a bucket separate for the owner API and the consent page. A caller can only lock out its own source.
   */
  async assertOwner(secret: string | undefined, source = 'unknown', bucket: 'api' | 'consent' = 'api'): Promise<void> {
    const guard = bucket === 'consent' ? this.consentGuard : this.ownerGuard;
    guard.reserve(source);
    if (!secret || !(await secretEquals(secret, this.ownerSecret))) {
      await this.auditAuthFailure();
      throw new RelayError('unauthorized', 'Invalid owner secret', 401);
    }
    guard.release(source);
  }

  /**
   * Revocation / kill path: never locked out by failed attempts (so an attacker cannot block it), only rate limited per source.
   * Safe because the owner secret must carry >= 128 bits of entropy.
   */
  async assertOwnerEmergency(secret: string | undefined, source = 'unknown'): Promise<void> {
    this.limiter.hit(`emergency:${source}`, 30, 60_000);
    if (!secret || !(await secretEquals(secret, this.ownerSecret))) {
      await this.auditAuthFailure();
      throw new RelayError('unauthorized', 'Invalid owner secret', 401);
    }
  }

  async createPairCode(kind: 'phone' | 'companion', issuedBy: string) {
    const code = randomPairCode();
    const expiresAt = this.now + RETENTION.pairCodeMs;
    await this.store.put('pair', await sha256Hex(normalizePairCode(code)), { kind, issuedBy }, { expiresAt });
    await this.audit(issuedBy, `pair_code.created`, { kind });
    return { code, expiresAt: new Date(expiresAt).toISOString() };
  }

  private async consumePairCode(code: string, kind: 'phone' | 'companion', source: string) {
    this.pairGuard.reserve(source);
    let ok = false;
    await this.store.update<{ kind: string }>('pair', await sha256Hex(normalizePairCode(code)), (c) => {
      if (c && c.kind === kind) {
        ok = true;
        return null;
      }
      return undefined;
    });
    if (!ok) throw new RelayError('invalid_pair_code', 'Pairing code is invalid, expired or already used', 403);
    this.pairGuard.release(source);
  }

  // ------------------------------------------------------------------ devices

  private async validateKey(publicKey: unknown): Promise<string> {
    if (typeof publicKey !== 'string' || publicKey.length < 60 || publicKey.length > 400) throw new RelayError('invalid_request', 'publicKey (base64 SPKI, ECDSA P-256) required', 400);
    try {
      await crypto.subtle.importKey('spki', b64ToBytes(publicKey), { name: 'ECDSA', namedCurve: 'P-256' }, false, ['verify']);
    } catch {
      throw new RelayError('invalid_request', 'publicKey is not a valid ECDSA P-256 SPKI key', 400);
    }
    return publicKey;
  }

  private async createDevice(role: 'phone' | 'companion', label: string, publicKey: string) {
    const token = randomToken('dcd');
    const tokenHash = await sha256Hex(token);
    const dev: Device = { id: randomId(role === 'phone' ? 'ph' : 'co'), role, label: String(label ?? role).slice(0, 60), publicKey, createdAt: this.now, tokenHash };
    await this.store.put('device', dev.id, dev);
    await this.store.put('devtoken', tokenHash, dev.id);
    return { dev, token };
  }

  private async revokeDevice(id: string) {
    const d = await this.store.get<Device>('device', id);
    if (!d || d.revokedAt) return;
    if (d.tokenHash) await this.store.delete('devtoken', d.tokenHash);
    await this.store.put('device', id, { ...d, revokedAt: this.now, fcmToken: undefined, tokenHash: undefined });
    if (d.role === 'companion') await this.store.delete('signal', signalKey(id));
  }

  /** A companion revokes itself (e.g. the user uninstalls it). Keeps id/label/public key, drops credential and signal slot. */
  async revokeCompanionSelf(co: Device) {
    await this.revokeDevice(co.id);
    await this.audit(`companion:${co.id}`, 'companion.self_revoked', { deviceId: co.id });
    return { ok: true, serverTime: this.now };
  }

  /**
   * The paired phone revokes a companion. Requires the phone key's signature over companionRevokeMessage within 10 minutes of
   * the relay clock. Idempotent: an already revoked companion returns `{already: true}` without a second audit entry; an id that
   * was never a companion throws 404 unknown_companion.
   */
  async revokeCompanionByPhone(phone: Device, companionId: string, signedAtRaw: string | undefined, signature: string | undefined) {
    const signedAt = signedAtRaw !== undefined && /^\d{1,16}$/.test(signedAtRaw) ? Number(signedAtRaw) : NaN;
    if (!Number.isSafeInteger(signedAt) || Math.abs(this.now - signedAt) > 10 * 60_000) {
      throw new RelayError('invalid_request', 'X-DayCue-Signed-At must be epoch milliseconds within 10 minutes of the relay clock', 400);
    }
    if (typeof signature !== 'string' || !(await verifyEcdsaP256(phone.publicKey, companionRevokeMessage(companionId, signedAt), signature))) {
      await this.audit(`phone:${phone.id}`, 'companion.revoke_bad_signature', { companionId: companionId.slice(0, 64) });
      throw new RelayError('bad_signature', 'Revoke signature does not verify against the paired device key', 403);
    }
    const co = await this.store.get<Device>('device', companionId);
    if (!co || co.role !== 'companion') throw new RelayError('unknown_companion', 'No such companion', 404);
    if (co.revokedAt) return { already: true };
    await this.revokeDevice(co.id);
    await this.audit(`phone:${phone.id}`, 'companion.phone_revoked', { deviceId: co.id });
    return { already: false };
  }

  async pairPhone(body: { code: string; publicKey: string; label?: string; fcmToken?: string }, source = 'unknown') {
    const key = await this.validateKey(body.publicKey);
    await this.consumePairCode(String(body.code ?? ''), 'phone', source);
    const prev = await this.store.get<string>('meta', 'phoneId');
    if (prev) {
      await this.revokeDevice(prev);
      for (const c of await this.allCommands()) {
        if (!TERMINAL.includes(c.state) && c.state !== 'expired') {
          await this.store.update<Command>('command', c.id, (x) => (x ? { ...x, state: 'expired', result: { message: 'Phone was re-paired before this command was handled.' } } : undefined));
        }
      }
    }
    const { dev, token } = await this.createDevice('phone', body.label ?? 'phone', key);
    if (body.fcmToken) await this.store.put('device', dev.id, { ...dev, fcmToken: String(body.fcmToken).slice(0, 4096) });
    await this.store.put('meta', 'phoneId', dev.id);
    await this.audit('phone', 'phone.paired', { deviceId: dev.id, replaced: !!prev });
    return { deviceId: dev.id, token, serverTime: this.now };
  }

  /** The phone revokes itself (unpair): credential and FCM token are dropped and its pending commands expire. */
  async revokePhoneSelf(phone: Device) {
    await this.revokeDevice(phone.id);
    const cur = await this.store.get<string>('meta', 'phoneId');
    if (cur === phone.id) await this.store.delete('meta', 'phoneId');
    for (const c of await this.allCommands()) {
      if (!TERMINAL.includes(c.state) && c.state !== 'expired') {
        await this.store.update<Command>('command', c.id, (x) => (x ? { ...x, state: 'expired', result: { message: 'Phone was unpaired before this command was handled.' } } : undefined));
      }
    }
    await this.audit(`phone:${phone.id}`, 'phone.self_revoked', { deviceId: phone.id });
    return { ok: true, serverTime: this.now };
  }

  /** Signed phone decision on a grant (approve / decline / revoke). */
  async phoneGrantDecision(phone: Device, grantId: string, body: { decision?: string; approvedScopes?: string[]; decidedAt?: number; signature?: string }) {
    const decision = body.decision;
    if (decision !== 'approve' && decision !== 'decline' && decision !== 'revoke') throw new RelayError('invalid_request', 'decision must be approve, decline or revoke', 400);
    if (!Number.isFinite(body.decidedAt) || Math.abs(this.now - (body.decidedAt as number)) > 10 * 60_000) {
      throw new RelayError('invalid_request', 'decidedAt must be within 10 minutes of the relay clock', 400);
    }
    const scopes = Array.isArray(body.approvedScopes) ? body.approvedScopes.map(String) : [];
    const msg = grantDecisionMessage(grantId, decision, scopes, body.decidedAt as number);
    if (typeof body.signature !== 'string' || !(await verifyEcdsaP256(phone.publicKey, msg, body.signature))) {
      await this.audit('phone', 'grant.bad_signature', { grantId });
      throw new RelayError('bad_signature', 'Decision signature does not verify against the paired device key', 403);
    }
    const r = await this.auth.phoneDecideGrant(grantId, decision, scopes.length ? scopes : undefined);
    return { ...r, grants: await this.auth.grantsForPhone(), serverTime: this.now };
  }

  async createCompanionCode(phone: Device) {
    return this.createPairCode('companion', `phone:${phone.id}`);
  }

  async pairCompanion(body: { code: string; publicKey: string; label?: string }, source = 'unknown') {
    const key = await this.validateKey(body.publicKey);
    await this.consumePairCode(String(body.code ?? ''), 'companion', source);
    const { dev, token } = await this.createDevice('companion', body.label ?? 'companion', key);
    await this.audit('companion', 'companion.paired', { deviceId: dev.id });
    return { deviceId: dev.id, token, serverTime: this.now };
  }

  async authDevice(token: string | undefined, role: 'phone' | 'companion'): Promise<Device> {
    if (!token) throw new RelayError('unauthorized', 'Missing device credential', 401);
    const id = await this.store.get<string>('devtoken', await sha256Hex(token));
    const dev = id ? await this.store.get<Device>('device', id) : undefined;
    if (!dev || dev.revokedAt || dev.role !== role) throw new RelayError('unauthorized', 'Invalid device credential', 401);
    if (!dev.lastSeenAt || this.now - dev.lastSeenAt > 30_000) {
      await this.store.update<Device>('device', dev.id, (c) => (c ? { ...c, lastSeenAt: this.now } : undefined));
    }
    return { ...dev, lastSeenAt: this.now };
  }

  async listDevices() {
    return (await this.store.list<Device>('device')).map(({ value: d }) => ({
      id: d.id, role: d.role, label: d.label, createdAt: new Date(d.createdAt).toISOString(),
      lastSeenAt: d.lastSeenAt ? new Date(d.lastSeenAt).toISOString() : null, revoked: !!d.revokedAt, push: !!d.fcmToken,
    }));
  }

  async revokeDeviceById(id: string) {
    await this.revokeDevice(id);
    await this.audit('owner', 'device.revoked', { deviceId: id });
  }

  async setPush(phone: Device, body: { fcmToken?: string | null; wakeOnActivity?: boolean }) {
    await this.store.update<Device>('device', phone.id, (c) => {
      if (!c) return undefined;
      const n = { ...c };
      if (body.fcmToken !== undefined) n.fcmToken = body.fcmToken ? String(body.fcmToken).slice(0, 4096) : undefined;
      if (body.wakeOnActivity !== undefined) n.wakeOnActivity = !!body.wakeOnActivity;
      return n;
    });
  }

  async currentPhone(): Promise<Device | undefined> {
    const id = await this.store.get<string>('meta', 'phoneId');
    const d = id ? await this.store.get<Device>('device', id) : undefined;
    return d && !d.revokedAt ? d : undefined;
  }

  async phoneStatus() {
    const p = await this.currentPhone();
    return {
      paired: !!p,
      lastSeenAt: p?.lastSeenAt,
      pushRegistered: !!p?.fcmToken,
      pushSenderConfigured: !(this.wakeSender instanceof NoopWakeSender),
    };
  }

  // ------------------------------------------------------------------ snapshot

  async putSnapshot(phone: Device, body: any) {
    if (!body || typeof body !== 'object' || !Number.isInteger(body.version) || body.version < 0 || typeof body.config !== 'object' || body.config === null) {
      throw new RelayError('invalid_request', 'snapshot needs integer version and object config', 400);
    }
    if (JSON.stringify(body).length > 262_144) throw new RelayError('too_large', 'snapshot exceeds 256 KiB', 413);
    const wantsMed = await this.auth.anyMedicationGrant();
    const config = { ...(body.config as Record<string, unknown>) };
    let medication = (body.medication && typeof body.medication === 'object' ? body.medication : undefined) as Record<string, unknown> | undefined;
    // Defensive: medication data never lives in the general config section.
    for (const k of ['medications', 'medication']) {
      if (k in config) {
        medication ??= { [k]: config[k] };
        delete config[k];
      }
    }
    if (!wantsMed) medication = undefined;
    const snap: Snapshot = {
      version: body.version,
      schemaVersion: Number.isInteger(body.schemaVersion) ? body.schemaVersion : undefined,
      publishedAt: Number.isFinite(body.publishedAt) ? body.publishedAt : this.now,
      receivedAt: this.now,
      config,
      medication,
      status: body.status && typeof body.status === 'object' ? body.status : {},
    };
    let stale = false;
    let jump = false;
    await this.store.update<Snapshot>('snapshot', 'latest', (c) => {
      if (c && c.version > snap.version) {
        stale = true;
        return undefined;
      }
      if (c && snap.version - c.version > MAX_SNAPSHOT_VERSION_JUMP) {
        jump = true;
        return undefined;
      }
      return snap;
    });
    if (stale) throw new RelayError('stale_snapshot', 'A newer snapshot version is already stored', 409);
    if (jump) throw new RelayError('version_jump', `snapshot version may not jump by more than ${MAX_SNAPSHOT_VERSION_JUMP}`, 400);
    return { ok: true, wants: { medication: wantsMed }, grantsVersion: await this.auth.grantsVersion(), pendingCommands: (await this.pending()).length, serverTime: this.now };
  }

  async getSnapshot(): Promise<Snapshot | undefined> {
    return this.store.get<Snapshot>('snapshot', 'latest');
  }

  // ------------------------------------------------------------------ commands

  private async allCommands(): Promise<Command[]> {
    return (await this.store.list<Command>('command')).map((x) => x.value);
  }

  private async fresh(c: Command): Promise<Command> {
    if ((c.state === 'queued' || c.state === 'delivered' || c.state === 'awaiting_confirmation') && c.expiresAt <= this.now) {
      const n = await this.store.update<Command>('command', c.id, (x) =>
        x && (x.state === 'queued' || x.state === 'delivered' || x.state === 'awaiting_confirmation') && x.expiresAt <= this.now ? { ...x, state: 'expired' } : undefined,
      );
      return n ?? c;
    }
    return c;
  }

  async getCommand(id: string): Promise<Command | undefined> {
    const c = await this.store.get<Command>('command', id);
    return c ? this.fresh(c) : undefined;
  }

  async pending(): Promise<Command[]> {
    const out: Command[] = [];
    for (const c of await this.allCommands()) {
      const f = await this.fresh(c);
      if (f.state === 'queued' || f.state === 'delivered' || f.state === 'awaiting_confirmation') out.push(f);
    }
    return out;
  }

  async enqueue(
    g: Grantee,
    input: { type: CommandType; payload: Record<string, unknown>; baseVersion?: number; idempotencyKey: string; ttlSeconds?: number },
  ): Promise<{ command: Command; deduplicated: boolean }> {
    const need = COMMAND_SCOPE[input.type];
    if (!need) throw new RelayError('invalid_request', 'Unknown command type', 400);
    if (!hasScope(g.scopes, need)) throw new RelayError('insufficient_scope', `This operation requires the ${need} scope`, 403, [need]);
    if (!/^[\w.:-]{8,128}$/.test(input.idempotencyKey ?? '')) throw new RelayError('invalid_request', 'idempotencyKey must be 8-128 chars [A-Za-z0-9_.:-]', 400);
    if (input.baseVersion !== undefined && (!Number.isInteger(input.baseVersion) || input.baseVersion < 0)) throw new RelayError('invalid_request', 'baseVersion must be a non-negative integer', 400);
    if (JSON.stringify(input.payload).length > 65_536) throw new RelayError('too_large', 'command payload exceeds 64 KiB', 413);
    const phone = await this.currentPhone();
    if (!phone) throw new RelayError('no_phone_paired', 'No phone is paired with this relay', 409);
    this.limiter.hit(`enqueue:${g.grantId}`, 120, 3600_000);

    const ttl = Math.min(Math.max(input.ttlSeconds ?? TTL_DEFAULT_S[input.type], 30), TTL_MAX_S[input.type]);
    const payloadHash = await sha256Hex(canonicalJson({ type: input.type, payload: input.payload, baseVersion: input.baseVersion ?? null }));
    const id = randomId('cmd');
    const expiresAt = this.now + ttl * 1000;
    const idemKey = await sha256Hex(`${g.clientId}\n${input.idempotencyKey}`);
    if ((await this.pending()).length >= MAX_PENDING_COMMANDS) {
      throw new RelayError('too_many_pending', 'Too many commands are waiting for the phone. Try again after it syncs.', 429, undefined, 60);
    }
    const reserved = await this.store.update<{ id: string; hash: string }>('idem', idemKey, (c) => c ?? { id, hash: payloadHash }, { expiresAt: expiresAt + RETENTION.commandAfterExpiryMs });
    if (reserved && reserved.id !== id) {
      if (reserved.hash !== payloadHash) throw new RelayError('idempotency_conflict', 'This idempotencyKey was already used with different content', 409);
      const existing = await this.getCommand(reserved.id);
      if (existing) return { command: existing, deduplicated: true };
    }
    const cmd: Command = {
      id: reserved!.id, type: input.type, payload: input.payload, baseVersion: input.baseVersion, idempotencyKey: input.idempotencyKey,
      payloadHash, grant: { grantId: g.grantId, clientId: g.clientId, clientLabel: g.clientLabel, scopes: [...g.scopes] },
      state: 'queued', createdAt: this.now, expiresAt, deliveryCount: 0,
    };
    await this.store.put('command', cmd.id, cmd, { expiresAt: expiresAt + RETENTION.commandAfterExpiryMs });
    await this.audit(`client:${g.grantId}`, 'command.queued', { commandId: cmd.id, type: cmd.type, client: g.clientLabel });
    const wake = await this.requestWake(phone, 'command');
    if (wake) {
      cmd.wake = wake;
      await this.store.update<Command>('command', cmd.id, (x) => (x ? { ...x, wake } : undefined));
    }
    return { command: cmd, deduplicated: false };
  }

  private async requestWake(phone: Device, reason: 'command' | 'activity') {
    if (!phone.fcmToken || this.wakeSender instanceof NoopWakeSender) return undefined;
    // Rate limit: at most one wake per 15 s per reason (a pull fetches everything pending anyway).
    let allowed = false;
    await this.store.update<number>('meta', `lastWake:${reason}`, (c) => {
      if (c !== undefined && this.now - c < 15_000) return undefined;
      allowed = true;
      return this.now;
    });
    if (!allowed) return { requestedAt: this.now, ok: true, detail: 'coalesced with a recent wake' };
    const r = await Promise.race([
      this.wakeSender.wake(phone.fcmToken, reason),
      this.clock.sleep(3000).then(() => ({ ok: false, detail: 'wake timed out' })),
    ]);
    return { requestedAt: this.now, ok: r.ok, detail: r.detail };
  }

  async pullCommands(phone: Device) {
    const wants = { medication: await this.auth.anyMedicationGrant() };
    const out = [];
    for (const c of await this.pending()) {
      if (c.state === 'awaiting_confirmation') continue;
      const n = (await this.store.update<Command>('command', c.id, (x) =>
        x && (x.state === 'queued' || x.state === 'delivered')
          ? { ...x, state: 'delivered', deliveredAt: x.deliveredAt ?? this.now, deliveryCount: x.deliveryCount + 1 }
          : undefined,
      )) ?? c;
      if (n.state !== 'delivered') continue;
      out.push({
        id: n.id, type: n.type, payload: n.payload, baseVersion: n.baseVersion ?? null, idempotencyKey: n.idempotencyKey,
        payloadHash: n.payloadHash, createdAt: n.createdAt, expiresAt: n.expiresAt,
        grant: { clientLabel: n.grant.clientLabel, scopes: n.grant.scopes },
      });
    }
    return { commands: out, wants, grants: await this.auth.grantsForPhone(), serverTime: this.now };
  }

  async ackCommand(
    phone: Device,
    id: string,
    body: { outcome: AckOutcome; result?: CommandResult; ackedAt: number; payloadHash: string; signature: string; signatureVersion?: number },
  ) {
    const cmd = await this.store.get<Command>('command', id);
    if (!cmd) throw new RelayError('not_found', 'Unknown command', 404);
    if (!['applied', 'rejected', 'failed', 'awaiting_confirmation'].includes(body.outcome)) throw new RelayError('invalid_request', 'bad outcome', 400);
    if (!Number.isFinite(body.ackedAt) || body.payloadHash !== cmd.payloadHash || typeof body.signature !== 'string') {
      throw new RelayError('invalid_request', 'ackedAt, payloadHash and signature are required and must match the command', 400);
    }
    const v2 = body.signatureVersion === 2;
    const msg = v2
      ? ackMessageV2(cmd, body.outcome, body.result?.newVersion, body.ackedAt, await sha256Hex(canonicalJson(body.result ?? null)))
      : ackMessage(cmd, body.outcome, body.result?.newVersion, body.ackedAt);
    if (!(await verifyEcdsaP256(phone.publicKey, msg, body.signature))) {
      await this.audit('phone', 'ack.bad_signature', { commandId: id });
      throw new RelayError('bad_signature', 'Ack signature does not verify against the paired device key', 403);
    }
    let conflict = false;
    let wasExpired = false;
    const n = await this.store.update<Command>('command', id, (c) => {
      if (!c) return undefined;
      if (TERMINAL.includes(c.state)) {
        if (c.state !== body.outcome) conflict = true;
        return undefined;
      }
      wasExpired = c.state === 'expired';
      if (wasExpired && body.outcome === 'awaiting_confirmation') return undefined; // an expired command cannot become pending again
      const state: CommandState = body.outcome;
      return { ...c, state, ackedAt: this.now, result: sanitizeResult(body.result), lateAck: wasExpired || c.lateAck, ackVersion: v2 ? 2 : 1 };
    });
    if (conflict) throw new RelayError('conflict', 'Command already finished with a different outcome', 409);
    await this.audit('phone', `command.${body.outcome}`, { commandId: id, type: cmd.type, newVersion: body.result?.newVersion, late: wasExpired || undefined });
    return { ok: true, state: n?.state };
  }

  async waitForCommand(id: string, waitSeconds: number): Promise<Command | undefined> {
    const deadline = this.now + Math.min(Math.max(waitSeconds, 0), 25) * 1000;
    for (;;) {
      const c = await this.getCommand(id);
      if (!c || (c.state !== 'queued' && c.state !== 'delivered')) return c;
      if (this.now >= deadline) return c;
      await this.clock.sleep(this.pollIntervalMs);
    }
  }

  async recentChanges(limit = 20) {
    const cmds = (await this.allCommands())
      .filter((c) => c.state === 'applied' && (c.type === 'config.apply' || c.type === 'config.undo'))
      .sort((a, b) => (b.ackedAt ?? 0) - (a.ackedAt ?? 0))
      .slice(0, limit);
    return cmds;
  }

  // ------------------------------------------------------------------ companion activity

  async postSignal(co: Device, body: { state: ActivityState; observedAt: number; ttlSeconds: number; signature: string }) {
    if (!['active', 'idle', 'locked', 'asleep'].includes(body.state)) throw new RelayError('invalid_request', 'state must be active|idle|locked|asleep', 400);
    if (!Number.isInteger(body.observedAt) || !Number.isInteger(body.ttlSeconds) || body.ttlSeconds < 10 || body.ttlSeconds > 600) {
      throw new RelayError('invalid_request', 'observedAt (epoch ms) and integer ttlSeconds 10-600 required', 400);
    }
    if (body.observedAt > this.now + 120_000) throw new RelayError('invalid_request', 'observedAt is in the future', 400);
    if (!(await verifyEcdsaP256(co.publicKey, signalMessage(co.id, body.state, body.observedAt, body.ttlSeconds), String(body.signature ?? '')))) {
      throw new RelayError('bad_signature', 'Signal signature does not verify', 403);
    }
    if (body.observedAt + body.ttlSeconds * 1000 <= this.now) throw new RelayError('already_expired', 'Signal is already past its ttl', 422);
    let stale = false;
    let transition = false;
    const sig: ActivitySignal = { companionId: co.id, state: body.state, observedAt: body.observedAt, ttlSeconds: body.ttlSeconds, sig: body.signature, receivedAt: this.now };
    // One slot per companion: replay protection (strictly newer observedAt) is independent per companion.
    await this.store.update<ActivitySignal>('signal', signalKey(co.id), (c) => {
      if (c && c.observedAt >= sig.observedAt) {
        stale = true;
        return undefined;
      }
      transition = !c || c.state !== sig.state || c.observedAt + c.ttlSeconds * 1000 <= this.now;
      return sig;
    }, { expiresAt: this.now + RETENTION.signalLogMs });
    if (stale) throw new RelayError('stale_signal', 'A newer or equal signal was already accepted (replay?)', 409);
    if (transition) {
      await this.store.update<Array<{ state: string; at: number; companionId?: string }>>('signal', 'log', (c) => {
        const cutoff = this.now - RETENTION.signalLogMs;
        return [...(c ?? []).filter((e) => e.at >= cutoff), { state: sig.state, at: sig.observedAt, companionId: co.id }].slice(-100);
      }, { expiresAt: this.now + RETENTION.signalLogMs });
      const phone = await this.currentPhone();
      if (phone?.wakeOnActivity) await this.requestWake(phone, 'activity');
    }
    return { ok: true, serverTime: this.now };
  }

  private async allSignals(): Promise<ActivitySignal[]> {
    return (await this.store.list<ActivitySignal>('signal', { prefix: SIGNAL_PREFIX })).map((x) => x.value);
  }

  async activity() {
    const signals = (await this.allSignals()).sort((a, b) => b.observedAt - a.observedAt);
    const labels = new Map((await this.store.list<Device>('device')).map((x) => [x.value.id, x.value.label]));
    const log = (await this.store.get<Array<{ state: string; at: number; companionId?: string }>>('signal', 'log')) ?? [];
    const companions = signals.map((s) => {
      const fresh = s.observedAt + s.ttlSeconds * 1000 > this.now;
      return {
        companionId: s.companionId,
        label: labels.get(s.companionId) ?? s.companionId,
        state: (fresh ? s.state : 'unknown') as ActivityState | 'unknown',
        fresh,
        observedAt: s.observedAt,
        expiresAt: s.observedAt + s.ttlSeconds * 1000,
        ttlSeconds: s.ttlSeconds,
      };
    });
    const freshStates = companions.filter((c) => c.fresh).map((c) => c.state as ActivityState);
    const state: ActivityState | 'unknown' = freshStates.length ? freshStates.reduce((a, b) => (STATE_RANK[b] < STATE_RANK[a] ? b : a)) : 'unknown';
    const latest = signals[0];
    return {
      state,
      fresh: freshStates.length > 0,
      companions,
      lastSignal: latest ? { state: latest.state, observedAt: latest.observedAt, expiresAt: latest.observedAt + latest.ttlSeconds * 1000, ttlSeconds: latest.ttlSeconds } : null,
      recentTransitions: log.filter((e) => e.at >= this.now - 6 * 3600_000).slice(-20),
    };
  }

  /** For the phone: the raw signed signals plus companion keys so it can verify independently of the relay. */
  async activityForPhone() {
    const all = (await this.allSignals()).sort((a, b) => b.observedAt - a.observedAt);
    const companions = (await this.store.list<Device>('device')).map((x) => x.value).filter((d) => d.role === 'companion' && !d.revokedAt)
      .map((d) => ({ id: d.id, label: d.label, publicKey: d.publicKey }));
    const live = new Set(companions.map((c) => c.id));
    const slots = all.filter((s) => live.has(s.companionId));
    return {
      signals: slots.map((s) => ({ companionId: s.companionId, state: s.state, observedAt: s.observedAt, ttlSeconds: s.ttlSeconds, signature: s.sig, receivedAt: s.receivedAt })),
      companions,
      signal: slots[0] ?? null,
      serverTime: this.now,
    };
  }

  async maintenance() {
    return this.store.purgeExpired();
  }
}

function sanitizeResult(r: CommandResult | undefined): CommandResult | undefined {
  if (!r || typeof r !== 'object') return undefined;
  const s = JSON.stringify(r);
  if (s.length > 65_536) return { message: 'result truncated (too large)' };
  return r;
}
