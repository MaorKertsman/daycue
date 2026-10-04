# Spotify for morning alarms (optional, off by default)

Code: `android/app/src/main/kotlin/app/daycue/integrations/spotify/`, adapter `android/app/src/spotifysdk/`. Facts below were checked on 2026-10-04 against developer.spotify.com and github.com/spotify/android-sdk. **Nothing here has run against a real Spotify account or a physical phone.**

## Read this first: Spotify's policy and a technical limit

1. **Spotify's Developer Policy (effective 15 May 2025, <https://developer.spotify.com/policy>, "Some prohibited applications") says: "Do not create ringtone or alert tone functionality or alarm functionality in an SDA, unless you receive Spotify's written approval."** A morning alarm that plays a Spotify playlist is alarm functionality in a Spotify-integrated app. A private, never-published app is not clearly exempt from that wording, and Spotify can revoke a client ID. Extended quota mode (the usual route to approval) is open to organizations only. Whether to accept that risk, or ask Spotify for written approval, is the owner's decision; the code ships **disabled** (see below) and the app works without it.
2. **App Remote needs your app or Spotify in the foreground.** Spotify's SDK 0.8.0 announcement (<https://developer.spotify.com/blog/2023-07-06-new-android-sdk-version>): communication "relies solely on a bound service, which requires either your app or Spotify to be in the foreground (or in the background while playing audio)", and the lifecycle guide says not to keep the connection alive in the background. A DayCue alarm fires from the background (the screen may be off, the phone locked). Expect connection failures or timeouts in exactly that situation. **Do not rely on Spotify to wake you.** The design therefore keeps the local tone ringing from the first moment and silences it only when playback is confirmed from the player state.
3. Premium: Spotify's App Remote README says "A Spotify Premium account is required to play a single track uri". What a free account can do with playlists or albums is not documented in what I fetched (unverified; test it).

## How it behaves in the app

- Alarm source "Spotify item" (`AlarmSource.SpotifyItem(uri, fallbackToneId)`) with `spotifyStartTimeoutSec` (default 10).
- When the alarm rings: the local tone starts at once. In the background DayCue checks that Spotify is installed and the network is up, connects through App Remote, asks it to play the URI, then **confirms from player state** (requested URI as track or context, not paused, position advancing between two samples about 1.2 s apart). Only then is the tone silenced. A returned `play()` call or a launched Spotify app is never treated as success.
- If playback stops later (paused, ended, disconnected for 3 s), the tone comes back at full volume.
- Failures are typed: not installed, not authorized (not signed in / expired / user did not authorize), no network, remote unavailable (Spotify not running or could not be reached), account restriction, timeout, SDK not bundled, unknown. The alarm screen gets the reason plus one recovery action (`facade.alarmMusic`, `facade.alarmMusicRecoveryIntent`, `facade.retryAlarmMusic`); see `docs/architecture/APP_API.md` section 11.
- Stop on the alarm screen also pauses Spotify.

## Enable it (developer steps)

### 1. Spotify developer dashboard

1. Sign in at <https://developer.spotify.com/dashboard>. Since 11 Feb 2026 new development-mode apps need the app owner to have Spotify Premium; one client ID per developer; at most **5 allowlisted users** (<https://developer.spotify.com/blog/2026-02-06-update-on-developer-access-and-platform-security>). Add every Spotify account that will use the alarm (including your own) under the app's User Management. Whether App Remote itself is gated by that allowlist is not stated in the docs I read: test with a non-listed account.
2. Create an app. Under **Redirect URIs** add exactly `app-daycue://spotify-callback` (the constant `SpotifyIntegration.REDIRECT_URI`). Spotify's redirect-URI rules (<https://developer.spotify.com/documentation/web-api/concepts/redirect_uri>) require HTTPS or loopback for web redirects; custom schemes for Android App Remote are what the Android SDK tutorials use, but I could not confirm the 2026 dashboard still accepts one. If the dashboard refuses it, change the constant and the registration together.
3. Under **Android packages** register package name `app.daycue` and the **SHA-1 fingerprint of each signing key you will install with**: one entry for the debug key and one for the release key. Debug key:
   ```powershell
   & "$env:USERPROFILE\dev-tools\jdk21\bin\keytool.exe" -list -v -keystore "$env:USERPROFILE\.android\debug.keystore" -alias androiddebugkey -storepass android -keypass android
   ```
   Release key: the same command against your release keystore (`android/keystore.properties`; never commit it). Copy the `SHA1:` line.
4. Copy the **Client ID**. It is not a secret in the PKCE/App Remote flow (there is no client secret in the app), but Spotify's terms forbid disclosing your credentials to others, so keep it out of the public repo.

### 2. Client ID (git-ignored)

Add one line to `android/local.properties` (git-ignored) or pass it on the command line:

```
daycue.spotify.clientId=<your client id>
```
```powershell
.\gradlew.bat :app:assembleDebug -Pdaycue.spotify.clientId=<your client id>
```

The committed default is the placeholder `YOUR_SPOTIFY_CLIENT_ID`. With the placeholder, connecting fails as "not authorized".

### 3. Add the App Remote SDK (not in the repo)

The SDK is an AAR. It is not on Maven Central (JitPack has `com.github.spotify:android-app-remote-sdk:0.8.0-appremote_v2.1.0-auth`). The GitHub repo `spotify/android-sdk` is Apache License 2.0 (the license permits redistribution) and publishes the file at `app-remote-lib/spotify-app-remote-release-0.8.0.aar`. Because the redistribution position under Spotify's Developer Terms was not fully verifiable, **the AAR is not committed**: `android/app/libs/.gitignore` ignores `*.aar`.

```powershell
curl.exe -L -o android\app\libs\spotify-app-remote-release-0.8.0.aar `
  https://raw.githubusercontent.com/spotify/android-sdk/master/app-remote-lib/spotify-app-remote-release-0.8.0.aar
```

When any `app/libs/spotify-app-remote*.aar` exists, Gradle adds it (plus `com.google.code.gson:gson:2.6.1`, required by the AAR), sets `BuildConfig.SPOTIFY_SDK = true` and compiles `src/spotifysdk/kotlin/.../AppRemoteSpotifyRemote.kt`. Delete the file to go back to the SDK-free build (a Spotify alarm then rings the local tone and reports `SdkNotBundled`). Both builds compile and pass the unit tests (checked 2026-10-04, SDK 0.8.0; latest GitHub release is from July 2023). If you ship the app publicly, keep Spotify's LICENSE/NOTICE with the binary and re-check their terms.

### 4. Authorize once, from the foreground

On the phone: Spotify installed and signed in, then open the alarm test (ALM-5) with the app in the foreground and press "Authorize" on the alarm screen (`retryAlarmMusic(interactive = true)`); Spotify shows its consent view. Built-in authorization keeps working offline for 24 hours with identical credentials per Spotify's README; the exact token lifetime is undocumented (unverified).

## Physical-device tests required (none has been run)

Run each with a real Premium account (and the free account where stated), the alarm set 2 minutes ahead, and note what you hear and what the alarm screen says. Pass = the tone rings until Spotify actually plays, then Spotify plays; failures end with the tone ringing and a correct reason.

1. **Screen locked, phone idle for 10+ minutes** (Doze), Spotify closed: does the connect work at all from the background? Expected on current evidence: often not; the tone must keep ringing and the reason must read remote unavailable or timeout.
2. **Phone asleep overnight** with the charger connected and with battery saver on.
3. **Spotify not running** (swiped away) versus Spotify running in the background versus Spotify paused in the foreground.
4. **Expired or revoked authorization** (remove DayCue under Spotify account > apps, or wait for expiry): reason = not authorized, "Authorize" works from the visible alarm screen.
5. **No network** (airplane mode): reason = no network, tone rings.
6. **No usable device / Spotify in offline mode / logged out**: reason = remote unavailable or not authorized as appropriate.
7. **Free account**: single track URI and playlist URI: does Spotify refuse (account restriction) or shuffle-play? Record the exact outcome.
8. **Playback stops mid-alarm** (pause in Spotify, or the track ends): the tone returns within about 3 s.
9. **Stop and Snooze** on the alarm screen: Spotify pauses; the next ring retries.
10. **Android 17 audio focus**: when Spotify starts, is the local tone actually silent and Spotify audible on the alarm stream? (DayCue's own focus requests are already refused by Android 17 background audio hardening; see APP_API.md section 6.)
11. **Allowlist**: an account that is not on the dashboard allowlist.

Do not promise unattended playback to yourself: until tests 1-3 pass on your phone, treat the Spotify alarm as a nice-to-have on top of the local tone, never as the thing that wakes you.
