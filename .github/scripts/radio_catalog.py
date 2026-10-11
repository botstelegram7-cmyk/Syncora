#!/usr/bin/env python3
"""Builds the offline station catalogs that ship inside the APK.

Syncora can always search radio-browser.info live, but a bundled list starts instantly and works
even when the directory is slow. This script asks radio-browser.info for the most loved stations of
each country, keeps only addresses that look playable, and writes the result as an app asset:

    app/src/main/assets/radio/india.json   -- Indian stations (private + community)
    app/src/main/assets/radio/world.json   -- a few of the best stations of many other countries

Run it in CI (which has internet) and commit the files back:

    python3 .github/scripts/radio_catalog.py --refresh

The All India Radio list (akashvani.json) is not generated here: it comes from
https://github.com/codito/akashvani, which scrapes the official Prasar Bharati streams every day.
See app/src/main/assets/radio/README.md.
"""

import argparse
import json
import os
import sys
import urllib.error
import urllib.parse
import urllib.request

SERVERS = [
    "https://de1.api.radio-browser.info",
    "https://fi1.api.radio-browser.info",
    "https://nl1.api.radio-browser.info",
    "https://at1.api.radio-browser.info",
    "https://all.api.radio-browser.info",
]

UA = "Syncora/0.6 (station catalog builder)"

# Countries in the "world" catalog, with how many stations to keep of each.
WORLD = [
    ("US", 6, "USA"), ("GB", 6, "UK"), ("DE", 5, "Germany"), ("FR", 5, "France"),
    ("ES", 4, "Spain"), ("IT", 4, "Italy"), ("NL", 4, "Netherlands"), ("PT", 3, "Portugal"),
    ("SE", 3, "Sweden"), ("NO", 3, "Norway"), ("DK", 3, "Denmark"), ("FI", 3, "Finland"),
    ("PL", 3, "Poland"), ("CH", 3, "Switzerland"), ("AT", 3, "Austria"), ("BE", 3, "Belgium"),
    ("IE", 3, "Ireland"), ("GR", 3, "Greece"), ("TR", 3, "Turkey"), ("RU", 3, "Russia"),
    ("UA", 3, "Ukraine"), ("CA", 5, "Canada"), ("MX", 3, "Mexico"), ("BR", 5, "Brazil"),
    ("AR", 3, "Argentina"), ("CL", 2, "Chile"), ("CO", 2, "Colombia"), ("PE", 2, "Peru"),
    ("AU", 5, "Australia"), ("NZ", 3, "New Zealand"), ("JP", 5, "Japan"), ("KR", 3, "South Korea"),
    ("SG", 3, "Singapore"), ("MY", 2, "Malaysia"), ("ID", 3, "Indonesia"), ("TH", 2, "Thailand"),
    ("PH", 2, "Philippines"), ("VN", 2, "Vietnam"), ("LK", 3, "Sri Lanka"), ("NP", 3, "Nepal"),
    ("PK", 3, "Pakistan"), ("BD", 3, "Bangladesh"), ("AE", 3, "UAE"), ("SA", 2, "Saudi Arabia"),
    ("IL", 2, "Israel"), ("ZA", 4, "South Africa"), ("NG", 2, "Nigeria"), ("KE", 2, "Kenya"),
    ("EG", 2, "Egypt"), ("MA", 2, "Morocco"),
]

PLAYABLE = ("MP3", "AAC", "AAC+", "HE-AAC", "OGG", "OPUS", "FLAC")


def api(path, timeout=25):
    """Call radio-browser.info, trying every mirror."""
    last = None
    for server in SERVERS:
        url = server + path
        request = urllib.request.Request(url, headers={"User-Agent": UA, "Accept": "application/json"})
        try:
            with urllib.request.urlopen(request, timeout=timeout) as response:
                return json.loads(response.read().decode("utf-8", "replace"))
        except (urllib.error.URLError, urllib.error.HTTPError, TimeoutError, ValueError) as error:
            last = "%s: %s" % (server, error)
    raise RuntimeError("radio-browser.info unreachable (%s)" % last)


def keep(station):
    """Is this a station Syncora can play?"""
    if station.get("lastcheckok", 1) != 1:
        return False
    url = station.get("url_resolved") or station.get("url") or ""
    if not url.lower().startswith(("http://", "https://")):
        return False
    codec = (station.get("codec") or "").upper()
    if codec and not any(codec.startswith(prefix) for prefix in PLAYABLE):
        return False
    if not (station.get("name") or "").strip():
        return False
    return True


def to_entry(station, index):
    url = station.get("url_resolved") or station.get("url") or ""
    codec = (station.get("codec") or "").upper()
    tags = ",".join([t.strip() for t in (station.get("tags") or "").split(",") if t.strip()][:3])
    return {
        "id": station.get("stationuuid") or ("rb%d" % index),
        "name": station["name"].strip(),
        "url": url,
        "tags": tags,
        "country": station.get("country") or "",
        "codec": "HLS" if station.get("hls") == 1 and not codec else codec,
        "bitrate": station.get("bitrate") or 0,
    }


def harvest(country_code, limit, country_name, seen):
    """Top stations of one country, deduplicated across the whole catalog."""
    path = ("/json/stations/search?countrycode=%s&order=votes&reverse=true&hidebroken=true&limit=%d"
            % (urllib.parse.quote(country_code), limit * 6))
    try:
        rows = api(path)
    except RuntimeError as error:
        print("  %s: %s" % (country_code, error))
        return []
    picked = []
    for row in rows:
        if not keep(row):
            continue
        name = row["name"].strip().lower()
        url = (row.get("url_resolved") or row.get("url") or "").strip()
        if name in seen or url in seen:
            continue
        seen.add(name)
        seen.add(url)
        entry = to_entry(row, len(picked))
        entry["country"] = entry["country"] or country_name
        picked.append(entry)
        if len(picked) >= limit:
            break
    print("  %s (%s): %d stations" % (country_code, country_name, len(picked)))
    return picked


def write(path, stations):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8") as handle:
        json.dump(stations, handle, ensure_ascii=False, indent=1)
        handle.write("\n")
    print("wrote %s (%d stations, %d bytes)" % (path, len(stations), os.path.getsize(path)))


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--refresh", action="store_true", help="query radio-browser.info and rewrite the assets")
    parser.add_argument("--assets", default="app/src/main/assets/radio")
    parser.add_argument("--india-limit", type=int, default=40)
    args = parser.parse_args()

    if not args.refresh:
        print("nothing to do (pass --refresh to rebuild the catalogs)")
        return 0

    print("Indian stations:")
    india = harvest("IN", args.india_limit, "India", set())
    print("World stations:")
    world = []
    seen = {s["name"].lower() for s in india} | {s["url"] for s in india}
    for code, limit, name in WORLD:
        world.extend(harvest(code, limit, name, seen))

    write(os.path.join(args.assets, "india.json"), india)
    write(os.path.join(args.assets, "world.json"), world)
    print("::notice title=Radio catalogs rebuilt::india=%d world=%d" % (len(india), len(world)))
    return 0


if __name__ == "__main__":
    sys.exit(main())
