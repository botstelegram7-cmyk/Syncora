#!/usr/bin/env python3
"""A tiny independent implementation of the Syncora speaker protocol, used to test the Android player end to end.

usage: virtual_speaker.py <port> <report-file>
Connects to http://localhost:<port> (adb-forwarded player), listens to the audio stream and drives the player with
commands. Exits non-zero if any check fails.
"""
import json
import socket
import struct
import sys
import time
import urllib.request

PORT = int(sys.argv[1])
REPORT = sys.argv[2]
HOST = "127.0.0.1"
T_WELCOME, T_STATE, T_AUDIO, T_FLUSH, T_SET, T_PING, T_BYE = 1, 2, 3, 4, 5, 6, 7
T_HELLO, T_CLIENT_STATE, T_COMMAND = 16, 17, 18

lines = []
failed = []


def log(msg):
    print(msg, flush=True)
    lines.append(msg)


def check(ok, what):
    log(("PASS  " if ok else "FAIL  ") + what)
    if not ok:
        failed.append(what)


class Conn:
    def __init__(self):
        hello = json.load(urllib.request.urlopen("http://%s:%d/hello" % (HOST, PORT), timeout=5))
        self.code = hello["code"]
        log("hello: %s" % hello)
        self.sock = socket.create_connection((HOST, PORT), timeout=5)
        req = "GET /stream?code=%s&id=ci-speaker&name=CI%%20speaker&v=2 HTTP/1.1\r\nHost: x\r\n\r\n" % self.code
        self.sock.sendall(req.encode())
        buf = b""
        while b"\r\n\r\n" not in buf:
            chunk = self.sock.recv(1)
            if not chunk:
                raise RuntimeError("closed during handshake")
            buf += chunk
        check(buf.startswith(b"HTTP/1.1 200"), "stream handshake answered 200")
        self.sock.settimeout(0.5)
        self.inbuf = b""
        self.frames = []  # (type, payload, arrival time)

    def send(self, ftype, obj):
        payload = json.dumps(obj).encode()
        self.sock.sendall(bytes([ftype]) + struct.pack("<i", len(payload)) + payload)

    def pump(self, seconds):
        """Read frames for the given time."""
        end = time.time() + seconds
        while time.time() < end:
            try:
                data = self.sock.recv(65536)
            except socket.timeout:
                continue
            if not data:
                raise RuntimeError("player closed the connection")
            self.inbuf += data
            while len(self.inbuf) >= 5:
                length = struct.unpack_from("<i", self.inbuf, 1)[0]
                if len(self.inbuf) < 5 + length:
                    break
                ftype = self.inbuf[0]
                payload = self.inbuf[5:5 + length]
                self.inbuf = self.inbuf[5 + length:]
                self.frames.append((ftype, payload, time.time()))

    def take(self):
        frames, self.frames = self.frames, []
        return frames


def audio_of(frames):
    out = []
    for ftype, payload, at in frames:
        if ftype == T_AUDIO:
            epoch, seq, play_at, rate, channels, nframes = struct.unpack_from("<iiqiBi", payload, 0)
            pcm = payload[25:]
            samples = struct.unpack("<%dh" % (len(pcm) // 2), pcm[: (len(pcm) // 2) * 2])
            out.append(dict(epoch=epoch, seq=seq, play_at=play_at, rate=rate, channels=channels, frames=nframes,
                            peak=max((abs(s) for s in samples), default=0), at=at))
    return out


def states_of(frames):
    return [json.loads(p.decode()) for t, p, a in frames if t == T_STATE]


def last_state(conn, wait=1.6):
    conn.pump(wait)
    st = states_of(conn.frames)
    return st[-1] if st else None


try:
    conn = Conn()
    conn.pump(1.5)
    welcome = None
    for t, p, a in conn.frames:
        if t == T_WELCOME:
            welcome = json.loads(p.decode())
    check(welcome is not None, "WELCOME received")
    if welcome:
        log("welcome: %s" % welcome)
        check(welcome.get("timePort", 0) > 0, "WELCOME announces a UDP time port")
    conn.send(T_HELLO, {"name": "CI speaker", "volume": 0.5, "muted": False, "channel": "stereo", "offsetMs": 0})
    conn.frames = []

    # ---- steady playback
    conn.pump(7)
    frames = conn.take()
    audio = audio_of(frames)
    states = states_of(frames)
    check(len(audio) > 50, "audio chunks are streaming (%d chunks in 7 s)" % len(audio))
    if audio:
        check(all(a["channels"] == 2 and a["rate"] in (44100, 48000) for a in audio), "stereo at a normal sample rate (%s Hz)" % audio[0]["rate"])
        check(max(a["peak"] for a in audio) > 2000, "decoded audio is not silence (peak %d)" % max(a["peak"] for a in audio))
        same = [a for a in audio if a["epoch"] == audio[-1]["epoch"]]
        contiguous = all(same[i + 1]["seq"] == same[i]["seq"] + 1 for i in range(len(same) - 1))
        check(contiguous, "sequence numbers are contiguous")
        gaps = [same[i + 1]["play_at"] - (same[i]["play_at"] + same[i]["frames"] * 1_000_000_000 // same[i]["rate"]) for i in range(len(same) - 1)]
        check(all(abs(g) < 2_000_000 for g in gaps), "play times are gapless (max gap %.3f ms)" % (max((abs(g) for g in gaps), default=0) / 1e6))
        span_media = (same[-1]["play_at"] - same[0]["play_at"]) / 1e9
        span_wall = same[-1]["at"] - same[0]["at"]
        log("media time %.2f s over wall time %.2f s" % (span_media, span_wall))
        if span_wall > 3:
            check(0.8 < span_media / span_wall < 1.25, "chunks are paced in real time (ratio %.2f)" % (span_media / span_wall))
        lead = (same[-1]["play_at"] - welcome["hostNanos"]) / 1e9 if welcome else 0
        log("last chunk due %.1f s after the welcome timestamp" % lead)
    if states:
        st = states[-1]
        log("state: playing=%s title=%s queue=%s/%s devices=%s" % (st.get("playing"), st.get("title"), st.get("queueIndex"), st.get("queueSize"),
                                                                    [(d["name"], d.get("volume")) for d in st.get("devices", [])]))
        check(st.get("playing") is True, "state says playing")
        check(st.get("source") == "music", "state source is music")
        check(st.get("queueSize", 0) >= 3, "queue holds the library songs")
        names = [d["name"] for d in st.get("devices", [])]
        check("CI speaker" in names, "this speaker is listed in the device list")
        mine = [d for d in st.get("devices", []) if d["name"] == "CI speaker"]
        check(bool(mine) and abs(mine[0]["volume"] - 0.5) < 0.01, "volume from HELLO was adopted by the player")
        check(any(d.get("host") for d in st.get("devices", [])), "the player is listed as the host device")
        title_before = st.get("title")
    else:
        check(False, "state frames arrive")
        title_before = None

    # ---- pause -> flush, silence
    conn.send(T_COMMAND, {"cmd": "pause"})
    conn.pump(2.0)
    frames = conn.take()
    check(any(t == T_FLUSH for t, p, a in frames), "pause flushes the speakers")
    st = states_of(frames)
    check(bool(st) and st[-1].get("playing") is False, "state says paused")
    conn.pump(1.5)
    check(len(audio_of(conn.take())) == 0, "no audio while paused")

    # ---- resume
    conn.send(T_COMMAND, {"cmd": "toggle"})
    conn.pump(3.0)
    frames = conn.take()
    audio = audio_of(frames)
    check(len(audio) > 20, "audio resumes after play (%d chunks)" % len(audio))
    check(any(t == T_FLUSH for t, p, a in frames), "resume starts a new timeline (flush)")

    # ---- seek
    conn.send(T_COMMAND, {"cmd": "seek", "ms": 8000})
    conn.pump(3.0)
    frames = conn.take()
    st = states_of(frames)
    check(any(t == T_FLUSH for t, p, a in frames), "seek flushes the speakers")
    if st:
        log("after seek: anchorMs=%s" % st[-1].get("anchorMs"))
        check(7000 <= st[-1].get("anchorMs", 0) <= 9500, "playback restarted near the seek target")
    check(len(audio_of(frames)) > 10, "audio continues after a seek")

    # ---- next
    conn.send(T_COMMAND, {"cmd": "next"})
    conn.pump(3.0)
    st = states_of(conn.take())
    if st and title_before is not None:
        check(st[-1].get("title") != title_before, "next changes the song (%s -> %s)" % (title_before, st[-1].get("title")))

    # ---- shuffle / repeat
    conn.send(T_COMMAND, {"cmd": "shuffle", "on": True})
    conn.send(T_COMMAND, {"cmd": "repeat", "mode": "one"})
    conn.pump(2.0)
    st = states_of(conn.take())
    if st:
        check(st[-1].get("shuffle") is True, "shuffle toggled")
        check(st[-1].get("repeat") == "one", "repeat mode set")

    # ---- device volume change travels back to this speaker
    conn.send(T_COMMAND, {"cmd": "setDevice", "id": "ci-speaker", "volume": 0.2})
    conn.pump(2.0)
    frames = conn.take()
    sets = [json.loads(p.decode()) for t, p, a in frames if t == T_SET]
    check(any(abs(s.get("volume", 1) - 0.2) < 0.01 for s in sets), "player forwards the device volume to this speaker (%s)" % sets)

    # ---- low latency toggle re-cues with a shorter lookahead
    conn.send(T_COMMAND, {"cmd": "audioMode", "mode": "low"})
    conn.pump(3.0)
    frames = conn.take()
    st = states_of(frames)
    if st:
        check(st[-1].get("audioMode") == "low", "low latency applied")
        check(st[-1].get("lookaheadMs") == 260, "lookahead is 260 ms in low latency mode (%s)" % st[-1].get("lookaheadMs"))
    check(len(audio_of(frames)) > 10, "audio keeps flowing after the mode change")
    conn.send(T_COMMAND, {"cmd": "audioMode", "mode": "balanced"})
    conn.pump(1.0)
except Exception as e:  # noqa: BLE001
    check(False, "script error: %r" % (e,))

open(REPORT, "w").write("\n".join(lines) + "\n")
print("FAILED CHECKS: %d" % len(failed))
sys.exit(1 if failed else 0)
