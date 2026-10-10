#!/usr/bin/env bash
# Runs on the CI runner while an Android emulator is booted. Installs the debug APK, seeds music, walks through the
# app's screens with debug-only deep links, takes screenshots and checks that nothing crashed.
set -u
PKG=com.serena.syncora
APK=${1:-app/build/outputs/apk/debug/app-debug.apk}
OUT=ci-output
mkdir -p "$OUT" /tmp/shots
FAILED=0
note() { echo "[smoke] $*"; }
fail() { echo "[smoke] FAIL: $*"; FAILED=1; }

shot() {
  adb exec-out screencap -p > "/tmp/shots/$1.png" 2>/dev/null
  python3 .github/scripts/to_jpeg.py "/tmp/shots/$1.png" "$OUT/$1.jpg" || fail "could not save screenshot $1"
}
launch() { adb shell am start -W -n "$PKG/.MainActivity" "$@" >/dev/null 2>&1; }
logdiag() { adb logcat -d -s SyncoraDiag:I | tail -n "${1:-3}"; }

note "installing $APK"
adb install -r -g "$APK" || { fail "install failed"; exit 1; }
adb shell pm grant $PKG android.permission.POST_NOTIFICATIONS 2>/dev/null
adb shell pm grant $PKG android.permission.READ_MEDIA_AUDIO 2>/dev/null
adb shell pm grant $PKG android.permission.RECORD_AUDIO 2>/dev/null

note "seeding music"
python3 .github/scripts/make_test_audio.py /tmp/music >/dev/null
adb shell mkdir -p /sdcard/Music
for f in /tmp/music/*.wav; do
  adb push "$f" /sdcard/Music/ >/dev/null
  adb shell am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d "file:///sdcard/Music/$(basename "$f" | sed 's/ /%20/g')" >/dev/null 2>&1
done
adb shell content call --uri content://media --method scan_volume --arg external_primary >/dev/null 2>&1
for i in $(seq 1 30); do
  COUNT=$(adb shell content query --uri content://media/external/audio/media --projection _id 2>/dev/null | grep -c "Row:")
  [ "${COUNT:-0}" -ge 4 ] && break
  sleep 2
done
note "MediaStore audio rows: ${COUNT:-0}"
adb shell content query --uri content://media/external/audio/media --projection _id:title:duration:is_music 2>/dev/null | head -n 8

adb logcat -c
note "launching"
adb shell input keyevent KEYCODE_WAKEUP; adb shell wm dismiss-keyguard >/dev/null 2>&1
launch --es tab player; sleep 4
shot 01_player_empty
launch --es tab source --es source music; sleep 4
shot 02_library
launch --es tab player --es play first; sleep 7
shot 03_player_playing
logdiag 3
launch --es sheet volume; sleep 2; shot 04_volume_sheet
adb shell input keyevent KEYCODE_BACK; sleep 1
launch --es sheet audio; sleep 2; shot 05_audio_sheet
adb shell input keyevent KEYCODE_BACK; sleep 1
launch --es sheet source; sleep 2; shot 06_source_sheet
adb shell input keyevent KEYCODE_BACK; sleep 1
launch --es sheet queue; sleep 2; shot 07_queue_sheet
adb shell input keyevent KEYCODE_BACK; sleep 1
launch --es tab devices; sleep 2; shot 08_devices
launch --es tab settings; sleep 2; shot 09_settings
launch --es tab source --es source radio; sleep 5; shot 10_radio
launch --es tab source --es source dlna; sleep 3; shot 11_dlna
launch --es tab source --es source mic; sleep 3; shot 12_mic
launch --es tab source --es source speaker; sleep 4; shot 13_speaker
launch --es tab player --es source speaker; sleep 2; shot 14_player_speaker
launch --es tab player --es source music --es theme light; sleep 3; shot 15_player_light
launch --es tab source --es theme dark; sleep 3; shot 16_library_again

note "checking for crashes"
adb logcat -d -v time > "$OUT/logcat-full.txt" 2>&1
grep -E "SyncoraDiag|AndroidRuntime|FATAL|Syncora" "$OUT/logcat-full.txt" | tail -n 250 > "$OUT/logcat.txt"
rm -f "$OUT/logcat-full.txt"
if grep -q "FATAL EXCEPTION" "$OUT/logcat.txt"; then
  fail "the app crashed"
  grep -A 25 "FATAL EXCEPTION" "$OUT/logcat.txt" | head -n 60
fi
if ! adb shell pidof $PKG >/dev/null; then fail "the app process is not running"; fi
echo "$FAILED" > "$OUT/smoke-result.txt"
exit $FAILED
