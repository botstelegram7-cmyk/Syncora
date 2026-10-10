package com.serena.syncora.dlna;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import java.io.StringReader;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

/** XML documents and parsers for a UPnP/DLNA MediaRenderer (AVTransport, RenderingControl, ConnectionManager). */
public final class UpnpXml {
    public static final String AVT = "urn:schemas-upnp-org:service:AVTransport:1";
    public static final String RCS = "urn:schemas-upnp-org:service:RenderingControl:1";
    public static final String CMS = "urn:schemas-upnp-org:service:ConnectionManager:1";
    public static final String DEVICE_TYPE = "urn:schemas-upnp-org:device:MediaRenderer:1";

    public static final String SINK_PROTOCOLS = "http-get:*:audio/mpeg:*,http-get:*:audio/mp3:*,http-get:*:audio/mp4:*,"
            + "http-get:*:audio/x-m4a:*,http-get:*:audio/aac:*,http-get:*:audio/x-aac:*,http-get:*:audio/flac:*,"
            + "http-get:*:audio/x-flac:*,http-get:*:audio/ogg:*,http-get:*:audio/x-ogg:*,http-get:*:audio/opus:*,"
            + "http-get:*:audio/wav:*,http-get:*:audio/x-wav:*,http-get:*:audio/L16:*,http-get:*:audio/3gpp:*,"
            + "http-get:*:audio/x-ms-wma:*,http-get:*:application/ogg:*";

    private UpnpXml() {
    }

    // ---------------------------------------------------------------- escaping

    public static String esc(String s) {
        if (s == null) return "";
        StringBuilder out = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&':
                    out.append("&amp;");
                    break;
                case '<':
                    out.append("&lt;");
                    break;
                case '>':
                    out.append("&gt;");
                    break;
                case '"':
                    out.append("&quot;");
                    break;
                case '\'':
                    out.append("&apos;");
                    break;
                default:
                    if (c >= 0x20 || c == '\n' || c == '\t' || c == '\r') out.append(c);
            }
        }
        return out.toString();
    }

    // ---------------------------------------------------------------- device description

    public static String deviceDescription(String friendlyName, String udn, String modelNumber) {
        StringBuilder x = new StringBuilder(2048);
        x.append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n");
        x.append("<root xmlns=\"urn:schemas-upnp-org:device-1-0\" xmlns:dlna=\"urn:schemas-dlna-org:device-1-0\">");
        x.append("<specVersion><major>1</major><minor>0</minor></specVersion>");
        x.append("<device>");
        x.append("<deviceType>").append(DEVICE_TYPE).append("</deviceType>");
        x.append("<friendlyName>").append(esc(friendlyName)).append("</friendlyName>");
        x.append("<manufacturer>Syncora</manufacturer>");
        x.append("<manufacturerURL>https://github.com/botstelegram7-cmyk/Syncora</manufacturerURL>");
        x.append("<modelDescription>Syncora multi-room audio renderer</modelDescription>");
        x.append("<modelName>Syncora</modelName>");
        x.append("<modelNumber>").append(esc(modelNumber)).append("</modelNumber>");
        x.append("<modelURL>https://github.com/botstelegram7-cmyk/Syncora</modelURL>");
        x.append("<serialNumber>").append(esc(udn.replace("uuid:", ""))).append("</serialNumber>");
        x.append("<UDN>").append(esc(udn)).append("</UDN>");
        x.append("<dlna:X_DLNADOC>DMR-1.50</dlna:X_DLNADOC>");
        x.append("<serviceList>");
        service(x, AVT, "urn:upnp-org:serviceId:AVTransport", "AVTransport");
        service(x, RCS, "urn:upnp-org:serviceId:RenderingControl", "RenderingControl");
        service(x, CMS, "urn:upnp-org:serviceId:ConnectionManager", "ConnectionManager");
        x.append("</serviceList>");
        x.append("</device></root>");
        return x.toString();
    }

    private static void service(StringBuilder x, String type, String id, String name) {
        x.append("<service><serviceType>").append(type).append("</serviceType><serviceId>").append(id)
                .append("</serviceId><SCPDURL>/dlna/").append(name).append(".xml</SCPDURL><controlURL>/dlna/").append(name)
                .append("/control</controlURL><eventSubURL>/dlna/").append(name).append("/event</eventSubURL></service>");
    }

    // ---------------------------------------------------------------- service descriptions (SCPD)

    private static String in(String name, String related) {
        return "<argument><name>" + name + "</name><direction>in</direction><relatedStateVariable>" + related
                + "</relatedStateVariable></argument>";
    }

    private static String out(String name, String related) {
        return "<argument><name>" + name + "</name><direction>out</direction><relatedStateVariable>" + related
                + "</relatedStateVariable></argument>";
    }

    private static String action(String name, String... args) {
        StringBuilder x = new StringBuilder("<action><name>").append(name).append("</name>");
        if (args.length > 0) {
            x.append("<argumentList>");
            for (String a : args) x.append(a);
            x.append("</argumentList>");
        }
        return x.append("</action>").toString();
    }

    private static String var(String name, String type, boolean events, String... allowed) {
        StringBuilder x = new StringBuilder("<stateVariable sendEvents=\"").append(events ? "yes" : "no").append("\"><name>")
                .append(name).append("</name><dataType>").append(type).append("</dataType>");
        if (allowed.length > 0) {
            x.append("<allowedValueList>");
            for (String v : allowed) x.append("<allowedValue>").append(v).append("</allowedValue>");
            x.append("</allowedValueList>");
        }
        return x.append("</stateVariable>").toString();
    }

    private static String scpd(String actions, String variables) {
        return "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<scpd xmlns=\"urn:schemas-upnp-org:service-1-0\">"
                + "<specVersion><major>1</major><minor>0</minor></specVersion><actionList>" + actions
                + "</actionList><serviceStateTable>" + variables + "</serviceStateTable></scpd>";
    }

    public static String avTransportScpd() {
        String id = in("InstanceID", "A_ARG_TYPE_InstanceID");
        String actions = action("SetAVTransportURI", id, in("CurrentURI", "AVTransportURI"),
                in("CurrentURIMetaData", "AVTransportURIMetaData"))
                + action("SetNextAVTransportURI", id, in("NextURI", "NextAVTransportURI"),
                in("NextURIMetaData", "NextAVTransportURIMetaData"))
                + action("GetMediaInfo", id, out("NrTracks", "NumberOfTracks"), out("MediaDuration", "CurrentMediaDuration"),
                out("CurrentURI", "AVTransportURI"), out("CurrentURIMetaData", "AVTransportURIMetaData"),
                out("NextURI", "NextAVTransportURI"), out("NextURIMetaData", "NextAVTransportURIMetaData"),
                out("PlayMedium", "PlaybackStorageMedium"), out("RecordMedium", "RecordStorageMedium"),
                out("WriteStatus", "RecordMediumWriteStatus"))
                + action("GetTransportInfo", id, out("CurrentTransportState", "TransportState"),
                out("CurrentTransportStatus", "TransportStatus"), out("CurrentSpeed", "TransportPlaySpeed"))
                + action("GetPositionInfo", id, out("Track", "CurrentTrack"), out("TrackDuration", "CurrentTrackDuration"),
                out("TrackMetaData", "CurrentTrackMetaData"), out("TrackURI", "CurrentTrackURI"),
                out("RelTime", "RelativeTimePosition"), out("AbsTime", "AbsoluteTimePosition"),
                out("RelCount", "RelativeCounterPosition"), out("AbsCount", "AbsoluteCounterPosition"))
                + action("GetDeviceCapabilities", id, out("PlayMedia", "PossiblePlaybackStorageMedia"),
                out("RecMedia", "PossibleRecordStorageMedia"), out("RecQualityModes", "PossibleRecordQualityModes"))
                + action("GetTransportSettings", id, out("PlayMode", "CurrentPlayMode"),
                out("RecQualityMode", "CurrentRecordQualityMode"))
                + action("Stop", id)
                + action("Play", id, in("Speed", "TransportPlaySpeed"))
                + action("Pause", id)
                + action("Seek", id, in("Unit", "A_ARG_TYPE_SeekMode"), in("Target", "A_ARG_TYPE_SeekTarget"))
                + action("Next", id)
                + action("Previous", id)
                + action("SetPlayMode", id, in("NewPlayMode", "CurrentPlayMode"))
                + action("GetCurrentTransportActions", id, out("Actions", "CurrentTransportActions"));
        String vars = var("TransportState", "string", false, "STOPPED", "PAUSED_PLAYBACK", "PLAYING", "TRANSITIONING",
                "NO_MEDIA_PRESENT")
                + var("TransportStatus", "string", false, "OK", "ERROR_OCCURRED")
                + var("PlaybackStorageMedium", "string", false, "NONE", "NETWORK", "UNKNOWN")
                + var("RecordStorageMedium", "string", false, "NOT_IMPLEMENTED")
                + var("PossiblePlaybackStorageMedia", "string", false)
                + var("PossibleRecordStorageMedia", "string", false)
                + var("CurrentPlayMode", "string", false, "NORMAL", "REPEAT_ONE", "REPEAT_ALL", "SHUFFLE")
                + var("TransportPlaySpeed", "string", false, "1")
                + var("RecordMediumWriteStatus", "string", false, "NOT_IMPLEMENTED")
                + var("CurrentRecordQualityMode", "string", false, "NOT_IMPLEMENTED")
                + var("PossibleRecordQualityModes", "string", false)
                + var("NumberOfTracks", "ui4", false)
                + var("CurrentTrack", "ui4", false)
                + var("CurrentTrackDuration", "string", false)
                + var("CurrentMediaDuration", "string", false)
                + var("CurrentTrackMetaData", "string", false)
                + var("CurrentTrackURI", "string", false)
                + var("AVTransportURI", "string", false)
                + var("AVTransportURIMetaData", "string", false)
                + var("NextAVTransportURI", "string", false)
                + var("NextAVTransportURIMetaData", "string", false)
                + var("RelativeTimePosition", "string", false)
                + var("AbsoluteTimePosition", "string", false)
                + var("RelativeCounterPosition", "i4", false)
                + var("AbsoluteCounterPosition", "ui4", false)
                + var("CurrentTransportActions", "string", false)
                + var("LastChange", "string", true)
                + var("A_ARG_TYPE_SeekMode", "string", false, "TRACK_NR", "REL_TIME", "ABS_TIME", "ABS_COUNT", "REL_COUNT")
                + var("A_ARG_TYPE_SeekTarget", "string", false)
                + var("A_ARG_TYPE_InstanceID", "ui4", false);
        return scpd(actions, vars);
    }

    public static String renderingControlScpd() {
        String id = in("InstanceID", "A_ARG_TYPE_InstanceID");
        String channel = in("Channel", "A_ARG_TYPE_Channel");
        String actions = action("ListPresets", id, out("CurrentPresetNameList", "PresetNameList"))
                + action("SelectPreset", id, in("PresetName", "A_ARG_TYPE_PresetName"))
                + action("GetMute", id, channel, out("CurrentMute", "Mute"))
                + action("SetMute", id, channel, in("DesiredMute", "Mute"))
                + action("GetVolume", id, channel, out("CurrentVolume", "Volume"))
                + action("SetVolume", id, channel, in("DesiredVolume", "Volume"));
        String vars = var("PresetNameList", "string", false)
                + var("LastChange", "string", true)
                + var("Mute", "boolean", false)
                + "<stateVariable sendEvents=\"no\"><name>Volume</name><dataType>ui2</dataType><allowedValueRange>"
                + "<minimum>0</minimum><maximum>100</maximum><step>1</step></allowedValueRange></stateVariable>"
                + var("A_ARG_TYPE_Channel", "string", false, "Master")
                + var("A_ARG_TYPE_InstanceID", "ui4", false)
                + var("A_ARG_TYPE_PresetName", "string", false, "FactoryDefaults");
        return scpd(actions, vars);
    }

    public static String connectionManagerScpd() {
        String actions = action("GetProtocolInfo", out("Source", "SourceProtocolInfo"), out("Sink", "SinkProtocolInfo"))
                + action("GetCurrentConnectionIDs", out("ConnectionIDs", "CurrentConnectionIDs"))
                + action("GetCurrentConnectionInfo", in("ConnectionID", "A_ARG_TYPE_ConnectionID"),
                out("RcsID", "A_ARG_TYPE_RcsID"), out("AVTransportID", "A_ARG_TYPE_AVTransportID"),
                out("ProtocolInfo", "A_ARG_TYPE_ProtocolInfo"),
                out("PeerConnectionManager", "A_ARG_TYPE_ConnectionManager"),
                out("PeerConnectionID", "A_ARG_TYPE_ConnectionID"), out("Direction", "A_ARG_TYPE_Direction"),
                out("Status", "A_ARG_TYPE_ConnectionStatus"));
        String vars = var("SourceProtocolInfo", "string", true)
                + var("SinkProtocolInfo", "string", true)
                + var("CurrentConnectionIDs", "string", true)
                + var("A_ARG_TYPE_ConnectionStatus", "string", false, "OK", "ContentFormatMismatch", "InsufficientBandwidth",
                "UnreliableChannel", "Unknown")
                + var("A_ARG_TYPE_ConnectionManager", "string", false)
                + var("A_ARG_TYPE_Direction", "string", false, "Input", "Output")
                + var("A_ARG_TYPE_ProtocolInfo", "string", false)
                + var("A_ARG_TYPE_ConnectionID", "i4", false)
                + var("A_ARG_TYPE_AVTransportID", "i4", false)
                + var("A_ARG_TYPE_RcsID", "i4", false);
        return scpd(actions, vars);
    }

    // ---------------------------------------------------------------- SOAP

    public static final class SoapRequest {
        public final String action;
        public final Map<String, String> args;

        SoapRequest(String action, Map<String, String> args) {
            this.action = action;
            this.args = args;
        }

        public String arg(String name) {
            String v = args.get(name);
            return v == null ? "" : v;
        }
    }

    /** Parses a SOAP envelope (the action element and its arguments). Returns null when it is not valid. */
    public static SoapRequest parseSoap(String xml) {
        try {
            Document doc = parse(xml);
            Element body = firstByLocalName(doc.getDocumentElement(), "Body");
            if (body == null) return null;
            Element actionElement = firstElementChild(body);
            if (actionElement == null) return null;
            Map<String, String> args = new LinkedHashMap<>();
            NodeList children = actionElement.getChildNodes();
            for (int i = 0; i < children.getLength(); i++) {
                Node n = children.item(i);
                if (n.getNodeType() == Node.ELEMENT_NODE) args.put(localName(n), n.getTextContent());
            }
            return new SoapRequest(localName(actionElement), args);
        } catch (Exception e) {
            return null;
        }
    }

    public static String soapResponse(String serviceType, String action, Map<String, String> outArgs) {
        StringBuilder x = new StringBuilder(512);
        x.append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n");
        x.append("<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" "
                + "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body>");
        x.append("<u:").append(action).append("Response xmlns:u=\"").append(serviceType).append("\">");
        if (outArgs != null) {
            for (Map.Entry<String, String> e : outArgs.entrySet()) {
                x.append('<').append(e.getKey()).append('>').append(esc(e.getValue())).append("</").append(e.getKey()).append('>');
            }
        }
        x.append("</u:").append(action).append("Response></s:Body></s:Envelope>");
        return x.toString();
    }

    public static String soapFault(int code, String description) {
        return "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" "
                + "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body><s:Fault>"
                + "<faultcode>s:Client</faultcode><faultstring>UPnPError</faultstring><detail>"
                + "<UPnPError xmlns=\"urn:schemas-upnp-org:control-1-0\"><errorCode>" + code + "</errorCode>"
                + "<errorDescription>" + esc(description) + "</errorDescription></UPnPError></detail></s:Fault></s:Body></s:Envelope>";
    }

    /** From {@code "urn:...:service:AVTransport:1#Play"} (quotes optional) returns {serviceType, action} or null. */
    public static String[] parseSoapActionHeader(String header) {
        if (header == null) return null;
        String h = header.trim();
        if (h.startsWith("\"") && h.endsWith("\"") && h.length() >= 2) h = h.substring(1, h.length() - 1);
        int hash = h.lastIndexOf('#');
        if (hash <= 0 || hash == h.length() - 1) return null;
        return new String[]{h.substring(0, hash), h.substring(hash + 1)};
    }

    // ---------------------------------------------------------------- eventing

    public static String avtLastChange(String state, String uri, String uriMeta, String trackMeta, long durationMs, boolean hasMedia) {
        String duration = formatTime(durationMs);
        StringBuilder x = new StringBuilder(1024);
        x.append("<Event xmlns=\"urn:schemas-upnp-org:metadata-1-0/AVT/\"><InstanceID val=\"0\">");
        val(x, "TransportState", state);
        val(x, "TransportStatus", "OK");
        val(x, "PlaybackStorageMedium", hasMedia ? "NETWORK" : "NONE");
        val(x, "CurrentPlayMode", "NORMAL");
        val(x, "TransportPlaySpeed", "1");
        val(x, "NumberOfTracks", hasMedia ? "1" : "0");
        val(x, "CurrentTrack", hasMedia ? "1" : "0");
        val(x, "CurrentTrackDuration", duration);
        val(x, "CurrentMediaDuration", duration);
        val(x, "CurrentTrackURI", uri);
        val(x, "AVTransportURI", uri);
        val(x, "CurrentTrackMetaData", trackMeta);
        val(x, "AVTransportURIMetaData", uriMeta);
        val(x, "CurrentTransportActions", transportActions(state));
        x.append("</InstanceID></Event>");
        return x.toString();
    }

    public static String rcsLastChange(int volumePercent, boolean mute) {
        return "<Event xmlns=\"urn:schemas-upnp-org:metadata-1-0/RCS/\"><InstanceID val=\"0\">"
                + "<Volume channel=\"Master\" val=\"" + volumePercent + "\"/>"
                + "<Mute channel=\"Master\" val=\"" + (mute ? 1 : 0) + "\"/></InstanceID></Event>";
    }

    private static void val(StringBuilder x, String name, String value) {
        x.append('<').append(name).append(" val=\"").append(esc(value)).append("\"/>");
    }

    public static String propertySet(String variable, String value) {
        return "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<e:propertyset xmlns:e=\"urn:schemas-upnp-org:event-1-0\">"
                + "<e:property><" + variable + ">" + esc(value) + "</" + variable + "></e:property></e:propertyset>";
    }

    public static String transportActions(String state) {
        if ("PLAYING".equals(state)) return "Pause,Stop,Seek,Next,Previous";
        if ("PAUSED_PLAYBACK".equals(state)) return "Play,Stop,Seek,Next,Previous";
        if ("STOPPED".equals(state)) return "Play,Seek,Next,Previous";
        return "";
    }

    // ---------------------------------------------------------------- DIDL-Lite

    public static final class Didl {
        public String title = "";
        public String artist = "";
        public String album = "";
        public String albumArtUri = "";
        public String resourceUri = "";
        public long durationMs;
    }

    /** Reads the first item of a DIDL-Lite document. Never fails: unknown / broken metadata gives empty fields. */
    public static Didl parseDidl(String xml) {
        Didl didl = new Didl();
        if (xml == null || xml.trim().isEmpty() || "NOT_IMPLEMENTED".equals(xml.trim())) return didl;
        try {
            Document doc = parse(xml);
            Element item = firstByLocalName(doc.getDocumentElement(), "item");
            if (item == null) item = firstByLocalName(doc.getDocumentElement(), "container");
            if (item == null) return didl;
            didl.title = text(item, "title");
            didl.artist = text(item, "artist");
            if (didl.artist.isEmpty()) didl.artist = text(item, "creator");
            didl.album = text(item, "album");
            didl.albumArtUri = text(item, "albumArtURI");
            Element res = firstByLocalName(item, "res");
            if (res != null) {
                didl.resourceUri = res.getTextContent().trim();
                String duration = res.getAttribute("duration");
                if (duration != null && !duration.isEmpty()) didl.durationMs = parseTime(duration);
            }
        } catch (Exception ignored) {
            // metadata is optional
        }
        return didl;
    }

    private static String text(Element parent, String localName) {
        Element e = firstByLocalName(parent, localName);
        return e == null ? "" : e.getTextContent().trim();
    }

    // ---------------------------------------------------------------- time

    /** "H:MM:SS" (UPnP also allows ".mmm", which this does not use). */
    public static String formatTime(long ms) {
        long total = Math.max(0, ms) / 1000;
        return String.format(Locale.US, "%d:%02d:%02d", total / 3600, (total / 60) % 60, total % 60);
    }

    /** Parses "H:MM:SS", "H:MM:SS.mmm" and "+H:MM:SS"; anything else gives 0. */
    public static long parseTime(String value) {
        if (value == null) return 0;
        String v = value.trim();
        if (v.startsWith("+")) v = v.substring(1);
        if (v.startsWith("-")) return 0;
        String[] parts = v.split(":");
        if (parts.length != 3) return 0;
        try {
            long hours = Long.parseLong(parts[0].trim());
            long minutes = Long.parseLong(parts[1].trim());
            double seconds = Double.parseDouble(parts[2].trim());
            return (long) ((hours * 3600 + minutes * 60) * 1000 + seconds * 1000);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    // ---------------------------------------------------------------- DOM helpers

    static Document parse(String xml) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(false);
        try {
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        } catch (Exception ignored) {
            // not supported on every parser
        }
        try {
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        } catch (Exception ignored) {
            // not supported on every parser
        }
        DocumentBuilder builder = factory.newDocumentBuilder();
        return builder.parse(new InputSource(new StringReader(xml)));
    }

    private static String localName(Node n) {
        String name = n.getNodeName();
        int colon = name.indexOf(':');
        return colon >= 0 ? name.substring(colon + 1) : name;
    }

    private static Element firstElementChild(Element parent) {
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            if (children.item(i).getNodeType() == Node.ELEMENT_NODE) return (Element) children.item(i);
        }
        return null;
    }

    private static Element firstByLocalName(Element root, String name) {
        if (localName(root).equals(name)) return root;
        NodeList children = root.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node n = children.item(i);
            if (n.getNodeType() != Node.ELEMENT_NODE) continue;
            Element found = firstByLocalName((Element) n, name);
            if (found != null) return found;
        }
        return null;
    }
}
