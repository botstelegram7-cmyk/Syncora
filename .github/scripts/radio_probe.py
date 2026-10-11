#!/usr/bin/env python3
"""Checks that the radio stations bundled with Syncora really stream audio.

Runs in CI (where the runner has internet) and reports, for every station:

  * the HTTP status and content type of its address,
  * what the bytes actually are (HLS master / HLS playlist / MPEG-TS / fMP4 / ADTS / MP3 / Ogg / FLAC / ...),
  * for HLS: whether the playlist is a master playlist, whether segments are encrypted,
    and what the first media segment contains (that decides which demuxer the app needs).

Results are written to ci-output/radio-probe.json, printed to the log, published as a
workflow notice (a few hundred characters) and, when useful, committed back to the branch.

Usage:
    python3 .github/scripts/radio_probe.py [--catalog FILE.json]... [--limit N] [--sample N] [--out DIR]
"""

import argparse
import concurrent.futures
import gzip
import json
import os
import re
import sys
import urllib.error
import urllib.parse
import urllib.request

UA = "Syncora/0.6 (Android; Internet radio)"
TIMEOUT = 18


def http(url, headers=None, max_bytes=65536):
    """GET a URL following redirects; returns (status, content_type, final_url, bytes)."""
    request = urllib.request.Request(url, headers={"User-Agent": UA, "Accept": "*/*",
                                                   "Icy-MetaData": "1", "Accept-Encoding": "gzip"})
    if headers:
        for key, value in headers.items():
            request.add_header(key, value)
    with urllib.request.urlopen(request, timeout=TIMEOUT) as response:
        raw = response.read(max_bytes)
        if response.headers.get("Content-Encoding", "") == "gzip":
            try:
                raw = gzip.decompress(raw)
            except OSError:
                pass
        return response.status, response.headers.get("Content-Type", ""), response.geturl(), raw


def sniff(data):
    """What kind of audio bytes are these?"""
    if len(data) < 8:
        return "empty"
    if data[:4] == b"OggS":
        inner = data[28:36] if len(data) > 36 else b""
        if inner.startswith(b"\x01vorbis"):
            return "ogg-vorbis"
        if inner.startswith(b"OpusHead"):
            return "ogg-opus"
        if inner.startswith(b"\x7fFLAC"):
            return "ogg-flac"
        return "ogg"
    if data[:4] == b"fLaC":
        return "flac"
    if data[:4] == b"RIFF" and data[8:12] == b"WAVE":
        return "wav"
    if data[4:8] in (b"ftyp", b"moof", b"styp", b"moov", b"sidx"):
        return "fmp4"
    if data[0] == 0x47 and len(data) >= 565 and data[188] == 0x47 and data[376] == 0x47:
        return "mpeg-ts"
    if data[:3] == b"ID3":
        return "id3"
    if data[0] == 0xFF and (data[1] & 0xF6) == 0xF0:
        return "adts-aac"
    if data[0] == 0xFF and (data[1] & 0xE0) == 0xE0:
        return "mp3"
    if data.lstrip()[:7] == b"#EXTM3U":
        return "m3u8"
    if re.match(rb"\s*\[playlist\]", data[:200], re.I):
        return "pls"
    return "unknown"


def parse_playlist(text, base):
    """Minimal M3U8 read: master/media, encryption, first segment."""
    out = {"master": False, "variants": [], "segments": [], "target_duration": 0,
           "end_list": False, "key": None, "media_renditions": []}
    pending = None
    for line in text.splitlines():
        line = line.strip()
        if not line:
            continue
        if line.startswith("#EXT-X-STREAM-INF"):
            out["master"] = True
            pending = line
        elif line.startswith("#EXT-X-MEDIA:"):
            out["master"] = True
            out["media_renditions"].append(line)
        elif line.startswith("#EXT-X-TARGETDURATION"):
            out["target_duration"] = int(re.sub(r"[^0-9]", "", line) or 0)
        elif line.startswith("#EXT-X-ENDLIST"):
            out["end_list"] = True
        elif line.startswith("#EXT-X-KEY"):
            method = re.search(r'METHOD=([^,]+)', line)
            out["key"] = method.group(1) if method else line
        elif line.startswith("#"):
            continue
        elif pending is not None:
            bandwidth = re.search(r"BANDWIDTH=(\d+)", pending)
            codecs = re.search(r'CODECS="([^"]+)"', pending)
            out["variants"].append({
                "url": urllib.parse.urljoin(base, line),
                "bandwidth": int(bandwidth.group(1)) if bandwidth else 0,
                "codecs": codecs.group(1) if codecs else "",
            })
            pending = None
        else:
            out["segments"].append(urllib.parse.urljoin(base, line))
    return out


def probe(station):
    """Full report for one station."""
    name = station.get("name", "?")
    url = station.get("url") or station.get("stream_url") or ""
    result = {"name": name, "url": url, "id": station.get("id", ""),
              "country": station.get("country", ""), "state": station.get("state", "")}
    try:
        status, ctype, final, data = http(url)
        result["status"] = status
        result["content_type"] = ctype
        result["final_url"] = final
        kind = sniff(data)
        result["kind"] = kind
        if kind in ("m3u8",) or "mpegurl" in ctype.lower():
            playlist = parse_playlist(data.decode("utf-8", "replace"), final)
            result["kind"] = "hls-master" if playlist["master"] else "hls-media"
            result["hls"] = {k: playlist[k] for k in ("master", "target_duration", "end_list", "key")}
            result["hls"]["variants"] = [v["codecs"] or str(v["bandwidth"]) for v in playlist["variants"]][:8]
            result["hls"]["renditions"] = [r[:120] for r in playlist["media_renditions"]][:4]
            target = None
            if playlist["master"]:
                audio = [v for v in playlist["variants"] if not re.search(r"avc1|hvc1|hev1", v["codecs"])]
                pick = (audio or playlist["variants"])
                target = min(pick, key=lambda v: v["bandwidth"])["url"] if pick else None
            elif playlist["segments"]:
                target = playlist["segments"][0]
            if target:
                result["hls"]["probe_target"] = target
                try:
                    s2, c2, f2, d2 = http(target, max_bytes=32768)
                    segment_kind = sniff(d2)
                    if segment_kind in ("m3u8",) or "mpegurl" in c2.lower():
                        inner = parse_playlist(d2.decode("utf-8", "replace"), f2)
                        segment_kind = "hls-master" if inner["master"] else "hls-media"
                        result["hls"]["inner_variants"] = [v["codecs"] or str(v["bandwidth"]) for v in inner["variants"]][:8]
                        if inner["segments"]:
                            target = inner["segments"][0]
                            s2, c2, f2, d2 = http(target, max_bytes=32768)
                            segment_kind = sniff(d2)
                            result["hls"]["inner_key"] = None
                    result["segment_kind"] = segment_kind
                    result["segment_type"] = c2
                    result["segment_url"] = target
                except Exception as error:  # noqa: BLE001 - a probe never fails the build
                    result["segment_error"] = "%s: %s" % (type(error).__name__, error)
        else:
            head = data[:24]
            result["icy_name"] = ""
            result["prefix"] = head.hex()
        result["ok"] = True
    except urllib.error.HTTPError as error:
        result.update(ok=False, error="HTTP %s" % error.code)
    except Exception as error:  # noqa: BLE001
        result.update(ok=False, error="%s: %s" % (type(error).__name__, error))
    return result


def load_stations(path):
    with open(path, encoding="utf-8") as handle:
        data = json.load(handle)
    if isinstance(data, dict):
        for key in ("stations", "items"):
            if key in data:
                data = data[key]
                break
    return [s for s in data if isinstance(s, dict) and (s.get("url") or s.get("stream_url"))]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--catalog", action="append", default=[], help="station JSON file")
    parser.add_argument("--url", action="append", default=[], help="extra station address to check")
    parser.add_argument("--limit", type=int, default=0, help="maximum stations to probe (0 = all)")
    parser.add_argument("--sample", type=int, default=0, help="probe every Nth station of large catalogs")
    parser.add_argument("--jobs", type=int, default=8)
    parser.add_argument("--out", default="ci-output")
    parser.add_argument("--report", default="radio-probe.json", help="report file name inside --out")
    parser.add_argument("--filter-out", default="", help="write only the stations that answered to this catalog file")
    parser.add_argument("--dropped-out", default="", help="write the stations that did not answer to this file")
    args = parser.parse_args()

    stations = []
    for path in args.catalog:
        if not os.path.exists(path):
            print("::warning::catalog not found: %s" % path)
            continue
        loaded = load_stations(path)
        if args.sample > 1 and len(loaded) > args.sample * 4:
            loaded = loaded[::args.sample]
        stations.extend(loaded)
        print("catalog %s: %d stations" % (path, len(loaded)))
    for url in args.url:
        stations.append({"name": url, "url": url})
    if args.limit:
        stations = stations[:args.limit]
    if not stations:
        print("::warning::no stations to probe")
        return 0

    with concurrent.futures.ThreadPoolExecutor(max_workers=args.jobs) as pool:
        results = list(pool.map(probe, stations))

    good = [r for r in results if r.get("ok")]
    bad = [r for r in results if not r.get("ok")]
    kinds = {}
    segments = {}
    for r in good:
        kinds[r.get("kind", "?")] = kinds.get(r.get("kind", "?"), 0) + 1
        key = r.get("segment_kind", "")
        if key:
            segments[key] = segments.get(key, 0) + 1

    os.makedirs(args.out, exist_ok=True)
    report = os.path.join(args.out, args.report)
    with open(report, "w", encoding="utf-8") as handle:
        json.dump({"total": len(results), "ok": len(good), "failed": len(bad),
                   "kinds": kinds, "segment_kinds": segments, "results": results}, handle, indent=1)

    print("\n=== radio probe: %d/%d reachable ===" % (len(good), len(results)))
    print("formats:", json.dumps(kinds, sort_keys=True))
    print("hls segment formats:", json.dumps(segments, sort_keys=True))
    for r in bad[:40]:
        print("  FAIL %-42s %s" % (r["name"][:42], r.get("error", "?")[:110]))
    for r in good[:25]:
        extra = r.get("segment_kind", "") or r.get("content_type", "")[:34]
        print("  OK   %-42s %-14s %s" % (r["name"][:42], r.get("kind", "?"), extra))

    if args.filter_out:
        working = [r for r in good]
        by_url = {}
        for r in working:
            by_url.setdefault(r["url"], r)
        kept, dropped = [], []
        for station in stations:
            url = station.get("url") or station.get("stream_url") or ""
            if url in by_url:
                kept.append(station)
            else:
                dropped.append(station)
        os.makedirs(os.path.dirname(args.filter_out) or ".", exist_ok=True)
        with open(args.filter_out, "w", encoding="utf-8") as handle:
            json.dump(kept, handle, ensure_ascii=False, indent=1)
            handle.write("\n")
        print("kept %d stations in %s (dropped %d)" % (len(kept), args.filter_out, len(dropped)))
        if args.dropped_out:
            with open(args.dropped_out, "w", encoding="utf-8") as handle:
                json.dump(dropped, handle, ensure_ascii=False, indent=1)
                handle.write("\n")

    summary = "radio probe: %d/%d ok; %s; hls segments: %s" % (
        len(good), len(results), json.dumps(kinds, sort_keys=True), json.dumps(segments, sort_keys=True))
    notice = summary.replace("%", "%25").replace("\n", "%0A")
    print("::notice title=Radio stream probe::%s" % notice[:1800])
    if bad:
        detail = "; ".join("%s=%s" % (r["name"][:24], r.get("error", "?")[:40]) for r in bad[:12])
        print("::warning title=Unreachable stations (%d)::%s" % (
            len(bad), detail.replace("%", "%25")[:1800]))
    print("report written to %s" % report)
    return 0


if __name__ == "__main__":
    sys.exit(main())
