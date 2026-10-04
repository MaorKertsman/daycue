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
| CIMD (SHOULD) | `client_id` that is an `https://` URL is fetched (5 s timeout, 16 KiB cap enforced while streaming, no redirects, port 443 only, `client_id` must equal the URL, redirect URIs validated, cache capped at 100 entries, 1 h). Refused by name, after stripping trailing dots (`localhost.` is `localhost`): non-https, IP literals in any numeric form, `localhost`, `.localhost`, `.local`, `.internal`, `.localdomain`, `.home.arpa`, single-label hosts. **DNS is vetted too** (Node server, `src/node/safefetch.ts`): the name is resolved once through a custom `lookup`, every returned address must be globally routable (private, loopback, link-local incl. 169.254.169.254, CGNAT, unique-local, multicast, IPv4-mapped/NAT64 forms are refused), and the socket connects to exactly that vetted address, so names like `127.0.0.1.nip.io` and DNS rebinding fail. TLS validates the certificate for the host name. **Residual risk**: on runtimes without `node:dns` (Workers/Deno/Bun using the portable default fetcher) only the name-level checks apply; outbound requests also reveal the relay's egress IP to the client-metadata host |
| DCR (MAY, deprecated) | `POST /register`, public clients, redirect URIs must be `https` or loopback `http` (private-use schemes are refused, as the MCP spec requires `localhost` or HTTPS); 5 registrations per source per hour; capped at 50 live registrations: when full, the oldest registration that was never approved and holds no grant is evicted, and a client that was approved or holds an active grant is never evicted (otherwise 429); unused registrations expire after 7 days, approved ones after 90; registrations are not audited (unauthenticated, spammable) |
| Token passthrough | None: tokens are opaque, issued and validated by this server only |
| Refresh | Rotating refresh tokens (30 days); reuse of a used token revokes the whole grant |

Tokens are opaque random strings (`dca_...`, `dcr_...`), stored only as SHA-256 hashes. A **grant** (client + approved scopes) is the unit of revocation: revoking it kills all its access and refresh tokens at once, because scopes and revocation are read from the grant on every request.

**Scopes**: `config:read`, `config:write` (implies `config:read`), `sessions:control`, `activity:read`, `medication`. Medication is independent, never implied, never pre-ticked on the consent page. Without it, medication labels are not served; in addition the relay tells the phone (`wants.medication`) whether any active grant holds the scope so the phone can avoid publishing medication data at all.

**Consent**: `GET /authorize` renders a page (client name, client id, **redirect host in a bordered line at the top**, full return address, per-scope checkboxes; the owner may grant fewer scopes than requested). Approving requires the **owner secret** typed into the page (see owner-secret protection below). The pending request is bound to an unguessable single-use transaction id (10 min; at most 100 pending, oldest dropped).

- **Trusted redirect hosts.** `DAYCUE_ALLOWED_REDIRECT_HOSTS` (default `claude.ai, claude.com, chatgpt.com`, exact host match over `https`) plus loopback `http` (`localhost`, `127.0.0.1`, `[::1]`, for Claude Code and other local clients, with a note that it is an app on this device). Any other host gets a red "UNRECOGNIZED REDIRECT ADDRESS" block, and approval needs an extra explicit tick ("I recognize <host>"). Official values, checked 2026-10-04: Claude's connector callback is `https://claude.ai/api/mcp/auth_callback` and "may change to `https://claude.com/api/mcp/auth_callback`" (Claude connector docs, <https://claude.com/docs/connectors/custom/remote-mcp>, and the Anthropic support article <https://support.claude.com/en/articles/11503834>); Claude Code uses RFC 8252 loopback redirects on ephemeral ports (a third-party report, not Anthropic documentation: <https://sunpeak.ai/blogs/claude-connector-oauth-authentication/>; the MCP spec itself allows `localhost` redirects); ChatGPT redirects to `https://chatgpt.com/connector/oauth/{callback_id}`, with the legacy `https://chatgpt.com/connector_platform_oauth_redirect` still working for older apps (OpenAI Apps SDK auth guide, <https://developers.openai.com/apps-sdk/build/auth>). Only the host is matched, not the path. The list is configuration; re-check those pages when a connector stops showing as trusted.
- **No open redirect.** Denying (or approving nothing) redirects the browser only when the host is trusted; for an unrecognized host the relay shows a local "denied" page and does not redirect. Redirect URIs must match the registration; private-use schemes are refused.
- **CSRF.** `GET /authorize` sets `daycue_csrf` (HttpOnly, SameSite=Strict, Secure on https, path `/authorize`, 10 min); the form carries the same token; `POST /authorize/decision` requires cookie == form token == the hash stored with the transaction, and refuses a cross-site `Origin` / `Sec-Fetch-Site`. These checks run before any owner-secret comparison, so forged posts cannot burn the owner's attempt budget.
- **Clickjacking.** `Content-Security-Policy: frame-ancestors 'none'`, `X-Frame-Options: DENY`, `Cache-Control: no-store`, `Referrer-Policy: no-referrer` on every page including errors.

**Owner-secret protection.** The secret must be >= 32 characters with ~128 bits of estimated entropy (the relay refuses to start otherwise); generate it with `npm run gen-secret` (`node -e "console.log(require('crypto').randomBytes(32).toString('base64url'))"`). Comparison is over fixed-length SHA-256 digests without early exit. Attempts are counted **before** the comparison in one synchronous step (so parallel requests cannot exceed the budget), **per source address** (Render: the entry of `X-Forwarded-For` that the trusted proxy appended, counted from the right via `DAYCUE_TRUSTED_PROXY_HOPS`; entries to the left are client-controlled and ignored): 5 failures lock only that source for 30 s doubling to 15 min (`429` + `Retry-After`). Separate buckets exist for the owner API, the consent page and pairing, so none can be used to block another. A global ceiling (120 checks/minute across all sources) throttles only sources that have never authenticated; a source that authenticated before is exempt, so a distributed flood cannot lock the owner out of a known address. **Revocation and the kill path never depend on those lockouts**: `DELETE /v1/owner/grants/:id`, `POST /v1/owner/revoke-all` and `DELETE /v1/owner/devices/:id` accept the secret even from a locked-out source and are only rate limited (30/min/source), which is safe because of the entropy floor. Lockout counters live in process memory (a restart resets them; failed requests never cost disk writes); failed attempts are audited at most 12 per hour in total.

**Request limits.** Bodies: 64 KiB default, 300 KiB for `PUT /v1/phone/snapshot`, 128 KiB for `/mcp` and `/v1/client/*` (`413`, enforced on `Content-Length` and while streaming, before parsing). Per source and minute: 600 requests overall (`/healthz` exempt); `/register` 5/hour, `GET /authorize` 20/10 min, `/authorize/decision` 30/10 min, `/token` 60/min, `/revoke` 30/min, `/v1/pair/*` 30/10 min; per grant 120 commands/hour; at most 100 commands waiting for the phone (`429 too_many_pending`). Audit log: at most 2000 entries (trimmed to 1500). A store that rewrites a whole file per mutation would degrade with size, so `FileStore` coalesces writes (one async write per 250 ms; a hard crash can lose up to that window; SIGTERM/SIGINT flush). Limits are in memory and per process: the relay is a single instance by design.

**Authorization code reuse.** A code is single use; a tombstone is kept 10 minutes. Redeeming a used code again revokes the grant (and with it all tokens) issued from the first redemption and is audited (`oauth.code_reuse`), including when the replay races the first redemption.

**Per-client isolation and medication redaction.** A client sees full results of its own commands (same `clientId`). For another client's command it sees only id/state/type unless it holds the scope that command needs (`config:write`; `sessions:control` for session commands). A command is medication-related if its grant held `medication` or its ops have a medication type; its `result` is reduced to `newVersion`/`sensitivity` plus a "withheld" message for viewers without the `medication` scope, in `get_command_status` and `list_recent_changes` (marked `resultWithheld`). `get_status.pendingCommands` lists only the caller's own commands (others are counted in `pendingFromOtherClients`). Phone-published `status.recentChanges`, `nextCues` and `activeSessions` entries that mention medication (or carry `"medication": true`) are replaced with a withheld placeholder for clients without the scope (the phone should tag them). `get_activity_summary` carries only coarse state and companion labels, nothing medication-related, so it needs no redaction.

**New grants and the phone** (`requirePhoneApprovalForNewGrants`, env `DAYCUE_REQUIRE_PHONE_APPROVAL`, default **on**): while a phone is paired, a new grant (OAuth consent or owner-minted client token) that includes `config:write`, `sessions:control` or `medication` starts `approval: "pending"`; those scopes are inactive (tools hidden, calls `403` saying the phone must approve; `wants.medication` ignores it) until the phone approves. Read-only grants are active at once. The owner secret alone therefore cannot silently activate write access. The phone API is in 4.6.

**Owner API** (`Authorization: Bearer <owner secret>`): `POST /v1/owner/pair-codes`, `POST /v1/owner/client-tokens`, `GET /v1/owner/grants`, `DELETE /v1/owner/grants/:id`, `POST /v1/owner/revoke-all`, `GET /v1/owner/devices`, `DELETE /v1/owner/devices/:id`, `GET /v1/owner/audit?limit=`.

### 3.2 Local stdio server (Claude Code)

Spec says stdio servers should take credentials from the environment, not OAuth. `src/stdio.ts` reads `DAYCUE_RELAY_URL` and `DAYCUE_CLIENT_TOKEN`. The token is a **client token** minted by the owner (`POST /v1/owner/client-tokens {label, scopes, expiresInDays<=365, default 90}`, shown once), stored as a grant like any other (so listed, scoped, audited and revocable), and sent to `POST /v1/client/<op>`. The stdio process contains no tool logic of its own beyond the shared `buildMcpServer`; scope checks happen on the relay.

### 3.3 Phone

1. Owner calls `POST /v1/owner/pair-codes` -> `{code: "ABCDE-FGHJK", expiresAt}` (10 min, single use, stored hashed). The app shows an input (or QR carrying relay URL + code).
2. Phone generates an **ECDSA P-256** key in Android Keystore and calls `POST /v1/pair/phone {code, publicKey, label, fcmToken?}` where `publicKey` is base64/base64url of `PublicKey.getEncoded()` (X.509 SPKI). Response `201 {deviceId, token, serverTime}`. `token` (`dcd_...`) is the device credential: `Authorization: Bearer` on every `/v1/phone/*` call. Store it in encrypted storage.
3. Pairing again (new code) revokes the previous phone credential and marks every non-terminal command `expired` ("phone re-paired").
4. 5 wrong codes from one source address lock pairing attempts from that source (30 s doubling, `Retry-After`); other sources are unaffected. Codes are ~49 bits.
5. Unpairing: `DELETE /v1/phone/self` (4.4) revokes the phone credential on the relay.

Why ECDSA P-256 and not Ed25519: Android Keystore supports P-256 on all API levels; Ed25519 only recently. Signatures may be raw `r||s` (64 bytes) or Java's DER output; both are accepted (base64 or base64url).

### 3.4 Windows companion

1. App calls `POST /v1/phone/companion-codes` (device auth) -> `{code, expiresAt}`; user types it into the companion.
2. Companion generates an ECDSA P-256 key (e.g. `ECDsa.Create(ECCurve.NamedCurves.nistP256)`, `ExportSubjectPublicKeyInfo()`), calls `POST /v1/pair/companion {code, publicKey, label}` -> `201 {deviceId, token, serverTime}`; credential `dcd_...`.
3. The companion can post only activity signals. Its credential cannot read anything.
4. Uninstalling or unpairing: the companion calls `DELETE /v1/companion/self` with its own credential (`200 {ok, serverTime}`). The relay revokes that companion (its device record keeps id, label and public key, and loses the credential hash), deletes its signal slot and audits `companion.self_revoked`; the credential then gets `401`. Other companions are unaffected. A phone credential or the owner secret cannot call it (`401`); the owner can still revoke any device with `DELETE /v1/owner/devices/:id`.

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
- `medication` is optional and **only stored if an active grant holds the `medication` scope**; otherwise it is silently dropped. Defensively, a `medications`/`medication` key inside `config` is moved out of `config` the same way. The response tells you: `{ok, wants: {medication: boolean}, grantsVersion, pendingCommands, serverTime}`; publish medication only when `wants.medication` is true (an ACTIVE medication grant exists, see 4.6) and the owner has enabled it.
- Max 256 KiB. Publish after every applied change (including phone-originated ones), and on app start.

### 4.2 Commands (relay -> phone)

`GET /v1/phone/commands` returns everything pending and marks it `delivered`; the same command is returned again on the next pull until acked (at-least-once). **The phone must dedupe by `id` using `command_log`** and never re-apply.

```json
{ "commands": [{
    "id": "cmd_...", "type": "config.apply", "payload": { "ops": [ {"type":"setHabitInterval","id":"...","minutes":90} ] },
    "baseVersion": 41, "idempotencyKey": "...", "payloadHash": "9f2c...", "createdAt": 1791114000000, "expiresAt": 1791135600000,
    "grant": { "clientLabel": "Claude", "scopes": ["config:read", "config:write"] }
  }],
  "wants": { "medication": false },
  "grants": { "version": 7, "items": [ /* see 4.6 */ ] }, "serverTime": 1791114001000 }
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
- `ops` JSON is the `ConfigOp` shape from `android/domain/.../edit/ConfigOp.kt` (`docs/architecture/DOMAIN.md`): camelCase `type` discriminator (`setHabitInterval`, `upsertCalendarRule`, ...) plus that op's fields, encoded with `DayCueJson`. The relay treats ops as opaque objects that each have a string `type`. `update_calendar_rules` emits the real op names `upsertCalendarRule {rule, index?}`, `deleteCalendarRule {id}` and `reorderCalendarRules {ruleIds}` (`CALENDAR_OP` in `src/tools.ts`); the tool descriptions of `propose_change`/`apply_change` carry checked JSON examples (`OP_EXAMPLES`, verified by a test against the op names in `ConfigOp.kt`).
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
| `GET /v1/phone/activity` | The latest signed signal **per companion** plus the companion public keys; the phone verifies each signal's signature (message in 4.5, `signals[].signature`) with the listed key. **Trust model: see 4.4.1.** Used by the opt-in "frequent check" mode. Response below |
| `DELETE /v1/companion/self` | Companion credential only (see 3.4): the companion revokes itself |
| `DELETE /v1/phone/self` | Phone credential only: **unpair**. Revokes this phone (credential and FCM token dropped, record keeps id/label/public key), clears the paired-phone slot, expires every non-terminal command ("phone was unpaired"), audits `phone.self_revoked`; `200 {ok, serverTime}`. The app should call it best effort before deleting its local credential; afterwards the old credential gets `401`. Without it the credential stays valid until the owner revokes the device |
| `GET /v1/phone/grants` | The grant list (4.6) |
| `POST /v1/phone/grants/:id/decision` | Approve, decline or revoke a grant (4.6) |

```json
{ "signals": [{ "companionId": "co_...", "state": "active", "observedAt": 1791114000000, "ttlSeconds": 180, "signature": "<base64url>", "receivedAt": 1791114000500 }],
  "companions": [{ "id": "co_...", "label": "pc", "publicKey": "<base64 SPKI>" }],
  "signal": { "companionId": "co_...", "state": "active", "observedAt": 1791114000000, "ttlSeconds": 180, "sig": "<base64url>", "receivedAt": 1791114000500 },
  "serverTime": 1791114001000 }
```

`signals` has one entry per non-revoked companion that has a stored signal (most recent `observedAt` first; `[]` if none). `signal` is the legacy single-signal field, kept for backward compatibility: the most recent signal by `observedAt` in the old shape (`sig` instead of `signature`), or `null`. New clients should read `signals`. The relay does not filter by freshness here; the phone applies `observedAt + ttlSeconds` itself.

#### 4.4.1 Trust model for companion keys (corrected)

The phone verifies signatures itself, but it learns each companion's **public key from the relay** (`companions[].publicKey`). A compromised relay (or whoever holds the owner secret) can pair its own fake companion, or list a key it controls, and then produce signals that verify. So this check protects against **tampering with a genuine companion's signal in transit and against replay**, but it does **not** remove the relay from the trust base for "which keys count as my companions". The residual risk is limited to forged activity state (it can make the phone believe the PC is active/idle/locked; it cannot change configuration, apply anything or read anything). Not implemented, and the intended fix needs phone and companion UI work (not part of the relay): bind the key at pairing time on the two devices, not through the relay. Design: (1) the phone **pins** each companion key the first time it sees it, stores the pin locally and ignores any later key for that id or any new companion id until the owner confirms it; (2) at pairing the companion shows a short fingerprint of its own key (e.g. the first 10 base32 characters of SHA-256 of the SPKI) and the phone shows the fingerprint it computed itself from the key it received; the owner compares the two screens before accepting. A phone-generated pairing code that also carries that fingerprint is an equivalent alternative. Until that exists, treat activity as relay-trusted.

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

Send on every state change and a heartbeat at about one third of the TTL while the state holds. The relay keeps the latest signal **per companion** (store collection `signal`, key `latest:<companionId>`, so replay protection is per companion) plus a shared transition log (`signal/log`, entries `{state, at, companionId}`, max 100 entries, 24 h). A signal that differs from that companion's previous one, or follows an expired one, is a transition. Reads past `observedAt + ttl` report `unknown`, never the stale state.

MCP `get_activity_summary` reports each companion's freshness (`companions: [{companionId, label, state, fresh, observedAt, expiresAt, ttlSeconds}]`) and an overall `state` combined from the **fresh** signals only: any `active` -> `active`, else any `idle` -> `idle`, else any `locked` -> `locked`, else `asleep`; no fresh signal -> `unknown`.

### 4.6 Grants on the phone (new)

`GET /v1/phone/commands` now also returns `grants: {version, items}`, and `PUT /v1/phone/snapshot` returns `grantsVersion` so the phone can tell cheaply that the list changed; `GET /v1/phone/grants` returns `{grants: {version, items}, serverTime}`. `version` increases on every create, approve, decline and revoke. Items (revoked grants are omitted):

```json
{ "id": "gr_...", "label": "Claude", "kind": "oauth", "scopes": ["config:read","config:write"], "activeScopes": ["config:read"],
  "approval": "pending", "createdAt": 1791114000000, "lastUsedAt": null }
```

`approval`: `pending` (gated scopes inactive), `approved`, `not_required` (read-only or no phone was paired). Labels are client-supplied and unverified: show them as such.

`POST /v1/phone/grants/:id/decision` body `{decision: "approve"|"decline"|"revoke", approvedScopes?: string[], decidedAt: <epoch ms>, signature}`. `approve` activates the grant (optionally narrowed to `approvedScopes`, a non-empty subset of the grant's scopes; it can never widen), `decline` and `revoke` revoke it. The signature is ECDSA P-256/SHA-256 with the **phone key** (so a stolen device token alone cannot approve) over the UTF-8 string (lines joined with `\n`, no trailing newline):

```
daycue.grant.v1
<grant id>
<decision>
<approvedScopes sorted and joined with a single space, empty string if none>
<decidedAt as integer>
```

`decidedAt` must be within 10 minutes of the relay clock. Answers `200 {ok, approval|revoked, grants, serverTime}`, `400` bad scopes or stale time, `403 bad_signature` (audited), `404` unknown or already revoked grant. Audit actions: `grant.phone_approved`, `grant.phone_declined`, `grant.phone_revoked`.

Phone to do (Android, not part of this change): notify on a new `pending` grant, show label/scopes, require an explicit on-device approval (biometric for `medication`), sign and post the decision; offer revoke for active grants; treat `401` after the relay revoked it as "this phone was unpaired from the relay" and say so. Tag medication entries in `status.recentChanges`/`nextCues` with `"medication": true`. Optional hardening: sign acks with `daycue.ack.v2` (below).

**Ack v2 (optional).** Send `signatureVersion: 2` with the ack and sign `daycue.ack.v2\n<id>\n<payloadHash>\n<outcome>\n<newVersion or empty>\n<ackedAt>\n<hex SHA-256 of canonicalJson(result ?? null)>` (`canonicalJson`: sorted keys, `undefined` dropped, as in `src/util.ts`). This binds the summary/errors/preview to the phone's key so a compromised relay cannot alter what the phone reported; v1 acks remain accepted until the phone adopts v2. Snapshot versions may not jump by more than 10 000 above the stored one (`400 version_jump`), which stops a stolen phone credential from blocking snapshots with a huge version.

## 5. Command state machine

```
            enqueue                  phone pull                 signed ack
 (none) ----------> queued ----------------------> delivered ----------------> applied | rejected | failed
                      |                                |  \                          ^
                      | now >= expiresAt               |   \ ack awaiting_confirmation
                      v                                v    v                       |
                   expired <---------------------- expired  awaiting_confirmation --+ (second signed ack)
```

- States: `queued`, `delivered`, `awaiting_confirmation`, `applied`, `rejected`, `failed`, `expired`. `awaiting_confirmation` is not terminal: it can still end `applied`/`rejected` by a second signed ack, or `expired` if `expiresAt` passes first.
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
| Devices | id, label, public key, last seen, FCM token, hash of device credential | until revoked or re-paired (a revoked record keeps id, label and public key, with the FCM token and credential hash removed; this applies to owner revocation and to companion self-revoke) |
| Config snapshot | redacted config + status (+ medication only with scope) | latest only, overwritten |
| Commands | type, ops payload, result, grant label/scopes, state | until `expiresAt` + 14 days, then purged |
| Idempotency index | hash of (client, key) -> command id | same as command |
| Activity | latest signal per companion + transition log (state, time, companion id) | 24 h, max 100 log entries; a companion's slot is deleted when it is revoked |
| Grants | client id/label, scopes, last used | until revoked (revoked grants remain as records) |
| Access/refresh tokens | SHA-256 hashes | 1 h / 30 days |
| OAuth codes, pending authorizations, CIMD cache | hashed codes (tombstone after use, for replay detection) / request data incl. CSRF token hash / client metadata | 60 s (+10 min tombstone) / 10 min, max 100 / 1 h, max 100 |
| DCR clients | name, redirect URIs, approved flag | 7 days unused / 90 days once approved; max 50 |
| Audit log | actor, action, ids, scope names, redirect host of approved consents, **no ops, no snapshot content, no IPs** | 90 days, at most 2000 entries |
| Lockout / rate-limit counters | per source address, **in process memory only** (never in the store or the audit log), bounded to 10 000 keys | at most 15 min to 1 h; reset by a restart |

Not stored: raw tokens/secrets, calendar contents, coordinates, history. The relay holds ops text the model wrote (habit ids, intervals, rule text) until purge, and anything the phone puts in `result` or the snapshot. Purge runs hourly in the Node server and lazily on read. The JSON file on the disk is `0600`.

## 10. Threat notes

- **Compromised relay or leaked owner secret**: can read the redacted snapshot, queue ops, withhold/delay/expire commands, and re-pair a new phone (owner secret is the root of trust). It cannot forge `applied` for the paired phone key, and sensitive/destructive changes still need on-phone confirmation. Mitigations: phone validates everything, confirmation, scopes, audit, revocation, small blast radius (redacted snapshot; no medication by default). Weakness: the phone does not authenticate command *origin* beyond the relay's TLS and device credential. If that matters, add a client-side signature on commands from a key held by the owner; not designed here.
- **Prompt injection through data**: calendar titles, place names, labels are wrapped as untrusted data and tool text says never to follow them; the phone never interprets them. Wrapping reduces but cannot eliminate model-side injection risk; sensitive changes still need on-phone confirmation, which is the real backstop.
- **Confused deputy / token theft**: audience-bound opaque tokens, scope per grant, 1 h access tokens, rotating refresh with reuse detection, immediate grant revocation, no token passthrough.
- **Open registration**: DCR is open by protocol design; it only creates unprivileged client ids. Every grant needs the owner secret on the consent page. Capped and expiring.
- **Brute force**: owner secret and pairing codes: per-source counted lockouts with exponential backoff, attempts reserved atomically before the comparison, in-memory (a restart resets them), plus an entropy floor on the owner secret (the real protection; the lockout bounds noise). Revocation and the kill path are not subject to lockouts. See 3.1 "Owner-secret protection". Residual: a botnet with many addresses can make a brand-new, never-authenticated source wait out the global ceiling (<= 1 minute per window); the kill path and any previously authenticated address still work.
- **CIMD SSRF**: see 3.1. The literal-name filter alone is bypassable (trailing dot, `nip.io`-style names); the DNS-vetted, connect-to-vetted-address fetch on Node closes that. `https` plus certificate validation still means internal plain-HTTP services are unreachable.
- **Phishing the consent page**: the redirect host is shown prominently, unrecognized hosts need an explicit extra confirmation and never receive a redirect on deny, CSRF and framing are blocked, and (default) a write or medication grant additionally needs approval on the phone. The owner secret is still typed into a web page: only type it on the relay's own origin and use `DAYCUE_ALLOWED_REDIRECT_HOSTS` to keep the trusted list short.
- **Rotating the owner secret**: change `DAYCUE_OWNER_SECRET`, redeploy, `POST /v1/owner/revoke-all` with the new secret, re-pair the phone. (An overlap window for the old secret is not implemented.)
- **Replay**: acks are signed over command id and hash and idempotent; companion signals need strictly increasing `observedAt`.
- **DNS rebinding (local dev)**: when the base URL is localhost the Host header must be loopback.
- **Free-tier spin-down / restarts** are availability issues, not integrity issues (state is durable under option A/B).

## 11. Verification status

| Item | Level |
|---|---|
| Pairing, OAuth (DCR + CIMD with fake fetch), audience/expiry/revocation, scopes, idempotency, offline pending, expiry, late ack, undo, sensitive confirmation flow, medication exclusion, untrusted marking, companion expiry/replay/forgery, several companions (per-companion replay, combined state), companion self-revoke, op-name/example checks against `ConfigOp.kt`, FileStore restart survival and write coalescing, stdio server as a real child process, FCM request shape; security review fixes: body/rate/cap limits, per-source owner throttling and kill path, consent page (trusted hosts, CSRF, headers), CIMD name/IP/DNS/size checks, code-reuse revocation, per-client isolation and medication redaction, phone approval of grants, phone self-revoke, ack v2 | **Unit/integration** (`mcp`: `npm test`, 103 tests, fake phone/companion; ran on Node 20.20 locally, the deployment target is Node 24 which is unverified here) |
| Built server (`npm run build`, `node dist/node/server.js`): health, 401 challenge, PRM | Smoke-tested locally with curl |
| Real Claude.ai / ChatGPT / Claude Code connection | **Unverified** (needs a public HTTPS URL and the owner's accounts) |
| Render deployment, `render.yaml` validity | **Unverified** (keys checked against Render's blueprint spec page; plan name `starter` may need adjusting) |
| `PgStore` | **Partially verified**: the Store contract and the full pairing/command flow pass against `pg-mem` (in-process Postgres emulation, `test/pgstore.test.ts`, including parallel updates). Not run against a real Postgres (isolation, pooling, TLS) |
| DNS-vetted CIMD fetch | **Unit** with a stubbed resolver (private, rebinding and `nip.io`-style answers are refused); no real outbound connection was made |
| Node 24 | **Unverified locally** (Node 20.20.2 on this machine); CI and `render.yaml` use Node 24, `engines` is `>=22`. Node 24 and 22 are the supported LTS lines per <https://nodejs.org/en/about/previous-releases> (checked 2026-10-04); Node 20 is end-of-life |
| Real FCM, Android Keystore signatures, .NET signatures against this verifier | **Unverified**; the DER/raw ECDSA path is unit-tested with WebCrypto-generated keys only |
