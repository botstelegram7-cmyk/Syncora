# Bundled radio catalogs

These JSON files ship inside the APK (`assets/radio/`) so a listener starts in one tap even when
the radio-browser.info directory is slow or unreachable. `com.serena.syncora.radio.RadioCatalog`
reads them; `Station` is the model. Every entry is a **live audio stream address**, never a page.

| file | what it holds | where it comes from |
|---|---|---|
| `akashvani.json` | 277 **All India Radio / Akashvani** channels (Vividh Bharati, Raagam, FM Gold, FM Rainbow, every state kendras), grouped by state with their broadcast languages | [codito/akashvani](https://github.com/codito/akashvani), which scrapes the official Prasar Bharati streams every day |
| `india.json` | Indian private and community stations (Bollywood, regional, devotional, news) | radio-browser.info, most voted first |
| `world.json` | a few of the best stations from ~50 other countries | radio-browser.info, most voted first |

All three are HLS-heavy: the Akashvani channels are **all** `.m3u8`, which is why Syncora has its
own HLS reader (`audio/HlsStream`, `audio/MpegTsReader`, `audio/Mp4StreamReader`) and plays the
audio track only, even when a playlist also offers video.

## Field shape

```json
{ "id": "air-1", "name": "Vividh Bharati", "url": "https://.../x.m3u8",
  "state": "NATIONAL", "language": "Hindi", "epg": "https://cuesheets.prasarbharati.org/viewsheet/133" }
```

`india.json` / `world.json` use the `Station` fields instead (`tags`, `country`, `codec`, `bitrate`).
Unknown fields are ignored, so a file may carry both shapes.

## Keeping them fresh

Radio addresses move. Both are rebuilt by CI, not by hand:

```bash
# in CI, or anywhere with internet
python3 .github/scripts/radio_catalog.py --refresh          # rewrites india.json + world.json
python3 .github/scripts/radio_probe.py \
    --catalog app/src/main/assets/radio/akashvani.json \
    --catalog app/src/main/assets/radio/india.json \
    --catalog app/src/main/assets/radio/world.json --sample 7   # reports which ones still stream
```

`radio_probe.py` classifies every address (HLS master / playlist / MPEG-TS / fMP4 / ADTS / MP3 /
Ogg / FLAC) and, for HLS, fetches the first segment so the demuxer choice is based on real bytes.
The push workflow runs both and writes `ci-output/radio-probe.json`.

## Credit and licence

`akashvani.json` is station data collected by [codito/akashvani](https://github.com/codito/akashvani)
(GPL-3.0) from the public Prasar Bharati "Akashvani" listings; the names and stream addresses are
reproduced here with credit to that project, and the streams themselves belong to All India Radio.
`india.json` and `world.json` come from [radio-browser.info](https://www.radio-browser.info/), a
community directory whose data is published under CC0 — the streams belong to their broadcasters.
