# Building, testing and releasing DayCue

Covers the three components on Windows, release signing, and the GitHub Actions workflows in `.github/`. Nothing here contains secrets; replace placeholders with your own values.

## 1. Toolchain

Tools live under `%USERPROFILE%\dev-tools` and are not on PATH. Node 20 is on PATH.

| Tool | Location | Version |
|---|---|---|
| JDK | `%USERPROFILE%\dev-tools\jdk21` | 21 (bytecode targets 17) |
| Android SDK | `%USERPROFILE%\dev-tools\android-sdk` | platform `android-37.0` |
| .NET SDK | `%USERPROFILE%\dev-tools\dotnet\dotnet.exe` | 10 |
| Node | PATH | 20 or newer |

The Gradle wrapper (9.8.0, checksum pinned) downloads Gradle itself; no separate Gradle install is needed.

## 2. Local builds (PowerShell)

```powershell
$env:JAVA_HOME    = "$env:USERPROFILE\dev-tools\jdk21"
$env:ANDROID_HOME = "$env:USERPROFILE\dev-tools\android-sdk"
$dotnet           = "$env:USERPROFILE\dev-tools\dotnet\dotnet.exe"

# Android: unit tests, debug APK, lint (same as CI)
cd android
.\gradlew.bat :domain:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
# APK: android\app\build\outputs\apk\debug\app-debug.apk
cd ..

# MCP relay (typecheck + tests, no network or accounts needed)
cd mcp
npm ci
npm run typecheck
npm test
cd ..

# Windows companion (its tests start the relay from mcp/ via tsx, so run npm ci in mcp/ first)
& $dotnet test companion
```

## 3. Continuous integration

`.github/workflows/ci.yml` runs on every push and pull request to `main` with `contents: read` only, cancels superseded runs, and caches Gradle (setup-gradle) and npm (setup-node).

| Job | Runner | Runs |
|---|---|---|
| `android` | ubuntu-latest, Temurin 21 | `:domain:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug`; uploads the debug APK and test/lint reports |
| `mcp` | ubuntu-latest, Node 20 | `npm ci`, `npm run typecheck`, `npm test` |
| `companion` | windows-latest, .NET 10, Node 20 | `npm ci` in `mcp/`, then `dotnet test companion` |

Third-party actions are pinned to commit SHAs (the version is in a trailing comment). Dependabot (`.github/dependabot.yml`) proposes weekly updates for Gradle, npm, NuGet and the actions themselves; review the diff and keep the SHA pins when updating by hand.

## 4. Release signing

Android only installs an update over an existing app when both are signed with the **same key**. The release keystore is therefore the identity of the app.

### 4.1 Create the keystore (once)

Keep it **outside the repository** (for example `%USERPROFILE%\secrets\daycue\`).

```powershell
$env:JAVA_HOME = "$env:USERPROFILE\dev-tools\jdk21"
New-Item -ItemType Directory -Force "$env:USERPROFILE\secrets\daycue" | Out-Null
& "$env:JAVA_HOME\bin\keytool.exe" -genkeypair -v `
  -keystore "$env:USERPROFILE\secrets\daycue\daycue-release.jks" `
  -alias daycue -keyalg RSA -keysize 4096 -validity 10000
```

`keytool` prompts for the store password, key password and a distinguished name. Use long random passwords from a password manager. The repository `.gitignore` already excludes `*.jks`, `*.keystore` and `keystore.properties`, but do not rely on that: never copy the keystore into the working tree.

### 4.2 Back it up

Back up the `.jks` file **and** both passwords and the alias in at least two separate places (for example a password manager entry with the file attached, plus an encrypted offline copy). If the keystore or its passwords are lost, no update can ever be installed over the existing app; users (you) must uninstall, which deletes all app data, and install a build signed with a new key.

Record the certificate fingerprint so you can confirm later that a build is signed with the right key:

```powershell
& "$env:JAVA_HOME\bin\keytool.exe" -list -v -keystore "$env:USERPROFILE\secrets\daycue\daycue-release.jks" -alias daycue
```

### 4.3 Sign local release builds

Either create `android/keystore.properties` (git-ignored; start from `android/keystore.properties.example`; paths are relative to `android/`):

```properties
storeFile=C:/Users/YOU/secrets/daycue/daycue-release.jks
storePassword=...
keyAlias=daycue
keyPassword=...
```

Or set environment variables for the current shell (the properties file wins when both exist):

```powershell
$env:DAYCUE_KEYSTORE_FILE     = "$env:USERPROFILE\secrets\daycue\daycue-release.jks"
$env:DAYCUE_KEYSTORE_PASSWORD = Read-Host "store password"
$env:DAYCUE_KEY_ALIAS         = "daycue"
$env:DAYCUE_KEY_PASSWORD      = Read-Host "key password"
```

Then build and verify:

```powershell
cd android
.\gradlew.bat :app:assembleRelease
& "$env:ANDROID_HOME\build-tools\37.0.0\apksigner.bat" verify --print-certs app\build\outputs\apk\release\app-release.apk
```

Warning: if any of the four values is missing or the keystore file does not exist, `android/app/build.gradle.kts` silently signs the release with the **debug key** and only prints a Gradle warning. That APK installs locally but must never be distributed, and it cannot be updated by a properly signed release. Always check the `apksigner` output: the certificate DN must not contain `Android Debug` and the SHA-256 must match the fingerprint you recorded.

### 4.4 GitHub secrets for the release workflow

Add four repository secrets (Settings, Secrets and variables, Actions) or use the CLI:

```powershell
$ks = "$env:USERPROFILE\secrets\daycue\daycue-release.jks"
[Convert]::ToBase64String([IO.File]::ReadAllBytes($ks)) | gh secret set DAYCUE_KEYSTORE_BASE64 --repo MaorKertsman/daycue
gh secret set DAYCUE_KEYSTORE_PASSWORD --repo MaorKertsman/daycue   # prompts for the value
gh secret set DAYCUE_KEY_ALIAS         --repo MaorKertsman/daycue
gh secret set DAYCUE_KEY_PASSWORD      --repo MaorKertsman/daycue
```

The names match `android/app/build.gradle.kts` (`DAYCUE_KEYSTORE_FILE` is set by the workflow itself to the decoded temp file).

## 5. Releasing

1. Bump `versionCode` (must increase on every release) and `versionName` in `android/app/build.gradle.kts`, merge to `main`.
2. Tag the commit and push the tag, or run the workflow manually from the Actions tab and enter the tag:

   ```powershell
   git tag v0.1.0
   git push origin v0.1.0
   ```

3. `.github/workflows/release.yml`:
   - fails immediately, with an explicit error, if any of the four secrets is missing, if the tag is not `vMAJOR.MINOR.PATCH`, or if the tag differs from `versionName`;
   - runs the domain and app unit tests, builds `:app:assembleRelease`, and runs `apksigner verify --print-certs`;
   - refuses to publish if the certificate is the Android debug key;
   - publishes `daycue-<version>.apk` and `SHA256SUMS.txt` to a GitHub release (a separate job holds `contents: write`; the build job that sees the secrets has read-only access).
4. Compare the printed certificate SHA-256 in the build log with the fingerprint recorded in 4.2 before installing.

The release job has not been run yet: it needs the secrets above and a pushed tag, so it is unverified until the first release.

## 6. How the signing identity is preserved

- One keystore, created once, used for every release build locally and in CI (the same file, via `DAYCUE_KEYSTORE_BASE64`).
- `applicationId` (`app.daycue`) and the key never change; `versionCode` only increases.
- The keystore and passwords exist only in your backups and in GitHub secrets, never in git history. If a secret is ever committed, treat the key as compromised; since rotating the key breaks updates, prevent this by checking `git diff --cached` before every push.
- Debug builds (CI artifacts, local `assembleDebug`) use a different key and cannot update a release install, and vice versa. Pick one channel on the phone.
