# DayCue Windows companion

A small tray app that tells your phone whether this PC is in active use (`active`, `idle`, `locked`, `asleep`). It is optional: without it, DayCue work/study sessions are manual (`docs/PRODUCT.md` WRK-8). Code: `companion/`. Wire protocol: `docs/architecture/RELAY.md` sections 3.4 and 4.5.

## What leaves this PC (exactly)

One HTTPS request per signal to the relay address you entered, nothing else:

```json
POST /v1/companion/signal      Authorization: Bearer <device credential>
{ "state": "active", "observedAt": 1791114000000, "ttlSeconds": 180, "signature": "<ECDSA P-256 signature>" }
```

- `state` is one of `active | idle | locked | asleep`.
- `observedAt` is when the state was observed (epoch ms); `ttlSeconds` is how long the phone may trust it.
- The signature proves the signal came from this paired app. The pairing request (once) additionally sends the pairing code, the app's **public** key and the device name you typed (default "Windows PC").

Never collected or sent: keystrokes, mouse movements, screenshots, window titles, process or application names, browser history, file names, document contents, hostname, IP address (the relay sees the connection IP at the network level; it does not store it, see RELAY.md section 9), or even how long you have been idle. "Idle" is one Windows number (`GetLastInputInfo`, time since the last input of any kind); what the input was is never visible to the app. This text is also in the tray menu under **About / Privacy**.

Sending cadence: on every state change, plus a heartbeat about every 60 s while active, idle or locked. Lock is sent immediately; sleep is sent best-effort just before suspend with the longest TTL (600 s) because a sleeping PC cannot send heartbeats.

## Install

Build the app (no installer, nothing is registered system-wide):

```powershell
$dotnet = "$env:USERPROFILE\dev-tools\dotnet\dotnet.exe"
# Self-contained single file, about 49 MB, runs on any 64-bit Windows 10/11 with nothing else installed:
& $dotnet publish companion\src\DayCue.Companion -c Release -r win-x64 --self-contained true `
  -p:PublishSingleFile=true -p:EnableCompressionInSingleFile=true -o companion\publish
```

Output: `companion\publish\DayCueCompanion.exe`. Copy it anywhere you like, for example `%LOCALAPPDATA%\DayCue\`, and run it.

Smaller alternative: replace `--self-contained true` with `--self-contained false` and drop the compression flag for a single file of about 230 KB that needs the .NET 10 Desktop Runtime installed. Self-contained is the default recommendation because it has no prerequisite; the cost is size and a slower first start.

The icon appears in the tray (it may be under the `^` overflow arrow). Dot colour: green active, amber idle, blue locked, purple asleep; hollow ring when unpaired or unknown; pause bars when paused.

## Pair

1. You need a relay (`docs/setup/MCP.md`) and the DayCue phone app already paired to it.
2. In the phone app, create a companion pairing code (it calls `POST /v1/phone/companion-codes`; codes live 10 minutes and work once).
3. Tray icon, right-click, **Pair...**. Enter the relay URL (`https://...`; plain `http` is accepted only for `localhost` / `127.0.0.1` testing), the code, and optionally a name. A relay on a free host can take up to about 90 s to wake; the dialog waits.
4. The app creates a P-256 key in the Windows CNG key store for your user (non-exportable, the private key cannot be read out), registers its public key, and stores only: relay URL, device id, the device credential (encrypted with DPAPI for your Windows user) and the key's name.

Config and state live in `%APPDATA%\DayCue\` (`pairing.json`, `state.json`, optional `settings.json`, `error.log` for exceptions only). Nothing is in the repository. `companion/appsettings.example.json` lists the tunables (idle threshold default 120 s, heartbeat 60 s, TTL 180 s, debounce 3 s, poll 5 s, HTTP timeout 100 s).

## Menu

- Status lines: paired (relay host), this PC's state, last sent (time and state), connection (online, retrying, offline, credential rejected).
- **Pause reporting**: for 1 hour, or until you resume. While paused nothing is sent. Because RELAY.md has no "paused" state, pausing sends one last signal repeating the current state with the minimum TTL (10 s), so the phone sees "unknown" within about 10 s instead of up to 3 minutes; then silence. A pause survives restarts. Resume sends the current state immediately.
- **Pair... / Unpair...**
- **Run at startup**: off by default; writes `HKCU\Software\Microsoft\Windows\CurrentVersion\Run\DayCueCompanion` (current user only).
- **About / Privacy...**, **Exit** (sends a final minimum-TTL signal, best effort, then quits).

Only one instance runs per config folder; a second launch exits silently.

## Behaviour on bad networks

The relay may be asleep or you may be offline. Each signal gets up to 4 attempts with 2, 4, 8 s backoff (100 s HTTP timeout per attempt); a newer signal replaces any older one waiting, and a signal past its TTL is dropped. There is no offline queue. When the network returns the app sends only the current state. If the PC clock is off by more than a few seconds the app learns the offset from the relay's `serverTime` and shifts `observedAt` so the relay's future and expiry checks still pass. If the relay answers 401 (credential revoked), sending stops and the menu says to pair again.

## Uninstall

1. Tray, **Unpair...** (deletes the key and `pairing.json`), turn **Run at startup** off, **Exit**.
2. Delete `DayCueCompanion.exe` and the folder `%APPDATA%\DayCue\` if you want no trace.
3. The relay keeps a record of the device until the owner revokes it: `DELETE /v1/owner/devices/<id>` (`docs/setup/MCP.md`, owner API). Revoke it there too; deleting the key already makes the credential unable to produce valid signals.

## Development

```powershell
& $dotnet build companion
& $dotnet test companion      # 40 tests; 3 start the real relay from mcp/ (needs node and `npm install` in mcp/; skipped otherwise)
```

`DAYCUE_COMPANION_DIR` overrides the config folder (use a throwaway folder for experiments). `DayCueCompanion.exe --exit-after 10` starts and quits cleanly after 10 s (smoke test).
