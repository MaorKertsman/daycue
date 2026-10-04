# Setting up the DayCue relay and MCP server

Design and wire protocol: `docs/architecture/RELAY.md`. Code: `mcp/`. The remote path is optional: the phone works with no relay at all.

Requirements: Node 22 or 24 (the supported LTS lines; the deployment targets Node 24). This machine only has Node 20 on PATH, so tests here ran on Node 20.20 (the code is compatible; Node 24 itself is unverified locally). All commands below are run from `mcp/` unless stated. Nothing in this document has been run against a real phone, Render, Claude.ai or ChatGPT; see "Verification status" in RELAY.md.

## 1. Run locally

```powershell
cd mcp
npm ci
npm test            # about 100 tests, no network or accounts needed
npm run typecheck
```

Create a secret and start the relay (the secret protects the consent page and owner API; keep it out of the repo). The relay **refuses to start** unless the secret is at least 32 characters with about 128 bits of randomness, so generate it rather than inventing one:

```powershell
$env:DAYCUE_OWNER_SECRET = npm run --silent gen-secret    # = node -e "console.log(require('crypto').randomBytes(32).toString('base64url'))"
$env:DAYCUE_OWNER_SECRET   # note it down somewhere private
npm run dev                # http://localhost:8787, data in mcp/.data/relay.json (git-ignored)
```

`mcp/.env.example` lists every variable. Without `DAYCUE_DATA_FILE`/`DATABASE_URL` the dev server stores data in `.data/relay.json`.

Abuse protection you will notice: wrong owner secrets lock out only the calling address (`429` with `Retry-After`); `DELETE /v1/owner/grants/<id>`, `POST /v1/owner/revoke-all` and `DELETE /v1/owner/devices/<id>` still work while locked out. Behind Render the relay reads the client address from `X-Forwarded-For` (`DAYCUE_TRUSTED_PROXY_HOPS`, default 1 on Render, 0 elsewhere); on a different proxy set it correctly or all callers share one bucket. Details: RELAY.md section 3.1.

**Phone approval of new grants.** By default (`DAYCUE_REQUIRE_PHONE_APPROVAL=true`) a new grant with `config:write`, `sessions:control` or `medication` stays read-only until the phone approves it (API in RELAY.md 4.6; the Android UI for this is not built yet, so until it is, write grants stay inactive once a phone is paired; set `DAYCUE_REQUIRE_PHONE_APPROVAL=false` to opt out explicitly). Read-only grants work immediately.

Owner API examples (PowerShell):

```powershell
$h = @{ Authorization = "Bearer $env:DAYCUE_OWNER_SECRET" }
Invoke-RestMethod -Method Post -Uri http://localhost:8787/v1/owner/pair-codes -Headers $h     # code for the phone
Invoke-RestMethod -Uri http://localhost:8787/v1/owner/grants -Headers $h                      # who has access
Invoke-RestMethod -Method Delete -Uri http://localhost:8787/v1/owner/grants/<id> -Headers $h  # revoke one
Invoke-RestMethod -Method Post -Uri http://localhost:8787/v1/owner/revoke-all -Headers $h
Invoke-RestMethod -Uri "http://localhost:8787/v1/owner/audit?limit=50" -Headers $h
```

## 2. Connect Claude Code (stdio)

1. Mint a client token (shown once; choose the narrowest scopes you need; add `config:write`, `sessions:control` only if you want Claude Code to change things):

   ```powershell
   Invoke-RestMethod -Method Post -Uri http://localhost:8787/v1/owner/client-tokens -Headers $h `
     -ContentType application/json -Body '{"label":"claude-code","scopes":["config:read","activity:read"],"expiresInDays":90}'
   ```

2. Put the token in a **user environment variable**, not in a file in the repo:

   ```powershell
   [Environment]::SetEnvironmentVariable("DAYCUE_CLIENT_TOKEN", "<token>", "User")
   ```

3. Register the server. Either build once and use `node`:

   ```powershell
   npm run build
   claude mcp add daycue --scope user -e DAYCUE_RELAY_URL=http://localhost:8787 -e DAYCUE_CLIENT_TOKEN=$env:DAYCUE_CLIENT_TOKEN -- node <repo>\mcp\dist\stdio.js
   ```

   or, without building, `-- node <repo>\mcp\node_modules\tsx\dist\cli.mjs <repo>\mcp\src\stdio.ts`.
   (Passing the token with `-e` stores it in Claude Code's config on your machine. To avoid that, use a `.mcp.json` outside the repo or inside it with `"env": {"DAYCUE_CLIENT_TOKEN": "${DAYCUE_CLIENT_TOKEN}"}`; never commit a literal token.)

4. In Claude Code run `/mcp`; `daycue` should list tools matching the token's scopes. The automated test `mcp/test/stdio.test.ts` starts this exact process and lists tools via the MCP SDK client.

Alternative (no stdio process): a client token is also accepted by the HTTP endpoint, so `claude mcp add --transport http daycue <relay>/mcp --header "Authorization: Bearer <token>"` works. The stdio route is the documented one.

If the relay is unreachable at launch the server starts, exposes all tools and returns clear errors per call.

## 3. Pair the phone and the companion (for the app/companion implementers)

Protocol details are in RELAY.md section 3.3-3.4 and 4. With the local relay: create a phone code with the owner API, enter relay URL + code in the app. The app then calls `/v1/pair/phone`. The companion code comes from the app (`POST /v1/phone/companion-codes`).

## 4. Deploy on Render (REQUIRES THE OWNER'S RENDER ACCOUNT; the delivery lead does this)

Nothing below has been done. Costs are **not $0**: a durable queue cannot be built on Render's free resources (free web services have no disks and spin down after 15 min; free Postgres expires after 30 days; free Key Value is not persisted: <https://render.com/docs/free>).

Recommended resources (RELAY.md section 2): one **Starter web service** with a **1 GB persistent disk**, about $7 + $0.25 = about **$7.25/month** (third-party price summaries; confirm on the Render dashboard before creating). Cheaper variant: free web service + paid Postgres Basic (about $6/month) with 15 minute spin-down and ~1 minute cold starts, using `DATABASE_URL`.

Steps:

1. Push the repo to a Git provider Render can read (the repo is public, which is fine: no secrets are in it).
2. Render dashboard -> New -> Blueprint -> select the repo; it reads `mcp/render.yaml` (set the blueprint path to `mcp/render.yaml` if asked). Check the plan name against the dashboard (Render may expect `starter` or the newer compute-plan names).
3. When prompted, enter `DAYCUE_OWNER_SECRET`: generate it with `npm run gen-secret` (>= 32 chars, ~128 bits; weaker values make the relay refuse to start) and keep a copy in a password manager. Leave `FCM_SERVICE_ACCOUNT_JSON` empty for now.
4. After deploy, note the service URL, e.g. `https://daycue-relay.onrender.com`. It is injected as `RENDER_EXTERNAL_URL`, which the server uses as its public origin; if you add a custom domain set `DAYCUE_BASE_URL` to it (tokens are bound to this origin).
5. Check `https://<url>/healthz` returns `{"ok":true}` and `https://<url>/.well-known/oauth-protected-resource/mcp` returns JSON.
6. Back up: Render takes daily disk snapshots (kept at least 7 days). Redeploys briefly stop the service because a disk is attached.

Optional FCM wake (needs the owner's free Firebase project): create a Firebase project, enable Cloud Messaging, download a service-account JSON, paste it as the `FCM_SERVICE_ACCOUNT_JSON` env var, and have the app register its FCM token with `PUT /v1/phone/push`. Firebase Cloud Messaging has no per-message charge as far as I could tell, but I did not verify current Firebase pricing; check <https://firebase.google.com/pricing> before enabling.

## 5. Connect Claude.ai (REQUIRES THE OWNER'S CLAUDE ACCOUNT AND A PUBLIC HTTPS RELAY)

Per <https://claude.com/docs/connectors/custom/remote-mcp>: Customize -> Connectors -> Add custom connector (Free plan allows one custom connector), URL `https://<relay>/mcp`, Authentication "Sign in now", OAuth client "Use Claude's published identity" (CIMD) or "Register automatically" (DCR). Claude opens `/authorize`; check that the page shows **claude.ai** (or claude.com) as the return address, choose scopes, type the owner secret, Approve. Any other host gets a red warning: press Deny unless you started that connection yourself. Claude Code and other local clients return to `127.0.0.1`/`localhost` and are recognized as local. Callback hosts (verified 2026-10-04): Claude `https://claude.ai/api/mcp/auth_callback` (may move to claude.com), ChatGPT `https://chatgpt.com/connector/oauth/{callback_id}` and the legacy `https://chatgpt.com/connector_platform_oauth_redirect`; override the list with `DAYCUE_ALLOWED_REDIRECT_HOSTS`. Keep `medication` unticked unless you want medication labels visible to Claude. Revoke any time with `DELETE /v1/owner/grants/<id>`. Unverified until the owner tries it.

## 6. Connect ChatGPT (REQUIRES THE OWNER'S CHATGPT ACCOUNT AND A PUBLIC HTTPS RELAY)

Per <https://developers.openai.com/api/docs/guides/developer-mode>: enable Developer mode (Settings -> Security and login), create a developer-mode app for a remote MCP server with URL `https://<relay>/mcp`, authentication OAuth. Write tools ask for confirmation by default. OpenAI's doc lists Pro/Plus/Business/Enterprise/Education; third-party reports say Plus/Pro are read-only for custom connectors, so write tools (`apply_change` etc.) may not be usable on those plans: check on the owner's plan. Unverified until the owner tries it.

## 7. Costs and limits summary

| Item | Expected cost | Source / status |
|---|---|---|
| Local relay, tests, Claude Code stdio | $0 | n/a |
| Render Starter + 1 GB disk (recommended) | about $7.25/month | Third-party price summaries; Render's pricing page did not render for me. **Unverified**, confirm in dashboard |
| Render free web + free Postgres | $0 but not durable | Official: <https://render.com/docs/free> (Postgres expires after 30 days; web spins down after 15 min, ~1 min cold start; 750 h/month) |
| Render free web + Basic Postgres | about $6/month | Unverified price; spin-down applies |
| FCM | no known per-message fee | Not verified; check Firebase pricing |
| Claude custom connector | available on Free (one connector), Pro, Max, Team, Enterprise | Claude docs above |
| ChatGPT developer mode | plan-dependent | OpenAI docs above |

The relay itself needs no paid API (no LLM calls); the only recurring cost is Render hosting.

## 8. Phone sync triggers, frequent check and battery (phone side)

Status: unit/emulator tested against the local relay; **not run on a physical phone**. API for the UI: `docs/architecture/APP_API.md` section 10.

Pairing in the app: relay URL + the 10-minute code from `POST /v1/owner/pair-codes`. The app creates a non-exportable ECDSA P-256 key in the Android Keystore, registers its public key, and stores the device credential AES-GCM encrypted with a Keystore key. Release builds accept `https://` relays only; debug builds also accept `http://localhost` and `http://10.0.2.2` (debug network security config; on the emulator `adb reverse tcp:<port> tcp:<port>` plus `http://localhost:<port>` worked, `10.0.2.2` timed out here because of the host firewall).

When the phone talks to the relay:

| Trigger | Latency | Notes |
|---|---|---|
| App open / foreground | immediate | |
| After a local or remote config change (debounced 1.5 s) | seconds | republishes the redacted snapshot |
| WorkManager periodic | **at least 15 minutes, not guaranteed** | Doze, standby buckets and battery saver can delay it much longer |
| FCM wake (optional, below) | seconds when warm, best effort | needs the owner's Firebase project |
| **Frequent check** (opt-in, off by default) | every 3 to 30 minutes (default 5) | inexact `setAndAllowWhileIdle` alarm chain, only while the engine says the phone is at a saved place with auto/suggested sessions or allowed activities, on a permitted day, inside the session permitted hours, with "use companion activity" on. In Doze the OS may stretch each tick to 9+ minutes. **Battery:** one short wakeup plus an HTTPS round trip per tick (a cold relay can hold the connection up to ~100 s); with a free-tier relay that sleeps, each tick may wait about a minute. It stops by itself outside the conditions. |

Companion activity: during a sync the phone fetches `GET /v1/phone/activity`, verifies each signal's ECDSA signature with the companion public key the relay lists (DER and raw r||s both accepted), and feeds the engine a `CompanionActivity` whose expiry is `observedAt + ttlSeconds` (3 minutes for active/idle/locked, 10 minutes for asleep). Stale signals are never fed (state unknown). A TTL of 30 s or less is the companion's "paused" marker (10 s): it is fed with its short expiry so the state lapses within seconds; if the marker is already expired when the phone looks, nothing is fed and the engine's earlier fresh signal runs out on its own TTL (the domain cannot retract a signal; limitation). Several companions combine like the relay does (any active, else idle, locked, asleep). Without FCM wake or frequent check, signals older than ~3 minutes are stale by the time the phone looks, so automatic work sessions mostly do not start: sessions stay manual (`docs/PRODUCT.md` WRK-8).

Local controls (kill switch, change policy, medication allowance) are phone-only; see APP_API.md section 10.

## 9. Phone push wake (optional)

Without this the app builds and runs normally (`PushProvider` defaults to a no-op, nothing about Firebase is in the build). FCM only wakes the phone to sync; the data message carries no config (`{type: "sync"}`, RELAY.md section 6). **Not verified end to end** (needs your Firebase project and a device): the template compiles against `firebase-messaging:25.1.3` (checked in a scratch copy of the project), nothing else is tested.

What you do (Firebase accounts are free; I did not verify Firebase pricing, see <https://firebase.google.com/pricing>):

1. Firebase console: create a project (no Google Analytics needed), then Add app > Android, package name **`app.daycue`** (debug and release use the same package). Download `google-services.json` and place it at `android/app/google-services.json` (git-ignored by the root `.gitignore`; never commit it).
2. In the same project, Project settings > Service accounts > Generate new private key (a JSON file). Paste its **whole content as one line** into the relay environment variable `FCM_SERVICE_ACCOUNT_JSON` (Render dashboard or local shell; see section 4). Never commit it.
3. Android Gradle changes (three small edits; versions current on 2026-10-04):
   - `android/build.gradle.kts` (root), in the `plugins { }` block: `id("com.google.gms.google-services") version "4.5.0" apply false`
   - `android/app/build.gradle.kts`, in `plugins { }`: `id("com.google.gms.google-services")`
   - `android/app/build.gradle.kts`, in `dependencies { }`: `implementation("com.google.firebase:firebase-messaging:25.1.3")`
   - Not tested: the google-services Gradle plugin 4.5.0 together with AGP 9.4.1 (only the `firebase-messaging` dependency and the template were compiled). If the plugin fails, check the plugin's release notes for an AGP 9 compatible version.
4. Copy `android/app/fcm-template/FirebasePushProvider.kt` to `android/app/src/main/kotlin/app/daycue/integrations/relay/FirebasePushProvider.kt`, and paste the `<service>` from `android/app/fcm-template/manifest-snippet.xml` into `<application>` of `android/app/src/main/AndroidManifest.xml`. `RelayService` finds the class by name and registers the token with `PUT /v1/phone/push`.
5. Build, install, pair, and switch on "Push wake" (`facade.remote.setPushWake(true)`). Then `GET /v1/owner/devices` shows `push: true`.
6. To test: queue a change from any MCP client and watch whether it applies within seconds with the app closed. High-priority FCM messages can be downgraded if they never produce a visible notification; the phone therefore posts a low-priority "DayCue applied a change from ..." notice after an applied remote change.

Remove the steps (or the file) and the app goes back to periodic sync only.
