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
    ("usb_permission", "Ask the user to grant USB Host permission for a device.",
     {"device": {"type": "string"}}, ["device"]),
    ("usb_hid_read", "Read a HID report from a USB device.",
     {"device": {"type": "string"}, "length": {"type": "integer", "default": 64}, "timeout_ms": {"type": "integer", "default": 1000}, "report_id": {"type": "integer"}}, None),
    ("usb_hid_write", "Write a HID report (text or hex) to a USB device.",
     {"device": {"type": "string"}, "hex": {"type": "string"}, "text": {"type": "string"}, "report_id": {"type": "integer"}, "timeout_ms": {"type": "integer"}}, None),
    ("usb_transfer", "Raw USB bulk, interrupt or control transfer.",
     {"device": {"type": "string"}, "type": {"type": "string"}, "direction": {"type": "string"}, "endpoint": {"type": "integer"}, "interface": {"type": "integer"}, "hex": {"type": "string"}, "length": {"type": "integer"}, "timeout_ms": {"type": "integer"}, "request": {"type": "integer"}, "value": {"type": "integer"}, "index": {"type": "integer"}, "request_type": {"type": "integer"}}, ["type", "direction"]),
    ("hw_session_list", "List open hardware sessions.", {}, None),
    ("hw_session_open", "Open a session over usb-serial, bt-spp or tcp. Logs I/O to a file.",
     {"transport": {"type": "string"}, "target": {"type": "string"}, "device": {"type": "string"}, "baud": {"type": "integer"}, "session": {"type": "string"}}, ["transport"]),
    ("hw_session_send", "Send text or hex to an open hardware session.",
     {"session": {"type": "string"}, "text": {"type": "string"}, "hex": {"type": "string"}}, ["session"]),
    ("hw_session_read", "Read from an open hardware session with a timeout.",
     {"session": {"type": "string"}, "timeout_ms": {"type": "integer", "default": 1000}}, ["session"]),
    ("hw_session_expect", "Read until a regex or literal pattern matches, or until timeout.",
     {"session": {"type": "string"}, "pattern": {"type": "string"}, "timeout_ms": {"type": "integer", "default": 5000}, "literal": {"type": "boolean"}}, ["session", "pattern"]),
    ("hw_session_log", "Return the session log file path and recent text.",
     {"session": {"type": "string"}}, ["session"]),
    ("hw_session_close", "Close a hardware session.",
     {"session": {"type": "string"}}, ["session"]),
    ("bluetooth_scan", "Scan for Bluetooth LE devices (optional classic discovery).",
     {"timeout_ms": {"type": "integer", "default": 8000}, "classic": {"type": "boolean"}}, None),
    ("bluetooth_connect", "Connect to a BLE device and discover GATT services.",
     {"address": {"type": "string"}, "timeout_ms": {"type": "integer"}}, ["address"]),
    ("bluetooth_services", "List GATT services and characteristics of the connected BLE device.", {}, None),
    ("bluetooth_read", "Read a GATT characteristic.",
     {"uuid": {"type": "string"}, "timeout_ms": {"type": "integer"}}, ["uuid"]),
    ("bluetooth_write", "Write a GATT characteristic (text or hex).",
     {"uuid": {"type": "string"}, "text": {"type": "string"}, "hex": {"type": "string"}, "timeout_ms": {"type": "integer"}}, ["uuid"]),
    ("bluetooth_notify", "Start, stop or wait for GATT notifications. action=start|stop|wait.",
     {"uuid": {"type": "string"}, "action": {"type": "string"}, "timeout_ms": {"type": "integer"}}, ["uuid"]),
    ("bluetooth_disconnect", "Disconnect the active BLE connection.", {}, None),
    ("bluetooth_spp_connect", "Connect to a classic Bluetooth SPP (RFCOMM) device.",
     {"address": {"type": "string"}, "uuid": {"type": "string"}, "timeout_ms": {"type": "integer"}}, ["address"]),
    ("bluetooth_spp_send", "Send text or hex over the open SPP socket.",
     {"text": {"type": "string"}, "hex": {"type": "string"}}, None),
    ("bluetooth_spp_read", "Read from the open SPP socket.",
     {"timeout_ms": {"type": "integer", "default": 1000}}, None),
    ("bluetooth_spp_close", "Close the SPP socket.", {}, None),
    ("wifi_scan", "Scan nearby Wi-Fi networks. Android may throttle scans (about 4 per 2 minutes).",
     {"timeout_ms": {"type": "integer"}}, None),
    ("wifi_connect", "Ask the user to join a Wi-Fi network (WifiNetworkSpecifier, Android 10+).",
     {"ssid": {"type": "string"}, "password": {"type": "string"}, "suggestion": {"type": "boolean"}, "timeout_ms": {"type": "integer"}}, ["ssid"]),
    ("net_ping", "Ping a host (ICMP if allowed, otherwise InetAddress.isReachable).",
     {"host": {"type": "string"}, "count": {"type": "integer"}, "timeout_ms": {"type": "integer"}}, ["host"]),
    ("net_dns", "Resolve a host name to IP addresses.",
     {"host": {"type": "string"}}, ["host"]),
    ("net_port", "Check whether a TCP port is open.",
     {"host": {"type": "string"}, "port": {"type": "integer"}, "timeout_ms": {"type": "integer"}}, ["host", "port"]),
    ("net_mdns", "Discover services on the LAN with mDNS / NsdManager.",
     {"type": {"type": "string"}, "timeout_ms": {"type": "integer"}}, None),
    ("net_tcp", "Open a TCP connection, optionally send data, and read a response.",
     {"host": {"type": "string"}, "port": {"type": "integer"}, "text": {"type": "string"}, "hex": {"type": "string"}, "timeout_ms": {"type": "integer"}}, ["host", "port"]),
    ("net_udp", "Send a UDP datagram and wait for a response.",
     {"host": {"type": "string"}, "port": {"type": "integer"}, "text": {"type": "string"}, "hex": {"type": "string"}, "timeout_ms": {"type": "integer"}, "listen_port": {"type": "integer"}}, ["host", "port"]),
    ("nfc_info", "Wait for an NFC tag in the foreground and return id and technologies.",
     {"timeout_ms": {"type": "integer", "default": 15000}}, None),
    ("nfc_read", "Wait for an NFC tag and read NDEF records (app must be on screen).",
     {"timeout_ms": {"type": "integer", "default": 15000}}, None),
    ("nfc_write", "Wait for a tag and write an NDEF text, URI or hex payload. Asks the user to confirm.",
     {"text": {"type": "string"}, "uri": {"type": "string"}, "hex": {"type": "string"}, "timeout_ms": {"type": "integer"}}, None),
    ("nfc_transceive", "Wait for a tag and send a raw ISO-DEP or NfcA command (hex or text).",
     {"hex": {"type": "string"}, "text": {"type": "string"}, "tech": {"type": "string"}, "timeout_ms": {"type": "integer"}}, None),
    ("camera_capture", "Capture one JPEG frame with CameraX. Starts a notified capture service if needed.",
     {"resolution": {"type": "string"}, "dir": {"type": "string"}}, None),
    ("camera_start", "Start continuous CameraX capture with a visible notification. interval_ms, resolution, ring buffer.",
     {"interval_ms": {"type": "integer", "default": 500}, "resolution": {"type": "string"}, "ring": {"type": "integer", "default": 8}, "dir": {"type": "string"}}, None),
    ("camera_stop", "Stop continuous camera capture.", {}, None),
    ("camera_latest", "Return the path of the latest captured JPEG frame.", {}, None),
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
    "usb_permission": "usb",
    "usb_hid_read": "usb",
    "usb_hid_write": "usb",
    "usb_transfer": "usb",
    "hw_session_list": "hw-session",
    "hw_session_open": "hw-session",
    "hw_session_send": "hw-session",
    "hw_session_read": "hw-session",
    "hw_session_expect": "hw-session",
    "hw_session_log": "hw-session",
    "hw_session_close": "hw-session",
    "bluetooth_scan": "bluetooth",
    "bluetooth_connect": "bluetooth",
    "bluetooth_services": "bluetooth",
    "bluetooth_read": "bluetooth",
    "bluetooth_write": "bluetooth",
    "bluetooth_notify": "bluetooth",
    "bluetooth_disconnect": "bluetooth",
    "bluetooth_spp_connect": "bluetooth",
    "bluetooth_spp_send": "bluetooth",
    "bluetooth_spp_read": "bluetooth",
    "bluetooth_spp_close": "bluetooth",
    "wifi_scan": "wifi-scaninfo",
    "wifi_connect": "wifi-connect",
    "net_ping": "net",
    "net_dns": "net",
    "net_port": "net",
    "net_mdns": "net",
    "net_tcp": "net",
    "net_udp": "net",
    "nfc_info": "nfc",
    "nfc_read": "nfc",
    "nfc_write": "nfc",
    "nfc_transceive": "nfc",
    "camera_capture": "camera-capture",
    "camera_start": "camera-start",
    "camera_stop": "camera-stop",
    "camera_latest": "camera-latest",
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
    # OpenCode MCP stdio is JSON-RPC one object per line (not LSP Content-Length).
    sys.stdout.write(json.dumps(msg, ensure_ascii=False) + chr(10))
    sys.stdout.flush()

def handle(msg):
    mid = msg.get("id")
    method = msg.get("method")
    params = msg.get("params") or {}
    if method == "initialize":
        return {"jsonrpc": "2.0", "id": mid, "result": {
            "protocolVersion": params.get("protocolVersion") or "2024-11-05",
            "capabilities": {"tools": {}},
            "serverInfo": {"name": "phone-mcp", "version": "0.3.0"},
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
        usb_subs = {
            "usb_permission": "permission",
            "usb_hid_read": "hid-read",
            "usb_hid_write": "hid-write",
            "usb_transfer": "transfer",
        }
        if name in usb_subs:
            args["sub"] = usb_subs[name]
        hw_subs = {
            "hw_session_list": "list",
            "hw_session_open": "open",
            "hw_session_send": "send",
            "hw_session_read": "read",
            "hw_session_expect": "expect",
            "hw_session_log": "log",
            "hw_session_close": "close",
        }
        if name in hw_subs:
            args["sub"] = hw_subs[name]
        bt_subs = {
            "bluetooth_scan": "scan",
            "bluetooth_connect": "connect",
            "bluetooth_services": "services",
            "bluetooth_read": "read",
            "bluetooth_write": "write",
            "bluetooth_notify": "notify",
            "bluetooth_disconnect": "disconnect",
            "bluetooth_spp_connect": "spp-connect",
            "bluetooth_spp_send": "spp-send",
            "bluetooth_spp_read": "spp-read",
            "bluetooth_spp_close": "spp-close",
        }
        if name in bt_subs:
            args["sub"] = bt_subs[name]
        net_subs = {
            "net_ping": "ping",
            "net_dns": "dns",
            "net_port": "port",
            "net_mdns": "mdns",
            "net_tcp": "tcp",
            "net_udp": "udp",
        }
        if name in net_subs:
            args["sub"] = net_subs[name]
        nfc_subs = {
            "nfc_info": "info",
            "nfc_read": "read",
            "nfc_write": "write",
            "nfc_transceive": "transceive",
        }
        if name in nfc_subs:
            args["sub"] = nfc_subs[name]
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
