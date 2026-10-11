package com.serena.syncora.audio;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import org.junit.Test;

public class HlsStreamTest {

    private static byte[] adtsFrame(int payloadLength, int fill) {
        int length = payloadLength + 7;
        byte[] frame = new byte[length];
        frame[0] = (byte) 0xFF;
        frame[1] = (byte) 0xF1;
        frame[2] = 0x50; // AAC-LC, 44100 Hz
        frame[3] = (byte) (0x80 | (length >> 11));
        frame[4] = (byte) ((length >> 3) & 0xFF);
        frame[5] = (byte) (((length & 7) << 5) | 0x1F);
        frame[6] = (byte) 0xFC;
        java.util.Arrays.fill(frame, 7, frame.length, (byte) fill);
        return frame;
    }

    private static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] part : parts) out.write(part, 0, part.length);
        return out.toByteArray();
    }

    private static byte[] readAll(InputStream input) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[512];
        int n;
        while ((n = input.read(buffer)) >= 0) {
            if (n > 0) out.write(buffer, 0, n);
        }
        return out.toByteArray();
    }

    @Test
    public void masterPlaylistStreamsItsAacVariantAsFrames() throws Exception {
        String masterUrl = "https://radio.test/live/master.m3u8";
        String mediaUrl = "https://radio.test/live/audio/media.m3u8";
        String segmentUrl = "https://radio.test/live/audio/segment.aac";
        byte[] master = ("#EXTM3U\n"
                + "#EXT-X-STREAM-INF:BANDWIDTH=64000,CODECS=\"mp4a.40.2\"\n"
                + "audio/media.m3u8\n").getBytes("UTF-8");
        byte[] media = ("#EXTM3U\n#EXT-X-TARGETDURATION:4\n#EXT-X-MEDIA-SEQUENCE:7\n"
                + "#EXTINF:4.0,\nsegment.aac\n#EXT-X-ENDLIST\n").getBytes("UTF-8");
        byte[] segment = concat(adtsFrame(20, 1), adtsFrame(20, 2), adtsFrame(20, 3));
        HlsStream.MapFetcher fetcher = new HlsStream.MapFetcher()
                .put(masterUrl, master)
                .put(mediaUrl, media)
                .put(segmentUrl, segment);

        HlsStream hls = HlsStream.open(masterUrl, fetcher, null);
        byte[] stream = readAll(hls);
        hls.close();
        assertArrayEquals(segment, stream);
        assertTrue(fetcher.requested.contains(masterUrl));
        assertTrue(fetcher.requested.contains(mediaUrl));
        assertTrue(fetcher.requested.contains(segmentUrl));

        AdtsFrameReader audio = new AdtsFrameReader(new PeekStream(new ByteArrayInputStream(stream)));
        assertTrue(audio.prime(1024));
        // AdtsFrameReader removes the seven-byte ADTS header before passing AAC to MediaCodec.
        assertEquals(20, audio.nextFrame().length);
        assertEquals(20, audio.nextFrame().length);
    }
}
