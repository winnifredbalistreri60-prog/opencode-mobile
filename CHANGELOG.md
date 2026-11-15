# Changelog

All notable releases of this unofficial OpenCode Mobile client. The official OpenCode CLI remains a separate project ([anomalyco/opencode](https://github.com/anomalyco/opencode)).

## 0.1.3 — 2026-11-15

Debug build, versionCode 4. Same debug certificate as 0.1.0–0.1.2 (`65b96eb0…2a90`).

- **In-app updates from GitHub Releases.** Manifest `opencode-mobile-version.json` from `releases/latest/download/…`, with the public GitHub API `releases/latest` as a fallback. No tokens.
- Check on launch at most every 6 hours (switch in App settings → App updates) and a **Check for updates** button. Versions are compared by `versionCode`; “Later” hides that version from the automatic check.
- Download with progress; size and sha256 checked; package name, `versionCode` and signing certificate must match the installed app. Any mismatch deletes the file and rejects the update.
- Install through PackageInstaller. After “Allow from this source” is granted and you return to the app, the dialog switches from “Open settings” to “Install” by itself (fixes the MURF bug). Cancelling the system prompt keeps the verified file for another try. The local server is stopped before installing.
- `scripts/make-release.sh`: builds the APK, verifies the certificate, writes the APK, `.sha256` and the manifest for a release. Publishes nothing.
- Test-only Gradle properties `-PocmUpdateTestServer`, `-PocmVersionCode`, `-PocmVersionName` for update tests against a local HTTP server. Default builds point at GitHub.
- Russian setup error text: “не удалось скачать …” instead of “не скачать …”.
- 17 new unit tests (manifest parsing, URL allow-list, API fallback, versionCode comparison, 6 h throttle, sha256, signer match, install-button state). 68 tests in total.

## 0.1.2 — 2026-11-15

Debug build, versionCode 3. Same debug certificate as 0.1.0 / 0.1.1 (`65b96eb0…2a90`).

- **BUG-23.** Phone API executor / accept-loop lifecycle: new pool per start, dedicated accept thread, close + unlink + wakeup-connect before bind. Stop/Start no longer crashes the process with `RejectedExecutionException` or leaves `Address already in use`. A stale Stop after a fast Start is ignored. Stress: 20 Stop/Start cycles on the emulator with no FATAL; the previous port is reused when it is still free.
- **BUG-18.** WebView history is cleared on origin/port change. System Back no longer returns to a dead previous port. The last free port and its password are remembered so cookies survive Stop/Start.
- **BUG-20.** Settings and About are overlaid; the WebView stays composed. Returning from Settings does not reload the session unless the server origin changed.
- **BUG-12.** Setup is stepwise (download, extract, apt, CLI, config) with skip markers. Rootfs/OpenCode archives are kept; a Retry does not re-download when the sha256 already matches.
- **BUG-19.** The first system Back on Settings closes Settings (Activity no longer steals Back for a hidden WebView).
- **BUG-11.** Start/Stop always act on the current mode. Switching mode asks and then stops the previous server. Termux missing/timeout shows a clear error plus `allow-external-apps=true` instructions, not an infinite “Waiting”.
- **BUG-21.** Remote status no longer mixes the embedded server message or invents port 80 for incomplete URLs.
- **BUG-22 / location JSON.** `termux-torch off` turns the torch off. Phone CLI errors (including `permission_pending`) are always JSON, never a bare string or a Python traceback. Phone API scripts are refreshed on every Start so upgrades pick up the fixes.
- Debug keystore removed from git and from rewritten local history; Gradle reads it from `local.properties` (`debug.keystore=`) or `OPENCODE_DEBUG_KEYSTORE`.
- Unit tests for origin/history policy, setup skip, torch argument parsing, and client JSON (51 tests).

## 0.1.1 — 2026-11-15

Debug build, versionCode 2. Same certificate as 0.1.0.

- Fixed proot self-test: `PROOT_NO_SECCOMP` only after a failed test (`noseccomp` file), so apt no longer fails with code 100.
- Official web-UI pairing: native `/api/pair` + `/auth/connect/{code}`; password is not injected into page JavaScript.
- MCP `phone` uses line-delimited JSON-RPC and OpenCode v2 `command` as an array.
- File chooser callback is owned only by the WebView; cancel no longer crashes.
- Cleartext for Remote HTTP, live UI refresh, stale serve-state cleanup, `URL.parse` polyfill on WebView 124, serve password kept out of `ps` argv, runtime location permission prompt, Start/Stop and settings UX fixes, Phone API notification text, and FGS `startForeground()` before any `stopSelf()`.

## 0.1.0 — 2026-11-15

First debug preview (versionCode 1).

- Embedded PRoot Ubuntu 24.04 + official OpenCode 2.3.0 linux-x64 / linux-arm64.
- Official web UI in a WebView, Termux and Remote modes, Phone API / MCP, USB serial list (no raw fd passthrough).
- Known issues from the first tester pass were addressed in 0.1.1 and 0.1.2; see those sections and `CHANGES.md`.
