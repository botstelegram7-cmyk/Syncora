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
