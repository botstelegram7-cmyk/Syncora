package com.serena.syncora.dlna;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.serena.syncora.net.MiniHttpServer;

import org.junit.After;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class DlnaRendererTest {

    static final class FakeController implements DlnaRenderer.Controller {
        final List<String> calls = Collections.synchronizedList(new ArrayList<String>());
        volatile DlnaRenderer.Status status = new DlnaRenderer.Status();

        @Override
        public void onSetUri(String uri, String metadata) {
            calls.add("uri:" + uri);
            DlnaRenderer.Status s = new DlnaRenderer.Status();
            s.uri = uri;
            s.metadata = metadata;
            s.transportState = "STOPPED";
            s.durationMs = 200_000;
            status = s;
        }

        @Override
        public void onPlay() {
            calls.add("play");
            status.transportState = "PLAYING";
            status.positionMs = 65_000;
        }

        @Override
        public void onPause() {
            calls.add("pause");
        }

        @Override
        public void onStop() {
            calls.add("stop");
        }

        @Override
        public void onSeek(long positionMs) {
            calls.add("seek:" + positionMs);
        }

        @Override
        public void onNext() {
            calls.add("next");
        }

        @Override
        public void onPrevious() {
            calls.add("previous");
        }

        @Override
        public void onSetVolume(int percent) {
            calls.add("volume:" + percent);
            status.volume = percent;
        }

        @Override
        public void onSetMute(boolean mute) {
            calls.add("mute:" + mute);
        }

        @Override
        public DlnaRenderer.Status status() {
            return status;
        }
    }

    private final MiniHttpServer http = new MiniHttpServer();
    private final FakeController controller = new FakeController();
    private DlnaRenderer renderer;
    private int port;

    @After
    public void tearDown() {
        http.stop();
        if (renderer != null) renderer.shutdown();
    }

    private void start() throws IOException {
        renderer = new DlnaRenderer("11111111-2222-3333-4444-555555555555", "Syncora (Test)", "0.2.0", controller);
        http.route("/dlna/", new MiniHttpServer.Handler() {
            @Override
            public boolean handle(MiniHttpServer.Request request, Socket socket, InputStream in, OutputStream out) throws IOException {
                return renderer.handle(request, socket, in, out);
            }
        });
        port = http.start(38200, 38300);
    }

    /** Minimal HTTP client; returns the whole response (status line, headers and body). */
    private String request(String method, String path, String headers, String body) throws IOException {
        try (Socket s = new Socket("127.0.0.1", port)) {
            s.setSoTimeout(4000);
            byte[] payload = body == null ? new byte[0] : body.getBytes("UTF-8");
            String head = method + " " + path + " HTTP/1.1\r\nHost: 127.0.0.1:" + port + "\r\n" + headers
                    + (body != null ? "Content-Length: " + payload.length + "\r\n" : "") + "\r\n";
            OutputStream out = s.getOutputStream();
            out.write(head.getBytes("UTF-8"));
            out.write(payload);
            out.flush();
            ByteArrayOutputStream all = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = s.getInputStream().read(buf)) > 0) all.write(buf, 0, n);
            return all.toString("UTF-8");
        }
    }

    private String soap(String service, String serviceType, String action, String args) throws IOException {
        String body = "<?xml version=\"1.0\"?><s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" "
                + "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body><u:" + action + " xmlns:u=\"" + serviceType
                + "\">" + args + "</u:" + action + "></s:Body></s:Envelope>";
        return request("POST", "/dlna/" + service + "/control",
                "Content-Type: text/xml; charset=\"utf-8\"\r\nSOAPACTION: \"" + serviceType + "#" + action + "\"\r\n", body);
    }

    @Test
    public void descriptionsAreServed() throws Exception {
        start();
        String desc = request("GET", "/dlna/desc.xml", "", null);
        assertTrue(desc, desc.startsWith("HTTP/1.1 200 OK"));
        assertTrue(desc.contains("<friendlyName>Syncora (Test)</friendlyName>"));
        assertTrue(desc.contains("uuid:11111111-2222-3333-4444-555555555555"));
        for (String scpd : new String[]{"AVTransport", "RenderingControl", "ConnectionManager"}) {
            String r = request("GET", "/dlna/" + scpd + ".xml", "", null);
            assertTrue(scpd, r.startsWith("HTTP/1.1 200 OK"));
            assertTrue(scpd, r.contains("<scpd"));
        }
        assertTrue(request("GET", "/dlna/nothing", "", null).startsWith("HTTP/1.1 404"));
    }

    @Test
    public void controllerCanCastPlaySeekAndChangeVolume() throws Exception {
        start();
        String set = soap("AVTransport", UpnpXml.AVT, "SetAVTransportURI",
                "<InstanceID>0</InstanceID><CurrentURI>http://192.168.1.9/a.mp3?x=1&amp;y=2</CurrentURI>"
                        + "<CurrentURIMetaData>&lt;DIDL-Lite&gt;&lt;item&gt;&lt;dc:title&gt;Hi&lt;/dc:title&gt;&lt;/item&gt;&lt;/DIDL-Lite&gt;</CurrentURIMetaData>");
        assertTrue(set, set.startsWith("HTTP/1.1 200 OK"));
        assertTrue(set.contains("SetAVTransportURIResponse"));
        assertTrue(controller.calls.contains("uri:http://192.168.1.9/a.mp3?x=1&y=2"));

        assertTrue(soap("AVTransport", UpnpXml.AVT, "Play", "<InstanceID>0</InstanceID><Speed>1</Speed>").startsWith("HTTP/1.1 200"));
        assertTrue(controller.calls.contains("play"));

        String info = soap("AVTransport", UpnpXml.AVT, "GetTransportInfo", "<InstanceID>0</InstanceID>");
        assertTrue(info, info.contains("<CurrentTransportState>PLAYING</CurrentTransportState>"));
        String pos = soap("AVTransport", UpnpXml.AVT, "GetPositionInfo", "<InstanceID>0</InstanceID>");
        assertTrue(pos, pos.contains("<RelTime>0:01:05</RelTime>"));
        assertTrue(pos, pos.contains("<TrackDuration>0:03:20</TrackDuration>"));
        assertTrue(pos, pos.contains("<TrackURI>http://192.168.1.9/a.mp3?x=1&amp;y=2</TrackURI>"));

        soap("AVTransport", UpnpXml.AVT, "Seek", "<InstanceID>0</InstanceID><Unit>REL_TIME</Unit><Target>0:02:00</Target>");
        assertTrue(controller.calls.contains("seek:120000"));
        soap("AVTransport", UpnpXml.AVT, "Pause", "<InstanceID>0</InstanceID>");
        soap("AVTransport", UpnpXml.AVT, "Stop", "<InstanceID>0</InstanceID>");
        soap("AVTransport", UpnpXml.AVT, "Next", "<InstanceID>0</InstanceID>");
        assertTrue(controller.calls.containsAll(java.util.Arrays.asList("pause", "stop", "next")));

        soap("RenderingControl", UpnpXml.RCS, "SetVolume", "<InstanceID>0</InstanceID><Channel>Master</Channel><DesiredVolume>35</DesiredVolume>");
        assertTrue(controller.calls.contains("volume:35"));
        String vol = soap("RenderingControl", UpnpXml.RCS, "GetVolume", "<InstanceID>0</InstanceID><Channel>Master</Channel>");
        assertTrue(vol, vol.contains("<CurrentVolume>35</CurrentVolume>"));
        String protocols = soap("ConnectionManager", UpnpXml.CMS, "GetProtocolInfo", "");
        assertTrue(protocols.contains("http-get:*:audio/mpeg:*"));
    }

    @Test
    public void invalidRequestsGetUpnpErrors() throws Exception {
        start();
        String unknown = soap("AVTransport", UpnpXml.AVT, "Explode", "<InstanceID>0</InstanceID>");
        assertTrue(unknown, unknown.startsWith("HTTP/1.1 500"));
        assertTrue(unknown.contains("<errorCode>401</errorCode>"));
        String badInstance = soap("AVTransport", UpnpXml.AVT, "Stop", "<InstanceID>7</InstanceID>");
        assertTrue(badInstance.contains("<errorCode>718</errorCode>"));
        // Play before anything was cast
        String early = soap("AVTransport", UpnpXml.AVT, "Play", "<InstanceID>0</InstanceID><Speed>1</Speed>");
        assertTrue(early.contains("<errorCode>701</errorCode>"));
        String badVolume = soap("RenderingControl", UpnpXml.RCS, "SetVolume", "<InstanceID>0</InstanceID><Channel>Master</Channel><DesiredVolume>loud</DesiredVolume>");
        assertTrue(badVolume.contains("<errorCode>402</errorCode>"));
        assertTrue(request("GET", "/dlna/AVTransport/control", "", null).startsWith("HTTP/1.1 405"));
        assertTrue(request("POST", "/dlna/AVTransport/control", "", "garbage").contains("<errorCode>401</errorCode>"));
    }

    @Test
    public void subscribersReceiveTheInitialEventAndLaterChanges() throws Exception {
        start();
        ServerSocket callback = new ServerSocket(0);
        callback.setSoTimeout(5000);
        int callbackPort = callback.getLocalPort();
        String response = request("SUBSCRIBE", "/dlna/AVTransport/event",
                "CALLBACK: <http://127.0.0.1:" + callbackPort + "/notify>\r\nNT: upnp:event\r\nTIMEOUT: Second-300\r\n", null);
        assertTrue(response, response.startsWith("HTTP/1.1 200 OK"));
        String sid = null;
        for (String line : response.split("\r\n")) if (line.toUpperCase().startsWith("SID:")) sid = line.substring(4).trim();
        assertTrue(response, sid != null && sid.startsWith("uuid:"));
        assertTrue(response.toUpperCase().contains("TIMEOUT: SECOND-1800"));

        String first = readNotify(callback);
        assertTrue(first, first.startsWith("NOTIFY /notify HTTP/1.1"));
        assertTrue(first, first.contains("SID: " + sid));
        assertTrue(first, first.contains("SEQ: 0"));
        assertTrue(first, first.contains("NO_MEDIA_PRESENT"));

        DlnaRenderer.Status s = new DlnaRenderer.Status();
        s.transportState = "PLAYING";
        s.uri = "http://x/y.mp3";
        controller.status = s;
        renderer.notifyStateChanged();
        String second = readNotify(callback);
        assertTrue(second, second.contains("SEQ: 1"));
        assertTrue(second, second.contains("PLAYING"));
        assertTrue(second, second.contains("http://x/y.mp3"));

        // renew and unsubscribe
        String renewed = request("SUBSCRIBE", "/dlna/AVTransport/event", "SID: " + sid + "\r\nTIMEOUT: Second-300\r\n", null);
        assertTrue(renewed, renewed.startsWith("HTTP/1.1 200 OK"));
        String bye = request("UNSUBSCRIBE", "/dlna/AVTransport/event", "SID: " + sid + "\r\n", null);
        assertTrue(bye, bye.startsWith("HTTP/1.1 200 OK"));
        assertFalse(renderer.subscriberIds().contains(sid));
        String again = request("UNSUBSCRIBE", "/dlna/AVTransport/event", "SID: " + sid + "\r\n", null);
        assertTrue(again.startsWith("HTTP/1.1 412"));
        callback.close();
    }

    private static String readNotify(ServerSocket callback) throws IOException {
        try (Socket s = callback.accept()) {
            s.setSoTimeout(4000);
            InputStream in = s.getInputStream();
            ByteArrayOutputStream all = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int contentLength = -1;
            int headerEnd = -1;
            while (true) {
                int n = in.read(buf);
                if (n < 0) break;
                all.write(buf, 0, n);
                String text = all.toString("UTF-8");
                headerEnd = text.indexOf("\r\n\r\n");
                if (headerEnd >= 0) {
                    for (String line : text.substring(0, headerEnd).split("\r\n")) {
                        if (line.toUpperCase().startsWith("CONTENT-LENGTH:")) contentLength = Integer.parseInt(line.substring(15).trim());
                    }
                    if (all.size() - (headerEnd + 4) >= contentLength) break;
                }
            }
            s.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n".getBytes("UTF-8"));
            return all.toString("UTF-8");
        }
    }

    @Test
    public void callbackHeaderParsing() {
        List<String> urls = DlnaRenderer.parseCallbacks("<http://192.168.1.2:5000/a><http://192.168.1.2:5001/b> <ftp://nope>");
        assertEquals(2, urls.size());
        assertEquals("http://192.168.1.2:5000/a", urls.get(0));
        assertTrue(DlnaRenderer.parseCallbacks(null).isEmpty());
        assertTrue(DlnaRenderer.parseCallbacks("garbage").isEmpty());
    }

    @Test
    public void ssdpHeaderLookup() {
        String m = "M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\nMX: 2\r\nST: ssdp:all\r\n\r\n";
        assertEquals("ssdp:all", SsdpAdvertiser.header(m, "st"));
        assertEquals("2", SsdpAdvertiser.header(m, "MX"));
        assertEquals("\"ssdp:discover\"", SsdpAdvertiser.header(m, "MAN"));
        assertEquals(null, SsdpAdvertiser.header(m, "NT"));
    }
}
