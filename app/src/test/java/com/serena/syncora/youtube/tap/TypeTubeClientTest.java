package com.serena.syncora.youtube.tap;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.io.IOException;

/**
 * The envelope bytes were produced by the typetube package's own {@code encodeTrackResult}, so passing
 * means this reader understands exactly what the cluster sends.
 */
public class TypeTubeClientTest {

    private static final String TRACK_B64 =
            "CAESzwQKC2RRdzR3OVdnWGNREgxTYW1wbGUgVGl0bGUaDVNhbXBsZSBBcnRpc3QiDVNhbXBsZSBBcnRpc3QqHGh0dHBz"
                    + "Oi8vYXZhdGFyLmV4YW1wbGUvYS5wbmcw1wE6MGh0dHBzOi8vaS55dGltZy5jb20vdmkvZFF3NHc5V2dYY1EvaHFk"
                    + "ZWZhdWx0LmpwZ0I4CjBodHRwczovL2kueXRpbWcuY29tL3ZpL2RRdzR3OVdnWGNRL2hxZGVmYXVsdC5qcGcQ4AMY"
                    + "6AJKRgiMARIGbWVkaXVtGh1hdWRpby9tcDQ7IGNvZGVjcz0ibXA0YS40MC4yIiCI9AcqFmh0dHBzOi8vcnIuZXhh"
                    + "bXBsZS8xNDBSSwiJARIGaGQxMDgwGgUxMDgwcCIfdmlkZW8vbXA0OyBjb2RlY3M9ImF2YzEuNjQwMDI4IioWaHR0"
                    + "cHM6Ly9yci5leGFtcGxlLzEzN1pGCIwBEgZtZWRpdW0aHWF1ZGlvL21wNDsgY29kZWNzPSJtcDRhLjQwLjIiIIj0"
                    + "ByoWaHR0cHM6Ly9yci5leGFtcGxlLzE0MFpdCPsBEgZtZWRpdW0aGWF1ZGlvL3dlYm07IGNvZGVjcz0ib3B1cyIg"
                    + "gOIJKhZodHRwczovL3JyLmV4YW1wbGUvMjUxMAE6F2h0dHBzOi8vcmF3LmV4YW1wbGUvMjUxYksIiQESBmhkMTA4"
                    + "MBoFMTA4MHAiH3ZpZGVvL21wNDsgY29kZWNzPSJhdmMxLjY0MDAyOCIqFmh0dHBzOi8vcnIuZXhhbXBsZS8xMzca"
                    + "FgnNzMzMzNxeQBEAAIBW/rx4QhgBIAo=";

    private static final String ERROR_B64 = "Ih0KCU5PVF9GT1VORBINbm8gc3VjaCB2aWRlbxiUAw==";

    @Test
    public void readsEverythingFromATrackEnvelope() throws IOException {
        TypeTubeClient.Track t = TypeTubeClient.parseTrackEnvelope(b64(TRACK_B64));
        assertEquals("dQw4w9WgXcQ", t.id);
        assertEquals("Sample Title", t.title);
        assertEquals("Sample Artist", t.author);
        assertEquals("Sample Artist", t.uploader);
        assertEquals(215, t.durationSeconds);
        assertEquals("https://i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg", t.thumbnail);

        assertEquals(2, t.audioStreams.size());
        TypeTubeClient.AudioStream a0 = t.audioStreams.get(0);
        assertEquals(140, a0.itag);
        assertEquals("medium", a0.quality);
        assertEquals("audio/mp4; codecs=\"mp4a.40.2\"", a0.mimeType);
        assertEquals(129544, a0.bitrate);
        assertEquals("https://rr.example/140", a0.url);
        assertEquals(false, a0.hiFi);
        TypeTubeClient.AudioStream a1 = t.audioStreams.get(1);
        assertEquals(251, a1.itag);
        assertTrue(a1.hiFi);
        assertEquals("audio/webm; codecs=\"opus\"", a1.mimeType);

        assertEquals(1, t.videoStreams.size());
        TypeTubeClient.VideoStream v = t.videoStreams.get(0);
        assertEquals(137, v.itag);
        assertEquals("hd1080", v.quality);
        assertEquals("1080p", v.resolution);
        assertEquals("https://rr.example/137", v.url);
    }

    @Test
    public void errorEnvelopesBecomeResolveExceptions() {
        try {
            TypeTubeClient.parseTrackEnvelope(b64(ERROR_B64));
            fail("expected a resolve error");
        } catch (TypeTubeClient.ResolveException e) {
            assertEquals("no such video", e.getMessage());
        } catch (IOException e) {
            fail("wrong error type: " + e);
        }
    }

    @Test
    public void emptyAnswersFailWithAReadableError() {
        try {
            // success=false with nothing else
            TypeTubeClient.parseTrackEnvelope(new byte[]{0x08, 0x00});
            fail("expected a resolve error");
        } catch (IOException e) {
            assertTrue(e.getMessage().length() > 10);
        }
    }

    private static byte[] b64(String s) {
        return java.util.Base64.getDecoder().decode(s);
    }
}
