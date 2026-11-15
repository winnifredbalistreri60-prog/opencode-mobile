# Changelog

All notable releases of this unofficial OpenCode Mobile client. The official OpenCode CLI remains a separate project ([anomalyco/opencode](https://github.com/anomalyco/opencode)).

## 0.2.1 — 2026-11-15

Debug build, versionCode 11. Same debug certificate as 0.1.0–0.2.0 (`65b96eb0…2a90`). Installs over 0.2.0 and keeps the environment, `opencode.json` and settings.

- **Termux mode works again (BUG-24).** New install script (`scripts/termux-install.sh`, also inside the app): Termux glibc (`glibc-repo`, `glibc-runner`) + `patchelf`, the official OpenCode Linux build with a sha256 check, only the ELF interpreter patched, an `opencode` launcher in `$PREFIX/bin`, `allow-external-apps=true`. The app shows the steps and a **Copy install command** button, plus **Open Termux** and a button for the “Run commands in Termux” permission. Fresh Termux installs get a current `libc++` so `patchelf` starts. arm64 phones only: on x86_64 Android seccomp kills the glibc loader (exit 159), and the error says so.
- **Termux errors are visible (BUG-25).** `RUN_COMMAND` now uses a `PendingIntent`: stdout, stderr, exit code and Termux’s own error reach the app. The reason is shown under the Start button and written to `termux.log` (with the tail of `serve.log`). The password is redacted.
- **Termux password is not in argv.** It goes through stdin into a 0600 file in `~/.opencode-mobile/`, which `opencode serve` reads via `exec`.
- Stop no longer uses `pkill -f`: only our pid on our port is stopped; other `opencode serve` processes survive.
- `termux-location` tries a fresh fix (default 15 s, `-T`) on the enabled providers before the newest last-known location; the reply has `source` and `age_ms`.
- The setup error is shown once; the embedded status names the port once; empty status is no longer “IDLE — ”.
- Start, Stop and install in the runtime service are serialized; the first Start right after Stop no longer races with the service shutting down. Termux health checks no longer run on the main thread (they always timed out before).
- **Updater:** “Check for updates” re-checks that the downloaded file still exists and matches sha256 before saying “File verified”. A network drop says “Download failed” (not “Update rejected”), and Retry resumes from a `.part` file with `Range`; sha256 is checked at the end. The auto-check switch toggles by tapping its label.
- **Remote password** is encrypted with an Android Keystore key (AES-GCM); the old plaintext value is migrated at app start.
- **Plugins (run5):** registry header templates are filled (`Bearer {smithery_api_key}` + your key → `Bearer <key>`, not the bare key; the field shows how the value will be written). The default name skips generic id segments (`com.notion/mcp` → `notion`) and stays unique. **Change key** for installed servers with headers (keeps the `Bearer ` prefix); “Sign in” (OAuth) is shown only for servers without an API key, and a keyed server that needs auth says the key was not accepted. Remote/Termux: no false “Not in opencode.json” mark, and the remove dialog no longer promises to edit opencode.json. Clearer catalog filter text and WebView-too-old message.
- `PROMPT.md`: a short brief for continuing development (architecture, modules, build, rules, known issues, next stages).
- 28 new unit tests (Termux serve/stop scripts run in real bash with a fake opencode, error texts and redaction, install script checks, status text, location plan, download resume, secret format, header templates, default names, WebView text). 117 tests in total.

## 0.2.0 — 2026-11-15

Debug build, versionCode 10. Same debug certificate as 0.1.0–0.1.3 (`65b96eb0…2a90`). Installs over 0.1.3 and keeps the environment and your config.

- **Config is merged, not overwritten.** Setup and every server start only merge the app's keys (`update: "disable"`, `mcp.servers.phone`) into the guest `opencode.json`. User MCP servers, `plugins`, providers and other settings stay. User fields on `phone` (for example `disabled`, `timeout`) stay too. A file that is not valid JSON is kept as `opencode.json.broken-<ms>.bak` and replaced by the template. The file is written atomically with mode 0600.
- **New “Plugins” screen** (Compose; puzzle icon in the top bar and App settings → Plugins). Two tabs on phones, list + details side by side on tablets.
  - Catalog from the Official MCP Registry (`/v0.1/servers?version=latest`): pagination (“Load more”), server-side search (3+ chars, shorter queries filter locally), a local cache of everything seen, and an offline mode that shows and searches the cache.
  - One-tap install for remote Streamable HTTP servers: entry written to `opencode.json`, then `PUT /api/experimental/mcp/:name` in the default and every loaded location so it connects right away. Name field (validated, unique, never `phone`) and fields for documented headers; secret headers are masked. Header values live only in `opencode.json` in app-private storage and are not logged.
  - Installed list with live status from `GET /api/mcp` (connected / sign-in needed / failed / disabled / connecting). The empty list right after a server start is shown as “waiting” and polled, not as an error. Remove = delete from `opencode.json` + `DELETE /api/experimental/mcp/:name`.
  - “Sign in” for `needs_auth` servers opens the web UI at Settings → Extensions → MCP, where OpenCode runs its OAuth flow.
  - npx/Node, uvx/PyPI, Docker and SSE-only servers are listed as “not available yet” with the reason.
  - `phone` is shown as built-in and cannot be removed.
  - Termux / Remote modes: install connects until the server restarts (the config file is not the app's), with a notice.
- 21 new unit tests (config merge: empty, broken JSON with backup, user servers/plugins kept, our key refreshed; registry parsing, remote filter, cache round-trip, status merge and warm-up). 89 tests in total.

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
