# Security and privacy review 1

Reviewer: security reviewer (independent). Date: 2026-10-04.

**Baseline.** The review started at `a8d8c71`. During the review another agent pushed `e1c2331` ("Places, geofencing, activity recognition and calendar provider sync"), which is now `HEAD` and `origin/main`. Source was read **from the working tree on disk**, which includes uncommitted edits by other agents to `android/app/src/debug/.../GalleryApp.kt`, `AppContainer.kt`, `LocationIntegration.kt`, `docs/architecture/APP_API.md` and `docs/architecture/FEASIBILITY.md`. The history scan covers all 15 commits, `74f680a`..`e1c2331`. No production code was changed. No Gradle build was run (as instructed). The only runtime checks were an in-process probe of the relay (Hono `app.request`, `MemoryStore`, fake fetch, no network) and `npm audit`.

Spec baseline: MCP authorization, revision 2026-07-28 (`/specification/2026-07-28/basic/authorization` and its `security-considerations` page, fetched 2026-10-04), together with OAuth 2.1 draft 13 and RFC 8707/9207/9728.

## Summary

| Severity | Count |
|---|---|
| Critical | 0 |
| High | 1 |
| Medium | 8 |
| Low | 14 |
| Info | 10 |

Overall: the design holds up for a single-owner system. Tokens are opaque and stored hashed. Grants are audience-bound and revocable. Phone acks are signed. Medication is opt-in. The release manifest hides the debug pieces, and CI follows least privilege. Several of the strongest claims were checked against the code and hold (see "Verified strengths").

The one High finding is a privacy hole on the phone side: **exact place coordinates leave the phone** inside preview diffs. Most Medium findings concern the **public relay's resistance to unauthenticated abuse**: owner lockout, lockout bypass, unbounded state growth and an EOL runtime. Fix those before the relay goes on a public URL.

---

## Threat model (single owner, personal system)

Assets, by sensitivity: medication schedule and history; precise place coordinates (home, work); calendar contents; daily routine and activity patterns (when the PC is active, alarm times); the ability to change reminders (missed alarm, disabled habits); the owner secret, client and device tokens, and the release signing key.

| Actor | Access | What they can try |
|---|---|---|
| Anonymous internet | Public relay URL (discoverable through the public repo docs and `render.yaml` name) | DoS (lock the owner out, fill storage), SSRF through CIMD, phishing the consent page, brute-forcing the owner secret or pairing codes |
| Phisher / malicious MCP client author | DCR/CIMD client registration, social engineering of the owner | Getting the owner to approve a grant named "Claude" that redirects elsewhere; then reading the snapshot and making ordinary changes |
| Prompt-injection author | Text that reaches the owner's LLM (web pages, mail, documents; calendar titles only if they ever enter the snapshot) | Making the model call `apply_change` (ordinary changes apply without phone confirmation), or `propose_change` to exfiltrate data through previews |
| Holder of a leaked token | Client token / refresh token / device credential | Read the snapshot within its scopes; queue commands; with a phone credential, forge the snapshot or block it with a huge `version` |
| Holder of the leaked owner secret, or a compromised relay host / Render account | Root of trust | Mint any grant (incl. medication), re-pair a new phone, revoke everything, alter unsigned result text, withhold or delay commands, substitute companion keys. **Cannot** forge `applied` with the phone key, and cannot apply sensitive changes without on-phone confirmation |
| Other Android apps on the phone | Intents to exported components | Spoofing notification actions (blocked: receivers not exported, PendingIntents immutable and explicit), opening `MainActivity` with crafted `daycue://` data (no effect today; see L-13) |
| Same-user malware on the PC | User profile | Read the DPAPI-protected companion token, use the CNG key, edit `pairing.json`. Out of scope as a boundary: same-user malware already owns the session |
| Readers of the public repo and its history | `git clone` | Personal data or secrets that were committed (none found beyond the known path; see I-1) |

---

## High

### H-1 Exact place coordinates leak to remote clients through preview and confirmation diffs

**Evidence.**
- `android/domain/.../edit/ConfigEditor.kt:103-118`: for arrays of id-keyed objects, `diff` recurses per id. An **added or removed element** falls to the `else` branch and becomes one `DiffLine(path = "places[<id>]", before/after = <whole object JSON>)`. `Place` serializes `center: {lat, lng}` (`domain/config/Place.kt:13-17`, `encodeDefaults = true`).
- `android/app/.../integrations/relay/SnapshotBuilder.kt:94-107`: `Redaction.summary` drops a line only when the **path** matches `(^|[.\[])(center|lat|lng)\b`. The path `places[home]` does not match, so the line survives with the coordinates in its value.
- `RelayClient.kt:303-320` (`preview`): every surviving line goes to the relay as `result.preview.diff[] = {path, before, after}`, and the relay returns it to the MCP client (`clientApi.ts:175`, wrapped as untrusted but intact).
- `RelayClient.kt:335-342`: the same path appears in `apply`'s `awaiting_confirmation` summary (`Redaction.summary`, 300 chars; `center` is the third field, so it falls inside the cut). It also appears in `undo` (`RelayClient.kt:377`) when the undo re-adds a deleted place.

**Exploit.** A grant with `config:write` (or a model steered by prompt injection) calls `propose_change` with `[{"type":"deletePlace","id":"home"}]`. The phone computes a preview (preview never needs confirmation) and acks. The result is `diff[0].before = {"id":"home","name":"Home","center":{"lat":..,"lng":..},...}`. The owner's home coordinates are now in the LLM transcript and stored on the relay for `expiresAt + 14 days`. This breaks the stated invariant ("Coordinates never leave the phone", `SnapshotBuilder.kt:51`, RELAY.md 4.1). Status: found by code reading, **not executed** (no Gradle run allowed).

**Fix (owner: integrations engineer + scheduling engineer).**
1. Redact by **value**, not only by path. Before any diff line leaves the phone, parse `before`/`after` and, when it is a JSON object or array, recursively drop `center`, `lat` and `lng` keys (reuse `SnapshotBuilder.redactPlaces` semantics, and treat any `places[...]` whole-object line the same way). Do the same for medication objects when the grant lacks `medication`; today they are filtered only by the `medications` path prefix.
2. Better: give `ConfigEditor.preview` a `RedactionPolicy` so the domain emits safe lines for remote callers (it already has `redactMedicationLabels`).
3. Add unit tests: preview, awaiting-confirmation summary and undo for `deletePlace`, `upsertPlace` (replace and new), `setPlaceLocation` and undo of `deletePlace` must contain no number from the place's center.

---

## Medium

### M-1 Anyone can lock the owner out of the owner API and the consent page (global lockout bucket)

**Evidence.** `mcp/src/relay.ts:115-139`: `assertOwner` uses a single global `lock/owner` bucket. Five failures from **any** caller lock it for 30 s, doubling to 15 min, and every new failure re-arms the lock (expiry is refreshed to 1 h). Pairing uses one global `pair` bucket that phone and companion codes share (`relay.ts:149-164`). Probe output:
```
parallel wrong owner secrets: 401s 40 ...
real owner right after attack: 429
```
**Scenario.** An attacker sends 5 junk `Authorization: Bearer x` requests every 15 minutes. The owner can never approve a new MCP connector, mint a client token, revoke a grant, read the audit log or pair a phone. Reminders are unaffected (the phone owns the schedule), but the owner loses remote control **and the ability to revoke**.

**Fix.** Key lockouts per source (`x-forwarded-for` first hop on Render, which terminates TLS; plus a coarse global ceiling). Never let lockout block **revocation**: keep a separate path such as `POST /v1/owner/revoke-all`, accepted with the secret even when locked but rate-limited. Use separate buckets for the consent page and the API. Add `Retry-After`.

### M-2 Lockout and pairing rate limits can be bypassed with parallel requests

**Evidence.** `guard()` reads the bucket, then `secretEquals` awaits WebCrypto digests, then `failed()` writes, with no atomic check-and-increment. Probe: 40 parallel wrong secrets were **all compared** (`secret comparisons audited 40`) against a design limit of 5 per window. `Relay` only enforces `ownerSecret.length >= 24` (`relay.ts:79`), not entropy.

**Scenario.** Brute force runs at whatever concurrency the attacker can sustain per lockout window. With the documented 32-byte random secret this is infeasible. With a human 24-character passphrase it is not. Pairing codes (~49 bits, 10 min) are not at practical risk, but the limit is not what the docs claim.

**Fix.** Do the check and increment atomically in one `store.update` *before* comparing (reserve an attempt slot, release it on success). Reject owner secrets below ~128 bits of estimated entropy at startup, or require base64url of >= 32 random bytes. Add a per-source token bucket in front of `/authorize/decision`, `/v1/owner/*` and `/v1/pair/*`.

### M-3 Unbounded, unauthenticated state growth and no request size limits on the public relay

**Evidence.**
- `/register` (open DCR) writes an audit entry per call with 90-day retention and no cap (`auth.ts:206`, `relay.ts:105-108`). Probe: `after 300 DCR: clients 50 audit entries 300`.
- `GET /authorize` stores a `txn` per request for 10 min, uncapped. Probe: `after 300 /authorize: pending txns 300`. The CIMD cache stores one entry per distinct URL for 1 h (`auth.ts:262`).
- `FileStore.changed()` (`store.ts:114-119`) rewrites **the whole JSON document synchronously** on every mutation. Each spam request costs O(store size) blocking I/O, and the 1 GB Render disk can fill.
- There is no body-size middleware in `app.ts`. `c.req.json()`, `parseBody()` and `c.req.raw.clone().json()` on `/mcp` buffer arbitrary bodies. The 256 KiB/64 KiB checks in `relay.ts:288,364` run **after** parsing. `listAudit` loads all entries (`relay.ts:111`).
- The CIMD fetch reads the whole body before its 16 KiB check (`auth.ts:237-238`).
- DCR eviction lets spam evict the owner's real DCR registration (`auth.ts:197-201`).

**Scenario.** A script costing pennies makes the relay unresponsive (event-loop blocking) and grows the disk without limit. Remote control and revocation stop working.

**Fix.** Add `hono/body-limit` (e.g. 64 KiB default, 300 KiB on `PUT /v1/phone/snapshot`). Add per-source rate limits on `/register`, `/authorize` and `/token`. Do not audit unauthenticated DCR, or cap and aggregate it (counter per hour). Cap pending txns and CIMD cache entries (LRU, e.g. 100). Never evict DCR clients that have an active grant. Debounce `FileStore` writes (coalesce into one async write per tick) or move to an append-only log. Stream the CIMD body with a byte counter that aborts at 16 KiB.

### M-4 Node.js 20 is end-of-life for the internet-facing relay

**Evidence.** `mcp/render.yaml` `NODE_VERSION: 20`; `mcp/package.json` `"engines": {"node": ">=20"}`; `.github/workflows/ci.yml` `node-version: '20'`. Node 20 LTS reached end-of-life on 2026-04-30 (Node release schedule; re-check at nodejs.org), so it gets no security fixes as of this review. `npm audit` reports 0 vulnerabilities, but that does not cover the runtime.

**Fix.** Move to an active LTS (Node 24, or 22 if it is still in maintenance; verify the dates) in `render.yaml`, `engines` and CI. Add Node to Dependabot or a scheduled check.

### M-5 Default remote policy applies alarm-defeating changes with no phone confirmation

**Evidence.** `ConfigEditor.kt:90-101`: only `Delete*` ops and medication ops are `destructive`/`sensitive`. Everything else is `ordinary`. `RelayClient.kt:334`: `needsConfirm` is true only for non-ordinary changes or `AlwaysConfirm`. The default policy is `ConfigPolicy.Auto` (`Ports.kt:45`, `AndroidStorage.kt:114`; APP_API.md section 10). So a remote client can, **silently and immediately**, `setAlarmEnabled(false)`, `skipNextAlarm`, `upsertAlarm` with a new time, `setPause {"type":"all"}` indefinitely, `setQuietHours` for all day, `setHabitEnabled(false)`, make a cue profile silent, `setPlaceLocation` (moves geofences), and change `setContextRules`/`setSessionRules`. Medication is correctly protected (`MedicationModule.kt:14-15`).

**Scenario.** A prompt-injected or misbehaving model "helpfully" turns off tomorrow's alarm or pauses all reminders. The only trace is a low-priority "applied" notification.

**Fix.** Classify as `sensitive`: any alarm op (enable/disable, time/day change, skip), global or indefinite pause, quiet-hours changes that cover > N hours, cue-profile volume/vibration to silent, `setPlaceLocation`, and `setHabitEnabled(false)`. Alternatively make `AlwaysConfirm` the default until the owner opts into `Auto`. Show the "applied" notice at default priority for anything that reduces alerting.

### M-6 Consent page can be phished; redirect hosts are not restricted

**Evidence.** DCR is open and `client_name` is attacker-chosen (`auth.ts:202`). The consent page renders the name in bold and the redirect host in small code text (`auth.ts:328`). Probe: `consent shows: Claude | evil.example`. There is no allowlist of trusted redirect hosts and no extra warning for `localhost`-only redirects. The spec says the AS "SHOULD display additional warnings for localhost-only redirect URIs", "MUST take precautions to prevent redirecting user agents to untrusted URI's", and "SHOULD only automatically redirect ... if it trusts the redirection URI". Deny needs no secret and redirects to the registered URI (probe: `deny without secret: 302 https://evil.example/cb?...error=access_denied`), which gives a minor open redirect via form POST. Private-use schemes are accepted (`auth.ts:73`), while the MCP spec says "All redirect URIs MUST be either localhost or use HTTPS".

**Scenario.** An attacker sends the owner a link "re-authorize your DayCue connector". The page says **Claude**. The owner types the owner secret, and the attacker receives a code for a grant with the scopes the owner ticked.

**Fix.** Add `DAYCUE_ALLOWED_REDIRECT_HOSTS` (default `claude.ai, claude.com, chatgpt.com, localhost, 127.0.0.1`). Refuse other hosts, or render a red warning that needs an extra checkbox. Show the CIMD `client_id` URL or the DCR id and creation time. Label DCR names "unverified". Refuse private-use schemes unless explicitly enabled. Only redirect on deny for trusted hosts.

### M-7 A single static owner secret is the root of trust, and the phone never learns about new grants

**Evidence.** The owner secret approves every grant (`app.ts:147-150`), mints client tokens of any scope for up to 365 days (`app.ts:198-206`), and re-pairs the phone, which silently revokes the real one (`relay.ts:202-219`). The phone receives only `wants.medication` (`relay.ts:412`), not the list of grants. Nothing tells the owner on the phone that a new client or new phone credential exists. The owner secret is typed into a web page (phishable, M-6) and is not rotatable without leaving every existing grant in place.

**Scenario.** After a one-time leak (shoulder-surfing, phishing, Render dashboard compromise), the attacker mints a `config:write medication` client token. The next phone publish includes medication if the owner has allowed it. The attacker then makes ordinary changes (M-5), and nothing on the phone shows a new client exists.

**Fix.** Include the active grants (id, label, scopes, createdAt) in the `GET /v1/phone/commands` response, signed-in-band or at least visible. The phone notifies on new grants and requires on-phone approval for any grant that holds `medication` or `config:write`, using the same `awaiting_confirmation` pattern. Notify loudly on 401 after re-pairing ("this phone was unpaired from the relay"). Document a rotation procedure (change env var, `POST /v1/owner/revoke-all`, re-pair) and add `DAYCUE_OWNER_SECRET_PREVIOUS` for overlap.

### M-8 Command results cross grant boundaries, including medication summaries

**Evidence.** `clientApi.ts:204-211` (`getCommand`) and `213-225` (`listChanges`) return **all** commands and their phone results to any grant with `config:read`, regardless of `c.grant.grantId`. The phone builds the `summary` with `redactMedicationLabels = !medScope` **of the issuing grant** (`RelayClient.kt:335-336`). So a change made by a `medication`-scoped client carries medication labels in `result.summary`, and `list_recent_changes` serves that summary to a grant **without** `medication`.

**Scenario.** The owner grants medication to Claude Code only. ChatGPT, holding `config:read` only, calls `list_recent_changes` and reads "medications[x].label: ... ".

**Fix.** On the relay, when the viewer's grant lacks `medication` and the command's grant had it, replace `result` with a generic `"(medication change, details withheld)"`. Better, have the phone always return a medication-free `summary` plus a separate `medicationSummary` that the relay strips per viewer. Consider limiting `get_command_status` to commands from the same `clientId`, and showing others only as id/state/type.

---

## Low

### L-1 CIMD SSRF filter is bypassable, and the fetch body is unbounded before the size check
`auth.ts:94-99` blocks names literally. Probe: `https://localhost./`, `https://foo.localhost./`, `https://metadata.google.internal./`, `https://127.0.0.1.nip.io/` and `https://169.254.169.254.nip.io/` were all **fetched**; IP literals in all numeric forms were correctly refused. Impact is limited because the fetch is HTTPS-only with `redirect: 'error'`, so internal plain-HTTP services and services without valid certificates fail the TLS handshake. It is blind apart from status codes echoed in the error page. The trigger is unauthenticated (`GET /authorize`). **Fix:** strip a trailing dot before checks. On Node, resolve with `dns.lookup(all)` and reject private, loopback, link-local, CGNAT and unique-local ranges, then connect to the vetted IP (undici `connect` / lookup hook) to defeat DNS rebinding. Cap body bytes while streaming (M-3). Optionally add a domain trust policy (spec section "Trust Policies").

### L-2 Authorization-code replay does not revoke the tokens it issued
`auth.ts:391-395` makes codes single-use, but a second redemption only fails. Probe: `code reuse second exchange: 400 | first token still valid after reuse: 200`. OAuth 2.1 (section 4.1.3) says the AS SHOULD revoke tokens issued from a reused code. **Fix:** keep a tombstone `{grantId}` for used codes until expiry and revoke the grant on reuse.

### L-3 The ack signature covers neither `result` nor the payload the phone actually applied
`relay.ts:57-59` signs id, payloadHash, outcome, newVersion and ackedAt. `sensitivity`, `summary`, `errors` and `preview` are unsigned, so a compromised relay can tell the MCP client "ordinary, applied: X" for a different change. The phone signs `payloadHash` "exactly as received" without recomputing it (`RelayClient.kt:255`), so the signature does not bind what was applied. **Fix:** `daycue.ack.v2` that adds `sha256(canonical(result))` and a phone-recomputed payload hash (`canonicalJson` is already specified in `util.ts:57-66`). Low because the relay is the verifier.

### L-4 The phone verifies companion signals against relay-supplied keys
`CompanionFeed.kt:24-35` uses `companions[].publicKey` from `GET /v1/phone/activity`. A compromised relay can add its own key and forge activity, which feeds session detection. "The phone verifies companion signatures itself" (RELAY.md 4.4) therefore adds no protection against the relay. **Fix:** pin each companion key on the phone at first sight and show a short fingerprint on both devices for the owner to compare; ignore keys added later without confirmation.

### L-5 Unpairing does not revoke credentials on the relay
The companion has `DELETE /v1/companion/self` but `TrayApp.Unpair()` (`TrayApp.cs:322-336`) never calls it. The phone has no self-revoke endpoint, and `RelayService.unpair()` (`RelayService.kt:173-180`) only clears the FCM token. The bearer token stays valid until the owner revokes it, and the phone token can still `PUT /v1/phone/snapshot` (inject untrusted text, or block snapshots with `version = 2^53`). **Fix:** call self-revoke (best effort) before deleting local secrets. Add `DELETE /v1/phone/self`. Cap snapshot `version` jumps (e.g. reject > current + 10 000).

### L-6 The stdio client and HTTP client accept `http://` to non-loopback relays
`stdio.ts:14-20` and `clientApi.ts:244-253` send `Authorization: Bearer <client token>` to any `DAYCUE_RELAY_URL`. **Fix:** refuse `http:` unless the host is loopback (the companion does this correctly in `Wire.TryNormalizeRelayUrl`).

### L-7 Postgres TLS does not verify the server
`node/server.ts:23`: `ssl: { rejectUnauthorized: false }`. **Fix:** verify by default; allow `DATABASE_SSL=insecure` explicitly. (`PgStore` is unverified anyway.)

### L-8 Device-to-device transfer is not excluded
`AndroidManifest.xml:43` sets `allowBackup="false"` but has no `android:dataExtractionRules`. For apps targeting API 31+, `allowBackup=false` disables cloud backup but, per Android documentation, **not device-to-device transfer**. The Room DB (medication history, config with coordinates, `command_log`) would move to a new phone. Keystore-encrypted relay credentials would fail to decrypt there (good). Not verified on a device. **Fix:** add `data_extraction_rules.xml` with `<cloud-backup><exclude domain="root"/>...` and `<device-transfer>` excluding `database`, `sharedpref/daycue_relay_secure.xml` and the device-protected `boot_snapshot`. Or include the DB deliberately if the owner wants migration, and document the decision.

### L-9 Release builds log item keys and place-related notes to logcat
`Log.i` calls stay in release because there is no `-assumenosideeffects` in `proguard-rules.pro`. Examples: `CueActionReceiver.kt:27` (`on $itemKey`, which includes `med:<id>`), `AndroidEffectSink.kt:40`, `LocationIntegration.kt:193,230` (geofence notes), `CalendarIntegration.kt:128`. Logcat is readable only by adb or system on modern Android, but bug reports carry it. Medication ids are not yet generated anywhere (`grep` found no id generator). If they are derived from names, names reach logs, relay command payloads and `nextWakeReason`. **Fix:** strip `Log.i/d/v` in release via R8 rules, never log place notes, and generate opaque ids for medications and places.

### L-10 Phone-side `command_log` and `audit_log` are never pruned
`Daos.kt` has delete queries for `config_history`, `history_event` and the calendar cache only. `command_log` keeps the full remote command JSON (ops, including medication changes) forever. **Fix:** prune terminal entries after ~30 days, which matches the relay's 14-day retention.

### L-11 Release job hardening
`release.yml`: the signing secrets are in the environment of the same Gradle invocation that runs unit tests (`:app:testDebugUnitTest`), so any test-classpath dependency can read them. No GitHub Environment with required reviewers protects the secrets. `main` is not protected (GitHub API: "Branch not protected"), so a stolen session or token can push a tag and get a signed release. **Fix:** run tests in a job without secrets and give the signing job only `assembleRelease`. Put the secrets in an Environment `release` with required reviewer = owner and tag protection for `v*`. Enable branch protection (no force-push) on `main`.

### L-12 `.gitignore` gaps for secret-bearing files
Verified with `git check-ignore`: these are **not** ignored: `.data/relay.json` at the repo root (FileStore default when the relay is run from the root), `service-account.json` / `firebase-adminsdk*.json` (the FCM key a user is likely to save before pasting it into `FCM_SERVICE_ACCOUNT_JSON`), `*.pem`, `*.p12`, and `companion/pairing.json` (if `DAYCUE_COMPANION_DIR` points into the repo). **Fix:** add `.data/`, `*.pem`, `*.p12`, `*.p8`, `*service-account*.json`, `*firebase-adminsdk*.json`, `pairing.json`, `error.log`, `.mcp.json` and `*.tmp` to the root `.gitignore`. Already correct: `*.jks`, `*.keystore`, `keystore.properties`, `local.properties`, `.env*` (with the example allowed), `google-services.json`, `*.aar`, `*.daycue-backup.json`, `exports/`.

### L-13 The future remote-confirmation screen would be reachable from any app
`MainActivity` is exported (launcher, `AndroidManifest.xml:50-57`). APP_API.md section 10 plans `daycue://open/remote?item=<commandId>` routed in `MainActivity` with Approve/Decline. Today `MainActivity` ignores intents, so nothing is exploitable now. Once routing ships, any app can open the approve screen for a pending command at a moment of its choosing (tapjacking, confusing context). **Fix before the UI ships:** host the confirmation in a separate `exported="false"` Activity (PendingIntents can launch non-exported activities). Never act on intent extras. Use `setHideOverlayWindows(true)` / `filterTouchesWhenObscured`. Require a deliberate gesture (hold, or biometric for medication).

### L-14 Notification PendingIntents can share extras across notifications
`CueActionReceiver.kt:43-62` uses requestCode `0`, `FLAG_UPDATE_CURRENT`, and a data URI built from `(kind, minutes, itemKey)` **without `cueId`**. Two live notifications for the same `itemKey` and action share one PendingIntent, and the older one's `cueId` extra is overwritten. If `itemKey` is not unique per dose slot, "Taken" on an older notification is attributed to the newer cue. Impact unverified. **Fix:** put `cueId` in the data URI, or use a distinct requestCode per cue.

---

## Info

- **I-1 Commit `8141492` path.** `docs/setup/MCP.md:56,59` contained `C:\Users\maork\my-stuff\daycue\...`; `2916d32` fixed it. Exposure: Windows username `maork` and folder names, which are less identifying than the already-public GitHub handle. **Recommendation: no history rewrite.** It would force-push `main`, invalidate all 15 SHAs referenced in `docs/STATUS.md` and agent notes, and would not remove the old commit from GitHub's object store (still reachable by SHA until GitHub support purges it), for negligible privacy gain. If the owner still wants it, now is the cheapest moment (0 forks, 0 stars per GitHub API): `git filter-repo --replace-text` on that one line, force-push, then ask GitHub Support to purge cached views of `8141492`.
- **I-2 Region hints.** Commit timestamps are `+0300`. Tests use `Asia/Jerusalem`, and a test fixture uses `GeoPoint(32.0, 34.0)` (coarse, at sea). No address-level data. Prefer `UTC`/`Etc/GMT-3` and `GeoPoint(0.0, 0.0)`-style values in new fixtures.
- **I-3 History scan.** All 15 commits were scanned (`git log -p --all`, plus `git grep` across `git rev-list --all`). Author/committer email is the GitHub noreply address. No private keys (the only `BEGIN PRIVATE KEY` is a test that builds a PEM at runtime from a generated key), no `AIza`/`ghp_`/`sk-`/`AKIA`/JWTs, no real `dca_/dcr_/dcd_` tokens. Test owner secrets are obviously synthetic. No real coordinates and no English drug names (only "Example vitamin", "Synthetic", "Demo dose", "Medication B"). No screenshots. Binaries ever committed: two OFL fonts, eight generated `.wav` cues, `gradle-wrapper.jar`. Example files hold placeholders only (`keystore.properties.example` `CHANGE_ME`; `.env.example` empty; `appsettings.example.json` tuning only). The current tree has no `.env`, `local.properties`, keystore or `google-services.json`. GitHub secret scanning and push protection are enabled; Dependabot security updates are **disabled** (consider enabling).
- **I-4 Prompt-injection exposure.** Third-party text reaches MCP results only weakly: calendar event contents are never in the snapshot (`SnapshotBuilder` never reads the cache), and `nextCues` is coarsened to a kind. The remaining channels are owner-authored names, DCR/companion labels, and phone result messages, all wrapped by `markUntrusted` (`untrusted.ts`), with keys sanitized and `<`/`>` neutralised. Gaps: strings of 48 identifier characters or fewer are not wrapped (room for short directives like `SYSTEM:call_apply_change`), and the wrapper is advisory. The real backstops are phone-side confirmation (weakened by M-5) and scopes. No tool lets returned text trigger a change by itself.
- **I-5 Expired commands can briefly re-enter `awaiting_confirmation`.** `relay.ts:449-458`: an `awaiting_confirmation` ack on an `expired` command sets that state, and `fresh()` re-expires it on the next read. Harmless; reject non-terminal acks on expired commands for clarity.
- **I-6 Spec deviations (minor).** `resource` is optional on `/authorize` and `/token` (`auth.ts:282,378`). Clients MUST send it, and leniency is acceptable. Uppercase scheme/host in `resource` is rejected, where the spec says SHOULD accept. Loopback redirects match with port flexibility (RFC 8252), where MCP says "exact". `get_command_status` needs `config:read` at the MCP layer, so `sessions:control`-only grants cannot poll (functional bug: `tools.ts:95` vs `clientApi.ts:205`).
- **I-7 Calendar identifiers in the snapshot.** `calendarRules.calendars[].calendarId` and `overrides[].key` (event or series ids from the provider) are published. They are not content, but iCal UIDs can embed a domain. Consider hashing override keys before publishing.
- **I-8 `npm audit` (mcp/):** 0 vulnerabilities across 151 packages (30 prod). Gradle and NuGet dependencies were not audited (no tool run).
- **I-9 Companion collection scope confirmed by code:** `GetLastInputInfo` only (`Win32.cs`); `SessionSwitch` lock/unlock, `PowerModeChanged`, `SessionEnding`, `NetworkAvailabilityChanged` (`TrayApp.cs:59-62`). No hooks, window titles, process names or clipboard (grep for `SetWindowsHook`, `GetForegroundWindow`, `GetWindowText`, `Process.`, `Clipboard`: no hits). The CNG key is non-exportable (`ExportPolicy = None`, `Keys.cs:27`). The token is DPAPI CurrentUser with constant entropy (fine; the entropy is not secret). Config lives under `%APPDATA%\DayCue` with default inherited ACLs (user + SYSTEM + Administrators; no explicit ACL set). The Run key is HKCU only, with the path quoted. TLS uses default validation (no certificate callback), `AllowAutoRedirect=false`, and `http` is allowed only for loopback. `error.log` records exception type and message only. The single-instance mutex is `Local\` (same-user DoS only). Changing the relay URL to an attacker host requires editing `pairing.json`, which only same-user code can do and which could read the DPAPI token anyway.
- **I-10 Kill switch and gating confirmed** (`RelayClient.kt:90-94,276-288`, `RelayService.kt:183-186`): disabled means no pull, apply, publish or poll. Expiry is checked on the phone's clock. Scope is checked per command type. Medication needs both the grant scope and the phone's `allowMedication`. Sensitive changes go to `awaiting_confirmation`. Duplicate delivery is never re-applied (`command_log`, crash means `failed`). Approval happens in-app only; the notification offers Decline only.

## Verified strengths (no action)

- Relay: opaque 256-bit tokens stored as SHA-256 (`auth.ts:121-136`). Audience re-checked on every request (`auth.ts:148-158`). Grant-level revocation. Refresh rotation with reuse detection that revokes the grant (`auth.ts:407-433`). PKCE S256 only with strict challenge/verifier syntax. `iss` on success and error, advertised. Codes single-use with 60 s lifetime. Consent page served with CSP `frame-ancestors 'none'`, `X-Frame-Options: DENY`, `no-store`, `no-referrer`, and HTML-escaped. Scope pre-check on `tools/call` plus per-scope tool registration plus per-op `need()` checks (defense in depth). No owner API under CORS. Medication snapshot dropped unless a medication grant exists. Audit records no ops or content. File store is `0600`.
- `applied` cannot be forged: ack verified against the phone's P-256 key (`relay.ts:442-446`). A stolen client token cannot reach `/v1/phone/*`. A stolen phone token cannot sign.
- Android: every receiver and service except system-broadcast receivers is `exported="false"`. All PendingIntents are `FLAG_IMMUTABLE` and explicit. `DevToolsReceiver` is `enabled="false"`, enabled at runtime only when `FLAG_DEBUGGABLE` is set, and requires `DUMP`. `GalleryActivity` and the cleartext `network_security_config` live only in `src/debug` (by source-set convention; the merged release manifest was not inspected). Release has no network config, so cleartext is blocked and user CAs are not trusted at target 37. Relay credential is AES-GCM with a non-exportable Keystore key. The device signing key is in the Keystore (StrongBox if available). Medication notifications are `VISIBILITY_PRIVATE` with a generic public version, and the locked-boot snapshot has no user text. Pairing refuses `http` outside debuggable builds.
- CI: top-level `permissions: contents: read`. All actions pinned by SHA. `persist-credentials: false`. Gradle wrapper validation. PR caches read-only. Fork PRs get no secrets (`pull_request`, not `pull_request_target`). The release job refuses debug-key signing, decodes the keystore with `umask 077`, removes it `if: always()`, and separates the write-scoped publish job from the secrets. Default workflow token permission is `read`.

---

## Prioritized fix list

**Before the relay is deployed on a public URL**
1. M-3 body limits, rate limits, caps, and FileStore write coalescing.
2. M-1 per-source lockout plus a revocation path that survives lockout.
3. M-2 atomic attempt reservation plus owner-secret entropy check.
4. M-4 move off Node 20.
5. M-6 redirect-host allowlist and stronger consent warnings.
6. L-1 CIMD trailing-dot and DNS checks, streaming size cap.
7. L-2 code-reuse revocation.
8. M-8 per-viewer medication redaction of results.
9. L-6 refuse `http` in the stdio client.
10. L-12 `.gitignore` additions (before anyone saves an FCM key).

**Before the APK is used daily with remote access enabled** (local-only use is not affected by the remote items)
1. H-1 coordinate redaction in diffs. Must land before any `config:write` grant exists.
2. M-5 alarm, pause and silence changes made sensitive (or `AlwaysConfirm` default).
3. M-7 phone visibility and approval of new grants; loud re-pair notice.
4. L-13 confirmation screen as a non-exported Activity (when the UI is built).
5. L-8 data-extraction rules.
6. L-9 strip info logs in release; opaque ids.
7. L-5 self-revoke on unpair (phone and companion).
8. L-14 per-cue PendingIntent identity.
9. L-10 `command_log` retention.

**Hardening, any time:** L-3 (ack v2), L-4 (companion key pinning), L-7, L-11, I-3 (enable Dependabot security updates), I-6.

**Contract notes for the delivery lead.**
- RELAY.md 3.1 says DNS-name SSRF is the only residual CIMD risk. L-1 shows the literal-name filter itself can be bypassed.
- RELAY.md 4.4 says the phone verifies companion signatures "instead of trusting the relay". Per L-4, it still trusts the relay for the keys.
- APP_API.md section 10 makes `Auto` the default, which conflicts with the intent "sensitive/destructive always ask" for alarm safety (M-5).

## What I could not assess

- Anything at runtime on Android (emulator or physical device): actual Keystore properties (StrongBox, attestation), notification lock-screen rendering, D2D transfer behaviour, FCM. **No Gradle build was run**, so the merged release manifest, R8 output and mapping, and the absence of debug classes from the release APK are **unverified** (judged from source sets and manifest attributes only).
- The UI is not built: deep-link routing, the confirmation screen, where export files are written (SAF or app storage), and import size limits.
- The relay running on Render: TLS config, proxy headers, real-world SSRF reachability, disk permissions, `render.yaml` validity. `PgStore` against a real Postgres. Real Claude.ai, ChatGPT and Claude Code clients. The MCP SDK internals (`createMcpHandler`, its `Origin` handling) were not reviewed.
- The companion running on Windows: DPAPI and CNG behaviour, ACLs of `%APPDATA%\DayCue`, roaming-profile effects.
- Dependency audits for Gradle (Android) and NuGet (companion), Spotify App Remote AAR provenance, and `gradle-wrapper.jar` checksum (CI validates it; not checked locally).
- Hebrew-language drug names in history (the medication-name scan used an English list only). The FCM template (`android/app/fcm-template/`) was not read in depth.
- Files other agents were editing during the review (`LocationIntegration.kt`, `AppContainer.kt`, `GalleryApp.kt`) were read as they were on disk at the time and may have changed since.
