#!/usr/bin/env bash
# DLNA smoke test through the adb-forwarded port: description, casting a file served from the runner, position polling.
# usage: dlna_check.sh <local-port> <report-file>
PORT=$1
REPORT=$2
: > "$REPORT"
say() { echo "$*" | tee -a "$REPORT"; }
FAIL=0
pass() { say "PASS  $*"; }
fail() { say "FAIL  $*"; FAIL=1; }
soap() {
  curl -s -m 8 -X POST "http://127.0.0.1:$PORT/dlna/AVTransport/control" -H 'Content-Type: text/xml; charset="utf-8"' \
    -H "SOAPACTION: \"urn:schemas-upnp-org:service:AVTransport:1#$1\"" \
    --data "<?xml version=\"1.0\"?><s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body><u:$1 xmlns:u=\"urn:schemas-upnp-org:service:AVTransport:1\">$2</u:$1></s:Body></s:Envelope>"
}
DESC=$(curl -s -m 5 "http://127.0.0.1:$PORT/dlna/desc.xml")
echo "$DESC" | grep -q "MediaRenderer" && pass "device description served" || fail "device description missing"
for s in AVTransport RenderingControl ConnectionManager; do
  curl -s -m 5 "http://127.0.0.1:$PORT/dlna/$s.xml" | grep -q "<scpd" && pass "$s SCPD served" || fail "$s SCPD missing"
done
soap GetTransportInfo '<InstanceID>0</InstanceID>' | grep -q "NO_MEDIA_PRESENT\|STOPPED" && pass "transport starts idle" || fail "transport info"
soap SetAVTransportURI '<InstanceID>0</InstanceID><CurrentURI>http://10.0.2.2:9000/Neon%20Rain.wav</CurrentURI><CurrentURIMetaData>&lt;DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/" xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/"&gt;&lt;item id="1" parentID="0" restricted="1"&gt;&lt;dc:title&gt;Cast Test Song&lt;/dc:title&gt;&lt;upnp:artist&gt;CI Artist&lt;/upnp:artist&gt;&lt;res protocolInfo="http-get:*:audio/wav:*" duration="0:00:24"&gt;http://10.0.2.2:9000/Neon%20Rain.wav&lt;/res&gt;&lt;/item&gt;&lt;/DIDL-Lite&gt;</CurrentURIMetaData>' | grep -q "SetAVTransportURIResponse" && pass "SetAVTransportURI accepted" || fail "SetAVTransportURI"
soap Play '<InstanceID>0</InstanceID><Speed>1</Speed>' | grep -q "PlayResponse" && pass "Play accepted" || fail "Play"
sleep 5
INFO=$(soap GetTransportInfo '<InstanceID>0</InstanceID>')
echo "$INFO" | grep -q "PLAYING" && pass "transport is PLAYING" || fail "transport state: $(echo "$INFO" | grep -o '<CurrentTransportState>[^<]*')"
P1=$(soap GetPositionInfo '<InstanceID>0</InstanceID>' | grep -o '<RelTime>[^<]*' | sed 's/<RelTime>//')
sleep 3
P2=$(soap GetPositionInfo '<InstanceID>0</InstanceID>' | grep -o '<RelTime>[^<]*' | sed 's/<RelTime>//')
say "position $P1 -> $P2"
[ -n "$P1" ] && [ "$P1" != "$P2" ] && pass "position advances while casting" || fail "position does not advance"
soap GetPositionInfo '<InstanceID>0</InstanceID>' | grep -q "Cast Test Song" && pass "metadata title reported" || fail "metadata title missing"
soap Pause '<InstanceID>0</InstanceID>' | grep -q "PauseResponse" && pass "Pause accepted" || fail "Pause"
sleep 1
soap GetTransportInfo '<InstanceID>0</InstanceID>' | grep -q "PAUSED_PLAYBACK" && pass "transport is PAUSED_PLAYBACK" || fail "pause state"
soap Stop '<InstanceID>0</InstanceID>' >/dev/null
exit $FAIL
