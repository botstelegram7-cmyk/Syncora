# Syncora · Product brief

**Tagline:** One Beat. Every Device.

## Product intent

A simple, offline-first way to make multiple nearby Android phones play one shared track together. Syncora borrows the useful multi-device listening concept behind SoundSeeder, while keeping the product focused on local discovery, quick pairing, and a calm, minimal visual language.

## Audience and job to be done

Friends, small gatherings, and everyday users who have a few Android phones but no dedicated multi-room speaker setup. They want to make the devices already in the room behave like one distributed speaker system without depending on mobile data or an online account.

## MVP user stories

- As a host, I can choose an audio file stored on my phone and start a room.
- As a listener, I can discover a room on the same Wi-Fi / hotspot, or enter its room ID or IP address.
- As a host, I can see the room ID, local IP, and which devices have joined.
- As a listener, I hear the host's selected track and remain close to the host's playback position.
- As a listener or host, I can toggle shared playback; the host can change the source track or end the room.
- As a user, I can do all of this without an account, internet connection, or cloud service.

## Experience principles

1. **One obvious next step:** host a room or join one; nearby rooms are visible on the first screen.
2. **Minimal, not sterile:** midnight green-black surfaces, crisp typography, a lime audio signal, and a small custom waveform motif.
3. **Clear connection language:** show room ID, host IP, device count, and offline/local-network status rather than hiding pairing details.
4. **Respect the source:** audio stays on the host and a temporary copy is kept in private cache only while the room is active.

## Offline network design

- UDP broadcast discovery on port 8788 announces the room ID, host name, track title, and audio-service port.
- The host streams the chosen audio file over a small local HTTP server (default TCP port 8787; fallback ports 8788–8797).
- Clients periodically read the host playback snapshot and correct larger playback drift by seeking to the host position. Play/pause requests from clients go back to the host.
- A room ID helps listeners find and validate a room. IP entry is available when broadcast discovery is blocked by a router.
- No cloud relay, internet backend, analytics SDK, or remote content source is included.

## MVP acceptance checks

- A host can choose a local audio file and hear it while advertising a room.
- A second phone on the same LAN can find the room or connect using its ID / host IP, then play the streamed track.
- Host play/pause updates connected clients; client play/pause controls the host.
- The host UI reflects the number/names of connected speakers and displays pairing details.
- Ending the room releases the network server/player and removes the temporary audio cache file.
- The app can be installed from the generated APK and does not require an internet connection for the listening flow.

## Scope and known constraints

The first cut targets Android API 26+ and platform-supported audio formats. Network scheduling and phone audio stacks introduce real-world latency; drift correction is best-effort, not sample-accurate synchronization. Router client isolation, multicast/broadcast filtering, aggressive battery management, and manufacturer hotspot behavior can prevent discovery or playback. On such networks, use a trusted phone hotspot and keep the host session active. Audio is served on the local network without transport encryption, so Syncora is intended for networks the user trusts.
