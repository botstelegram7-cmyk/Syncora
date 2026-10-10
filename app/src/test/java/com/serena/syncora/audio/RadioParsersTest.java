package com.serena.syncora.audio;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

public class RadioParsersTest {

    private static byte[] mp3Frame(int fill, boolean padding) {
        // MPEG1 layer III, 128 kbps, 44.1 kHz, stereo: 417 bytes (418 with padding)
        int len = padding ? 418 : 417;
        byte[] frame = new byte[len];
        frame[0] = (byte) 0xFF;
        frame[1] = (byte) 0xFB;
        frame[2] = (byte) (padding ? 0x92 : 0x90);
        frame[3] = 0x00;
        for (int i = 4; i < len; i++) frame[i] = (byte) fill;
        return frame;
    }

    private static byte[] adtsFrame(int payloadLen, int fill) {
        int len = payloadLen + 7;
        byte[] frame = new byte[len];
        frame[0] = (byte) 0xFF;
        frame[1] = (byte) 0xF1;
        frame[2] = 0x50; // LC, 44100 Hz
        frame[3] = (byte) (0x80 | (len >> 11)); // 2 channels
        frame[4] = (byte) ((len >> 3) & 0xFF);
        frame[5] = (byte) (((len & 7) << 5) | 0x1F);
        frame[6] = (byte) 0xFC;
        for (int i = 7; i < len; i++) frame[i] = (byte) fill;
        return frame;
    }

    private static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] p : parts) out.write(p, 0, p.length);
        return out.toByteArray();
    }

    @Test
    public void mp3FramesAreSplitAtTheirRealLengths() throws Exception {
        byte[] stream = concat(mp3Frame(1, false), mp3Frame(2, true), mp3Frame(3, false), mp3Frame(4, false));
        Mp3FrameReader reader = new Mp3FrameReader(new PeekStream(new ByteArrayInputStream(stream)));
        byte[] a = reader.nextFrame();
        byte[] b = reader.nextFrame();
        byte[] c = reader.nextFrame();
        assertEquals(417, a.length);
        assertEquals(418, b.length);
        assertEquals(417, c.length);
        assertEquals(1, a[10]);
        assertEquals(2, b[10]);
        assertEquals(3, c[10]);
        assertEquals(44100, reader.sampleRate());
        assertEquals(2, reader.channels());
        assertEquals(1152, reader.samplesPerFrame());
        // the last frame has no successor to confirm it but is complete, so it is still returned
        byte[] d = reader.nextFrame();
        assertEquals(417, d.length);
        assertNull(reader.nextFrame());
    }

    private static byte[] id3Tag(int bodySize) {
        byte[] id3 = new byte[10 + bodySize];
        id3[0] = 'I';
        id3[1] = 'D';
        id3[2] = '3';
        id3[3] = 3;
        id3[8] = (byte) ((bodySize >> 7) & 0x7F);
        id3[9] = (byte) (bodySize & 0x7F);
        for (int i = 10; i < id3.length; i++) id3[i] = (byte) 0xFF; // even fake sync bytes inside the tag are skipped
        return id3;
    }

    private static List<Integer> marks(Mp3FrameReader reader) throws IOException {
        List<Integer> marks = new ArrayList<>();
        byte[] f;
        while ((f = reader.nextFrame()) != null) marks.add((int) f[20]);
        return marks;
    }

    @Test
    public void mp3ReaderKeepsEveryFrameAroundId3Tags() throws Exception {
        // a tag at the start and another one in the middle of the stream (new song)
        byte[] stream = concat(id3Tag(300), mp3Frame(7, false), mp3Frame(7, false), id3Tag(40), mp3Frame(8, false), mp3Frame(9, false));
        List<Integer> marks = marks(new Mp3FrameReader(new PeekStream(new ByteArrayInputStream(stream))));
        assertEquals("frames: " + marks, 4, marks.size());
        assertEquals(7, marks.get(0).intValue());
        assertEquals(7, marks.get(1).intValue());
        assertEquals(8, marks.get(2).intValue());
        assertEquals(9, marks.get(3).intValue());
    }

    @Test
    public void mp3ReaderResynchronisesAfterGarbage() throws Exception {
        byte[] garbage = new byte[]{0x11, 0x22, (byte) 0xFF, 0x00, 0x33};
        byte[] stream = concat(mp3Frame(5, false), mp3Frame(6, false), garbage, mp3Frame(7, false), mp3Frame(8, false), mp3Frame(9, false));
        List<Integer> marks = marks(new Mp3FrameReader(new PeekStream(new ByteArrayInputStream(stream))));
        // the frame right before the damage cannot be confirmed and is dropped; everything after it is recovered
        assertEquals("frames: " + marks, 4, marks.size());
        assertEquals(5, marks.get(0).intValue());
        assertEquals(7, marks.get(1).intValue());
        assertEquals(8, marks.get(2).intValue());
        assertEquals(9, marks.get(3).intValue());
    }

    @Test
    public void adtsReaderStripsHeadersAndBuildsAudioSpecificConfig() throws Exception {
        byte[] stream = concat(adtsFrame(200, 5), adtsFrame(150, 6), adtsFrame(300, 7));
        AdtsFrameReader reader = new AdtsFrameReader(new PeekStream(new ByteArrayInputStream(stream)));
        byte[] a = reader.nextFrame();
        byte[] b = reader.nextFrame();
        byte[] c = reader.nextFrame();
        assertEquals(200, a.length);
        assertEquals(150, b.length);
        assertEquals(300, c.length);
        assertEquals(5, a[0]);
        assertEquals(7, c[299]);
        assertNull(reader.nextFrame());
        assertEquals(44100, reader.sampleRate());
        assertEquals(2, reader.channels());
        // AAC-LC (2), 44100 Hz (index 4), stereo (2) => 0x12 0x10
        assertArrayEquals(new byte[]{0x12, 0x10}, reader.audioSpecificConfig());
    }

    @Test
    public void sniffingTellsMp3FromAdts() throws Exception {
        byte[] mp3 = concat(new byte[]{0, 1, 2}, mp3Frame(1, false), mp3Frame(1, false), mp3Frame(1, false));
        byte[] aac = concat(new byte[]{9, 9}, adtsFrame(100, 1), adtsFrame(100, 1), adtsFrame(100, 1));
        PeekStream m = new PeekStream(new ByteArrayInputStream(mp3));
        PeekStream a = new PeekStream(new ByteArrayInputStream(aac));
        assertEquals(3, Mp3FrameReader.probe(m, 4096));
        assertEquals(-1, AdtsFrameReader.probe(new PeekStream(new ByteArrayInputStream(mp3)), 4096));
        assertEquals(2, AdtsFrameReader.probe(a, 4096));
        assertEquals(-1, Mp3FrameReader.probe(new PeekStream(new ByteArrayInputStream(aac)), 4096));
    }

    @Test
    public void icyMetadataIsRemovedFromTheAudioAndReported() throws Exception {
        final int metaInt = 16;
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        ByteArrayOutputStream expected = new ByteArrayOutputStream();
        for (int block = 0; block < 3; block++) {
            for (int i = 0; i < metaInt; i++) {
                raw.write(block * 20 + i);
                expected.write(block * 20 + i);
            }
            if (block == 1) {
                byte[] text = "StreamTitle='Daft Punk - One More Time';StreamUrl='';".getBytes("UTF-8");
                int blocks = (text.length + 15) / 16;
                raw.write(blocks);
                raw.write(text, 0, text.length);
                for (int i = text.length; i < blocks * 16; i++) raw.write(0);
            } else {
                raw.write(0);
            }
        }
        final List<String> titles = new ArrayList<>();
        IcyInputStream in = new IcyInputStream(new ByteArrayInputStream(raw.toByteArray()), metaInt, new IcyInputStream.Listener() {
            @Override
            public void onTitle(String t) {
                titles.add(t);
            }
        });
        ByteArrayOutputStream audio = new ByteArrayOutputStream();
        byte[] buf = new byte[7];
        int n;
        try {
            while ((n = in.read(buf, 0, buf.length)) > 0) audio.write(buf, 0, n);
        } catch (IOException endOfData) {
            // the trailing metadata length byte hits the end of the stream
        }
        assertArrayEquals(expected.toByteArray(), audio.toByteArray());
        assertEquals(1, titles.size());
        assertEquals("Daft Punk - One More Time", titles.get(0));
    }

    @Test
    public void titleParsingHandlesQuotesAndEmptyTitles() {
        assertEquals("Song", IcyInputStream.parseTitle("StreamTitle='Song';"));
        assertEquals("It's Alive", IcyInputStream.parseTitle("StreamTitle='It's Alive';StreamUrl='x';"));
        assertEquals("", IcyInputStream.parseTitle("StreamTitle='';"));
        assertNull(IcyInputStream.parseTitle("StreamUrl='x';"));
        assertTrue(IcyInputStream.parseTitle("StreamTitle='no terminator").length() > 0);
    }
}
