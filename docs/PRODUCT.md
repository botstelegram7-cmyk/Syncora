# Syncora · Product brief

**Tagline:** One Beat. Every Device.

## Product intent

Make the phones already in the room behave like one distributed speaker system. One device is the **player**
(music on the device, internet radio, a DLNA cast, or a microphone / aux input); the others are **speakers**
that play the same audio at the same moment. The experience is premium, calm and offline-first: rooms run over the
user's own Wi-Fi or hotspot, with no account or cloud service. Only internet radio needs internet, and only on the player.

## Audience and job to be done

Friends, small gatherings and everyday users with a few Android phones and no multi-room speaker. They want to pick
music once, hear it everywhere in sync, and fix any device that is a little early or late.

## Core user stories

- As a player, I see **all the music on my phone** in the app, search it, shuffle it, loop the queue or repeat one song.
- As a player, I can switch the **current source**: My Music, Online Radio, DLNA Renderer, Microphone / Aux, Speaker Mode.
- As a player, I paste a **YouTube video or public playlist link**; playlist songs appear in a tappable list, and a tap
  resolves and plays that song in sync with my speakers. I can also tap a video thumbnail to play its audio, or save
  the video, audio, thumbnail, lyrics or subtitles to my phone in the background. Lookups are rate-limited and the
  download queue is bounded to keep repeated taps from flooding requests.
- As a listener, I **swipe the cover art** to read the lyrics of the song that is playing; synced lyrics follow along.
- As a speaker, I see the **same music player** (cover art, title, progress) as the player does; the player owns shared track/queue controls, while speaker pause/mute is local to that device.
- As anyone in the room, I open the **volume panel** and set the volume of every connected device with its own slider,
  and I can switch on **low-latency sync** or tap **Sync now**.
- As anyone, I open **Audio settings** to see the connection state, nudge a device's sync with **+ / −**, **Reset** it,
  and choose **Mono / Stereo / Left / Right**.
- As a speaker, I see the player card (*Waiting for connection* / *Connected*), nearby players, and can connect by IP.
- In **Settings** I change the device name, the IP address for connect, theme, audio mode and animations; for speaker
  mode I choose auto connect and the re-sync frequency.

## Experience principles

1. **One obvious next step:** pick a song, or open Speaker Mode and tap a player.
2. **Premium, not busy:** midnight surfaces (or AMOLED / light), a lime (or chosen) accent, blurred cover backdrops,
   generated covers for songs and stations without artwork, subtle motion that can be switched off.
3. **Honest connection language:** show room code, IP, device status, sync error in milliseconds.
4. **Respect the source:** speakers never receive files — only the sound, live — and radio streams are fetched once.

## Audio design

- The player decodes to PCM and schedules every 40 ms chunk for *now + lookahead* on its own monotonic clock.
- Speakers estimate the clock offset (UDP probes, minimum-RTT filter), measure real render times from the audio
  output timestamps and keep each chunk on schedule by inserting silence or skipping a few frames.
- Audio modes: **Stable** (800 ms lookahead), **Balanced** (450 ms), **Low latency** (260 ms, tight tolerance).
- Per-device volume, mute, channel mode and sync offset are stored on the device and controlled over the same connection.
- Sources: MediaStore songs (MediaExtractor + MediaCodec), radio (HTTP + ICY metadata + MP3/AAC frame parsing),
  UPnP AV renderer (SSDP, SOAP, eventing), microphone / aux (AudioRecord).

## Network design

- UDP broadcast discovery (port 8788) lists players; IP entry works when broadcast is blocked.
- One TCP port (8787–8797) serves `/hello`, `/stream` (binary frames: audio, state, flush, settings, commands), `/art`
  and the DLNA documents under `/dlna/`. A separate UDP port answers clock probes.
- No analytics, no cloud relay. The player's radio traffic is the only internet use.

## Acceptance checks

- A song picked from **My Music** plays on the player and on a second phone in Speaker Mode, in sync, with the same cover.
- Pausing, seeking, next / previous, shuffle and repeat modes behave identically on both devices.
- Volume sliders change each device; *Sync now* and *Low latency* work; sync offset and channels apply on the right device.
- Radio, DLNA casting and the microphone play on the speakers; Speaker Mode shows *Waiting for connection* until a player is found.
- The APK installs and updates in place from the GitHub Release.

## Credits

Syncora is created by **Technical Serena** and **XioquiXin**.

- Telegram: [@TechnicalSerena](https://t.me/TechnicalSerena) · [@XioquiXin](https://t.me/XioquiXin)
- Instagram: [@Prnce572002](https://instagram.com/Prnce572002)

## Scope and known constraints

Android 8.0+ (API 26). Sync quality depends on Wi-Fi quality and device audio paths; Bluetooth outputs add delay that the
sync offset compensates. Router client isolation, broadcast filtering and battery management can block discovery or
background playback. Streams are unencrypted — use trusted networks.

YouTube support reads public stream addresses and may stop working when YouTube changes its service. Downloads are
for content the user has the right to keep. Lyrics are looked up by title and artist and can be wrong.
