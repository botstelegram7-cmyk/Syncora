# Syncora

**One Beat. Every Device.**

Syncora is an Android app concept that turns multiple phones into one synchronized sound system. Use the devices you already have to create a shared listening experience.

## App identity

- **App name:** Syncora
- **Package name:** `com.serena.syncora`
- **Tagline:** One Beat. Every Device.
- **Platform:** Android

## Vision

Syncora aims to let people connect multiple nearby phones and play audio together, making a group of devices feel like one sound system.

## Planned experience

The following describes the product direction, not a claim that these features are already implemented:

- **Create or join a session** to bring devices together.
- **Synchronized playback** designed to keep connected devices as closely in sync as possible.
- **Host controls** for starting, pausing, and managing a session.
- **Simple connection flow** so friends can join without a complicated setup.
- **Clear connection status** to show which devices are connected and ready.

## Important technical considerations

- Network latency and device-specific audio delays can affect synchronization.
- Bluetooth speakers and different phone hardware may introduce additional delay.
- The app will need a reliable discovery/session protocol, clock or playback-offset coordination, and testing across different Android devices.
- Exact synchronization performance should be measured on real devices rather than promised in advance.

## Development status

This repository is at an early stage. Implementation details, supported Android versions, and feature availability will be documented as development progresses.

## Package

```text
com.serena.syncora
```

## License

No license has been specified yet. Until one is added, reuse and redistribution permissions are not explicitly granted.
