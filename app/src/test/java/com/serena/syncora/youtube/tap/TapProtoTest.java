package com.serena.syncora.youtube.tap;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.io.IOException;

public class TapProtoTest {

    @Test
    public void readsTagsVarintsAndStrings() throws IOException {
        // field 1 varint 300, field 2 string "hi", field 3 varint 1
        byte[] data = hex("08" + "ac02" + "12" + "02" + "6869" + "18" + "01");
        TapProto r = new TapProto(data);
        int tag = r.readTag();
        assertEquals(1, TapProto.fieldOf(tag));
        assertEquals(0, TapProto.wireOf(tag));
        assertEquals(300, r.readVarint());
        tag = r.readTag();
        assertEquals(2, TapProto.fieldOf(tag));
        assertEquals("hi", r.readString());
        tag = r.readTag();
        assertEquals(3, TapProto.fieldOf(tag));
        assertEquals(1, r.readVarint());
        assertEquals(-1, r.readTag());
        assertFalse(r.hasMore());
    }

    @Test
    public void readsNestedMessages() throws IOException {
        // field 1 -> message { field 1 string "in" }
        byte[] data = hex("0a" + "04" + "0a" + "02" + "696e");
        TapProto r = new TapProto(data);
        assertEquals(1, TapProto.fieldOf(r.readTag()));
        TapProto inner = r.readMessage();
        assertEquals(1, TapProto.fieldOf(inner.readTag()));
        assertEquals("in", inner.readString());
        assertFalse(inner.hasMore());
        assertFalse(r.hasMore());
    }

    @Test
    public void readsLittleEndianDoubles() throws IOException {
        // field 1 fixed64: 123.45d
        byte[] data = hex("09" + "cdccccccccdc5e40");
        TapProto r = new TapProto(data);
        assertEquals(1, TapProto.fieldOf(r.readTag()));
        assertEquals(123.45, r.readDouble(), 0.0001);
    }

    @Test
    public void skipsUnknownWireTypes() throws IOException {
        // field 7 varint, field 8 bytes, field 9 fixed64, field 10 fixed32
        byte[] data = hex("38" + "01" + "42" + "03" + "616263" + "49" + "0102030405060708" + "55" + "01020304");
        TapProto r = new TapProto(data);
        while (r.hasMore()) {
            int tag = r.readTag();
            if (tag < 0) break;
            r.skip(TapProto.wireOf(tag));
        }
        assertFalse(r.hasMore());
    }

    @Test
    public void handlesSixtyFourBitVarints() throws IOException {
        // field 1 varint 2^40
        byte[] data = hex("08" + "808080808020");
        TapProto r = new TapProto(data);
        r.readTag();
        assertEquals(1L << 40, r.readVarint());
    }

    @Test
    public void truncatedDataFailsCleanly() {
        try {
            TapProto r = new TapProto(hex("0a" + "05" + "61"));
            r.readTag();
            r.readString();
            fail("expected a truncation error");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("Truncated"));
        }
    }

    private static byte[] hex(String s) {
        s = s.replace(" ", "");
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) out[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
        return out;
    }
}
