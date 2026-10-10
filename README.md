# Syncora

**One Beat. Every Device.**

Syncora turns nearby Android phones into a shared sound system. One phone hosts a track from its local storage; other phones join and play the same audio in sync. It is designed for a shared Wi-Fi network or a personal hotspot and does not need an account, a cloud service, or an internet connection.

## What is here

- Native Android app branded **Syncora** (`com.serena.syncora`), with a minimal dark interface and custom adaptive/legacy launcher icons.
- Create a room by choosing an audio file on the host phone.
- Discover rooms on the local network, or join with a `SYNC-XXXXXX` room ID / host IP address.
- Play audio on the host and multiple client phones, with shared play/pause controls and periodic drift correction.
- Show the room ID, host IP, connection status, and joined speakers. Playback continues through a foreground media notification.
- Local-only HTTP audio streaming with byte-range support. The selected file is copied to the host app's private cache for the duration of the room, then removed when the room ends.

## Use it

1. Install Syncora on each Android phone and connect them to the same Wi-Fi network, or turn on one phone's personal hotspot and connect the other phones to it.
2. On the host, tap **Create a room** and choose a local audio file.
3. On another phone, tap the discovered room, or choose **Join a room** and enter the room ID / host IP displayed on the host.
4. Keep the host app running while the room is active. Use the play/pause control on either side; the host controls the source track.

The app does not upload selected audio. The host serves the track directly over the local network (TCP; default port 8787, with a small fallback range). Room discovery uses UDP broadcast on port 8788. Some routers isolate connected devices or block local broadcasts; a personal hotspot is a useful alternative in that case. Audio latency depends on each device and Wi-Fi conditions, so Syncora periodically corrects playback drift rather than promising sample-accurate studio synchronization.

## Build

Requires JDK 17, Android SDK Platform 35 / Build Tools 35.0.0, and Gradle 8.9.

```sh
gradle assembleDebug
```

The installable debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`. The GitHub Actions workflow builds this APK on pushes to the session branch and publishes it to the **Syncora v0.1.0** GitHub Release.

## Privacy and permissions

Syncora has no analytics, sign-in, advertising, or cloud API. Android's `INTERNET` permission is used only for the local host/client sockets. A room ID is used to validate join and playback-control requests; audio and sync metadata are served to devices on the same network, so use a trusted Wi-Fi network or hotspot. The selected source is read through Android's document picker and staged in private app cache while hosting.
