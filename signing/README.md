# Sideload signing key

`syncora-sideload.jks` is the key every Syncora APK from this repository is signed with
(alias `syncora`, password `syncora-sideload`).

It is checked in **on purpose**: it lets each new release install *over* the previous one on a phone
without uninstalling first, no matter which machine or CI run built it.

Because the key is public, anybody could sign an APK with it. That is fine for sideloading your own
builds between your own phones, but **do not use this key for Google Play** or any store. To use a
private key instead, build with these environment variables set (for example from GitHub secrets):

```
SYNCORA_KEYSTORE_PATH=/path/to/your.jks
SYNCORA_KEYSTORE_PASSWORD=...
SYNCORA_KEY_ALIAS=...
SYNCORA_KEY_PASSWORD=...
```
