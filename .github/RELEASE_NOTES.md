## Syncora v0.7.0 — YouTube That Keeps Working, Direct Links

**New**
- **TypeTube YouTube resolution** — YouTube videos are resolved through the [typetube](https://github.com/botstelegram7-cmyk/typetube) stream service first (the TAP protocol, implemented natively in the app — no extra install). When that cannot answer, the app falls back to YouTube's own player API, so playback and downloads keep working even when one method breaks.
- **Direct media links** — paste a link like `https://telegram-file-to-links.onrender.com/stream?id=888` on the YouTube page and it just works: **Play audio in sync** streams from the stream link (`/stream?id=…`) on every speaker, and **Save file** downloads through the download link (`/stream?id=…&d=true`). Other direct http(s) media links play and save the same way.

**Improved**
- Clearer errors when a video cannot be read (both resolvers explain what failed).
- The pasted text is normalized (HTML-escaped `&amp;` in links is handled).

**Install**
Install `Syncora-v0.7.0.apk` on Android 8.0 (API 26) or newer. Devices must be on the same Wi-Fi or hotspot; the player needs internet for radio, lyrics and YouTube.

> APKs remain signed with Syncora's repository sideload key, so v0.2.0 and later can update in place.

---

## Syncora v0.6.0 — Akashvani Radio and Reliable Speakers

**New**
- **Akashvani radio** — browse the bundled All India Radio channels, including live HLS stations such as Live News 24x7 and Vividh Bharati.
- **Indian MP3 stations** — popular Indian community and private stations are bundled for one-tap playback, even when radio-browser.info is unavailable.
- **HLS audio** — play HLS playlists that carry MP3/AAC directly or in MPEG-TS. AES-128 HLS segments are supported; unsupported audio containers and encryption modes fail with a clear error.

**Improved**
- **Speaker connection recovery** — idle connections exchange keep-alive pings, and slow speakers discard audio that is already late instead of being disconnected when a short network backlog fills.
- **Radio verification** — the emulator smoke test plays a real Akashvani HLS stream and an Indian MP3 station, and verifies that each reaches decoded playback (`src=RADIO playing=true`, not just a tap or loading screen).

**Install**
Install `Syncora-v0.6.0.apk` on Android 8.0 (API 26) or newer. Devices must be on the same Wi-Fi or hotspot; the player needs internet for radio, lyrics and YouTube.

> APKs remain signed with Syncora's repository sideload key, so v0.2.0 and later can update in place.

---

## Syncora v0.5.0 — YouTube, Lyrics and Fixes

**New**
- **YouTube page** — paste a link or video ID. Play a video's **audio in sync** on the phone and every speaker, or save the **video with sound** (MP4), **audio only**, the **thumbnail**, **lyrics** (LRC) or **subtitles** (SRT / VTT).
- **Background downloads** — a download queue with progress, cancel and *Clear finished*. Files go to `Download/Syncora/YouTube`.
- **Lyrics** — swipe the cover art to read lyrics. Synced lyrics follow the song and tapping a line jumps to it. Lyrics come from LRCLIB, or from captions for YouTube videos. Speakers show the same lyrics.

**Fixed**
- **Radio** — tapping a station or adding one now starts it, or tells you why it cannot start, instead of doing nothing.
- **Media notification** — the seekbar on the lock screen and notification now shows and moves with playback.

**Use responsibly**
Save only content you have the right to keep: your own uploads, Creative Commons videos, or content YouTube itself lets you download. YouTube's terms still apply. YouTube changes how it serves media often, so YouTube playback and downloads may stop working until an update.

**Install**
Install `Syncora-v0.5.0.apk` on Android 8.0 (API 26) or newer. Devices must be on the same Wi-Fi or hotspot; the player needs internet for radio, lyrics and YouTube.

> APKs remain signed with Syncora's repository sideload key, so v0.2.0 and later can update in place.

---

## Syncora v0.4.0 — Tighter Sync, Stronger Connections

This release makes multi-device playback more dependable across real Android hardware and busy local networks, while polishing the player and speaker experience.

**Improved**
- **More reliable synchronization** — corrections now continue across audio chunks until playback is aligned, and large timing errors are fixed immediately.
- **Safer role switching** — stale clock offsets are cleared when a device leaves Speaker Mode, preventing silent or mistimed local playback.
- **Stable rooms** — each player keeps its room code across restarts so speakers can reconnect more reliably.
- **Broader audio-device support** — when Android's low-latency output is unavailable, Syncora automatically falls back to the standard audio path.
- **Smoother recovery** — stalled or unavailable audio outputs back off before retrying instead of repeatedly flooding the device with errors.
- **Hardened discovery** — stricter room-announcement validation and verified SSDP messages improve LAN and DLNA interoperability.
- **Clearer interface** — refined Speaker Mode states, paused status, device-role labels, truncation, spacing and first-run guidance.

**Quality**
- Added independent virtual-player and virtual-speaker protocol checks.
- Expanded automated coverage for clock sync, room discovery, DLNA, radio, microphone and the main UI journey.
- Removed generated emulator screenshots and logs from the source tree; CI keeps verification output as workflow artifacts instead.

**Install**
Install `Syncora-v0.4.0.apk` on Android 8.0 (API 26) or newer. Devices must be on the same Wi-Fi or hotspot; only the player needs internet for radio.

> APKs remain signed with Syncora's repository sideload key, so v0.2.0 and later can update in place. The old v0.1.0 debug build used a different key and must be uninstalled first.
