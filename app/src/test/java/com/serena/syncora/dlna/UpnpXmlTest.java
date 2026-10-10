package com.serena.syncora.dlna;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.Test;

public class UpnpXmlTest {

    @Test
    public void allDocumentsAreWellFormedXml() throws Exception {
        UpnpXml.parse(UpnpXml.deviceDescription("Syncora (Pixel 7 & friends)", "uuid:1234", "0.2.0"));
        UpnpXml.parse(UpnpXml.avTransportScpd());
        UpnpXml.parse(UpnpXml.renderingControlScpd());
        UpnpXml.parse(UpnpXml.connectionManagerScpd());
        UpnpXml.parse(UpnpXml.soapFault(701, "Transition not available <now>"));
        UpnpXml.parse(UpnpXml.propertySet("LastChange", UpnpXml.avtLastChange("PLAYING", "http://x/a?b=1&c=2", "<DIDL-Lite/>", "", 200000, true)));
        UpnpXml.parse(UpnpXml.rcsLastChange(35, false));
        UpnpXml.parse(UpnpXml.avtLastChange("STOPPED", "", "", "", 0, false));
    }

    @Test
    public void deviceDescriptionAdvertisesThreeServicesAndEscapesTheName() throws Exception {
        String xml = UpnpXml.deviceDescription("Tom & Jerry's <phone>", "uuid:abc", "1");
        assertTrue(xml.contains("<friendlyName>Tom &amp; Jerry&apos;s &lt;phone&gt;</friendlyName>"));
        assertTrue(xml.contains("<UDN>uuid:abc</UDN>"));
        assertTrue(xml.contains("urn:schemas-upnp-org:device:MediaRenderer:1"));
        for (String name : new String[]{"AVTransport", "RenderingControl", "ConnectionManager"}) {
            assertTrue(name, xml.contains("<controlURL>/dlna/" + name + "/control</controlURL>"));
            assertTrue(name, xml.contains("<eventSubURL>/dlna/" + name + "/event</eventSubURL>"));
            assertTrue(name, xml.contains("<SCPDURL>/dlna/" + name + ".xml</SCPDURL>"));
        }
    }

    @Test
    public void scpdListsTheActionsControllersNeed() {
        String avt = UpnpXml.avTransportScpd();
        for (String a : new String[]{"SetAVTransportURI", "Play", "Pause", "Stop", "Seek", "Next", "Previous",
                "GetPositionInfo", "GetTransportInfo", "GetMediaInfo", "GetCurrentTransportActions"}) {
            assertTrue(a, avt.contains("<name>" + a + "</name>"));
        }
        String rcs = UpnpXml.renderingControlScpd();
        assertTrue(rcs.contains("<name>SetVolume</name>") && rcs.contains("<name>GetMute</name>"));
        assertTrue(UpnpXml.connectionManagerScpd().contains("<name>GetProtocolInfo</name>"));
    }

    private static final String SET_URI = "<?xml version=\"1.0\"?>\n"
            + "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">"
            + "<s:Body><u:SetAVTransportURI xmlns:u=\"urn:schemas-upnp-org:service:AVTransport:1\">"
            + "<InstanceID>0</InstanceID><CurrentURI>http://192.168.1.9:8200/MediaItems/42.mp3?a=1&amp;b=2</CurrentURI>"
            + "<CurrentURIMetaData>&lt;DIDL-Lite xmlns=&quot;urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/&quot; "
            + "xmlns:dc=&quot;http://purl.org/dc/elements/1.1/&quot; xmlns:upnp=&quot;urn:schemas-upnp-org:metadata-1-0/upnp/&quot;&gt;"
            + "&lt;item id=&quot;42&quot; parentID=&quot;0&quot; restricted=&quot;1&quot;&gt;&lt;dc:title&gt;Blinding Lights&lt;/dc:title&gt;"
            + "&lt;upnp:artist&gt;The Weeknd&lt;/upnp:artist&gt;&lt;upnp:album&gt;After Hours&lt;/upnp:album&gt;"
            + "&lt;upnp:albumArtURI&gt;http://192.168.1.9:8200/AlbumArt/42.jpg&lt;/upnp:albumArtURI&gt;"
            + "&lt;res protocolInfo=&quot;http-get:*:audio/mpeg:*&quot; duration=&quot;0:03:20.000&quot;&gt;http://192.168.1.9:8200/MediaItems/42.mp3&lt;/res&gt;"
            + "&lt;upnp:class&gt;object.item.audioItem.musicTrack&lt;/upnp:class&gt;&lt;/item&gt;&lt;/DIDL-Lite&gt;</CurrentURIMetaData>"
            + "</u:SetAVTransportURI></s:Body></s:Envelope>";

    @Test
    public void soapRequestIsParsedIncludingEscapedMetadata() {
        UpnpXml.SoapRequest req = UpnpXml.parseSoap(SET_URI);
        assertNotNull(req);
        assertEquals("SetAVTransportURI", req.action);
        assertEquals("0", req.arg("InstanceID"));
        assertEquals("http://192.168.1.9:8200/MediaItems/42.mp3?a=1&b=2", req.arg("CurrentURI"));
        UpnpXml.Didl didl = UpnpXml.parseDidl(req.arg("CurrentURIMetaData"));
        assertEquals("Blinding Lights", didl.title);
        assertEquals("The Weeknd", didl.artist);
        assertEquals("After Hours", didl.album);
        assertEquals("http://192.168.1.9:8200/AlbumArt/42.jpg", didl.albumArtUri);
        assertEquals("http://192.168.1.9:8200/MediaItems/42.mp3", didl.resourceUri);
        assertEquals(200_000, didl.durationMs);
    }

    @Test
    public void brokenInputNeverThrows() {
        assertNull(UpnpXml.parseSoap("not xml at all"));
        assertNull(UpnpXml.parseSoap("<a><b/></a>"));
        assertEquals("", UpnpXml.parseDidl("garbage").title);
        assertEquals("", UpnpXml.parseDidl(null).title);
        assertEquals("", UpnpXml.parseDidl("NOT_IMPLEMENTED").title);
        // DOCTYPE (XXE) payloads are refused or ignored
        String xxe = "<?xml version=\"1.0\"?><!DOCTYPE foo [<!ENTITY x SYSTEM \"file:///etc/passwd\">]><s:Envelope xmlns:s=\"x\"><s:Body><u:Play><InstanceID>&x;</InstanceID></u:Play></s:Body></s:Envelope>";
        UpnpXml.SoapRequest r = UpnpXml.parseSoap(xxe);
        assertTrue(r == null || !r.arg("InstanceID").contains("root:"));
    }

    @Test
    public void soapResponsesKeepArgumentOrderAndEscape() throws Exception {
        Map<String, String> out = new LinkedHashMap<>();
        out.put("Track", "1");
        out.put("TrackMetaData", "<DIDL-Lite>&</DIDL-Lite>");
        String xml = UpnpXml.soapResponse(UpnpXml.AVT, "GetPositionInfo", out);
        UpnpXml.parse(xml);
        assertTrue(xml.contains("<u:GetPositionInfoResponse xmlns:u=\"urn:schemas-upnp-org:service:AVTransport:1\">"));
        assertTrue(xml.indexOf("<Track>") < xml.indexOf("<TrackMetaData>"));
        assertTrue(xml.contains("&lt;DIDL-Lite&gt;&amp;&lt;/DIDL-Lite&gt;"));
        UpnpXml.SoapRequest back = UpnpXml.parseSoap(xml);
        assertEquals("GetPositionInfoResponse", back.action);
        assertEquals("<DIDL-Lite>&</DIDL-Lite>", back.arg("TrackMetaData"));
    }

    @Test
    public void soapActionHeader() {
        String[] a = UpnpXml.parseSoapActionHeader("\"urn:schemas-upnp-org:service:AVTransport:1#Play\"");
        assertEquals("urn:schemas-upnp-org:service:AVTransport:1", a[0]);
        assertEquals("Play", a[1]);
        assertEquals("Stop", UpnpXml.parseSoapActionHeader("urn:x:service:AVTransport:1#Stop")[1]);
        assertNull(UpnpXml.parseSoapActionHeader("nonsense"));
        assertNull(UpnpXml.parseSoapActionHeader(null));
    }

    @Test
    public void timeFormatting() {
        assertEquals("0:00:00", UpnpXml.formatTime(0));
        assertEquals("0:03:20", UpnpXml.formatTime(200_999));
        assertEquals("1:02:03", UpnpXml.formatTime(3_723_000));
        assertEquals(200_000, UpnpXml.parseTime("0:03:20"));
        assertEquals(200_500, UpnpXml.parseTime("00:03:20.500"));
        assertEquals(3_723_000, UpnpXml.parseTime("+1:02:03"));
        assertEquals(0, UpnpXml.parseTime("NOT_IMPLEMENTED"));
        assertEquals(0, UpnpXml.parseTime("-0:00:05"));
        assertEquals(0, UpnpXml.parseTime(null));
    }
}
