#!/usr/bin/env python3
"""A tiny independent implementation of the Syncora *player* side, used to test the Android *speaker* role end to end.

usage: virtual_player.py <port> <report-file> <duration-seconds> [required,commands]

It serves /hello, /art and /stream on <port>, answers clock probes on a UDP port, streams a stereo tone timeline with
play times on its own monotonic clock, mirrors player state, reacts to commands from the speaker and finally checks what
the speaker reported back. Exits non-zero if a check fails.
"""
import io
import json
import math
import select
import socket
import socketserver
import struct
import sys
import threading
import time

PORT = int(sys.argv[1])
REPORT = sys.argv[2]
DURATION = float(sys.argv[3])
REQUIRED = [c for c in (sys.argv[4].split(",") if len(sys.argv) > 4 else []) if c]

CODE = "SYNC-CIPLAY"
NAME = "CI Player"
RATE = 44100
CHUNK_FRAMES = 1764  # 40 ms
CHUNK_NS = CHUNK_FRAMES * 1_000_000_000 // RATE
LOOKAHEAD_NS = 450_000_000
T_WELCOME, T_STATE, T_AUDIO, T_FLUSH, T_SET, T_PING, T_BYE = 1, 2, 3, 4, 5, 6, 7
T_HELLO, T_CLIENT_STATE, T_COMMAND = 16, 17, 18

TRACKS = [
    ("Virtual Sunrise", "Syncora CI", "Test Album", (330.0, 440.0), 180_000),
    ("Neon Echoes", "Syncora CI", "Test Album", (262.0, 392.0), 200_000),
    ("Golden Static", "Syncora CI", "Test Album", (196.0, 294.0), 210_000),
]

lock = threading.RLock()
log_lines = []
checks = []


def now_ns():
    return time.monotonic_ns()


def log(msg):
    line = "[%6.1fs] %s" % (time.time() - START_WALL, msg)
    print(line, flush=True)
    log_lines.append(line)


def check(ok, what):
    checks.append((ok, what))
    log(("PASS  " if ok else "FAIL  ") + what)


START_WALL = time.time()


class Client:
    def __init__(self, sock, cid, name, address):
        self.sock = sock
        self.id = cid
        self.name = name
        self.address = address
        self.outq = []
        self.info = {"volume": 1.0, "muted": False, "channel": "stereo", "offsetMs": 0, "syncErrMs": 0, "status": "connecting"}
        self.history = []  # (wall, syncErrMs, status, volume, channel, offsetMs)
        self.hello = None
        self.closed = False
        self.connected_at = time.time()


class Player:
    def __init__(self):
        self.epoch = 1
        self.playing = True
        self.index = 0
        self.paused_ms = 0
        self.anchor_ms = 0
        self.anchor_ns = 0
        self.chunk = 0
        self.seq = 0
        self.timeline_start = 0
        self.media_start_ms = 0
        self.shuffle = False
        self.repeat = "off"
        self.clients = {}
        self.seen = []
        self.state_dirty = True
        self.commands = []
        self.udp_probes = 0
        self.last_state_sent = 0
        self.restart(0, flush=False)

    # ---- timeline
    def restart(self, position_ms, flush=True):
        with lock:
            if flush:
                self.epoch += 1
                self.broadcast(self.frame(T_FLUSH, struct.pack("<i", self.epoch)))
            self.media_start_ms = position_ms
            self.timeline_start = now_ns() + LOOKAHEAD_NS
            self.chunk = 0
            self.anchor_ms = position_ms
            self.anchor_ns = self.timeline_start
            self.state_dirty = True

    def position_ms(self):
        if not self.playing:
            return self.paused_ms
        return self.anchor_ms + max(0, (now_ns() - self.anchor_ns) // 1_000_000)

    # ---- frames
    @staticmethod
    def frame(ftype, payload):
        return bytes([ftype]) + struct.pack("<i", len(payload)) + payload

    def json_frame(self, ftype, obj):
        return self.frame(ftype, json.dumps(obj).encode())

    def broadcast(self, data):
        with lock:
            for c in self.clients.values():
                c.outq.append(data)

    def audio_frame(self):
        left, right = TRACKS[self.index][3]
        base = self.media_start_ms / 1000.0 + self.chunk * CHUNK_FRAMES / RATE
        samples = []
        for i in range(CHUNK_FRAMES):
            t = base + i / RATE
            trem = 0.75 + 0.25 * math.sin(2 * math.pi * 2.0 * t)
            samples.append(int(9000 * trem * math.sin(2 * math.pi * left * t)))
            samples.append(int(9000 * trem * math.sin(2 * math.pi * right * t)))
        play_at = self.timeline_start + self.chunk * CHUNK_NS
        header = struct.pack("<iiqiBi", self.epoch, self.seq, play_at, RATE, 2, CHUNK_FRAMES)
        self.seq += 1
        self.chunk += 1
        return self.frame(T_AUDIO, header + struct.pack("<%dh" % len(samples), *samples))

    def pump_audio(self):
        with lock:
            if not self.playing or not self.clients:
                return
            while now_ns() >= self.timeline_start + self.chunk * CHUNK_NS - LOOKAHEAD_NS:
                self.broadcast(self.audio_frame())

    def devices(self):
        out = [{"id": "ci-player", "name": NAME, "addr": "10.0.2.2", "host": True, "volume": 1.0, "muted": False,
                "channel": "stereo", "offsetMs": 0, "syncErrMs": 0, "status": "synced"}]
        for c in self.clients.values():
            d = {"id": c.id, "name": c.name, "addr": c.address, "host": False}
            d.update(c.info)
            out.append(d)
        return out

    def state(self):
        title, artist, album, _, duration = TRACKS[self.index]
        up = [{"i": i, "t": TRACKS[i][0], "a": TRACKS[i][1]} for i in range(self.index + 1, len(TRACKS))]
        return {"v": 2, "source": "music", "playing": self.playing, "hasMedia": True, "anchorMs": self.anchor_ms,
                "anchorNanos": self.anchor_ns, "pausedMs": self.paused_ms, "hostNanos": now_ns(), "durationMs": duration,
                "live": False, "title": title, "subtitle": artist + " \u00B7 " + album, "album": album,
                "artKey": "tvirtual%d" % self.index, "artReady": True, "shuffle": self.shuffle, "repeat": self.repeat,
                "canSeek": True, "canSkip": True, "queueIndex": self.index, "queueSize": len(TRACKS),
                "audioMode": "balanced", "lookaheadMs": LOOKAHEAD_NS // 1_000_000, "hostName": NAME, "roomCode": CODE,
                "station": "", "stationStatus": "", "upNext": up, "devices": self.devices()}

    def maybe_send_state(self, force=False):
        with lock:
            if force or self.state_dirty or time.time() - self.last_state_sent > 1.0:
                self.state_dirty = False
                self.last_state_sent = time.time()
                self.broadcast(self.json_frame(T_STATE, self.state()))

    # ---- commands from speakers
    def command(self, client, cmd):
        name = cmd.get("cmd", "")
        with lock:
            self.commands.append(name)
            log("command from %s: %s" % (client.name, cmd))
            if name in ("toggle", "pause", "play"):
                if name == "toggle":
                    name = "pause" if self.playing else "play"
                if name == "pause" and self.playing:
                    self.paused_ms = self.position_ms()
                    self.playing = False
                    self.epoch += 1
                    self.broadcast(self.frame(T_FLUSH, struct.pack("<i", self.epoch)))
                    self.state_dirty = True
                elif name == "play" and not self.playing:
                    self.playing = True
                    self.restart(self.paused_ms)
            elif name == "seek":
                self.paused_ms = int(cmd.get("ms", 0))
                if self.playing:
                    self.restart(self.paused_ms)
                self.state_dirty = True
            elif name == "next":
                self.index = (self.index + 1) % len(TRACKS)
                self.paused_ms = 0
                self.playing = True
                self.restart(0)
            elif name == "shuffle":
                self.shuffle = bool(cmd.get("on", False))
                self.state_dirty = True
            elif name == "repeat":
                self.repeat = cmd.get("mode", "off")
                self.state_dirty = True
            elif name == "setDevice":
                target = self.clients.get(cmd.get("id", ""))
                fields = {k: cmd[k] for k in ("volume", "muted", "channel", "offsetMs") if k in cmd}
                if target is not None and fields:
                    target.outq.append(self.json_frame(T_SET, fields))
                    target.info.update(fields)
                    self.state_dirty = True


P = Player()


def make_art(index):
    from PIL import Image, ImageDraw
    palettes = [((255, 66, 120), (40, 120, 255)), ((255, 170, 0), (190, 0, 160)), ((0, 200, 150), (20, 40, 120))]
    c1, c2 = palettes[index % len(palettes)]
    size = 512
    img = Image.new("RGB", (size, size))
    px = img.load()
    for y in range(size):
        for x in range(size):
            t = (x + y) / (2.0 * size)
            px[x, y] = (int(c1[0] * (1 - t) + c2[0] * t), int(c1[1] * (1 - t) + c2[1] * t), int(c1[2] * (1 - t) + c2[2] * t))
    d = ImageDraw.Draw(img)
    for r, shade in ((210, (15, 15, 20)), (170, (35, 35, 45)), (120, (15, 15, 20)), (80, (35, 35, 45)), (30, (240, 240, 245))):
        d.ellipse((size // 2 - r, size // 2 - r, size // 2 + r, size // 2 + r), fill=shade)
    buf = io.BytesIO()
    img.save(buf, "JPEG", quality=88)
    return buf.getvalue()


ART = {}


class Handler(socketserver.BaseRequestHandler):
    def read_request(self):
        data = b""
        self.request.settimeout(5)
        while b"\r\n\r\n" not in data:
            chunk = self.request.recv(1024)
            if not chunk:
                return None
            data += chunk
            if len(data) > 16384:
                return None
        return data.decode("latin-1")

    def respond(self, status, ctype, body):
        head = "HTTP/1.1 %s\r\nContent-Type: %s\r\nContent-Length: %d\r\nConnection: close\r\n\r\n" % (status, ctype, len(body))
        self.request.sendall(head.encode() + body)

    def handle(self):
        try:
            request = self.read_request()
            if not request:
                return
            line = request.split("\r\n", 1)[0].split(" ")
            target = line[1] if len(line) > 1 else "/"
            path, _, query = target.partition("?")
            params = dict(p.split("=", 1) for p in query.split("&") if "=" in p)
            if path == "/hello":
                self.respond("200 OK", "application/json", json.dumps({"app": "Syncora", "v": 2, "code": CODE, "name": NAME,
                                                                     "title": TRACKS[P.index][0], "clients": len(P.clients)}).encode())
            elif path == "/art":
                key = params.get("k", "")
                idx = int(key[len("tvirtual"):]) if key.startswith("tvirtual") and key[len("tvirtual"):].isdigit() else 0
                if idx not in ART:
                    ART[idx] = make_art(idx)
                log("art requested: %s" % key)
                self.respond("200 OK", "image/jpeg", ART[idx])
            elif path == "/stream":
                self.stream(params)
            else:
                self.respond("404 Not Found", "text/plain", b"not found")
        except (OSError, ValueError) as e:
            log("handler error: %r" % (e,))

    def stream(self, params):
        sock = self.request
        from urllib.parse import unquote
        if params.get("code", "") != CODE:
            self.respond("403 Forbidden", "text/plain", b"Wrong room code")
            return
        cid = unquote(params.get("id", "unknown"))
        name = unquote(params.get("name", "Speaker")).replace("+", " ")
        client = Client(sock, cid, name, self.client_address[0])
        sock.sendall(b"HTTP/1.1 200 OK\r\nContent-Type: application/x-syncora-stream\r\nConnection: close\r\n\r\n")
        sock.settimeout(None)
        log("speaker connected: %s (%s)" % (name, cid))
        with lock:
            P.clients[cid] = client
            P.seen.append(client)
            client.outq.append(P.json_frame(T_WELCOME, {"v": 2, "hostNanos": now_ns(), "timePort": UDP_PORT, "epoch": P.epoch,
                                                        "audioMode": "balanced", "hostName": NAME, "roomCode": CODE}))
            client.outq.append(P.json_frame(T_STATE, P.state()))
            P.state_dirty = True
        buf = b""
        try:
            while not client.closed and time.time() < END_WALL:
                with lock:
                    out, client.outq = client.outq, []
                for data in out:
                    sock.sendall(data)
                readable, _, _ = select.select([sock], [], [], 0.01)
                if readable:
                    data = sock.recv(65536)
                    if not data:
                        log("speaker closed the connection")
                        break
                    buf += data
                    while len(buf) >= 5:
                        length = struct.unpack_from("<i", buf, 1)[0]
                        if len(buf) < 5 + length:
                            break
                        ftype, payload, buf = buf[0], buf[5:5 + length], buf[5 + length:]
                        self.on_frame(client, ftype, payload)
        except OSError as e:
            log("stream error: %r" % (e,))
        finally:
            client.closed = True
            with lock:
                if P.clients.get(cid) is client:
                    del P.clients[cid]
                    P.state_dirty = True

    def on_frame(self, client, ftype, payload):
        try:
            obj = json.loads(payload.decode())
        except ValueError:
            return
        if ftype == T_HELLO:
            client.hello = obj
            with lock:
                client.info.update({k: obj[k] for k in ("volume", "muted", "channel", "offsetMs") if k in obj})
                if obj.get("name"):
                    client.name = obj["name"]
                P.state_dirty = True
            log("hello: %s" % obj)
        elif ftype == T_CLIENT_STATE:
            with lock:
                client.info.update({k: obj[k] for k in ("volume", "muted", "channel", "offsetMs", "syncErrMs", "status") if k in obj})
                client.history.append((time.time(), obj.get("syncErrMs", 0), obj.get("status", ""), obj.get("volume", 1.0),
                                       obj.get("channel", ""), obj.get("offsetMs", 0)))
                P.state_dirty = True
        elif ftype == T_COMMAND:
            P.command(client, obj)


class Server(socketserver.ThreadingTCPServer):
    allow_reuse_address = True
    daemon_threads = True


def udp_time_server(sock):
    sock.settimeout(0.5)
    while time.time() < END_WALL + 2:
        try:
            data, addr = sock.recvfrom(64)
        except socket.timeout:
            continue
        t1 = now_ns()
        if len(data) >= 13 and data[0:1] == b"T":
            seq, t0 = struct.unpack(">iq", data[1:13])
            with lock:
                P.udp_probes += 1
            sock.sendto(b"R" + struct.pack(">iqqq", seq, t0, t1, now_ns()), addr)


END_WALL = time.time() + DURATION
udp = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
udp.bind(("0.0.0.0", 0))
UDP_PORT = udp.getsockname()[1]
threading.Thread(target=udp_time_server, args=(udp,), daemon=True).start()
server = Server(("0.0.0.0", PORT), Handler)
threading.Thread(target=server.serve_forever, daemon=True).start()
log("virtual player listening on %d (time port %d), running %.0f s" % (PORT, UDP_PORT, DURATION))

sent_set = False
sent_resync = False
connected_at = None
while time.time() < END_WALL:
    P.pump_audio()
    P.maybe_send_state()
    with lock:
        if P.clients and connected_at is None:
            connected_at = time.time()
        if connected_at and not sent_set and time.time() - connected_at > 22:
            sent_set = True
            for c in P.clients.values():
                c.outq.append(P.json_frame(T_SET, {"volume": 0.6}))
                c.info["volume"] = 0.6
            log("sent SET volume 0.6 to the speaker")
        if connected_at and not sent_resync and time.time() - connected_at > 24:
            sent_resync = True
            for c in P.clients.values():
                c.outq.append(P.json_frame(T_SET, {"resync": True}))
            log("sent SET resync to the speaker")
    time.sleep(0.004)

# ------------------------------------------------------------------ verdict
log("---- verdict")
speakers = list(P.seen)
check(connected_at is not None, "the Android speaker connected to the virtual player")
check(len(P.clients) > 0, "the speaker stayed connected until the end of the test")
check(P.udp_probes >= 3, "the speaker measured the clock with UDP probes (%d probes)" % P.udp_probes)
if speakers:
    c = speakers[0]
    check(c.hello is not None and c.hello.get("name"), "HELLO announced the speaker's name (%s)" % (c.hello,))
    check(len(c.history) >= 5, "the speaker reports its state every second (%d reports)" % len(c.history))
    statuses = set(h[2] for h in c.history)
    log("reported statuses: %s" % sorted(statuses))
    check("synced" in statuses, "the speaker reported itself as synced")
    errs = [abs(h[1]) for h in c.history[4:]]
    if errs:
        errs.sort()
        median = errs[len(errs) // 2]
        log("sync error after warm-up: median %d ms, max %d ms over %d reports" % (median, errs[-1], len(errs)))
        check(median <= 40, "median sync error is within 40 ms (%d ms)" % median)
    volumes = [round(h[3], 2) for h in c.history]
    check(any(abs(v - 0.6) < 0.02 for v in volumes), "the speaker applied the volume the player sent (reported volumes: %s)" % sorted(set(volumes)))
    log("channels seen: %s, offsets seen: %s" % (sorted(set(h[4] for h in c.history)), sorted(set(h[5] for h in c.history))))
    if "toggle" in REQUIRED:  # the emulator script also changes this speaker's own settings
        check(any(h[4] == "left" for h in c.history), "the speaker reported the Left channel after it was selected")
        check(any(h[5] == 40 for h in c.history), "the speaker reported its +40 ms sync offset after it was set")
else:
    check(False, "a speaker was still connected at the end of the test")
for required in REQUIRED:
    check(required in P.commands, "the player received the speaker's '%s' command" % required)
log("commands received: %s" % P.commands)

open(REPORT, "w").write("\n".join(log_lines) + "\n")
failed = [what for ok, what in checks if not ok]
print("FAILED CHECKS: %d" % len(failed))
sys.exit(1 if failed else 0)
