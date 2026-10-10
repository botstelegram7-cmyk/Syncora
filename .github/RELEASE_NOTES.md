## Syncora v0.2.0 — One Beat. Every Device.

A big step up: Syncora now plays your music, radio, DLNA casts and microphone on every device at the same moment,
with a premium player on all of them.

**Fixed**
- The speaker no longer stays silent with the *"host audio was interrupted"* pop-up. Audio is now sent as timestamped
  sound (no more fragile file streaming + constant re-seeking) and every device plays each moment at the same time.

**New**
- **My Music library** — every song on the phone is listed inside the app (search, sort, shuffle all). No file picker.
- **Shuffle, Loop (repeat the queue) and Repeat one.**
- **Same player on every device** — cover art, title, progress and controls are mirrored on all speakers.
- **Volume panel** — tap the speaker button to set the volume of every connected device with its own slider.
- **Sync** — one-tap re-sync and a *Low-latency sync* switch for the tightest timing.
- **Sources** — My Music · Online Radio · DLNA Renderer · Microphone / Aux · Speaker Mode.
- **Audio settings** — connection state, sync offset with + / −, Reset, and Mono / Stereo / Left / Right channels
  (use two phones as a stereo pair).
- **Speaker Mode** — shows the player, its connection status ("Waiting for connection"), nearby players and a manual IP connect.
- **Settings** — device name, IP address for connect, theme (Dark / AMOLED / Light / System) + accent colours,
  audio mode, animations, and speaker options (auto connect, re-sync frequency).
- Premium dark design with blurred cover backdrops, animated visuals and a media notification with lock-screen controls.

**Install**
Install `Syncora-v0.2.0.apk` on Android 8.0 (API 26) or newer. Allow access to your music when asked.
Devices must be on the same Wi-Fi or hotspot. Radio needs internet on the player device only.

> This build is signed with the repository's *sideload* key (see `signing/README.md`), which is different from the
> v0.1.0 debug build — uninstall v0.1.0 once; later versions will update in place.
