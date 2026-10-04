# Setting up the DayCue relay and MCP server

Design and wire protocol: `docs/architecture/RELAY.md`. Code: `mcp/`. The remote path is optional: the phone works with no relay at all.

Requirements: Node 20 (on PATH on this machine). All commands below are run from `mcp/` unless stated. Nothing in this document has been run against a real phone, Render, Claude.ai or ChatGPT; see "Verification status" in RELAY.md.

## 1. Run locally

```powershell
cd mcp
npm ci
npm test            # 47 tests, no network or accounts needed
npm run typecheck
```

Create a secret and start the relay (the secret protects the consent page and owner API; keep it out of the repo):

```powershell
$env:DAYCUE_OWNER_SECRET = node -e "console.log(require('crypto').randomBytes(32).toString('base64url'))"
$env:DAYCUE_OWNER_SECRET   # note it down somewhere private
npm run dev                # http://localhost:8787, data in mcp/.data/relay.json (git-ignored)
```

`mcp/.env.example` lists every variable. Without `DAYCUE_DATA_FILE`/`DATABASE_URL` the dev server stores data in `.data/relay.json`.

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
   claude mcp add daycue --scope user -e DAYCUE_RELAY_URL=http://localhost:8787 -e DAYCUE_CLIENT_TOKEN=$env:DAYCUE_CLIENT_TOKEN -- node C:\Users\maork\my-stuff\daycue\mcp\dist\stdio.js
   ```

   or, without building, `-- node C:\Users\maork\my-stuff\daycue\mcp\node_modules\tsx\dist\cli.mjs C:\Users\maork\my-stuff\daycue\mcp\src\stdio.ts`.
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
3. When prompted, enter `DAYCUE_OWNER_SECRET` (random, >= 24 chars, from a password manager). Leave `FCM_SERVICE_ACCOUNT_JSON` empty for now.
4. After deploy, note the service URL, e.g. `https://daycue-relay.onrender.com`. It is injected as `RENDER_EXTERNAL_URL`, which the server uses as its public origin; if you add a custom domain set `DAYCUE_BASE_URL` to it (tokens are bound to this origin).
5. Check `https://<url>/healthz` returns `{"ok":true}` and `https://<url>/.well-known/oauth-protected-resource/mcp` returns JSON.
6. Back up: Render takes daily disk snapshots (kept at least 7 days). Redeploys briefly stop the service because a disk is attached.

Optional FCM wake (needs the owner's free Firebase project): create a Firebase project, enable Cloud Messaging, download a service-account JSON, paste it as the `FCM_SERVICE_ACCOUNT_JSON` env var, and have the app register its FCM token with `PUT /v1/phone/push`. Firebase Cloud Messaging has no per-message charge as far as I could tell, but I did not verify current Firebase pricing; check <https://firebase.google.com/pricing> before enabling.

## 5. Connect Claude.ai (REQUIRES THE OWNER'S CLAUDE ACCOUNT AND A PUBLIC HTTPS RELAY)

Per <https://claude.com/docs/connectors/custom/remote-mcp>: Customize -> Connectors -> Add custom connector (Free plan allows one custom connector), URL `https://<relay>/mcp`, Authentication "Sign in now", OAuth client "Use Claude's published identity" (CIMD) or "Register automatically" (DCR). Claude opens `/authorize`; choose scopes, type the owner secret, Approve. Keep `medication` unticked unless you want medication labels visible to Claude. Revoke any time with `DELETE /v1/owner/grants/<id>`. Unverified until the owner tries it.

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
