import { RelayError, hasScope, type Command, type Grantee, type Scope } from './types.js';
import type { Relay } from './relay.js';
import { markUntrusted } from './untrusted.js';
import { iso } from './util.js';

/** What MCP tools talk to. Implemented in-process (LocalClientApi) and over HTTP for the stdio server. */
export interface ClientApi {
  whoami(): Promise<{ clientLabel: string; scopes: string[] }>;
  getConfig(p: { section: string }): Promise<any>;
  getStatus(): Promise<any>;
  submit(p: SubmitParams): Promise<CommandView>;
  undo(p: { commandId?: string; version?: number; idempotencyKey: string; ttlSeconds?: number; waitSeconds?: number }): Promise<CommandView>;
  getCommand(p: { id: string; waitSeconds?: number }): Promise<CommandView>;
  listChanges(p: { limit?: number }): Promise<any>;
  getActivity(): Promise<any>;
}

export interface SubmitParams {
  type: 'config.preview' | 'config.apply' | 'session.control';
  payload: Record<string, unknown>;
  baseVersion?: number;
  idempotencyKey: string;
  ttlSeconds?: number;
  waitSeconds?: number;
}

export interface CommandView {
  commandId: string;
  type: string;
  state: string;
  /** True ONLY when the phone has confirmed (signed ack) that a change took effect. */
  applied: boolean;
  /** True when the phone computed a preview (nothing was changed). */
  previewed: boolean;
  deduplicated?: boolean;
  createdAt: string;
  expiresAt: string;
  /** Trusted, relay-authored explanation of exactly what is and is not known. */
  summary: string;
  /** Phone-provided result; text fields are marked untrusted. */
  result?: unknown;
  lateAck?: boolean;
}

const MAX_WAIT_S = 25;
const ago = (ms: number) => {
  const s = Math.max(0, Math.round(ms / 1000));
  if (s < 90) return `${s}s`;
  if (s < 5400) return `${Math.round(s / 60)} min`;
  return `${Math.round(s / 3600)} h`;
};

export class LocalClientApi implements ClientApi {
  constructor(private relay: Relay, private g: Grantee) {}

  private need(s: Scope) {
    if (!hasScope(this.g.scopes, s)) throw new RelayError('insufficient_scope', `This operation requires the ${s} scope`, 403, [s]);
  }
  private get now() {
    return this.relay.clock.now();
  }

  async whoami() {
    return { clientLabel: this.g.clientLabel, scopes: this.g.scopes };
  }

  private async phoneLine() {
    const ps = await this.relay.phoneStatus();
    if (!ps.paired) return 'No phone is paired.';
    const seen = ps.lastSeenAt ? `Phone last contacted the relay ${ago(this.now - ps.lastSeenAt)} ago.` : 'Phone has never contacted the relay since pairing.';
    return seen;
  }

  private async snapMeta() {
    const s = await this.relay.getSnapshot();
    if (!s) return { exists: false as const, note: 'No snapshot has been published yet; the phone has to sync once. Nothing below reflects the real configuration.' };
    return {
      exists: true as const,
      configVersion: s.version,
      publishedAt: iso(s.publishedAt),
      receivedAt: iso(s.receivedAt),
      ageSeconds: Math.round((this.now - s.publishedAt) / 1000),
      note: `Snapshot of config version ${s.version}, published by the phone ${ago(this.now - s.publishedAt)} ago. Changes made on the phone since then, and any pending commands, are not reflected.`,
    };
  }

  async getConfig({ section }: { section: string }) {
    this.need('config:read');
    const s = await this.relay.getSnapshot();
    const meta = await this.snapMeta();
    const pending = (await this.relay.pending()).length;
    if (!s) return { snapshot: meta, pendingCommands: pending, section, data: null };
    const KEYS: Record<string, string> = {
      habits: 'habits', routines: 'routines', cueProfiles: 'cueProfiles', calendarRules: 'calendarRules',
      places: 'places', settings: 'settings', alarms: 'alarms', postureCycle: 'postureCycle', contextRules: 'contextRules',
    };
    let data: unknown;
    let note: string | undefined;
    if (section === 'medications') {
      if (!hasScope(this.g.scopes, 'medication')) {
        throw new RelayError('insufficient_scope', 'Medication data requires the medication scope, which the owner has not granted to this client', 403, ['medication']);
      }
      data = s.medication ?? null;
      if (!s.medication) note = 'The phone has not published medication data (the owner may have it disabled).';
    } else if (section === 'all') {
      data = s.config;
    } else if (KEYS[section]) {
      data = (s.config as Record<string, unknown>)[KEYS[section]] ?? null;
      if (data === null) note = `Section ${section} is not present in the snapshot.`;
    } else {
      throw new RelayError('invalid_request', `Unknown section ${section}`, 400);
    }
    return { snapshot: meta, pendingCommands: pending, section, note, data: markUntrusted(data) };
  }

  async getStatus() {
    this.need('config:read');
    const s = await this.relay.getSnapshot();
    const pending = await this.relay.pending();
    return {
      snapshot: await this.snapMeta(),
      phone: { ...(await this.relay.phoneStatus()), lastSeenAt: iso((await this.relay.phoneStatus()).lastSeenAt) },
      pendingCommands: pending.map((c) => ({ commandId: c.id, type: c.type, state: c.state, createdAt: iso(c.createdAt), expiresAt: iso(c.expiresAt) })),
      status: markUntrusted(s?.status ?? null),
    };
  }

  private async view(c: Command, extra: { deduplicated?: boolean } = {}): Promise<CommandView> {
    const ps = await this.relay.phoneStatus();
    const isPreview = c.type === 'config.preview';
    const r = c.result;
    const ver = r?.newVersion !== undefined ? ` Config is now at version ${r.newVersion}.` : '';
    let summary: string;
    switch (c.state) {
      case 'queued': {
        const wake = c.wake
          ? c.wake.ok
            ? 'A wake push was requested; delivery is best-effort.'
            : `A wake push was attempted but failed (${c.wake.detail ?? 'unknown'}).`
          : 'No push wake is configured, so the phone only notices this at its next sync (app open, session start, or periodic background sync of at least ~15 minutes; not guaranteed).';
        summary = `NOT APPLIED. Queued on the relay; the phone has not fetched it yet. ${await this.phoneLine()} ${wake} The command expires ${ago(c.expiresAt - this.now)} from now; if the phone does not pick it up by then it is dropped and the change never happens. Poll with get_command_status.`;
        break;
      }
      case 'delivered':
        summary = `NOT CONFIRMED. The phone fetched this command ${ago(this.now - (c.deliveredAt ?? c.createdAt))} ago but has not reported a result. It may be applied or not; do not assume. Poll with get_command_status.`;
        break;
      case 'awaiting_confirmation':
        summary = 'NOT APPLIED YET. This change is sensitive or destructive and the phone is waiting for the owner to confirm it on the device. Tell the user to open DayCue and confirm or decline.';
        break;
      case 'applied':
        summary = isPreview
          ? 'Preview computed by the phone. NOTHING WAS CHANGED. Use apply_change to make the change.'
          : `APPLIED. The phone confirmed with a signed acknowledgement.${ver}${c.lateAck ? ' (Acknowledged after the relay-side expiry time.)' : ''}`;
        break;
      case 'rejected':
        summary = 'REJECTED by the phone. NOT applied. See result for the phone\'s validation errors or conflict (a conflict means the config changed since baseVersion: re-read and retry).';
        break;
      case 'failed':
        summary = 'FAILED on the phone. NOT applied (or not known to be). See result.';
        break;
      case 'expired':
        summary = `EXPIRED at ${iso(c.expiresAt)} without a phone result. The change did NOT happen. ${await this.phoneLine()}${ps.pushRegistered ? '' : ' No push wake is registered.'}`;
        break;
    }
    return {
      commandId: c.id,
      type: c.type,
      state: c.state,
      applied: c.state === 'applied' && !isPreview,
      previewed: c.state === 'applied' && isPreview,
      deduplicated: extra.deduplicated || undefined,
      createdAt: iso(c.createdAt)!,
      expiresAt: iso(c.expiresAt)!,
      summary: extra.deduplicated ? `Duplicate request (same idempotencyKey): returning the original command. ${summary}` : summary,
      result: r ? markUntrusted(r) : undefined,
      lateAck: c.lateAck,
    };
  }

  async submit(p: SubmitParams): Promise<CommandView> {
    const { command, deduplicated } = await this.relay.enqueue(this.g, {
      type: p.type, payload: p.payload, baseVersion: p.baseVersion, idempotencyKey: p.idempotencyKey, ttlSeconds: p.ttlSeconds,
    });
    const c = (await this.relay.waitForCommand(command.id, Math.min(p.waitSeconds ?? 0, MAX_WAIT_S))) ?? command;
    return this.view(c, { deduplicated });
  }

  async undo(p: { commandId?: string; version?: number; idempotencyKey: string; ttlSeconds?: number; waitSeconds?: number }) {
    this.need('config:write');
    let version = p.version;
    let target = p.commandId;
    if (p.commandId) {
      const t = await this.relay.getCommand(p.commandId);
      if (!t || (t.type !== 'config.apply' && t.type !== 'config.undo')) throw new RelayError('invalid_request', 'commandId is not a known config change', 400);
      if (t.state !== 'applied' || t.result?.newVersion === undefined) throw new RelayError('invalid_request', `That command is ${t.state}, not applied; there is nothing to undo`, 409);
      version = t.result.newVersion;
    }
    if (version === undefined) throw new RelayError('invalid_request', 'Provide commandId or version', 400);
    const payload: Record<string, unknown> = { targetVersion: version };
    if (target) payload.targetCommandId = target;
    return this.submit({ type: 'config.undo' as any, payload, baseVersion: version, idempotencyKey: p.idempotencyKey, ttlSeconds: p.ttlSeconds, waitSeconds: p.waitSeconds });
  }

  async getCommand({ id, waitSeconds }: { id: string; waitSeconds?: number }) {
    if (!hasScope(this.g.scopes, 'config:read') && !hasScope(this.g.scopes, 'sessions:control')) {
      throw new RelayError('insufficient_scope', 'Requires config:read or sessions:control', 403, ['config:read']);
    }
    const c = await this.relay.waitForCommand(id, Math.min(waitSeconds ?? 0, MAX_WAIT_S));
    if (!c) throw new RelayError('not_found', 'Unknown or purged command id', 404);
    return this.view(c);
  }

  async listChanges({ limit = 20 }: { limit?: number }) {
    this.need('config:read');
    const s = await this.relay.getSnapshot();
    const remote = await this.relay.recentChanges(Math.min(limit, 50));
    return {
      note: 'remoteChanges are changes applied through this relay (phone-acknowledged). phoneChanges is the phone\'s own recent-change list from its last snapshot, if it publishes one.',
      remoteChanges: remote.map((c) => ({
        commandId: c.id, type: c.type, appliedAt: iso(c.ackedAt), newVersion: c.result?.newVersion, requestedBy: markUntrusted(c.grant.clientLabel),
        summary: markUntrusted((c.result as any)?.summary ?? (c.result as any)?.message ?? null),
      })),
      phoneChanges: markUntrusted((s?.status as any)?.recentChanges ?? null),
    };
  }

  async getActivity() {
    this.need('activity:read');
    const a = await this.relay.activity();
    return {
      ...a,
      companions: a.companions.map((c) => ({ ...c, label: markUntrusted(c.label) })),
      note: a.fresh
        ? 'State combines the fresh signals of all Windows companions (any active -> active, else idle, else locked, else asleep); stale companions are ignored. See companions[] for each one\'s freshness.'
        : 'No fresh signal from the Windows companion (stale, missing, or companion offline). State is UNKNOWN, not idle: do not infer that the user is away or working.',
    };
  }
}

/** Used by the stdio server: same interface over the relay's /v1/client/* HTTP endpoints. */
export class HttpClientApi implements ClientApi {
  constructor(private baseUrl: string, private token: string, private fetchImpl: typeof fetch = fetch) {}

  private async call(op: string, body: unknown = {}): Promise<any> {
    const res = await this.fetchImpl(`${this.baseUrl.replace(/\/$/, '')}/v1/client/${op}`, {
      method: 'POST',
      headers: { authorization: `Bearer ${this.token}`, 'content-type': 'application/json' },
      body: JSON.stringify(body),
      signal: AbortSignal.timeout(40_000),
    });
    const j: any = await res.json().catch(() => ({}));
    if (!res.ok) throw new RelayError(j.error ?? 'relay_error', j.message ?? `Relay returned HTTP ${res.status}`, res.status, j.requiredScopes);
    return j;
  }
  whoami() { return this.call('whoami'); }
  getConfig(p: { section: string }) { return this.call('getConfig', p); }
  getStatus() { return this.call('getStatus'); }
  submit(p: SubmitParams) { return this.call('submit', p); }
  undo(p: any) { return this.call('undo', p); }
  getCommand(p: any) { return this.call('getCommand', p); }
  listChanges(p: any) { return this.call('listChanges', p); }
  getActivity() { return this.call('getActivity'); }
}

export const CLIENT_OPS = ['whoami', 'getConfig', 'getStatus', 'submit', 'undo', 'getCommand', 'listChanges', 'getActivity'] as const;
