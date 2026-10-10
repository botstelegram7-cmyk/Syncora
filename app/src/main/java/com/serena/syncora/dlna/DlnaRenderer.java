package com.serena.syncora.dlna;

import com.serena.syncora.net.MiniHttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A UPnP AV MediaRenderer. Controller apps cast songs to it; the actual playback is done by the host engine,
 * which then shares the audio with every connected speaker.
 */
public final class DlnaRenderer {

    /** What the host engine reports about the cast session. */
    public static final class Status {
        public String transportState = "NO_MEDIA_PRESENT";
        public String uri = "";
        public String metadata = "";
        public long positionMs;
        public long durationMs;
        public int volume = 100;
        public boolean mute;

        String signature() {
            return transportState + '|' + uri + '|' + metadata + '|' + durationMs + '|' + volume + '|' + mute;
        }
    }

    public interface Controller {
        void onSetUri(String uri, String metadata);

        void onPlay();

        void onPause();

        void onStop();

        void onSeek(long positionMs);

        void onNext();

        void onPrevious();

        void onSetVolume(int percent);

        void onSetMute(boolean mute);

        Status status();
    }

    private static final class UpnpError extends Exception {
        final int code;

        UpnpError(int code, String message) {
            super(message);
            this.code = code;
        }
    }

    private static final class Subscription {
        final String sid;
        final String service;
        final List<String> callbacks;
        volatile long expiresAt;
        int seq;
        int failures;

        Subscription(String sid, String service, List<String> callbacks, long expiresAt) {
            this.sid = sid;
            this.service = service;
            this.callbacks = callbacks;
            this.expiresAt = expiresAt;
        }
    }

    private static final int SUBSCRIPTION_SECONDS = 1800;

    private final String udn;
    private final String friendlyName;
    private final String modelNumber;
    private final Controller controller;
    private final Map<String, Subscription> subscriptions = new ConcurrentHashMap<>();
    private final ExecutorService notifier = Executors.newSingleThreadExecutor();
    private final AtomicBoolean notifyScheduled = new AtomicBoolean();
    private volatile String lastSignature = "";
    private String nextUri = "";

    public DlnaRenderer(String uuid, String friendlyName, String modelNumber, Controller controller) {
        this.udn = "uuid:" + uuid;
        this.friendlyName = friendlyName;
        this.modelNumber = modelNumber;
        this.controller = controller;
    }

    public void shutdown() {
        subscriptions.clear();
        notifier.shutdownNow();
    }

    // ---------------------------------------------------------------- HTTP

    /** Route handler for everything under /dlna/. */
    public boolean handle(MiniHttpServer.Request request, Socket socket, InputStream in, OutputStream out) throws IOException {
        String path = request.path;
        if ("/dlna/desc.xml".equals(path)) {
            xml(out, UpnpXml.deviceDescription(friendlyName, udn, modelNumber));
            return false;
        }
        String service = serviceOf(path);
        if (service == null) {
            MiniHttpServer.respondText(out, 404, "Not Found", "Not found");
            return false;
        }
        if (path.endsWith(".xml")) {
            if ("AVTransport".equals(service)) xml(out, UpnpXml.avTransportScpd());
            else if ("RenderingControl".equals(service)) xml(out, UpnpXml.renderingControlScpd());
            else xml(out, UpnpXml.connectionManagerScpd());
            return false;
        }
        if (path.endsWith("/control")) {
            control(request, out, service);
            return false;
        }
        if (path.endsWith("/event")) {
            if ("SUBSCRIBE".equals(request.method)) subscribe(request, out, service);
            else if ("UNSUBSCRIBE".equals(request.method)) unsubscribe(request, out);
            else MiniHttpServer.respondText(out, 405, "Method Not Allowed", "");
            return false;
        }
        MiniHttpServer.respondText(out, 404, "Not Found", "Not found");
        return false;
    }

    private static String serviceOf(String path) {
        for (String name : new String[]{"AVTransport", "RenderingControl", "ConnectionManager"}) {
            if (path.equals("/dlna/" + name + ".xml") || path.startsWith("/dlna/" + name + "/")) return name;
        }
        return null;
    }

    private static void xml(OutputStream out, String body) throws IOException {
        MiniHttpServer.respond(out, 200, "OK", "text/xml; charset=\"utf-8\"", body.getBytes(MiniHttpServer.UTF8),
                "SERVER: Android UPnP/1.0 Syncora/0.2");
    }

    private static String serviceType(String name) {
        if ("AVTransport".equals(name)) return UpnpXml.AVT;
        if ("RenderingControl".equals(name)) return UpnpXml.RCS;
        return UpnpXml.CMS;
    }

    // ---------------------------------------------------------------- SOAP control

    private void control(MiniHttpServer.Request request, OutputStream out, String service) throws IOException {
        if (!"POST".equals(request.method)) {
            MiniHttpServer.respondText(out, 405, "Method Not Allowed", "");
            return;
        }
        String[] header = UpnpXml.parseSoapActionHeader(request.header("soapaction"));
        UpnpXml.SoapRequest soap = UpnpXml.parseSoap(request.bodyText());
        String type = serviceType(service);
        String action = header != null ? header[1] : soap != null ? soap.action : null;
        if (action == null || soap == null) {
            fault(out, 401, "Invalid Action");
            return;
        }
        try {
            Map<String, String> result;
            if ("AVTransport".equals(service)) result = avTransport(action, soap);
            else if ("RenderingControl".equals(service)) result = renderingControl(action, soap);
            else result = connectionManager(action, soap);
            MiniHttpServer.respond(out, 200, "OK", "text/xml; charset=\"utf-8\"",
                    UpnpXml.soapResponse(type, action, result).getBytes(MiniHttpServer.UTF8), "EXT:",
                    "SERVER: Android UPnP/1.0 Syncora/0.2");
            scheduleNotify();
        } catch (UpnpError e) {
            fault(out, e.code, e.getMessage());
        } catch (RuntimeException e) {
            fault(out, 501, "Action Failed");
        }
    }

    private static void fault(OutputStream out, int code, String text) throws IOException {
        MiniHttpServer.respond(out, 500, "Internal Server Error", "text/xml; charset=\"utf-8\"",
                UpnpXml.soapFault(code, text).getBytes(MiniHttpServer.UTF8), "EXT:");
    }

    private static void requireInstance(UpnpXml.SoapRequest soap) throws UpnpError {
        String id = soap.args.get("InstanceID");
        if (id != null && !id.trim().isEmpty() && !"0".equals(id.trim())) throw new UpnpError(718, "Invalid InstanceID");
    }

    private Map<String, String> avTransport(String action, UpnpXml.SoapRequest soap) throws UpnpError {
        requireInstance(soap);
        Map<String, String> out = new LinkedHashMap<>();
        Status s = controller.status();
        boolean hasMedia = !s.uri.isEmpty();
        switch (action) {
            case "SetAVTransportURI":
                controller.onSetUri(soap.arg("CurrentURI"), soap.arg("CurrentURIMetaData"));
                break;
            case "SetNextAVTransportURI":
                nextUri = soap.arg("NextURI");
                break;
            case "Play":
                if (!hasMedia) throw new UpnpError(701, "Transition not available");
                controller.onPlay();
                break;
            case "Pause":
                controller.onPause();
                break;
            case "Stop":
                controller.onStop();
                break;
            case "Seek": {
                String unit = soap.arg("Unit");
                if ("REL_TIME".equals(unit) || "ABS_TIME".equals(unit)) {
                    controller.onSeek(UpnpXml.parseTime(soap.arg("Target")));
                } else if ("TRACK_NR".equals(unit)) {
                    controller.onSeek(0);
                } else {
                    throw new UpnpError(710, "Seek mode not supported");
                }
                break;
            }
            case "Next":
                controller.onNext();
                break;
            case "Previous":
                controller.onPrevious();
                break;
            case "SetPlayMode":
                break;
            case "GetTransportInfo":
                out.put("CurrentTransportState", s.transportState);
                out.put("CurrentTransportStatus", "OK");
                out.put("CurrentSpeed", "1");
                break;
            case "GetPositionInfo":
                out.put("Track", hasMedia ? "1" : "0");
                out.put("TrackDuration", UpnpXml.formatTime(s.durationMs));
                out.put("TrackMetaData", s.metadata);
                out.put("TrackURI", s.uri);
                out.put("RelTime", UpnpXml.formatTime(s.positionMs));
                out.put("AbsTime", UpnpXml.formatTime(s.positionMs));
                out.put("RelCount", "2147483647");
                out.put("AbsCount", "2147483647");
                break;
            case "GetMediaInfo":
                out.put("NrTracks", hasMedia ? "1" : "0");
                out.put("MediaDuration", UpnpXml.formatTime(s.durationMs));
                out.put("CurrentURI", s.uri);
                out.put("CurrentURIMetaData", s.metadata);
                out.put("NextURI", nextUri);
                out.put("NextURIMetaData", "");
                out.put("PlayMedium", hasMedia ? "NETWORK" : "NONE");
                out.put("RecordMedium", "NOT_IMPLEMENTED");
                out.put("WriteStatus", "NOT_IMPLEMENTED");
                break;
            case "GetDeviceCapabilities":
                out.put("PlayMedia", "NETWORK");
                out.put("RecMedia", "NOT_IMPLEMENTED");
                out.put("RecQualityModes", "NOT_IMPLEMENTED");
                break;
            case "GetTransportSettings":
                out.put("PlayMode", "NORMAL");
                out.put("RecQualityMode", "NOT_IMPLEMENTED");
                break;
            case "GetCurrentTransportActions":
                out.put("Actions", UpnpXml.transportActions(s.transportState));
                break;
            default:
                throw new UpnpError(401, "Invalid Action");
        }
        return out;
    }

    private Map<String, String> renderingControl(String action, UpnpXml.SoapRequest soap) throws UpnpError {
        requireInstance(soap);
        Map<String, String> out = new LinkedHashMap<>();
        Status s = controller.status();
        switch (action) {
            case "GetVolume":
                out.put("CurrentVolume", String.valueOf(s.volume));
                break;
            case "SetVolume": {
                int v;
                try {
                    v = Integer.parseInt(soap.arg("DesiredVolume").trim());
                } catch (NumberFormatException e) {
                    throw new UpnpError(402, "Invalid Args");
                }
                controller.onSetVolume(Math.max(0, Math.min(100, v)));
                break;
            }
            case "GetMute":
                out.put("CurrentMute", s.mute ? "1" : "0");
                break;
            case "SetMute": {
                String v = soap.arg("DesiredMute").trim();
                controller.onSetMute("1".equals(v) || "true".equalsIgnoreCase(v));
                break;
            }
            case "ListPresets":
                out.put("CurrentPresetNameList", "FactoryDefaults");
                break;
            case "SelectPreset":
                break;
            default:
                throw new UpnpError(401, "Invalid Action");
        }
        return out;
    }

    private Map<String, String> connectionManager(String action, UpnpXml.SoapRequest soap) throws UpnpError {
        Map<String, String> out = new LinkedHashMap<>();
        switch (action) {
            case "GetProtocolInfo":
                out.put("Source", "");
                out.put("Sink", UpnpXml.SINK_PROTOCOLS);
                break;
            case "GetCurrentConnectionIDs":
                out.put("ConnectionIDs", "0");
                break;
            case "GetCurrentConnectionInfo":
                out.put("RcsID", "0");
                out.put("AVTransportID", "0");
                out.put("ProtocolInfo", "");
                out.put("PeerConnectionManager", "");
                out.put("PeerConnectionID", "-1");
                out.put("Direction", "Input");
                out.put("Status", "OK");
                break;
            default:
                throw new UpnpError(401, "Invalid Action");
        }
        return out;
    }

    // ---------------------------------------------------------------- eventing

    private void subscribe(MiniHttpServer.Request request, OutputStream out, String service) throws IOException {
        String sidHeader = request.header("sid");
        long expires = System.currentTimeMillis() + SUBSCRIPTION_SECONDS * 1000L;
        if (sidHeader != null && !sidHeader.isEmpty()) {
            Subscription existing = subscriptions.get(sidHeader);
            if (existing == null) {
                MiniHttpServer.respond(out, 412, "Precondition Failed", "text/plain", new byte[0]);
                return;
            }
            existing.expiresAt = expires;
            subscribeOk(out, existing.sid);
            return;
        }
        List<String> callbacks = parseCallbacks(request.header("callback"));
        if (callbacks.isEmpty()) {
            MiniHttpServer.respond(out, 412, "Precondition Failed", "text/plain", new byte[0]);
            return;
        }
        pruneExpired();
        final Subscription sub = new Subscription("uuid:" + UUID.randomUUID(), service, callbacks, expires);
        subscriptions.put(sub.sid, sub);
        subscribeOk(out, sub.sid);
        // The first event (full state) is sent right after the controller got the SID.
        notifier.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    Thread.sleep(150);
                } catch (InterruptedException e) {
                    return;
                }
                send(sub, bodyFor(sub.service, controller.status()));
            }
        });
    }

    private static void subscribeOk(OutputStream out, String sid) throws IOException {
        MiniHttpServer.respond(out, 200, "OK", "text/plain", new byte[0], "SID: " + sid,
                "TIMEOUT: Second-" + SUBSCRIPTION_SECONDS, "SERVER: Android UPnP/1.0 Syncora/0.2");
    }

    private void unsubscribe(MiniHttpServer.Request request, OutputStream out) throws IOException {
        String sid = request.header("sid");
        if (sid != null && subscriptions.remove(sid) != null) {
            MiniHttpServer.respond(out, 200, "OK", "text/plain", new byte[0]);
        } else {
            MiniHttpServer.respond(out, 412, "Precondition Failed", "text/plain", new byte[0]);
        }
    }

    static List<String> parseCallbacks(String header) {
        List<String> urls = new ArrayList<>();
        if (header == null) return urls;
        int i = 0;
        while (true) {
            int open = header.indexOf('<', i);
            if (open < 0) break;
            int close = header.indexOf('>', open);
            if (close < 0) break;
            String url = header.substring(open + 1, close).trim();
            if (url.startsWith("http://")) urls.add(url);
            i = close + 1;
        }
        return urls;
    }

    private void pruneExpired() {
        long now = System.currentTimeMillis();
        for (Subscription sub : new ArrayList<>(subscriptions.values())) {
            if (sub.expiresAt < now) subscriptions.remove(sub.sid);
        }
    }

    /** Tell subscribers about a change (call whenever the cast session state changes). */
    public void notifyStateChanged() {
        if (subscriptions.isEmpty()) return;
        scheduleNotify();
    }

    private void scheduleNotify() {
        if (subscriptions.isEmpty() || !notifyScheduled.compareAndSet(false, true)) return;
        try {
            notifier.execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        Thread.sleep(120); // coalesce bursts of changes
                    } catch (InterruptedException e) {
                        return;
                    } finally {
                        notifyScheduled.set(false);
                    }
                    Status status = controller.status();
                    String signature = status.signature();
                    if (signature.equals(lastSignature)) return;
                    lastSignature = signature;
                    for (Subscription sub : new ArrayList<>(subscriptions.values())) {
                        if (!"ConnectionManager".equals(sub.service)) send(sub, bodyFor(sub.service, status));
                    }
                }
            });
        } catch (RuntimeException e) {
            notifyScheduled.set(false);
        }
    }

    private String bodyFor(String service, Status s) {
        if ("AVTransport".equals(service)) {
            boolean hasMedia = !s.uri.isEmpty();
            return UpnpXml.propertySet("LastChange",
                    UpnpXml.avtLastChange(s.transportState, s.uri, s.metadata, s.metadata, s.durationMs, hasMedia));
        }
        if ("RenderingControl".equals(service)) {
            return UpnpXml.propertySet("LastChange", UpnpXml.rcsLastChange(s.volume, s.mute));
        }
        return "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<e:propertyset xmlns:e=\"urn:schemas-upnp-org:event-1-0\">"
                + "<e:property><SourceProtocolInfo></SourceProtocolInfo></e:property>"
                + "<e:property><SinkProtocolInfo>" + UpnpXml.esc(UpnpXml.SINK_PROTOCOLS) + "</SinkProtocolInfo></e:property>"
                + "<e:property><CurrentConnectionIDs>0</CurrentConnectionIDs></e:property></e:propertyset>";
    }

    private void send(Subscription sub, String body) {
        for (String callback : sub.callbacks) {
            if (sendNotify(callback, sub.sid, sub.seq, body)) {
                sub.seq++;
                sub.failures = 0;
                return;
            }
        }
        if (++sub.failures >= 3) subscriptions.remove(sub.sid);
    }

    private static boolean sendNotify(String callback, String sid, int seq, String body) {
        try {
            URL url = new URL(callback);
            int port = url.getPort() > 0 ? url.getPort() : 80;
            String path = url.getFile().isEmpty() ? "/" : url.getFile();
            byte[] payload = body.getBytes(MiniHttpServer.UTF8);
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(url.getHost(), port), 2000);
                socket.setSoTimeout(2500);
                String head = "NOTIFY " + path + " HTTP/1.1\r\nHOST: " + url.getHost() + ":" + port
                        + "\r\nCONTENT-TYPE: text/xml; charset=\"utf-8\"\r\nNT: upnp:event\r\nNTS: upnp:propchange\r\nSID: " + sid
                        + "\r\nSEQ: " + seq + "\r\nCONTENT-LENGTH: " + payload.length + "\r\nConnection: close\r\n\r\n";
                OutputStream out = socket.getOutputStream();
                out.write(head.getBytes(MiniHttpServer.UTF8));
                out.write(payload);
                out.flush();
                byte[] response = new byte[64];
                int n = socket.getInputStream().read(response);
                return n > 0;
            }
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    /** For tests and diagnostics. */
    List<String> subscriberIds() {
        List<String> ids = new ArrayList<>(subscriptions.keySet());
        Collections.sort(ids);
        return ids;
    }
}
