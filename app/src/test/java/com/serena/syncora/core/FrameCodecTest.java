package com.serena.syncora.core;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import org.junit.Test;

public class FrameCodecTest {
    @Test
    public void audioRoundTrip() throws Exception {
        short[] pcm = new short[]{0, 1, -1, 32767, -32768, 1234, -4321, 7};
        PcmChunk in = new PcmChunk(9, 42, 123456789012345L, 44100, 2, 4, pcm);
        byte[] bytes = FrameCodec.encodeAudio(in);
        FrameCodec.Frame frame = FrameCodec.read(new ByteArrayInputStream(bytes));
        assertEquals(FrameCodec.T_AUDIO, frame.type);
        PcmChunk out = FrameCodec.decodeAudio(frame.payload);
        assertEquals(9, out.epoch);
        assertEquals(42, out.seq);
        assertEquals(123456789012345L, out.playAtNanos);
        assertEquals(44100, out.sampleRate);
        assertEquals(2, out.channels);
        assertEquals(4, out.frames);
        assertArrayEquals(pcm, out.samples);
    }

    @Test
    public void textAndFlushFramesFollowEachOther() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        bytes.write(FrameCodec.encodeText(FrameCodec.T_STATE, "{\"a\":\"\u00E9\u4E2D\"}"));
        bytes.write(FrameCodec.encodeFlush(-5));
        bytes.write(FrameCodec.encode(FrameCodec.T_PING, new byte[0]));
        ByteArrayInputStream in = new ByteArrayInputStream(bytes.toByteArray());
        FrameCodec.Frame a = FrameCodec.read(in);
        assertEquals(FrameCodec.T_STATE, a.type);
        assertEquals("{\"a\":\"\u00E9\u4E2D\"}", a.text());
        FrameCodec.Frame b = FrameCodec.read(in);
        assertEquals(FrameCodec.T_FLUSH, b.type);
        assertEquals(-5, FrameCodec.decodeFlush(b.payload));
        FrameCodec.Frame c = FrameCodec.read(in);
        assertEquals(FrameCodec.T_PING, c.type);
        assertEquals(0, c.payload.length);
        assertNull(FrameCodec.read(in));
    }

    @Test(expected = EOFException.class)
    public void truncatedFrameFails() throws Exception {
        byte[] bytes = FrameCodec.encodeText(FrameCodec.T_STATE, "hello world");
        byte[] cut = new byte[bytes.length - 3];
        System.arraycopy(bytes, 0, cut, 0, cut.length);
        FrameCodec.read(new ByteArrayInputStream(cut));
    }

    @Test(expected = IOException.class)
    public void absurdLengthIsRejected() throws Exception {
        byte[] bad = new byte[]{3, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, 0x7F};
        FrameCodec.read(new ByteArrayInputStream(bad));
    }

    @Test(expected = IOException.class)
    public void badAudioHeaderIsRejected() throws Exception {
        PcmChunk in = new PcmChunk(1, 1, 1, 100, 2, 1, new short[2]); // 100 Hz is not a real sample rate
        FrameCodec.decodeAudio(FrameCodec.read(new ByteArrayInputStream(FrameCodec.encodeAudio(in))).payload);
    }
}
