package com.serena.syncora.net;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class RoomDiscoveryTest {
    @Test
    public void roomAnnouncementRoundTripsEvenWithAwkwardText() {
        RoomDiscovery.RoomInfo room = new RoomDiscovery.RoomInfo("SYNC-ABC123", "ignored", 8788, "Rahul's phone | Living room",
                "Tum Hi Ho \u2014 100% \u0939\u093F\u0928\u094D\u0926\u0940", true, 3, "radio");
        String message = RoomDiscovery.buildRoomMessage(room);
        assertEquals("pipes in free text must be encoded", 8, message.length() - message.replace("|", "").length());
        RoomDiscovery.RoomInfo back = RoomDiscovery.parseRoomMessage(message, "192.168.1.7");
        assertNotNull(back);
        assertEquals("SYNC-ABC123", back.code);
        assertEquals("192.168.1.7", back.ip);
        assertEquals(8788, back.port);
        assertEquals("Rahul's phone | Living room", back.name);
        assertEquals("Tum Hi Ho \u2014 100% \u0939\u093F\u0928\u094D\u0926\u0940", back.title);
        assertTrue(back.playing);
        assertEquals(3, back.clients);
        assertEquals("radio", back.source);
    }

    @Test
    public void emptyTitleAndNotPlaying() {
        RoomDiscovery.RoomInfo room = new RoomDiscovery.RoomInfo("sync-xyz999", "x", 8787, "Kitchen", "", false, 0, "music");
        RoomDiscovery.RoomInfo back = RoomDiscovery.parseRoomMessage(RoomDiscovery.buildRoomMessage(room), "10.0.0.2");
        assertNotNull(back);
        assertEquals("room codes are normalised to upper case", "SYNC-XYZ999", back.code);
        assertEquals("", back.title);
        assertFalse(back.playing);
    }

    @Test
    public void invalidAnnouncementsAreIgnored() {
        assertNull(RoomDiscovery.parseRoomMessage(null, "1.2.3.4"));
        assertNull(RoomDiscovery.parseRoomMessage("hello", "1.2.3.4"));
        assertNull("old protocol version", RoomDiscovery.parseRoomMessage("SYNCORA_ROOM|1|SYNC-A|8787|n|t", "1.2.3.4"));
        assertNull("too few fields", RoomDiscovery.parseRoomMessage("SYNCORA_ROOM|2|SYNC-A|8787|n|t|1|0", "1.2.3.4"));
        assertNull("bad port", RoomDiscovery.parseRoomMessage("SYNCORA_ROOM|2|SYNC-A|abc|n|t|1|0|music", "1.2.3.4"));
        assertNull("port out of range", RoomDiscovery.parseRoomMessage("SYNCORA_ROOM|2|SYNC-A|70000|n|t|1|0|music", "1.2.3.4"));
        assertNull("bad client count", RoomDiscovery.parseRoomMessage("SYNCORA_ROOM|2|SYNC-A|8787|n|t|1|x|music", "1.2.3.4"));
    }
}
