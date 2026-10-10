package com.serena.syncora.dlna;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class SsdpMessagesTest {
    private static final String LOCATION = "http://192.168.1.5:8787/dlna/desc.xml";

    @Test
    public void searchResponseHasEveryHeaderControllersLookFor() {
        String m = SsdpAdvertiser.searchResponse(LOCATION, UpnpXml.DEVICE_TYPE, "uuid:abc::" + UpnpXml.DEVICE_TYPE,
                "Sat, 10 Oct 2026 07:00:00 GMT");
        assertTrue(m.startsWith("HTTP/1.1 200 OK\r\n"));
        assertTrue(m.contains("\r\nCACHE-CONTROL: max-age=1800\r\n"));
        assertTrue(m.contains("\r\nEXT:\r\n"));
        assertTrue(m.contains("\r\nLOCATION: " + LOCATION + "\r\n"));
        assertTrue(m.contains("\r\nST: urn:schemas-upnp-org:device:MediaRenderer:1\r\n"));
        assertTrue(m.contains("\r\nUSN: uuid:abc::urn:schemas-upnp-org:device:MediaRenderer:1\r\n"));
        assertTrue("terminated by a blank line", m.endsWith("\r\n\r\n"));
        assertTrue(SsdpAdvertiser.header(m, "ST").endsWith("MediaRenderer:1"));
    }

    @Test
    public void aliveAdvertisementCarriesLocationAndLifetime() {
        String m = SsdpAdvertiser.notifyMessage("ssdp:alive", LOCATION, "upnp:rootdevice", "uuid:abc::upnp:rootdevice");
        assertTrue(m.startsWith("NOTIFY * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\n"));
        assertTrue(m.contains("CACHE-CONTROL: max-age=1800"));
        assertTrue(m.contains("LOCATION: " + LOCATION));
        assertTrue(m.contains("NT: upnp:rootdevice\r\nNTS: ssdp:alive\r\n"));
        assertTrue(m.contains("USN: uuid:abc::upnp:rootdevice"));
    }

    @Test
    public void byebyeHasNoLocation() {
        String m = SsdpAdvertiser.notifyMessage("ssdp:byebye", LOCATION, "uuid:abc", "uuid:abc");
        assertTrue(m.contains("NTS: ssdp:byebye"));
        assertFalse(m.contains("LOCATION"));
        assertFalse(m.contains("CACHE-CONTROL"));
    }
}
