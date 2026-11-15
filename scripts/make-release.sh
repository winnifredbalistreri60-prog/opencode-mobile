#!/usr/bin/env bash
# Build the OpenCode Mobile APK and prepare GitHub release assets.
# Publishes NOTHING: it only writes files into the output directory.
#
#   scripts/make-release.sh [--out DIR] [--notes FILE] [--download-base URL] [--apk PATH] [--skip-build]
#
# Output (DIR, default dist/v<version>/):
#   opencode-mobile-<version>.apk
#   opencode-mobile-<version>.apk.sha256
#   opencode-mobile-version.json   <- manifest read by the in-app updater
#
# Upload BOTH the APK and opencode-mobile-version.json to the GitHub release v<version>
# (mark it "latest"). The app reads
#   https://github.com/<repo>/releases/latest/download/opencode-mobile-version.json
# The keystore is read by Gradle (local.properties debug.keystore or OPENCODE_DEBUG_KEYSTORE);
# this script never prints or copies it.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
REPO="winnifredbalistreri60-prog/opencode-mobile"
EXPECTED_CERT="65b96eb07abe416771b1a8c96184d87641d2e1cf51954ec340d40d34b32d2a90"
OUT="" NOTES_FILE="" DOWNLOAD_BASE="" APK_IN="" SKIP_BUILD=0

while [ $# -gt 0 ]; do
  case "$1" in
    --out) OUT="$2"; shift 2 ;;
    --notes) NOTES_FILE="$2"; shift 2 ;;
    --download-base) DOWNLOAD_BASE="$2"; shift 2 ;;   # test servers only
    --apk) APK_IN="$2"; SKIP_BUILD=1; shift 2 ;;
    --skip-build) SKIP_BUILD=1; shift ;;
    -h|--help) sed -n '2,17p' "$0"; exit 0 ;;
    *) echo "unknown option: $1" >&2; exit 2 ;;
  esac
done

cd "$ROOT"
if [ "$SKIP_BUILD" = 0 ]; then
  ./gradlew --no-daemon :app:testDebugUnitTest :app:assembleDebug
fi
APK_SRC="${APK_IN:-app/build/outputs/apk/debug/app-debug.apk}"
[ -f "$APK_SRC" ] || { echo "APK not found: $APK_SRC" >&2; exit 1; }

SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$(sed -n 's/^sdk.dir=//p' local.properties 2>/dev/null)}}"
BT="$(ls -d "$SDK"/build-tools/* 2>/dev/null | sort -V | tail -1)"
if [ -n "${JAVA_HOME:-}" ]; then PATH="$JAVA_HOME/bin:$PATH"; fi
command -v java >/dev/null || { echo "java not found (set JAVA_HOME)" >&2; exit 1; }
[ -x "$BT/aapt2" ] && [ -x "$BT/apksigner" ] || { echo "Android build-tools not found (sdk: $SDK)" >&2; exit 1; }

BADGING_ALL="$("$BT/aapt2" dump badging "$APK_SRC")"
BADGING="${BADGING_ALL%%$'\n'*}"
PKG="$(sed -n "s/.*package: name='\([^']*\)'.*/\1/p" <<<"$BADGING")"
CODE="$(sed -n "s/.*versionCode='\([0-9]*\)'.*/\1/p" <<<"$BADGING")"
NAME="$(sed -n "s/.*versionName='\([^']*\)'.*/\1/p" <<<"$BADGING")"
MINSDK="$(sed -n "s/^minSdkVersion:'\([0-9]*\)'.*/\1/p" <<<"$BADGING_ALL")"
[ "$PKG" = "ru.dredd20.opencode" ] || { echo "unexpected package $PKG" >&2; exit 1; }

CERT="$("$BT/apksigner" verify --print-certs "$APK_SRC" | sed -n 's/^Signer #1 certificate SHA-256 digest: //p')"
if [ "$CERT" != "$EXPECTED_CERT" ]; then
  echo "Signing certificate ${CERT:0:8}… is not the release key ${EXPECTED_CERT:0:8}…. Refusing." >&2
  exit 1
fi
if [ "$(unzip -p "$APK_SRC" 'classes*.dex' 2>/dev/null | grep -ac "TEST http" || true)" != 0 ]; then
  echo "This APK was built with -PocmUpdateTestServer (test update server). Refusing." >&2
  [ -n "$DOWNLOAD_BASE" ] || exit 1
fi

OUT="${OUT:-dist/v$NAME}"
mkdir -p "$OUT"
ASSET="opencode-mobile-$NAME.apk"
cp "$APK_SRC" "$OUT/$ASSET"
SHA="$(sha256sum "$OUT/$ASSET" | cut -d' ' -f1)"
SIZE="$(stat -c %s "$OUT/$ASSET")"
echo "$SHA  $ASSET" > "$OUT/$ASSET.sha256"

BASE="${DOWNLOAD_BASE:-https://github.com/$REPO/releases/download/v$NAME}"
NOTES=""
if [ -n "$NOTES_FILE" ]; then NOTES="$(cat "$NOTES_FILE")"; fi

python3 - "$OUT/opencode-mobile-version.json" "$CODE" "$NAME" "${BASE%/}/$ASSET" "$SHA" "$SIZE" "${MINSDK:-26}" "$NOTES" <<'PY'
import json, sys
out, code, name, url, sha, size, minsdk, notes = sys.argv[1:9]
m = {"versionCode": int(code), "versionName": name, "url": url, "sha256": sha,
     "size": int(size), "minSdk": int(minsdk), "notes": notes[:4000]}
open(out, "w").write(json.dumps(m, ensure_ascii=False, indent=2) + "\n")
PY

echo "Prepared release v$NAME (versionCode $CODE) in $OUT:"
echo "  $ASSET  sha256 $SHA  size $SIZE"
echo "  opencode-mobile-version.json -> ${BASE%/}/$ASSET"
echo "Nothing was published. Upload both files to the GitHub release v$NAME and mark it latest."
