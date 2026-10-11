#!/usr/bin/env bash
# Runs on the CI runner while an Android emulator is booted. Installs the debug APK, seeds music, walks through the
# app's screens with debug-only deep links, takes screenshots and checks that nothing crashed.
set -u
PKG=com.serena.syncora
APK=${1:-app/build/outputs/apk/debug/app-debug.apk}
OUT=ci-output
mkdir -p "$OUT" /tmp/shots
FAILED=0
START=$(date +%s)
note() { echo "[smoke +$(( $(date +%s) - START ))s] $*"; echo "[+$(( $(date +%s) - START ))s] $*" >> "$OUT/progress.txt"; }
fail() { echo "[smoke] FAIL: $*"; echo "FAIL: $*" >> "$OUT/progress.txt"; FAILED=1; }

# Push what we have so far, so a later hang cannot hide earlier results.
publish_partial() {
  [ -n "${CI_SHOTS:-}" ] && [ -n "${GITHUB_REF_NAME:-}" ] && [ "${GITHUB_EVENT_NAME:-}" = "push" ] || return 0
  (
    git config user.name "syncora-ci" && git config user.email "ci@users.noreply.github.com" &&
    git add -f ci-output >/dev/null 2>&1 &&
    git commit -qm "CI: partial results after $1 [skip ci]" >/dev/null 2>&1 &&
    timeout 60 git pull --rebase -q origin "$GITHUB_REF_NAME" >/dev/null 2>&1 &&
    timeout 60 git push -q origin "HEAD:$GITHUB_REF_NAME" >/dev/null 2>&1
  ) || true
}

shot() {
  timeout 30 adb exec-out screencap -p > "/tmp/shots/$1.png" 2>/dev/null
  python3 .github/scripts/to_jpeg.py "/tmp/shots/$1.png" "$OUT/$1.jpg" || fail "could not save screenshot $1"
}
launch() { timeout 40 adb shell am start -W -n "$PKG/.MainActivity" "$@" >/dev/null 2>&1; }
logdiag() { timeout 30 adb logcat -d -s SyncoraDiag:I | tail -n "${1:-3}"; }

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
# first run: the library asks for access to the music before it can list anything
timeout 30 adb shell pm revoke $PKG android.permission.READ_MEDIA_AUDIO; sleep 2
launch --es tab source --es source music; sleep 4
shot 01b_library_permission_card
timeout 30 adb shell pm grant $PKG android.permission.READ_MEDIA_AUDIO; sleep 1
timeout 30 adb shell am force-stop $PKG; sleep 1
launch --es tab source --es source music; sleep 5
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
timeout 20 adb shell input swipe 540 1900 540 600 400; sleep 1; shot 09b_settings_scrolled
launch --es tab source --es source radio; sleep 5; shot 10_radio
launch --es tab source --es source dlna; sleep 3; shot 11_dlna
launch --es tab source --es source mic; sleep 3; shot 12_mic
launch --es tab source --es source speaker; sleep 4; shot 13_speaker
launch --es tab player --es source speaker; sleep 2; shot 14_player_speaker
launch --es tab player --es source music --es theme light; sleep 3; shot 15_player_light
launch --es tab source --es theme dark; sleep 3; shot 16_library_again
publish_partial "UI tour"

# ---------------------------------------------------------------- end-to-end checks against the real player
roomport() {
  local p
  p=$(adb logcat -d -s SyncoraDiag:I | grep -o 'room=[0-9]*' | tail -n 1 | cut -d= -f2)
  echo "${p:-8787}"
}

note "virtual speaker (independent protocol client) against the player"
launch --es tab player --es source music --es play first; sleep 6
PORT=$(roomport); adb forward tcp:18787 tcp:"$PORT" >/dev/null
if timeout 150 python3 .github/scripts/virtual_speaker.py 18787 "$OUT/virtual-speaker.txt"; then note "virtual speaker OK"; else fail "virtual speaker checks failed"; fi
shot 17_after_virtual_speaker
publish_partial "virtual speaker"

note "speaker role against an independent virtual player"
(setsid nohup python3 .github/scripts/virtual_player.py 8787 "$OUT/virtual-player.txt" 95 toggle,next,seek > /tmp/virtual-player.log 2>&1 < /dev/null &)
sleep 3
launch --es source speaker --es connect 10.0.2.2:8787 --es tab player; sleep 14
shot 22_speaker_connected_player
launch --es tab source; sleep 2; shot 23_speaker_connected_page
launch --es tab devices; sleep 2; shot 24_speaker_devices
launch --es tab player --es sheet volume; sleep 2; shot 25_speaker_volume_sheet
timeout 20 adb shell input keyevent KEYCODE_BACK; sleep 1
launch --es cmd toggle; sleep 3; shot 26_speaker_paused_by_command
launch --es cmd toggle; sleep 4
launch --es cmd next; sleep 4; shot 27_speaker_next_song
launch --es cmd seek; sleep 3
launch --es cmd offset; sleep 2
launch --es cmd left; sleep 2
launch --es sheet audio; sleep 2; shot 28_speaker_audio_sheet
timeout 20 adb shell input keyevent KEYCODE_BACK; sleep 1
note "waiting for the virtual player to finish"
for i in $(seq 1 45); do pgrep -f virtual_player.py >/dev/null || break; sleep 2; done
if [ -f "$OUT/virtual-player.txt" ] && ! grep -q "^FAILED CHECKS: [1-9]" /tmp/virtual-player.log; then note "speaker role OK"; else fail "speaker role checks failed"; fi
tail -n 40 /tmp/virtual-player.log | cut -c1-240
cp /tmp/virtual-player.log "$OUT/virtual-player-log.txt" 2>/dev/null
publish_partial "speaker role"

note "DLNA casting from a file served by the runner"
(cd /tmp/music && setsid nohup python3 -m http.server 9000 --bind 0.0.0.0 >/tmp/http.log 2>&1 < /dev/null &)
sleep 1
launch --es source dlna; sleep 4
PORT=$(roomport); adb forward tcp:18787 tcp:"$PORT" >/dev/null
if timeout 120 bash .github/scripts/dlna_check.sh 18787 "$OUT/dlna.txt"; then note "DLNA OK"; else fail "DLNA checks failed"; fi
launch --es tab player; sleep 2; shot 18_dlna_cast_player
launch --es tab source; sleep 1; shot 19_dlna_page_casting
pkill -f "http.server 9000" >/dev/null 2>&1
publish_partial "DLNA"

note "Akashvani HLS radio"
launch --es source radio --es play akashvani --es tab player; sleep 30
shot 20_radio_akashvani_hls
HLS_PLAYING=$(adb logcat -d -s SyncoraDiag:I | grep -c 'src=RADIO playing=true.*station=air-5' || true)
HLS_READY=$(adb logcat -d -s SyncoraDiag:I | grep 'src=RADIO playing=true' | grep -c 'buffering=false station=air-5' || true)
note "Akashvani HLS diagnostics: playing=$HLS_PLAYING ready=$HLS_READY"
echo "Akashvani HLS: src=RADIO playing=true lines=$HLS_PLAYING decoded/ready lines=$HLS_READY" > "$OUT/radio.txt"
if [ "$HLS_PLAYING" -lt 5 ] || [ "$HLS_READY" -lt 3 ]; then fail "Akashvani HLS radio did not produce playing PCM diagnostics"; fi
publish_partial "Akashvani HLS radio"

note "Indian MP3 radio"
launch --es source radio --es play india-mp3 --es tab player; sleep 24
shot 20b_radio_india_mp3
INDIA_PLAYING=$(adb logcat -d -s SyncoraDiag:I | grep 'src=RADIO playing=true' | grep -vc 'station=air-5' || true)
INDIA_READY=$(adb logcat -d -s SyncoraDiag:I | grep 'src=RADIO playing=true' | grep 'buffering=false' | grep -vc 'station=air-5' || true)
note "Indian MP3 diagnostics: playing=$INDIA_PLAYING ready=$INDIA_READY"
echo "Indian MP3: src=RADIO playing=true lines=$INDIA_PLAYING decoded/ready lines=$INDIA_READY" >> "$OUT/radio.txt"
adb logcat -d -s Syncora:I Syncora:W | grep -i "radio\|source error" | tail -n 8 >> "$OUT/radio.txt"
if [ "$INDIA_PLAYING" -lt 5 ] || [ "$INDIA_READY" -lt 3 ]; then fail "Indian MP3 radio did not produce playing PCM diagnostics"; fi
publish_partial "Indian MP3 radio"

note "microphone / aux capture"
timeout 60 adb logcat -d -t 1 >/dev/null 2>&1
launch --es source mic --es play mic --es tab source; sleep 7
shot 21_mic_live
MIC_LINES=$(adb logcat -d -s SyncoraDiag:I | grep -c 'src=MIC playing=true')
echo "mic diag lines with playing=true: $MIC_LINES" > "$OUT/mic.txt"
note "mic diagnostics lines: $MIC_LINES"
if [ "$MIC_LINES" -lt 3 ]; then fail "microphone capture did not start"; fi
publish_partial "microphone"

# ---------------------------------------------------------------- logs
note "collecting logs and checking for crashes"
timeout 90 adb logcat -d -v time > /tmp/logcat-full.txt 2>&1
grep "SyncoraDiag" /tmp/logcat-full.txt > "$OUT/diag.txt"
grep -E "AndroidRuntime|FATAL|I/Syncora |W/Syncora |E/Syncora |ANR" /tmp/logcat-full.txt | tail -n 300 > "$OUT/logcat.txt"
python3 - "$OUT/diag.txt" > "$OUT/diag-summary.txt" <<'PY'
import re, sys, collections
rows = collections.defaultdict(list)
for line in open(sys.argv[1], errors="replace"):
    m = re.search(r"src=(\w+) playing=(\w+) pos=(-?\d+).*?syncErrMs=(-?\d+) aligned=(\w+) queuedChunks=(-?\d+)", line)
    if m and m.group(2) == "true":
        rows[m.group(1)].append((int(m.group(4)), m.group(5) == "true", int(m.group(6))))
bad = []
for src, r in rows.items():
    errs = [abs(e) for e, a, q in r]
    aligned = sum(1 for e, a, q in r if a)
    median = sorted(errs)[len(errs) // 2]
    print("%-7s samples=%3d aligned=%3d%% maxErr=%5d ms medianErr=%3d ms maxQueued=%d" % (src, len(r), 100 * aligned // len(r), max(errs), median, max(q for e, a, q in r)))
    if src != "MIC" and median > 100:
        bad.append(src)
for src in ("MUSIC", "DLNA", "RADIO", "SPEAKER"):
    if src not in rows:
        bad.append(src + " (no samples)")
if bad:
    print("SYNC DIAGNOSTICS OUT OF RANGE: %s" % bad)
    sys.exit(1)
PY
[ $? -ne 0 ] && fail "sync diagnostics out of range (see diag-summary.txt)"
cat "$OUT/diag-summary.txt"
if grep -q "FATAL EXCEPTION" "$OUT/logcat.txt"; then
  fail "the app crashed"
  grep -A 25 "FATAL EXCEPTION" "$OUT/logcat.txt" | head -n 60
fi
if grep -q "W/Syncora .*audio output error" "$OUT/logcat.txt"; then fail "the audio output reported errors"; grep "audio output error" "$OUT/logcat.txt" | head -n 5; fi
if ! timeout 20 adb shell pidof $PKG >/dev/null; then fail "the app process is not running"; fi
echo "$FAILED" > "$OUT/smoke-result.txt"
exit $FAILED
