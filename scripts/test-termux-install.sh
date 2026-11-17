#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
PREFIX="$TMP/prefix"
HOME="$TMP/home"
MOCK_BIN="$TMP/mock-bin"
MOCK_TREE="$TMP/mock-tree"
MOCK_APT_LOG="$TMP/apt.log"
MOCK_ARCHIVE="$TMP/opencode-linux-arm64.tar.gz"
TEST_GLIBC_LOADER="$PREFIX/glibc/lib/ld-linux-aarch64.so.1"
mkdir -p "$PREFIX/bin" "$PREFIX/etc/apt/sources.list.d" "$PREFIX/tmp" "$HOME/.termux" "$MOCK_BIN" "$MOCK_TREE"

cat > "$PREFIX/etc/apt/sources.list" <<'SOURCES'
deb https://packages.termux.dev/apt/termux-main stable main
deb https://mirror.example/freebuff-termux stable main
SOURCES
cat > "$PREFIX/etc/apt/sources.list.d/extra.sources" <<'SOURCES'
Types: deb
URIs: https://codebuff-termux.example/apt
Suites: stable
Components: main

Types: deb
URIs: https://packages.termux.dev/apt/termux-root
Suites: stable
Components: root
SOURCES
cp "$PREFIX/etc/apt/sources.list" "$TMP/sources.list.original"
cp "$PREFIX/etc/apt/sources.list.d/extra.sources" "$TMP/extra.sources.original"
printf 'other-setting=true\nallow-external-apps=false\n' > "$HOME/.termux/termux.properties"

cat > "$MOCK_TREE/opencode" <<'OPENCODE'
#!/usr/bin/env bash
if [ -n "${LD_LIBRARY_PATH:-}" ]; then
  echo "test failure: inherited LD_LIBRARY_PATH reached OpenCode" >&2
  exit 91
fi
if [ "${1:-}" = "--version" ]; then
  echo 'OpenCode test build 2.3.0'
  exit 0
fi
echo 'fake OpenCode server'
OPENCODE
chmod +x "$MOCK_TREE/opencode"
tar -czf "$MOCK_ARCHIVE" -C "$MOCK_TREE" opencode
MOCK_SHA256="$(sha256sum "$MOCK_ARCHIVE" | awk '{print $1}')"

cat > "$MOCK_BIN/pkg" <<'PKG'
#!/usr/bin/env bash
exit 0
PKG
cat > "$MOCK_BIN/apt-get" <<'APT'
#!/usr/bin/env bash
set -e
[ -z "${LD_LIBRARY_PATH:-}" ] || { echo 'LD_LIBRARY_PATH leaked to apt-get' >&2; exit 92; }
printf '%s\n' "$*" >> "$MOCK_APT_LOG"
if [[ " $* " == *' install '* ]]; then
  mkdir -p "$(dirname "$TEST_GLIBC_LOADER")"
  touch "$TEST_GLIBC_LOADER"
  chmod +x "$TEST_GLIBC_LOADER"
fi
for source_file in "$PREFIX/etc/apt/sources.list" "$PREFIX/etc/apt/sources.list.d/"*.list "$PREFIX/etc/apt/sources.list.d/"*.sources; do
  [ -f "$source_file" ] || continue
  if grep -Eiq 'freebuff-termux|codebuff-termux' "$source_file"; then
    echo "obsolete source still active: $source_file" >&2
    exit 93
  fi
done
APT
cat > "$MOCK_BIN/uname" <<'UNAME'
#!/usr/bin/env bash
[ "${1:-}" = "-m" ] && { echo aarch64; exit 0; }
exec /usr/bin/uname "$@"
UNAME
cat > "$MOCK_BIN/curl" <<'CURL'
#!/usr/bin/env bash
set -e
[ -z "${LD_LIBRARY_PATH:-}" ] || { echo 'LD_LIBRARY_PATH leaked to curl' >&2; exit 94; }
if [[ " $* " == *' --version '* ]]; then echo 'curl mock 1.0'; exit 0; fi
out=''
url=''
while [ "$#" -gt 0 ]; do
  if [ "$1" = '-o' ]; then out="$2"; shift 2
  else url="$1"; shift
  fi
done
case "$url" in
  */SHA256SUMS) printf '%s  opencode-linux-arm64.tar.gz\n' "$MOCK_SHA256" > "$out" ;;
  */opencode-linux-arm64.tar.gz) cp "$MOCK_ARCHIVE" "$out" ;;
  *) echo "unexpected mocked URL: $url" >&2; exit 95 ;;
esac
CURL
cat > "$MOCK_BIN/patchelf" <<'PATCHELF'
#!/usr/bin/env bash
set -e
[ -z "${LD_LIBRARY_PATH:-}" ] || { echo 'LD_LIBRARY_PATH leaked to patchelf' >&2; exit 96; }
case "${1:-}" in
  --version) echo 'patchelf mock 1.0' ;;
  --set-interpreter) printf '%s\n' "$2" > "$3.ocm-test-interpreter" ;;
  --print-interpreter) cat "$2.ocm-test-interpreter" ;;
  *) echo "unexpected patchelf arguments: $*" >&2; exit 97 ;;
esac
PATCHELF
cat > "$MOCK_BIN/termux-reload-settings" <<'RELOAD'
#!/usr/bin/env bash
exit 0
RELOAD
cat > "$MOCK_BIN/timeout" <<'TIMEOUT'
#!/usr/bin/env bash
[ "${1:-}" = "120" ] && shift
exec bash "$@"
TIMEOUT
chmod +x "$MOCK_BIN/"*

run_installer() {
  env PREFIX="$PREFIX" HOME="$HOME" PATH="$MOCK_BIN:$PATH" \
    MOCK_APT_LOG="$MOCK_APT_LOG" MOCK_ARCHIVE="$MOCK_ARCHIVE" MOCK_SHA256="$MOCK_SHA256" \
    TEST_GLIBC_LOADER="$TEST_GLIBC_LOADER" OCM_OPENCODE_VERSION=mock-test \
    LD_LIBRARY_PATH="$TMP/foreign-codex-libs" bash "$ROOT/scripts/termux-install.sh"
}

OUTPUT="$(run_installer 2>&1)"
printf '%s\n' "$OUTPUT" | grep -q 'Ignoring inherited LD_LIBRARY_PATH'
printf '%s\n' "$OUTPUT" | grep -q 'sha256 ok:'
printf '%s\n' "$OUTPUT" | grep -q 'OpenCode test build 2.3.0'
! grep -Eiq 'freebuff-termux|codebuff-termux' "$PREFIX/etc/apt/sources.list"
! grep -Eiq 'freebuff-termux|codebuff-termux' "$PREFIX/etc/apt/sources.list.d/extra.sources"
grep -q 'packages.termux.dev/apt/termux-main' "$PREFIX/etc/apt/sources.list"
grep -q 'packages.termux.dev/apt/termux-root' "$PREFIX/etc/apt/sources.list.d/extra.sources"
cmp -s "$TMP/sources.list.original" "$PREFIX/etc/apt/sources.list.ocm-backup-"*
cmp -s "$TMP/extra.sources.original" "$PREFIX/etc/apt/sources.list.d/extra.sources.ocm-backup-"*
grep -q '^other-setting=true$' "$HOME/.termux/termux.properties"
grep -q '^allow-external-apps=true$' "$HOME/.termux/termux.properties"
[ "$(grep -c '^update$' "$MOCK_APT_LOG")" -ge 1 ]

# Execute the generated launcher with a contaminated environment; the wrapped
# OpenCode binary must see the cleaned environment and report its version.
LAUNCHER_VERSION="$(env HOME="$HOME" LD_LIBRARY_PATH="$TMP/foreign-codex-libs" bash "$PREFIX/bin/opencode" --version)"
[ "$LAUNCHER_VERSION" = 'OpenCode test build 2.3.0' ]

# A full second install is idempotent and does not create extra backups.
OUTPUT_AGAIN="$(run_installer 2>&1)"
printf '%s\n' "$OUTPUT_AGAIN" | grep -q 'sha256 ok:'
! printf '%s\n' "$OUTPUT_AGAIN" | grep -q 'Disabled obsolete Termux repository entry'
[ "$(find "$PREFIX/etc/apt" -name '*.ocm-backup-*' | wc -l | tr -d ' ')" = 2 ]

printf 'PASS: installer cleaned LD_LIBRARY_PATH, backed up/removed only obsolete repos, kept valid sources, installed a checksum-verified test archive, generated a clean launcher, and reran safely.\n'
