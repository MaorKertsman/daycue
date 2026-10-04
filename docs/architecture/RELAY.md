# DayCue relay and MCP server

Owner: integrations engineer. Code: `mcp/`. Setup steps: `docs/setup/MCP.md`. Status: built and unit/integration tested against a fake phone and fake companion; **nothing here has run against a real phone, a real Windows companion, real FCM, Render, Claude.ai or ChatGPT** (see "Verification status").

The decided design is `docs/ARCHITECTURE.md` section 3.5. This file is the wire protocol the Android app (`android/app`) and the Windows companion (`companion/`) implement, plus the relay's auth, state machine, storage and operational facts.

## 1. Shape

```
Claude.ai / ChatGPT --(HTTPS, MCP Streamable HTTP + OAuth 2.1)--> relay /mcp
Claude Code --(stdio)--> local `daycue` MCP process --(HTTPS, client token)--> relay /v1/client/*
Windows companion --(HTTPS, signed signal)--> relay /v1/companion/signal
relay --(FCM data message, optional)--> phone --(HTTPS pull + signed ack)--> relay /v1/phone/*
```

- Single owner, one phone (re-pairable), any number of companions and MCP clients. No signup.
- The relay is a plain Node HTTP server (Hono + a `Store` interface). Core logic (`src/relay.ts`, `src/auth.ts`, `src/clientApi.ts`, `src/tools.ts`, `src/app.ts`) uses only web-standard APIs and WebCrypto, so it also runs on Workers/Deno/Bun. Node-only code lives in `src/node/` and `src/store.ts` (FileStore).
- **The phone is the only authority.** The relay never validates or interprets `ConfigOp`s; it queues them, and a command becomes `applied` only when it holds an ack signed by the phone's device key.

## 2. Hosting target: Render (primary), storage designed accordingly

Facts verified 2026-10-04 from Render's docs (`https://render.com/docs/free`, `https://render.com/docs/disks`, `https://render.com/docs/blueprint-spec`):

| Resource | Free-tier fact | Consequence |
|---|---|---|
| Free web service | Spins down after 15 min without inbound traffic; ~1 minute to spin back up; 750 free instance hours/workspace/month; **cannot attach a persistent disk**; single instance; no SSH | No local file persistence; first request after idle takes about a minute |
| Free Postgres | **Expires 30 days after creation** (+14 day grace before deletion); 1 GB; one per workspace; no backups | Not durable. Pairing, grants and the command queue would vanish every month |
| Free Key Value | Does **not** persist state to disk (data lost on restart); one per workspace | Unusable for a durable queue |
| Persistent disk | Paid web services only; single instance; adding a disk disables zero-downtime deploys (old instance stops before the new one starts); daily snapshots kept at least 7 days | Fits a single-owner relay: 1 instance, brief deploy blip |

Prices: Render's own pricing page did not render in my fetch, so these come from third-party 2026 summaries and **must be re-checked in the Render dashboard before purchase**: Starter web service about $7/month; persistent disk about $0.25/GB/month; Postgres Basic-256mb about $6/month plus $0.30/GB/month storage (e.g. <https://www.srvrlss.io/provider/render/>, <https://costbench.com/software/developer-tools/render/hidden-costs/>). One source reports Render changed prices twice in September 2026, so treat all figures as unverified.

### Options (durable queue is a hard requirement, so $0 is not achievable on Render)

| | Resources | Cost (unverified) | Durable? | Spin-down? |
|---|---|---|---|---|
| **A. Recommended** | Starter web service + 1 GB disk (`render.yaml`), `FileStore` | about $7.25/month | yes (disk, daily snapshots) | no |
| B | Free web service + Basic Postgres (`DATABASE_URL`, `PgStore`) | about $6/month | yes | **yes** (15 min idle, ~1 min cold start) |
| C ($0, degraded) | Free web service + free Postgres | $0 | **no**: DB expires every 30 days, owner must recreate it and re-pair the phone | yes |

Why A: a durable queue plus a wake path that depends on the relay being awake makes spin-down the larger problem than the $6 vs $7 difference. `PgStore` (`src/pgstore.ts`) is written to the same `Store` contract but **has not been run against a real Postgres** (none available); `FileStore` is tested including restart survival. Cloudflare Workers + D1 was the previous default and remains possible (the core is platform-neutral) but needs a D1 `Store` adapter that does not exist yet.

### How spin-down affects each path (applies to options B and C; option A does not spin down)

- **OAuth and MCP from Claude.ai/ChatGPT**: the first request after 15 idle minutes waits about a minute for the service to boot. Adding the connector (discovery, 401, authorize, token) can time out on the first attempt; retrying works. A single `tools/call` after idle may exceed a client timeout. Nothing is lost: all OAuth state (pending authorization, auth codes with 60 s lifetime, tokens) is in the store, not process memory.
- **Commands**: a queued command is durable in the store, so restarts and spin-downs never lose it. Tool calls that wait for the phone (`waitSeconds` up to 25) are ordinary HTTP requests.
- **Wake chain**: the relay can only send FCM while it is running. Path with a sleeping relay: MCP client request -> ~60 s boot -> command stored -> FCM push -> phone pull. Expect 1 to 2 minutes best case; no guarantee.
- **Companion signals**: a POST during boot can time out. Signals have a ~3 minute TTL (`docs/PRODUCT.md` companion stale rule), so a cold relay makes them stale. The companion should use a >= 90 s HTTP timeout and retry. A companion posting at least every 10 minutes would itself keep a free service awake (one free service running 24/7 is about 744 h, within the 750 h allowance) but that is a side effect, not a guarantee, and the companion may be off.
- **Phone pulls** (WorkManager, app open): use a >= 90 s read timeout and treat timeouts as "try later", never as failure of the command.

## 3. Authorization

### 3.1 MCP clients (Claude.ai, ChatGPT, anything remote)

Verified 2026-10-04 against `https://modelcontextprotocol.io/specification/latest/basic/authorization` (revision 2026-07-28) and its client-registration page. The relay is both resource server and authorization server at one origin (`issuer` = base URL, `resource` = `<base>/mcp`).

| Requirement (spec) | Implementation |
|---|---|
| Streamable HTTP | `ALL /mcp`, stateless per request via `createMcpHandler` from `@modelcontextprotocol/server` 2.3.0 (also serves 2025-era clients through its stateless legacy fallback), JSON responses |
| RFC 9728 PRM (MUST) | `/.well-known/oauth-protected-resource` and `/.well-known/oauth-protected-resource/mcp` |
| `WWW-Authenticate` on 401 (MUST) | `Bearer resource_metadata="...", scope="config:read activity:read"`; invalid/expired/wrong-audience/revoked tokens: 401 with `error="invalid_token"` |
| Insufficient scope | 403 with `error="insufficient_scope", scope="<needed>", resource_metadata=...` (checked on `tools/call` before dispatch) |
| RFC 8414 AS metadata | `/.well-known/oauth-authorization-server` (also served at `/.well-known/openid-configuration`) |
| OAuth 2.1 + PKCE | `S256` only; code single-use, 60 s; public clients only (`token_endpoint_auth_method: none`) |
| RFC 8707 audience (MUST) | `resource` accepted only if equal to `<base>/mcp` (else `invalid_target`); every access token is bound to the resource and re-checked on each request |
| RFC 9207 `iss` (SHOULD) | Included on every authorization response (success and error); advertised via `authorization_response_iss_parameter_supported: true` |
| CIMD (SHOULD) | `client_id` that is an `https://` URL is fetched (5 s timeout, 16 KiB cap, no redirects, `client_id` must equal the URL, redirect URIs validated, cached 1 h). Refused: non-https, IP literals, `localhost`, `.local`, `.internal`, single-label hosts. **Residual risk**: DNS names resolving to private addresses are not blocked (no resolver control on a generic runtime); on Render the relay has no private network peers configured, so impact is limited |
| DCR (MAY, deprecated) | `POST /register`, public clients, redirect URIs must be https / loopback http / private-use scheme; capped at 50 live registrations (oldest evicted), 90 day expiry |
| Token passthrough | None: tokens are opaque, issued and validated by this server only |
| Refresh | Rotating refresh tokens (30 days); reuse of a used token revokes the whole grant |

Tokens are opaque random strings (`dca_...`, `dcr_...`), stored only as SHA-256 hashes. A **grant** (client + approved scopes) is the unit of revocation: revoking it kills all its access and refresh tokens at once, because scopes and revocation are read from the grant on every request.

**Scopes**: `config:read`, `config:write` (implies `config:read`), `sessions:control`, `activity:read`, `medication`. Medication is independent, never implied, never pre-ticked on the consent page. Without it, medication labels are not served; in addition the relay tells the phone (`wants.medication`) whether any active grant holds the scope so the phone can avoid publishing medication data at all.

**Consent**: `GET /authorize` renders a page (client name, redirect host, per-scope checkboxes; the owner may grant fewer scopes than requested). Approving requires the **owner secret** (`DAYCUE_OWNER_SECRET`, >= 24 chars) typed into the page; 5 consecutive failures lock owner checks for 30 s doubling to 15 min. The pending request is bound to an unguessable single-use transaction id (10 min); the page is `frame-ancestors 'none'`, `no-store`, `no-referrer`.

**Owner API** (`Authorization: Bearer <owner secret>`): `POST /v1/owner/pair-codes`, `POST /v1/owner/client-tokens`, `GET /v1/owner/grants`, `DELETE /v1/owner/grants/:id`, `POST /v1/owner/revoke-all`, `GET /v1/owner/devices`, `DELETE /v1/owner/devices/:id`, `GET /v1/owner/audit?limit=`.

### 3.2 Local stdio server (Claude Code)

Spec says stdio servers should take credentials from the environment, not OAuth. `src/stdio.ts` reads `DAYCUE_RELAY_URL` and `DAYCUE_CLIENT_TOKEN`. The token is a **client token** minted by the owner (`POST /v1/owner/client-tokens {label, scopes, expiresInDays<=365, default 90}`, shown once), stored as a grant like any other (so listed, scoped, audited and revocable), and sent to `POST /v1/client/<op>`. The stdio process contains no tool logic of its own beyond the shared `buildMcpServer`; scope checks happen on the relay.

### 3.3 Phone

1. Owner calls `POST /v1/owner/pair-codes` -> `{code: "ABCDE-FGHJK", expiresAt}` (10 min, single use, stored hashed). The app shows an input (or QR carrying relay URL + code).
2. Phone generates an **ECDSA P-256** key in Android Keystore and calls `POST /v1/pair/phone {code, publicKey, label, fcmToken?}` where `publicKey` is base64/base64url of `PublicKey.getEncoded()` (X.509 SPKI). Response `201 {deviceId, token, serverTime}`. `token` (`dcd_...`) is the device credential: `Authorization: Bearer` on every `/v1/phone/*` call. Store it in encrypted storage.
3. Pairing again (new code) revokes the previous phone credential and marks every non-terminal command `expired` ("phone re-paired").
4. 5 wrong codes lock pairing attempts (30 s doubling). Codes are ~49 bits.

Why ECDSA P-256 and not Ed25519: Android Keystore supports P-256 on all API levels; Ed25519 only recently. Signatures may be raw `r||s` (64 bytes) or Java's DER output; both are accepted (base64 or base64url).

### 3.4 Windows companion

1. App calls `POST /v1/phone/companion-codes` (device auth) -> `{code, expiresAt}`; user types it into the companion.
2. Companion generates an ECDSA P-256 key (e.g. `ECDsa.Create(ECCurve.NamedCurves.nistP256)`, `ExportSubjectPublicKeyInfo()`), calls `POST /v1/pair/companion {code, publicKey, label}` -> `201 {deviceId, token, serverTime}`; credential `dcd_...`.
3. The companion can post only activity signals. Its credential cannot read anything.

## 4. Wire protocol

All bodies JSON, `Content-Type: application/json`, times are epoch milliseconds unless stated. Errors: `{error, error_description, message}` with HTTP 4xx. Never retry 4xx other than 429/401-after-repair; do retry network errors and 5xx with backoff.

### 4.1 Snapshot (phone -> relay)

`PUT /v1/phone/snapshot`

```json
{
  "version": 42,
  "schemaVersion": 1,
  "publishedAt": 1791114000000,
  "config": { "habits": [], "routines": [], "cueProfiles": [], "calendarRules": [], "places": [], "settings": {}, "alarms": [], "postureCycle": {}, "contextRules": [] },
  "medication": { "medications": [] },
  "status": { "activeSessions": [], "nextCues": [], "recentChanges": [{ "version": 41, "at": 1791113000000, "source": "ui", "summary": "..." }] }
}
```

- `version` is `DayCueConfig.version`; an older version than the stored one is rejected `409 stale_snapshot`. Same version may be re-sent to refresh `status`.
- **Redaction is the phone's job**: no coordinates, no calendar event contents, no tokens, no history. Place names only. `config` section names above are what MCP `get_config` exposes (`habits`, `routines`, `cueProfiles`, `calendarRules`, `places`, `settings`, `alarms`, `postureCycle`, `contextRules`; `all` returns the whole object). Use these key names (or tell me to change `clientApi.ts`).
- `medication` is optional and **only stored if an active grant holds the `medication` scope**; otherwise it is silently dropped. Defensively, a `medications`/`medication` key inside `config` is moved out of `config` the same way. The response tells you: `{ok, wants: {medication: boolean}, pendingCommands, serverTime}`; publish medication only when `wants.medication` is true and the owner has enabled it.
- Max 256 KiB. Publish after every applied change (including phone-originated ones), and on app start.

### 4.2 Commands (relay -> phone)

`GET /v1/phone/commands` returns everything pending and marks it `delivered`; the same command is returned again on the next pull until acked (at-least-once). **The phone must dedupe by `id` using `command_log`** and never re-apply.

```json
{ "commands": [{
    "id": "cmd_...", "type": "config.apply", "payload": { "ops": [ { "type": "SetHabitInterval", "habitId": "..." } ] },
    "baseVersion": 41, "idempotencyKey": "...", "payloadHash": "9f2c...", "createdAt": 1791114000000, "expiresAt": 1791135600000,
    "grant": { "clientLabel": "Claude", "scopes": ["config:read", "config:write"] }
  }],
  "wants": { "medication": false }, "serverTime": 1791114001000 }
```

| `type` | `payload` | Phone behavior |
|---|---|---|
| `config.preview` | `{ops}` | Run `preview(config, ops)` (no change). Ack `applied` with `result.preview = {diff, sensitivity}` |
| `config.apply` | `{ops}` | `applyOps(config, ops, baseVersion)`. Ack `applied` + `result.newVersion`, or `rejected` + `result.errors[]` / `result.conflict.currentVersion`, or `awaiting_confirmation` |
| `config.undo` | `{targetVersion, targetCommandId?}`, `baseVersion = targetVersion` | Revert the change that produced `targetVersion` using `config_history`; **only valid if `targetVersion` is still the current version**, else `rejected` with `conflict`. Undo creates a new version |
| `session.control` | `{action: start\|pause\|resume\|stop, target: {kind: routine\|work_session, id?}}` | Emit the corresponding engine event. Ack `applied` or `rejected` (e.g. unknown routine) |

Rules for the phone:

- **Expiry**: never apply a command whose `expiresAt` has passed on the phone's clock.
- **Sensitivity**: the relay passes the grant's `scopes`. The phone decides sensitivity via `preview().sensitivity`. `sensitive`/`destructive` changes (medication schedule edits and deletions, deletions generally) must show an on-phone confirmation unless the owner has explicitly granted otherwise; ack `awaiting_confirmation` immediately, then a second ack (`applied`/`rejected`) after the owner decides. Changes touching medication also require the `medication` scope in `grant.scopes`, else `rejected` with an error code (the relay cannot know).
- `ops` JSON is the `ConfigOp` shape from `docs/architecture/DOMAIN.md` (not yet available when this was written; the relay treats ops as opaque objects that each have a string `type`). **`update_calendar_rules` uses provisional op names** (`SetCalendarRule`, `DeleteCalendarRule`, `ReorderCalendarRules`) in `src/tools.ts` `CALENDAR_OP`; align that constant with the domain module.
- Pull on: app open, FCM wake, WorkManager periodic sync, session start. Ack immediately after applying (retry acks on network failure; acks are idempotent).

### 4.3 Ack (phone -> relay)

`POST /v1/phone/commands/:id/ack`

```json
{ "outcome": "applied", "result": { "newVersion": 42, "sensitivity": "ordinary", "summary": "Sunscreen interval 120 -> 90 min" },
  "ackedAt": 1791114002000, "payloadHash": "9f2c...", "signature": "<base64url>" }
```

`outcome`: `applied | rejected | failed | awaiting_confirmation`. `result` fields (all optional): `newVersion`, `preview`, `sensitivity`, `errors: [{path?, code, message}]`, `conflict: {currentVersion}`, `message`, `summary`. Max 64 KiB. Anything in `result` is shown to MCP clients as untrusted text; do not put secrets in it.

**Signature** = ECDSA P-256/SHA-256 over the UTF-8 string (lines joined with `\n`, no trailing newline):

```
daycue.ack.v1
<command id>
<payloadHash exactly as received>
<outcome>
<result.newVersion, or empty string if none>
<ackedAt as integer>
```

Relay verifies against the public key registered at pairing and answers `403 bad_signature` otherwise (and audits it). So the relay cannot forge `applied`; a compromised relay can still withhold, delay or inject commands (see threats). `payloadHash` is relay-computed and opaque to the phone: echo it. A repeat of a terminal outcome is accepted idempotently (`200`); a different terminal outcome is `409`.

A signed ack that arrives after the relay marked the command `expired` is accepted and recorded as `applied` with `lateAck: true` (the truth is what the phone did). This only happens if the phone applied before its own `expiresAt` but the ack was delayed.

### 4.4 Other phone endpoints

| Call | Purpose |
|---|---|
| `PUT /v1/phone/push {fcmToken?: string\|null, wakeOnActivity?: boolean}` | Register/clear the FCM registration token; opt in to wake on companion state change |
| `POST /v1/phone/companion-codes` | Mint a companion pairing code |
| `GET /v1/phone/activity` | `{signal, companions: [{id, label, publicKey}], serverTime}`: the latest raw signed signal and the companion public keys, so **the phone verifies companion signatures itself** instead of trusting the relay. Used by the opt-in "frequent check" mode |

### 4.5 Companion signal

`POST /v1/companion/signal` (companion credential)

```json
{ "state": "active", "observedAt": 1791114000000, "ttlSeconds": 180, "signature": "<base64url>" }
```

`state`: `active | idle | locked | asleep`; `ttlSeconds` integer 10..600; `observedAt` not more than 2 min in the future and `observedAt + ttl` must still be in the future (else `422 already_expired`); must be strictly newer than the last accepted signal from that companion (`409 stale_signal`, replay protection). Signature over:

```
daycue.signal.v1
<companion deviceId>
<state>
<observedAt>
<ttlSeconds>
```

Send on every state change and a heartbeat at about one third of the TTL while the state holds. The relay keeps only the latest signal plus a transition log (max 100 entries, 24 h). Reads past `observedAt + ttl` report `unknown`, never the stale state.

## 5. Command state machine

```
            enqueue                  phone pull                 signed ack
 (none) ----------> queued ----------------------> delivered ----------------> applied | rejected | failed
                      |                                |  \                          ^
                      | now >= expiresAt               |   \ ack awaiting_confirmation
                      v                                v    v                       |
                   expired <---------------------- expired  awaiting_confirmation --+ (second signed ack)
```

- `queued`: stored, phone has not fetched. `delivered`: phone fetched, no result yet (may or may not have applied). `awaiting_confirmation`: waiting for on-device owner confirmation. Terminal: `applied`, `rejected`, `failed`, `expired`.
- Expiry is evaluated lazily on every read (and by the purge job); default TTL: apply/undo 6 h, session control and preview 10 min; bounds 30 s .. 7 d (apply/undo) or 1 h (session/preview).
- **Idempotency**: key scope is `(client, idempotencyKey)`; same key + same content returns the original command (`deduplicated: true`); same key + different content is `409 idempotency_conflict`. Applied-once on the phone is enforced by the phone's `command_log` keyed by command id; the relay re-delivers until acked, so the phone must dedupe.
- `baseVersion` is checked by the phone only (the relay does not know the live version). A mismatch is `rejected` with `conflict`.
- `config.preview` reaching `applied` means "phone computed the preview"; the MCP view reports `previewed: true, applied: false`.

### How MCP tools report outcomes

Every mutation result carries `state`, `applied` (true only for a phone-confirmed non-preview change), `previewed`, and a relay-authored `summary` that begins `NOT APPLIED`, `NOT CONFIRMED`, `APPLIED`, `REJECTED`, `FAILED`, or `EXPIRED` and explains why a command is pending (phone last seen N ago; whether an FCM wake was requested or none is configured; when it expires). Tool descriptions and server instructions tell the model to report anything other than `applied` as pending or failed. Tools wait up to `waitSeconds` (default 10, max 25) for the phone, then return the honest current state.

Tools: `get_config`, `get_status`, `propose_change` (preview), `apply_change`, `update_calendar_rules`, `control_session`, `get_command_status`, `get_activity_summary`, `list_recent_changes`, `undo_change`. Only tools permitted by the token's scopes are listed. Read results always state snapshot config version and age, and the number of pending commands not reflected in it.

**Untrusted text**: everything derived from phone data (config strings, status, command results, client labels) passes through `markUntrusted`: strings that are identifier-like (<= 48 chars of `A-Za-z0-9_.:+-/`) stay plain; all others become `<untrusted>...</untrusted>` with control characters stripped, `<`/`>` replaced by look-alike angle quotes (so the wrapper cannot be closed early) and length capped at 500. Each tool result starts with a notice that this is data, not instructions. Relay-authored `summary` text is the only trusted prose and never embeds phone-supplied strings.

## 6. FCM wake

`WakeSender` interface (`src/wake.ts`): `NoopWakeSender` (default), `FakeWakeSender` (tests), `FcmHttpV1Sender` (service-account JWT -> OAuth token -> `POST https://fcm.googleapis.com/v1/projects/<id>/messages:send`). Message: **data-only, `android.priority: HIGH`, ttl 120 s, payload `{type: "sync", reason: "command"|"activity"}`**, no config content. Enabled only when `FCM_SERVICE_ACCOUNT_JSON` is set and the phone registered a token. Wakes are coalesced to at most one per 15 s per reason (a single pull fetches everything). Enqueue waits at most 3 s for the FCM call; failure is recorded on the command and surfaced in the tool summary.

Verified (<https://firebase.google.com/docs/cloud-messaging/android-message-priority>): high-priority messages are delivered immediately but FCM may downgrade them to normal priority if over 7 days they do not result in user-facing notifications. **Phone-side consequence**: sometimes show a user-visible notification as a result of a wake (e.g. "DayCue applied a change from Claude" or a pending-confirmation prompt), otherwise wakes may be deprioritised and arrive late (Doze). The real FCM sender is **unverified** (no credentials); only its request shape is unit-tested.

## 7. Latency: what is and is not guaranteed

- **Guaranteed**: the relay never reports `applied` before a signed phone ack; commands are durable (option A/B) and are dropped only by expiry, which is reported as `expired`; nothing is applied after expiry by a correct phone.
- **Not guaranteed**: any delivery time. With FCM and a warm relay: typically seconds, but Doze/standby, downgraded priority, manufacturer battery limits, or no network can delay it. Without FCM: the phone only syncs on app open, session start, and periodic WorkManager (>= 15 min, best effort), so a command can sit `queued` for much longer than its tool call waits. With a sleeping free-tier relay add about a minute.
- Tool calls wait at most 25 s. Anything slower is returned as pending with the reason; the model is told to poll `get_command_status` or tell the user.
- Companion-driven automatic sessions need the phone to see signals within ~3 minutes: only possible with FCM wake-on-activity or the opt-in frequent-check poll; otherwise sessions are manual (`docs/ARCHITECTURE.md` 3.5).

## 8. Network path per client

| Client | Path | Notes |
|---|---|---|
| Claude.ai (web/desktop custom connector) | Anthropic's cloud -> `https://<relay>/mcp` | Relay must be reachable from the public internet over HTTPS. Choose OAuth client "Use Claude's published identity" (CIMD) or "Register automatically" (DCR); both supported. Callback `https://claude.ai/api/mcp/auth_callback` (also `https://claude.com/api/mcp/auth_callback` for Desktop), per Claude's docs. Source: <https://claude.com/docs/connectors/custom/remote-mcp> |
| ChatGPT (developer mode app) | OpenAI cloud -> `https://<relay>/mcp` | Developer mode needs Settings -> Security and login; SSE and streaming HTTP; OAuth with static credentials, CIMD or DCR; write tools require confirmation by default and honour `readOnlyHint`, which the tools set. Plan availability per OpenAI docs: Pro, Plus, Business, Enterprise, Education (web). Source: <https://developers.openai.com/api/docs/guides/developer-mode>. Third-party reports say Plus/Pro may be read-only for custom connectors; **verify in the owner's account** |
| Claude Code | stdio child process on the owner's PC -> `https://<relay>/v1/client/*` with a client token | No inbound network exposure. Can also run against `http://localhost:8787` |
| Windows companion | PC -> `https://<relay>/v1/companion/signal` | Outbound only |
| Phone | Phone -> `https://<relay>/v1/phone/*`; FCM -> phone | Outbound only |

A relay on a public URL is required for Claude.ai and ChatGPT (their servers connect to it); a localhost relay works only for Claude Code and local tests.

## 9. Data held remotely and retention

| Data | Contents | Retention |
|---|---|---|
| Pairing codes | SHA-256 of code, kind | 10 min, deleted on use |
| Devices | id, label, public key, last seen, FCM token, hash of device credential | until revoked or re-paired (a revoked record keeps id, label and public key, with the FCM token and credential hash removed) |
| Config snapshot | redacted config + status (+ medication only with scope) | latest only, overwritten |
| Commands | type, ops payload, result, grant label/scopes, state | until `expiresAt` + 14 days, then purged |
| Idempotency index | hash of (client, key) -> command id | same as command |
| Activity | latest signal + transition log (state + time) | 24 h, max 100 entries |
| Grants | client id/label, scopes, last used | until revoked (revoked grants remain as records) |
| Access/refresh tokens | SHA-256 hashes | 1 h / 30 days |
| OAuth codes, pending authorizations, CIMD cache | hashed codes / request data | 60 s / 10 min / 1 h |
| DCR clients | name, redirect URIs | 90 days, max 50 |
| Audit log | actor, action, ids, scope names, **no ops, no snapshot content, no IPs** | 90 days |
| Lockout counters | failure counts | 1 h |

Not stored: raw tokens/secrets, calendar contents, coordinates, history. The relay holds ops text the model wrote (habit ids, intervals, rule text) until purge, and anything the phone puts in `result` or the snapshot. Purge runs hourly in the Node server and lazily on read. The JSON file on the disk is `0600`.

## 10. Threat notes

- **Compromised relay or leaked owner secret**: can read the redacted snapshot, queue ops, withhold/delay/expire commands, and re-pair a new phone (owner secret is the root of trust). It cannot forge `applied` for the paired phone key, and sensitive/destructive changes still need on-phone confirmation. Mitigations: phone validates everything, confirmation, scopes, audit, revocation, small blast radius (redacted snapshot; no medication by default). Weakness: the phone does not authenticate command *origin* beyond the relay's TLS and device credential. If that matters, add a client-side signature on commands from a key held by the owner; not designed here.
- **Prompt injection through data**: calendar titles, place names, labels are wrapped as untrusted data and tool text says never to follow them; the phone never interprets them. Wrapping reduces but cannot eliminate model-side injection risk; sensitive changes still need on-phone confirmation, which is the real backstop.
- **Confused deputy / token theft**: audience-bound opaque tokens, scope per grant, 1 h access tokens, rotating refresh with reuse detection, immediate grant revocation, no token passthrough.
- **Open registration**: DCR is open by protocol design; it only creates unprivileged client ids. Every grant needs the owner secret on the consent page. Capped and expiring.
- **Brute force**: owner secret, pairing codes: counted lockouts with exponential backoff (stored, so they survive restarts). The owner secret is long random; do not reuse a human password.
- **CIMD SSRF**: see 3.1 residual risk.
- **Replay**: acks are signed over command id and hash and idempotent; companion signals need strictly increasing `observedAt`.
- **DNS rebinding (local dev)**: when the base URL is localhost the Host header must be loopback.
- **Free-tier spin-down / restarts** are availability issues, not integrity issues (state is durable under option A/B).

## 11. Verification status

| Item | Level |
|---|---|
| Pairing, OAuth (DCR + CIMD with fake fetch), audience/expiry/revocation, scopes, idempotency, offline pending, expiry, late ack, undo, sensitive confirmation flow, medication exclusion, untrusted marking, companion expiry/replay/forgery, FileStore restart survival, stdio server as a real child process, FCM request shape | **Unit/integration** (`mcp`: `npm test`, 47 tests, fake phone/companion) |
| Built server (`npm run build`, `node dist/node/server.js`): health, 401 challenge, PRM | Smoke-tested locally with curl |
| Real Claude.ai / ChatGPT / Claude Code connection | **Unverified** (needs a public HTTPS URL and the owner's accounts) |
| Render deployment, `render.yaml` validity | **Unverified** (keys checked against Render's blueprint spec page; plan name `starter` may need adjusting) |
| `PgStore` | **Unverified** (no Postgres available) |
| Real FCM, Android Keystore signatures, .NET signatures against this verifier | **Unverified**; the DER/raw ECDSA path is unit-tested with WebCrypto-generated keys only |
