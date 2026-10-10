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

# ---------------------------------------------------------------- end-to-end checks against the real player
roomport() {
  local p
  p=$(adb logcat -d -s SyncoraDiag:I | grep -o 'room=[0-9]*' | tail -n 1 | cut -d= -f2)
  echo "${p:-8787}"
}

note "virtual speaker (independent protocol client) against the player"
launch --es tab player --es source music --es play first; sleep 6
PORT=$(roomport); adb forward tcp:18787 tcp:"$PORT" >/dev/null
if python3 .github/scripts/virtual_speaker.py 18787 "$OUT/virtual-speaker.txt"; then note "virtual speaker OK"; else fail "virtual speaker checks failed"; fi
shot 17_after_virtual_speaker

note "DLNA casting from a file served by the runner"
(cd /tmp/music && nohup python3 -m http.server 9000 --bind 0.0.0.0 >/tmp/http.log 2>&1 &)
sleep 1
launch --es source dlna; sleep 4
PORT=$(roomport); adb forward tcp:18787 tcp:"$PORT" >/dev/null
if bash .github/scripts/dlna_check.sh 18787 "$OUT/dlna.txt"; then note "DLNA OK"; else fail "DLNA checks failed"; fi
launch --es tab player; sleep 2; shot 18_dlna_cast_player
launch --es tab source; sleep 1; shot 19_dlna_page_casting

note "internet radio"
launch --es source radio --es play radio --es tab player; sleep 18
shot 20_radio_playing
RADIO_LINES=$(adb logcat -d -s SyncoraDiag:I | grep -c 'src=RADIO playing=true')
note "radio diagnostics lines: $RADIO_LINES"
echo "radio diag lines with playing=true: $RADIO_LINES" > "$OUT/radio.txt"
adb logcat -d -s Syncora:I Syncora:W | grep -i "radio\|source error" | tail -n 5 >> "$OUT/radio.txt"
if [ "$RADIO_LINES" -lt 5 ]; then fail "radio did not start playing (needs internet from the emulator)"; fi

note "microphone / aux capture"
launch --es source mic --es play mic --es tab source; sleep 7
shot 21_mic_live
MIC_LINES=$(adb logcat -d -s SyncoraDiag:I | grep -c 'src=MIC playing=true')
echo "mic diag lines with playing=true: $MIC_LINES" > "$OUT/mic.txt"
note "mic diagnostics lines: $MIC_LINES"
if [ "$MIC_LINES" -lt 3 ]; then fail "microphone capture did not start"; fi

# ---------------------------------------------------------------- logs
note "collecting logs and checking for crashes"
adb logcat -d -v time > /tmp/logcat-full.txt 2>&1
grep "SyncoraDiag" /tmp/logcat-full.txt > "$OUT/diag.txt"
grep -E "AndroidRuntime|FATAL|I/Syncora |W/Syncora |E/Syncora |ANR" /tmp/logcat-full.txt | tail -n 300 > "$OUT/logcat.txt"
python3 - "$OUT/diag.txt" > "$OUT/diag-summary.txt" <<'PY'
import re, sys, collections
rows = collections.defaultdict(list)
for line in open(sys.argv[1], errors="replace"):
    m = re.search(r"src=(\w+) playing=(\w+) pos=(-?\d+).*?syncErrMs=(-?\d+) aligned=(\w+) queuedChunks=(-?\d+)", line)
    if m and m.group(2) == "true":
        rows[m.group(1)].append((int(m.group(4)), m.group(5) == "true", int(m.group(6))))
for src, r in rows.items():
    errs = [abs(e) for e, a, q in r]
    aligned = sum(1 for e, a, q in r if a)
    print("%-6s samples=%3d aligned=%3d%% maxErr=%4d ms medianErr=%3d ms maxQueued=%d" % (src, len(r), 100 * aligned // len(r), max(errs), sorted(errs)[len(errs) // 2], max(q for e, a, q in r)))
PY
cat "$OUT/diag-summary.txt"
if grep -q "FATAL EXCEPTION" "$OUT/logcat.txt"; then
  fail "the app crashed"
  grep -A 25 "FATAL EXCEPTION" "$OUT/logcat.txt" | head -n 60
fi
if grep -q "W/Syncora .*audio output error" "$OUT/logcat.txt"; then fail "the audio output reported errors"; grep "audio output error" "$OUT/logcat.txt" | head -n 5; fi
if ! adb shell pidof $PKG >/dev/null; then fail "the app process is not running"; fi
echo "$FAILED" > "$OUT/smoke-result.txt"
exit $FAILED
