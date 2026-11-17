#!/data/data/com.termux/files/usr/bin/bash
# OpenCode Mobile: install the official OpenCode CLI into Termux (MIT).
#
# Why this is needed: Termux uses Android's Bionic libc, the official OpenCode Linux build is
# linked against glibc, and npm refuses "os: android". So we:
#   1. install Termux's own glibc (glibc-repo + glibc-runner) and patchelf,
#   2. download the official opencode-linux-<arch>.tar.gz from GitHub and check its sha256,
#   3. patch ONLY the ELF interpreter (no RPATH, it would corrupt the bundled payload),
#   4. add $PREFIX/bin/opencode, a launcher that clears LD_PRELOAD (termux-exec is a Bionic library)
#      and execs the binary directly (not through grun/ld.so, so /proc/self/exe stays opencode),
#   5. set allow-external-apps=true so OpenCode Mobile can start "opencode serve" via RUN_COMMAND.
# Re-running is safe; it also updates the binary. Env: OCM_OPENCODE_VERSION (default 2.3.0),
# OCM_SKIP_ALLOW_EXTERNAL=1 to leave termux.properties alone.
set -euo pipefail

OC_VERSION="${OCM_OPENCODE_VERSION:-2.3.0}"
LIB_DIR="$HOME/.local/lib/opencode"
LAUNCHER="${PREFIX:-/data/data/com.termux/files/usr}/bin/opencode"
MARK="OpenCode Mobile launcher"

say() { printf '\n==> %s\n' "$*"; }
die() { printf '\nERROR: %s\n' "$*" >&2; exit 1; }

# A third-party runtime can export its own libc++ directory here. Termux's apt,
# ffmpeg and other Bionic tools must not load libraries from that foreign path.
if [ -n "${LD_LIBRARY_PATH:-}" ]; then
  say "Ignoring inherited LD_LIBRARY_PATH for Termux package tools"
  unset LD_LIBRARY_PATH
fi

if [ -z "${PREFIX:-}" ] || [ ! -d "$PREFIX/bin" ] || ! command -v pkg >/dev/null 2>&1; then
  die "run this inside the Termux app (F-Droid or GitHub build)."
fi

# Remove only the two obsolete third-party sources known to return 404. Save an
# exact copy first; preserve all unrelated one-line sources and deb822 stanzas.
disable_obsolete_sources() {
  local apt_dir="$PREFIX/etc/apt"
  local source_file temp backup stamp
  local -a source_files=()
  shopt -s nullglob
  source_files=("$apt_dir/sources.list" "$apt_dir/sources.list.d/"*.list "$apt_dir/sources.list.d/"*.sources)
  shopt -u nullglob
  stamp="$(date +%Y%m%d%H%M%S)"
  for source_file in "${source_files[@]}"; do
    [ -f "$source_file" ] || continue
    grep -Eiq 'freebuff-termux|codebuff-termux' "$source_file" || continue
    temp="$(mktemp "${source_file}.ocm.XXXXXX")" || die "cannot prepare $source_file"
    if [[ "$source_file" == *.sources ]]; then
      awk 'BEGIN { RS=""; ORS="\n\n" } tolower($0) !~ /(freebuff-termux|codebuff-termux)/ && length($0) { print }' "$source_file" > "$temp"
    else
      awk 'tolower($0) !~ /(freebuff-termux|codebuff-termux)/ { print }' "$source_file" > "$temp"
    fi
    if cmp -s "$source_file" "$temp"; then
      rm -f "$temp"
      continue
    fi
    backup="$source_file.ocm-backup-$stamp"
    cp -p "$source_file" "$backup" || { rm -f "$temp"; die "cannot back up $source_file"; }
    cat "$temp" > "$source_file" || { rm -f "$temp"; die "cannot update $source_file (original saved as $backup)"; }
    rm -f "$temp"
    say "Disabled obsolete Termux repository entry; backup: $backup"
  done
}
disable_obsolete_sources

case "$(uname -m)" in
  aarch64|arm64)
    ASSET="opencode-linux-arm64.tar.gz"; LOADER="ld-linux-aarch64.so.1"
    PINNED="3207fb5cdec448d479242c0f1a14f4f7bdfcad3a79209b5eb8f9003725e93711" ;;
  x86_64)
    ASSET="opencode-linux-x64.tar.gz"; LOADER="ld-linux-x86-64.so.2"
    PINNED="fc0f81522aceeabac47693d88389efa2bc517ce74c2c38fd6967819fcc6e4d3b" ;;
  *) die "unsupported CPU $(uname -m): official OpenCode is built for arm64 and x86_64 only." ;;
esac
[ "$OC_VERSION" = "2.3.0" ] || PINNED=""

APT_OPTS=(-y -o Dpkg::Options::=--force-confdef -o Dpkg::Options::=--force-confold)
export DEBIAN_FRONTEND=noninteractive

GLIBC_LOADER="$PREFIX/glibc/lib/$LOADER"
# "command -v" is not enough: on a fresh Termux bootstrap a new patchelf can fail
# with "CANNOT LINK EXECUTABLE ... _ZNSt6__ndk113__hash_memory" because libc++ is older.
tools_ok() {
  [ -x "$GLIBC_LOADER" ] && patchelf --version >/dev/null 2>&1 && curl --version >/dev/null 2>&1
}
if tools_ok; then
  say "glibc, patchelf and curl are already installed"
else
  say "Installing glibc-repo (Termux glibc packages)"
  apt-get update || pkg update
  apt-get install "${APT_OPTS[@]}" glibc-repo
  say "Installing glibc-runner, patchelf, curl (and current libc++)"
  apt-get update
  apt-get install "${APT_OPTS[@]}" glibc-runner patchelf curl tar coreutils libc++
  if ! tools_ok; then
    say "patchelf/curl still do not start: upgrading Termux packages (pkg upgrade)"
    apt-get "${APT_OPTS[@]}" full-upgrade
  fi
  tools_ok || die "patchelf or curl does not start even after pkg upgrade; run 'pkg upgrade' in Termux and retry."
fi
[ -x "$GLIBC_LOADER" ] || die "glibc loader not found at $GLIBC_LOADER (pkg install glibc-repo glibc-runner)."

TMP="$(mktemp -d "${TMPDIR:-$PREFIX/tmp}/ocm-install.XXXXXX")"
trap 'rm -rf "$TMP"' EXIT
BASE="https://github.com/anomalyco/opencode/releases/download/v$OC_VERSION"

say "Downloading OpenCode $OC_VERSION ($ASSET, about 120 MB)"
curl -fL --retry 3 --retry-delay 3 -o "$TMP/$ASSET" "$BASE/$ASSET"
EXPECTED="$PINNED"
if [ -z "$EXPECTED" ]; then
  curl -fsSL --retry 3 -o "$TMP/SHA256SUMS" "$BASE/SHA256SUMS" || die "cannot download SHA256SUMS for v$OC_VERSION"
  EXPECTED="$(awk -v f="$ASSET" '$2==f || $2=="*"f {print $1}' "$TMP/SHA256SUMS" | head -n1)"
  [ -n "$EXPECTED" ] || die "$ASSET is not listed in SHA256SUMS"
fi
ACTUAL="$(sha256sum "$TMP/$ASSET" | cut -d' ' -f1)"
[ "$ACTUAL" = "$EXPECTED" ] || die "sha256 mismatch for $ASSET: got $ACTUAL, expected $EXPECTED"
echo "sha256 ok: $ACTUAL"

say "Unpacking to $LIB_DIR"
rm -rf "$LIB_DIR.new"
mkdir -p "$LIB_DIR.new"
tar -xzf "$TMP/$ASSET" -C "$LIB_DIR.new"
[ -f "$LIB_DIR.new/opencode" ] || die "archive has no opencode binary"
say "Patching the ELF interpreter only -> $GLIBC_LOADER"
patchelf --set-interpreter "$GLIBC_LOADER" "$LIB_DIR.new/opencode"
[ "$(patchelf --print-interpreter "$LIB_DIR.new/opencode")" = "$GLIBC_LOADER" ] || die "patchelf did not set the interpreter"
chmod 755 "$LIB_DIR.new/opencode"
rm -rf "$LIB_DIR.old"
if [ -d "$LIB_DIR" ]; then mv "$LIB_DIR" "$LIB_DIR.old"; fi
mv "$LIB_DIR.new" "$LIB_DIR"
rm -rf "$LIB_DIR.old"

say "Creating the launcher $LAUNCHER"
if [ -e "$LAUNCHER" ] && ! grep -q "$MARK" "$LAUNCHER" 2>/dev/null; then
  BAK="$LAUNCHER.bak-$(date +%Y%m%d%H%M%S)"
  mv "$LAUNCHER" "$BAK"
  echo "previous $LAUNCHER saved as $BAK"
fi
cat > "$LAUNCHER" <<'LAUNCHER_EOF'
#!/data/data/com.termux/files/usr/bin/bash
# OpenCode Mobile launcher: runs the glibc build of OpenCode in Termux.
# Termux's LD_PRELOAD is a Bionic library, and third-party LD_LIBRARY_PATH values
# can inject incompatible libc++ libraries into this glibc binary.
unset LD_PRELOAD LD_LIBRARY_PATH
exec "${HOME:-/data/data/com.termux/files/home}/.local/lib/opencode/opencode" "$@"
LAUNCHER_EOF
chmod 755 "$LAUNCHER"

if [ "${OCM_SKIP_ALLOW_EXTERNAL:-0}" != "1" ]; then
  say "Allowing OpenCode Mobile to run commands in Termux (allow-external-apps=true)"
  PROPS="$HOME/.termux/termux.properties"
  mkdir -p "$HOME/.termux"
  touch "$PROPS"
  if ! grep -Eq '^[[:space:]]*allow-external-apps[[:space:]]*=[[:space:]]*true[[:space:]]*$' "$PROPS"; then
    sed -i -E '/^[[:space:]]*allow-external-apps[[:space:]]*=/d' "$PROPS"
    echo "allow-external-apps=true" >> "$PROPS"
  fi
  termux-reload-settings >/dev/null 2>&1 || true
fi

say "Checking: opencode --version"
set +e
VER="$(timeout 120 "$LAUNCHER" --version 2>&1)"; RC=$?
set -e
if [ "$RC" -ne 0 ]; then
  if [ "$RC" -eq 159 ]; then
    die "opencode --version was killed by SIGSYS (exit 159): Android's seccomp filter blocks a system call
used by the glibc loader. This is known on x86_64 Android (emulators, some Chromebooks); OpenCode in
Termux is supported on arm64 phones. Use the built-in mode of OpenCode Mobile instead.
$VER"
  fi
  die "opencode --version failed (exit $RC):
$VER"
fi
echo "$VER"

cat <<'DONE'

Done. OpenCode is installed in Termux.
Next: open OpenCode Mobile -> App settings -> Mode: Termux, allow "Run commands in Termux",
then tap Start.
Uninstall: rm -rf ~/.local/lib/opencode $PREFIX/bin/opencode

Готово. OpenCode установлен в Termux.
Дальше: OpenCode Mobile -> Настройки приложения -> Режим: Termux, разрешите «запускать команды
в Termux» и нажмите «Запустить».
DONE
