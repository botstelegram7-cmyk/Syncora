package com.serena.syncora.audio;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import org.junit.Test;

public class MpegTsReaderTest {

    private static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] part : parts) out.write(part, 0, part.length);
        return out.toByteArray();
    }

    private static byte[] packet(int pid, boolean start, byte[] payload) {
        byte[] packet = new byte[188];
        Arrays.fill(packet, (byte) 0xFF);
        packet[0] = 0x47;
        packet[1] = (byte) ((start ? 0x40 : 0) | ((pid >> 8) & 0x1F));
        packet[2] = (byte) pid;
        packet[3] = 0x10; // payload only
        System.arraycopy(payload, 0, packet, 4, Math.min(payload.length, 184));
        return packet;
    }

    private static byte[] adtsFrame(int payloadLength, int fill) {
        int length = payloadLength + 7;
        byte[] frame = new byte[length];
        frame[0] = (byte) 0xFF;
        frame[1] = (byte) 0xF1;
        frame[2] = 0x50; // AAC-LC, 44100 Hz
        frame[3] = (byte) (0x80 | (length >> 11)); // stereo
        frame[4] = (byte) ((length >> 3) & 0xFF);
        frame[5] = (byte) (((length & 7) << 5) | 0x1F);
        frame[6] = (byte) 0xFC;
        Arrays.fill(frame, 7, frame.length, (byte) fill);
        return frame;
    }

    @Test
    public void demuxesAacFromTransportStreamForTheRadioFrameReader() throws Exception {
        byte[] pat = packet(0, true, new byte[]{0,
                0x00, (byte) 0xB0, 0x0D, 0x00, 0x01, (byte) 0xC1, 0x00, 0x00,
                0x00, 0x01, (byte) 0xE1, 0x00, 0x00, 0x00, 0x00, 0x00});
        byte[] pmt = packet(256, true, new byte[]{0,
                0x02, (byte) 0xB0, 0x12, 0x00, 0x01, (byte) 0xC1, 0x00, 0x00,
                (byte) 0xE1, 0x01, (byte) 0xF0, 0x00, 0x0F, (byte) 0xE1, 0x01, (byte) 0xF0, 0x00,
                0x00, 0x00, 0x00, 0x00});

        byte[] frames = concat(adtsFrame(20, 1), adtsFrame(20, 2), adtsFrame(20, 3));
        byte[] pes = new byte[9 + frames.length];
        pes[2] = 1;
        pes[3] = (byte) 0xC0;
        pes[4] = (byte) (pes.length >> 8);
        pes[5] = (byte) pes.length;
        pes[6] = (byte) 0x80;
        System.arraycopy(frames, 0, pes, 9, frames.length);
        byte[] ts = concat(pat, pmt, packet(257, true, pes));

        PeekStream transportProbe = new PeekStream(new ByteArrayInputStream(ts));
        assertTrue(MpegTsReader.looksLikeTransportStream(transportProbe));
        MpegTsReader demux = new MpegTsReader(transportProbe);
        PeekStream audio = new PeekStream(demux);
        AdtsFrameReader reader = new AdtsFrameReader(audio);
        assertTrue(reader.prime(1024));
        assertEquals(0x0F, demux.audioStreamType());
        assertNotNull(reader.nextFrame());
        assertNotNull(reader.nextFrame());
    }

    @Test
    public void ordinaryAudioIsNotMistakenForTransportStream() throws Exception {
        byte[] audio = concat(adtsFrame(20, 1), adtsFrame(20, 2), adtsFrame(20, 3));
        assertTrue(!MpegTsReader.looksLikeTransportStream(new PeekStream(new ByteArrayInputStream(audio))));
    }
}
