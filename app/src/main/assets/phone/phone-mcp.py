#!/usr/bin/env python3
"""OpenCode Mobile phone-mcp: stdio MCP server (MIT). Talks to /run/phone.sock."""
import json, os, socket, sys, uuid

SOCK = os.environ.get("PHONE_SOCK", "/run/phone.sock")
TOKEN_FILE = os.environ.get("PHONE_TOKEN_FILE", "/run/phone.token")

TOOLS = [
    ("battery_status", "Read battery level, status, temperature.", {}, None),
    ("sensor_list", "List available device sensors.", {}, None),
    ("sensor_read", "Read N samples from a named sensor.",
     {"sensor": {"type": "string"}, "count": {"type": "integer", "default": 1}}, ["sensor"]),
    ("location", "Get a single location fix (foreground).", {}, None),
    ("camera_photo", "Take a photo and return a saved file path.", {}, None),
    ("clipboard_get", "Read the clipboard (works when the app is visible).", {}, None),
    ("clipboard_set", "Set clipboard text.", {"text": {"type": "string"}}, ["text"]),
    ("notification", "Post a notification.", {"title": {"type": "string"}, "text": {"type": "string"}}, ["text"]),
    ("toast", "Show a short toast.", {"text": {"type": "string"}}, ["text"]),
    ("vibrate", "Vibrate the device.", {"duration_ms": {"type": "integer", "default": 200}}, None),
    ("torch", "Toggle the flashlight.", {"on": {"type": "boolean"}}, None),
    ("tts_speak", "Speak text with TTS.", {"text": {"type": "string"}}, ["text"]),
    ("volume", "Get or set a volume stream (music, ring, alarm, notification).",
     {"stream": {"type": "string"}, "volume": {"type": "integer"}}, None),
    ("wifi_info", "Current Wi-Fi connection info.", {}, None),
    ("share", "Share text or a URL via the system share sheet.", {"text": {"type": "string"}}, ["text"]),
    ("open_url", "Open a URL in the system browser.", {"url": {"type": "string"}}, ["url"]),
    ("dial", "Open the system dialer (ACTION_DIAL). Never places the call itself.",
     {"number": {"type": "string"}}, ["number"]),
    ("usb_list", "List attached USB devices.", {}, None),
    ("usb_serial_open", "Open a USB serial device.",
     {"device": {"type": "string"}, "baud": {"type": "integer", "default": 9600}}, ["device"]),
    ("usb_serial_write", "Write UTF-8 text to the open USB serial device.",
     {"text": {"type": "string"}}, ["text"]),
    ("usb_serial_read", "Read pending text from the open USB serial device.",
     {"timeout_ms": {"type": "integer", "default": 1000}}, None),
    ("usb_serial_close", "Close the USB serial device.", {}, None),
]

CMD_MAP = {
    "battery_status": "battery-status",
    "sensor_list": "sensor",
    "sensor_read": "sensor",
    "location": "location",
    "camera_photo": "camera-photo",
    "clipboard_get": "clipboard-get",
    "clipboard_set": "clipboard-set",
    "notification": "notification",
    "toast": "toast",
    "vibrate": "vibrate",
    "torch": "torch",
    "tts_speak": "tts-speak",
    "volume": "volume",
    "wifi_info": "wifi-connectioninfo",
    "share": "share",
    "open_url": "open-url",
    "dial": "telephony-call",
    "usb_list": "usb",
    "usb_serial_open": "usb",
    "usb_serial_write": "usb",
    "usb_serial_read": "usb",
    "usb_serial_close": "usb",
}

def tool_schema(name, desc, props, required):
    return {
        "name": name,
        "description": desc,
        "inputSchema": {"type": "object", "properties": props, "required": required or []},
    }

def token():
    try:
        with open(TOKEN_FILE) as f:
            return f.read().strip()
    except OSError:
        return ""

def call(cmd, args):
    req = {"v": 1, "id": str(uuid.uuid4()), "token": token(), "cmd": cmd, "args": args}
    data = (json.dumps(req) + "\n").encode()
    s = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
    s.settimeout(120)
    connected = False
    if os.path.exists(SOCK):
        try:
            s.connect(SOCK)
            connected = True
        except OSError:
            s.close()
            s = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
            s.settimeout(120)
    if not connected:
        s.connect(b"\0ru.dredd20.opencode.phone")
    s.sendall(data)
    buf = b""
    while True:
        chunk = s.recv(65536)
        if not chunk:
            break
        buf += chunk
        if b"\n" in buf:
            break
    s.close()
    return json.loads(buf.decode("utf-8", "replace"))

def read_msg():
    line = sys.stdin.readline()
    if not line:
        return None
    if line.lower().startswith("content-length:"):
        length = int(line.split(":")[1].strip())
        while True:
            h = sys.stdin.readline()
            if h in ("", "\r\n", "\n"):
                break
        body = sys.stdin.read(length)
        return json.loads(body)
    return json.loads(line)

def write_msg(msg):
    body = json.dumps(msg, ensure_ascii=False)
    sys.stdout.write("Content-Length: %d\r\n\r\n%s" % (len(body.encode()), body))
    sys.stdout.flush()

def handle(msg):
    mid = msg.get("id")
    method = msg.get("method")
    params = msg.get("params") or {}
    if method == "initialize":
        return {"jsonrpc": "2.0", "id": mid, "result": {
            "protocolVersion": params.get("protocolVersion") or "2024-11-05",
            "capabilities": {"tools": {}},
            "serverInfo": {"name": "phone-mcp", "version": "0.1.0"},
        }}
    if method == "notifications/initialized":
        return None
    if method == "tools/list":
        tools = [tool_schema(n, d, p, r) for (n, d, p, r) in TOOLS]
        return {"jsonrpc": "2.0", "id": mid, "result": {"tools": tools}}
    if method == "tools/call":
        name = params.get("name")
        arguments = params.get("arguments") or {}
        cmd = CMD_MAP.get(name)
        if not cmd:
            return {"jsonrpc": "2.0", "id": mid, "error": {"code": -32601, "message": "unknown tool %s" % name}}
        args = dict(arguments)
        if name == "sensor_list":
            args["list"] = True
        if name == "usb_list":
            args["sub"] = "list"
        if name == "usb_serial_open":
            args["sub"] = "open"
        if name == "usb_serial_write":
            args["sub"] = "write"
        if name == "usb_serial_read":
            args["sub"] = "read"
        if name == "usb_serial_close":
            args["sub"] = "close"
        if name == "open_url" and "url" in args and "text" not in args:
            args["text"] = args["url"]
        if name == "dial" and "number" in args and "text" not in args:
            args["text"] = args["number"]
        try:
            resp = call(cmd, args)
        except Exception as e:
            return {"jsonrpc": "2.0", "id": mid, "result": {
                "content": [{"type": "text", "text": "phone API error: %s" % e}],
                "isError": True,
            }}
        if not resp.get("ok"):
            return {"jsonrpc": "2.0", "id": mid, "result": {
                "content": [{"type": "text", "text": resp.get("error") or "failed"}],
                "isError": True,
            }}
        data = resp.get("data")
        text = data if isinstance(data, str) else json.dumps(data, ensure_ascii=False, indent=2)
        return {"jsonrpc": "2.0", "id": mid, "result": {"content": [{"type": "text", "text": text or "ok"}]}}
    if mid is not None:
        return {"jsonrpc": "2.0", "id": mid, "error": {"code": -32601, "message": "unknown method %s" % method}}
    return None

def main():
    while True:
        try:
            msg = read_msg()
        except Exception as e:
            sys.stderr.write("bad mcp frame: %s\n" % e)
            sys.stderr.flush()
            continue
        if msg is None:
            break
        reply = handle(msg)
        if reply is not None:
            write_msg(reply)

if __name__ == "__main__":
    main()
