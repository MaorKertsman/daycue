export const SCOPES = ['config:read', 'config:write', 'sessions:control', 'activity:read', 'medication'] as const;
export type Scope = (typeof SCOPES)[number];
/** Scopes that stay inactive on a new grant until the phone approves it (requirePhoneApprovalForNewGrants). */
export const PHONE_GATED_SCOPES: readonly Scope[] = ['config:write', 'sessions:control', 'medication'];
export const DEFAULT_SCOPES: Scope[] = ['config:read', 'activity:read'];

/** config:write implies config:read. `medication` is independent and never implied. */
export function hasScope(granted: readonly string[], need: Scope): boolean {
  if (granted.includes(need)) return true;
  if (need === 'config:read' && granted.includes('config:write')) return true;
  return false;
}

export type CommandType = 'config.preview' | 'config.apply' | 'config.undo' | 'session.control';
export const COMMAND_SCOPE: Record<CommandType, Scope> = {
  'config.preview': 'config:write',
  'config.apply': 'config:write',
  'config.undo': 'config:write',
  'session.control': 'sessions:control',
};

export type CommandState =
  | 'queued'
  | 'delivered'
  | 'awaiting_confirmation'
  | 'applied'
  | 'rejected'
  | 'failed'
  | 'expired';
export const TERMINAL: CommandState[] = ['applied', 'rejected', 'failed'];

export type AckOutcome = 'applied' | 'rejected' | 'failed' | 'awaiting_confirmation';

export interface Grantee {
  grantId: string;
  clientId: string;
  clientLabel: string;
  /** Effective (active) scopes. Gated scopes of a grant that still awaits phone approval are not included. */
  scopes: string[];
  /** Scopes the owner granted that stay inactive until the phone approves the grant. */
  pendingScopes?: string[];
}

export interface CommandResult {
  /** Config version after the change (applied config.apply / config.undo). */
  newVersion?: number;
  /** Phone-computed preview (human-readable diff + sensitivity). */
  preview?: { diff?: unknown; sensitivity?: 'ordinary' | 'sensitive' | 'destructive'; [k: string]: unknown };
  sensitivity?: 'ordinary' | 'sensitive' | 'destructive';
  errors?: Array<{ path?: string; code: string; message: string }>;
  conflict?: { currentVersion: number };
  message?: string;
  [k: string]: unknown;
}

export interface Command {
  id: string;
  type: CommandType;
  payload: Record<string, unknown>;
  baseVersion?: number;
  idempotencyKey: string;
  payloadHash: string;
  grant: Grantee;
  state: CommandState;
  createdAt: number;
  expiresAt: number;
  deliveredAt?: number;
  deliveryCount: number;
  ackedAt?: number;
  result?: CommandResult;
  /** True if the phone's signed ack arrived after the relay had already marked it expired. */
  lateAck?: boolean;
  /** 2 when the phone's signature also covered the canonical result (daycue.ack.v2). */
  ackVersion?: 1 | 2;
  wake?: { requestedAt: number; ok: boolean; detail?: string };
}

export interface Device {
  id: string;
  role: 'phone' | 'companion';
  label: string;
  publicKey: string;
  createdAt: number;
  lastSeenAt?: number;
  revokedAt?: number;
  tokenHash?: string;
  fcmToken?: string;
  wakeOnActivity?: boolean;
}

export interface Snapshot {
  version: number;
  schemaVersion?: number;
  publishedAt: number;
  receivedAt: number;
  config: Record<string, unknown>;
  medication?: Record<string, unknown>;
  status: Record<string, unknown>;
}

export type ActivityState = 'active' | 'idle' | 'locked' | 'asleep';
export interface ActivitySignal {
  companionId: string;
  state: ActivityState;
  observedAt: number;
  ttlSeconds: number;
  sig: string;
  receivedAt: number;
}

export class RelayError extends Error {
  constructor(
    public code: string,
    message: string,
    public status = 400,
    public requiredScopes?: string[],
    public retryAfterS?: number,
  ) {
    super(message);
  }
}
