<h1 align="center">Syncora</h1>
<p align="center"><b>One Beat. Every Device.</b></p>

Syncora turns the phones already in the room into one distributed speaker system. One device is the **player**
(your music, an internet radio station, a DLNA cast or a microphone); every other device is a **speaker** that plays the
same audio at the same moment. Everything runs on your own Wi-Fi or phone hotspot.

## Features

| | |
|---|---|
| **My Music** | Every song on the phone is listed inside the app (search, sort, *Shuffle*). No file picker. |
| **Shuffle · Loop · Repeat** | Shuffle the queue, loop the whole queue, or repeat one song. |
| **Same player everywhere** | Cover art, title, progress and controls are mirrored on every speaker; speakers can control playback too. |
| **Volume for every device** | Tap the speaker button: each connected device gets its own slider, mute and an *All devices* fader. |
| **Sync** | One-tap *Sync now* and a **Low-latency sync** switch (shorter buffer, tighter alignment). |
| **Sources** | My Music · Online Radio · YouTube · DLNA Renderer · Microphone / Aux · Speaker Mode |
| **Radio** | Search worldwide, or browse bundled All India Radio / Akashvani HLS channels and popular Indian MP3 stations—even without the directory. |
| **YouTube** | Paste a link: play the video's audio in sync, or save video with sound, audio only, thumbnail, lyrics and subtitles. Background download queue. Videos resolve through the [typetube](https://github.com/botstelegram7-cmyk/typetube) service first, with YouTube's own player API as a fallback. |
| **Direct links** | Paste a direct media link (for example a telegram-file-to-links `…/stream?id=888` link): *Play audio in sync* streams it, *Save file* downloads it (`…&d=true`). |
| **Lyrics** | Swipe the cover art for lyrics. Synced lyrics follow playback and can be tapped to jump. Speakers show them too. |
| **Audio settings** | Connection state, sync offset with **+ / −** and **Reset**, channels **Mono / Stereo / Left / Right** (two phones = a stereo pair). |
| **Speaker mode** | Shows the player and its status (*Waiting for connection*), nearby players, connect by IP, auto connect. |
| **Settings** | Device name, IP address for connect, theme (Dark / AMOLED / Light / System) + accent, audio mode, animations, speaker options (auto connect, re-sync frequency). |
| **Premium look** | Blurred cover backdrops, generated covers, animated visuals, media notification with lock-screen controls. |

## How it works

Older versions let each phone stream the same *file* and nudge itself by seeking, which made sound stutter or stop
(*"host audio was interrupted"*). Syncora now works like a multi-room system:

1. The **player decodes** the source (song, radio stream, DLNA item, microphone) to PCM audio.
2. It cuts the audio into 40 ms chunks and gives every chunk a **play time on its own clock**: *now + lookahead*,
   where the lookahead is 260 / 450 / 800 ms for *Low latency / Balanced / Stable*.
3. Chunks go to every speaker over one TCP connection (`/stream`) and into the player's own speaker.
4. Each device measures its **clock offset** to the player (NTP-style UDP probes, lowest round-trip wins) and measures
   when audio **really leaves its speaker** (`AudioTrack` timestamps). It inserts a little silence or skips a few
   frames to hit the planned time — within 10–35 ms depending on the audio mode — and re-checks as often as the
   *Re-sync frequency* setting says.
5. The per-device **sync offset** (+/−) compensates for slow outputs such as Bluetooth speakers.

Speakers need no files and no internet; radio is fetched once by the player. Cover art is served at `/art`.

```
app/src/main/java/com/serena/syncora
├── core/    play queue, PCM chunk + wire format, clock sync, ScheduledPlayer (the sync engine)
├── audio/   MediaCodec decoder, radio (ICY/MP3/AAC) reader, microphone, stream pump, MediaStore library
├── net/     mini HTTP server, group hub (player) / client (speaker), UDP time sync, LAN discovery
├── dlna/    SSDP + UPnP AV MediaRenderer (AVTransport / RenderingControl / ConnectionManager)
├── radio/   station model and the radio-browser.info directory client
├── ui/      theme, custom views, bottom sheets and the four screens
├── SyncoraService.java   the player/speaker brain (foreground service, notification, media session)
└── MainActivity.java     edge-to-edge shell, bottom navigation, mini player
```

## Using it

1. Install the APK from [Releases](../../releases/latest) on every phone (Android 8.0+). Allow access to your music.
2. Put the phones on the same Wi-Fi, or switch one on as a hotspot and connect the others to it.
3. **Player**: pick a song in *Library* (or a radio station, or choose DLNA / Mic).
4. **Speakers**: *Player* tab → *Source* → **Speaker Mode**. Players nearby appear — tap **Connect**, or type the
   player's IP address (shown in its *Devices* tab). With *Auto connect* a speaker rejoins its last player by itself.
5. If a speaker sounds a little early or late, open *Audio* (the sliders button) and nudge **Sync** with + / −.

DLNA: choose *DLNA Renderer* as the source, then cast to **Syncora (device name)** from BubbleUPnP, VLC, Kodi, Windows
*Cast to device*, etc. The cast item plays on the player and on all speakers.

## Build

```bash
gradle assembleDebug          # Gradle 8.9, JDK 17, Android SDK 35
gradle testDebugUnitTest      # sync engine, wire format, network, DLNA and radio tests
```

The app has no third-party runtime dependencies (the typetube stream service is spoken natively over its TAP
protocol). Releases are built by `.github/workflows/android-release.yml`
(on every push to `main`, or manually) and published to GitHub Releases. `.github/workflows/ci.yml` builds and runs the
unit tests on every push to an `arena/**` branch; put `[emulator]` in a commit message (or run the workflow by hand) to
also drive the app on an Android emulator: UI tour with screenshots, an independent protocol client talking to the
player, an independent player talking to the speaker role, DLNA casting, radio and microphone checks
(`.github/scripts/`). Add `[ci-shots]` as well to have the screenshots and logs committed back to the branch.

APKs are signed with the repository's **sideload key** (`signing/`), so each release updates the previous one in place.
The key is public by design — fine for sideloading, never for a store. See `signing/README.md` to use your own key.

## Limits (be honest about physics)

- Wi-Fi power saving, router *client isolation* and broadcast filtering can hide rooms (use a hotspot or type the IP).
- Sync is as good as the weakest link: expect roughly ±10–30 ms on a healthy network, then tune with the offset.
- Streams use no transport encryption: use networks you trust. Anyone on the network who knows the room code can join.
- Radio supports MP3 and AAC/ADTS streams, plus HLS (`.m3u8`) playlists that carry MP3/AAC directly or in MPEG-TS (AES-128 HLS is supported). Fragmented-MP4 HLS, other encryption modes, Ogg/FLAC/Opus streams and video playback are not supported.
- YouTube reads public stream and caption addresses directly, without a decoding step for protected (ciphered) streams, which are skipped. Playback or downloads can stop working when YouTube changes its service. Save only content you have the right to keep, and respect YouTube's terms.
- Lyrics come from LRCLIB and YouTube captions. Matches are by title and artist, so a song can get the wrong lyrics.
