#!/bin/bash
# MIT. Tiny JSON-line client for the OpenCode Mobile Phone API.
set -euo pipefail
SOCK="${PHONE_SOCK:-/run/phone.sock}"
TOKEN_FILE="${PHONE_TOKEN_FILE:-/run/phone.token}"
CMD="$1"
shift || true

ARGS_JSON="$(python3 - "$CMD" "$@" << 'PY'
import json, sys
cmd = sys.argv[1]
argv = sys.argv[2:]
args = {}
i = 0
positional = []
while i < len(argv):
    a = argv[i]
    if a in ("-l", "--list"):
        args["list"] = True
        i += 1
    elif a in ("-s", "--sensor") and i + 1 < len(argv):
        args["sensor"] = argv[i+1]; i += 2
    elif a in ("-n", "--count") and i + 1 < len(argv):
        args["count"] = int(argv[i+1]); i += 2
    elif a in ("-d", "--delay") and i + 1 < len(argv):
        args["delay"] = int(argv[i+1]); i += 2
    elif a in ("-c", "--camera") and i + 1 < len(argv):
        args["camera"] = argv[i+1]; i += 2
    elif a in ("-f", "--file") and i + 1 < len(argv):
        args["file"] = argv[i+1]; i += 2
    elif a in ("-t", "--title") and i + 1 < len(argv):
        args["title"] = argv[i+1]; i += 2
    elif a in ("-e", "--exec"):
        args["exec"] = True; i += 1
    elif a in ("-b", "--baud") and i + 1 < len(argv):
        args["baud"] = int(argv[i+1]); i += 2
    elif a.startswith("-"):
        positional.append(a); i += 1
    else:
        positional.append(a); i += 1
if positional:
    args["argv"] = positional
    if cmd in ("toast", "tts-speak", "notification", "share", "open-url", "telephony-call", "clipboard-set") and "text" not in args:
        args["text"] = " ".join(positional)
    if cmd in ("torch",) and positional:
        v = positional[0].strip().lower()
        args["on"] = v in ("on", "1", "true", "yes", "enable", "enabled")
        if v in ("off", "0", "false", "no", "disable", "disabled"):
            args["on"] = False
    if cmd in ("volume",) and "stream" not in args and positional:
        args["stream"] = positional[0]
        if len(positional) > 1:
            try: args["volume"] = int(positional[1])
            except ValueError: pass
    if cmd in ("usb",) and positional:
        args["sub"] = positional[0]
        args["device"] = positional[1] if len(positional) > 1 else None
print(json.dumps(args))
PY
)"

ID="$(python3 -c 'import uuid; print(uuid.uuid4())')"
REQ="$(PHONE_TOKEN_FILE="$TOKEN_FILE" PHONE_CMD="$CMD" PHONE_ARGS_JSON="$ARGS_JSON" PHONE_ID="$ID" python3 - << 'REQPY'
import json, os
token = ""
path = os.environ.get("PHONE_TOKEN_FILE") or ""
if path:
    try:
        with open(path) as f:
            token = f.read().strip()
    except OSError:
        token = ""
print(json.dumps({
    "v": 1,
    "id": os.environ["PHONE_ID"],
    "token": token,
    "cmd": os.environ["PHONE_CMD"],
    "args": json.loads(os.environ.get("PHONE_ARGS_JSON") or "{}"),
}))
REQPY
)"

RESP="$(PHONE_REQ="$REQ" python3 - "$SOCK" << 'PY'
import json, os, socket, sys
path = sys.argv[1]
data = os.environ["PHONE_REQ"].encode()
abstract = b"\0ru.dredd20.opencode.phone"
try:
    s = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
    s.settimeout(120)
    connected = False
    if os.path.exists(path):
        try:
            s.connect(path)
            connected = True
        except OSError:
            s.close()
            s = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
            s.settimeout(120)
    if not connected:
        s.connect(abstract)
    s.sendall(data if data.endswith(b"\n") else data + b"\n")
    buf = b""
    while True:
        chunk = s.recv(65536)
        if not chunk:
            break
        buf += chunk
        if b"\n" in buf:
            break
    s.close()
    sys.stdout.write(buf.decode("utf-8", "replace"))
except Exception as e:
    # Always emit protocol JSON so the wrapper never prints a traceback.
    sys.stdout.write(json.dumps({"ok": False, "error": "phone api unreachable: %s" % e}) + "\n")
PY
)"

python3 -c '
import json, sys
raw = sys.argv[1].strip()
if not raw:
    print(json.dumps({"error": "empty response from phone API"}))
    sys.exit(2)
try:
    msg = json.loads(raw)
except json.JSONDecodeError:
    print(json.dumps({"error": "invalid response from phone API"}))
    sys.exit(2)
if not msg.get("ok"):
    out = {}
    perm = msg.get("needs_permission") or msg.get("needsPermission")
    err = msg.get("error") or "request failed"
    if perm or err == "permission_pending":
        out["error"] = "permission_pending"
        if perm:
            out["permission"] = perm
    else:
        out["error"] = err
    print(json.dumps(out, ensure_ascii=False))
    sys.exit(1)
data = msg.get("data")
if data is None:
    sys.exit(0)
if isinstance(data, (dict, list)):
    print(json.dumps(data, ensure_ascii=False, indent=2))
else:
    # Never print a bare permission string; keep JSON for consistency.
    print(json.dumps({"value": data}, ensure_ascii=False) if not isinstance(data, (int, float, bool)) else json.dumps(data))
' "$RESP"
