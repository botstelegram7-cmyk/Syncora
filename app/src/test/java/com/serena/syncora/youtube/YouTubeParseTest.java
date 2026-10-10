package com.serena.syncora.youtube;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;

public class YouTubeParseTest {

    private static final String SAMPLE = "{"
            + "\"playabilityStatus\":{\"status\":\"OK\"},"
            + "\"videoDetails\":{\"title\":\"Sample Song\",\"author\":\"Sample Artist\",\"lengthSeconds\":\"215\","
            + "\"thumbnail\":{\"thumbnails\":[{\"url\":\"https://i.ytimg.com/small.jpg\"},"
            + "{\"url\":\"https://i.ytimg.com/large.jpg\"}]}},"
            + "\"streamingData\":{"
            + "\"formats\":[{\"itag\":18,\"mimeType\":\"video/mp4; codecs=\\\"avc1.42001E, mp4a.40.2\\\"\","
            + "\"bitrate\":500000,\"width\":640,\"height\":360,\"qualityLabel\":\"360p\",\"url\":\"https://rr.example/18\"}],"
            + "\"adaptiveFormats\":["
            + "{\"itag\":140,\"mimeType\":\"audio/mp4; codecs=\\\"mp4a.40.2\\\"\",\"bitrate\":130000,"
            + "\"audioSampleRate\":\"44100\",\"audioChannels\":2,\"contentLength\":\"4300000\",\"url\":\"https://rr.example/140\"},"
            + "{\"itag\":251,\"mimeType\":\"audio/webm; codecs=\\\"opus\\\"\",\"bitrate\":160000,\"url\":\"https://rr.example/251\"},"
            + "{\"itag\":137,\"mimeType\":\"video/mp4; codecs=\\\"avc1.640028\\\"\",\"bitrate\":2500000,"
            + "\"width\":1920,\"height\":1080,\"qualityLabel\":\"1080p\",\"url\":\"https://rr.example/137\"},"
            + "{\"itag\":999,\"mimeType\":\"audio/mp4; codecs=\\\"mp4a.40.2\\\"\",\"signatureCipher\":\"s=abc\"}"
            + "]},"
            + "\"captions\":{\"playerCaptionsTracklistRenderer\":{\"captionTracks\":["
            + "{\"baseUrl\":\"https://www.youtube.com/api/timedtext?v=x&lang=en\",\"name\":{\"simpleText\":\"English\"},"
            + "\"languageCode\":\"en\"},"
            + "{\"baseUrl\":\"https://www.youtube.com/api/timedtext?v=x&lang=en&kind=asr\","
            + "\"name\":{\"simpleText\":\"English (auto-generated)\"},\"languageCode\":\"en\",\"kind\":\"asr\"}"
            + "]}}"
            + "}";

    @Test
    public void readsDetailsFromPlayerResponse() throws IOException {
        YouTubeVideo v = YouTubeClient.parse(SAMPLE, "abcdefghijk", "UA/1");
        assertEquals("Sample Song", v.title);
        assertEquals("Sample Artist", v.author);
        assertEquals(215000L, v.durationMs);
        assertEquals("https://i.ytimg.com/large.jpg", v.thumbnailUrl);
        assertEquals("yabcdefghijk", v.artKey());
    }

    @Test
    public void listsCaptionTracksAndMarksAutomaticOnes() throws IOException {
        YouTubeVideo v = YouTubeClient.parse(SAMPLE, "abcdefghijk", "UA/1");
        assertEquals(2, v.captions.size());
        assertFalse(v.captions.get(0).auto);
        assertTrue(v.captions.get(1).auto);
        assertEquals("en", v.captions.get(0).languageCode);
    }

    @Test
    public void audioStreamsSkipCipheredOnesAndBestIsAac() throws IOException {
        YouTubeVideo v = YouTubeClient.parse(SAMPLE, "abcdefghijk", "UA/1");
        assertEquals(2, v.audioFormats().size());
        // highest bitrate first
        assertEquals(251, v.audioFormats().get(0).itag);
        // playback prefers AAC even when Opus has the higher bitrate
        assertEquals(140, v.bestAudioForPlayback().itag);
        assertEquals(140, v.bestMuxAudio().itag);
    }

    @Test
    public void videoChoicesIncludeMuxedAndH264() throws IOException {
        YouTubeVideo v = YouTubeClient.parse(SAMPLE, "abcdefghijk", "UA/1");
        assertEquals(2, v.mp4VideoFormats().size());
        assertEquals(1080, v.mp4VideoFormats().get(0).height);
        assertEquals(360, v.mp4VideoFormats().get(1).height);
        assertEquals(YouTubeFormat.Kind.MUXED, v.formats.get(0).kind);
    }

    @Test(expected = YouTubeClient.VideoUnavailableException.class)
    public void loginRequiredIsReportedAsUnavailable() throws IOException {
        YouTubeClient.parse("{\"playabilityStatus\":{\"status\":\"LOGIN_REQUIRED\",\"reason\":\"Sign in\"}}",
                "abcdefghijk", "UA/1");
    }

    @Test(expected = IOException.class)
    public void responseWithoutStreamsFails() throws IOException {
        YouTubeClient.parse("{\"playabilityStatus\":{\"status\":\"OK\"}}", "abcdefghijk", "UA/1");
    }

    @Test
    public void idsAreReadFromCommonLinks() {
        assertEquals("dQw4w9WgXcQ", YouTubeIds.parse("dQw4w9WgXcQ"));
        assertEquals("dQw4w9WgXcQ", YouTubeIds.parse("https://www.youtube.com/watch?v=dQw4w9WgXcQ&t=10s"));
        assertEquals("dQw4w9WgXcQ", YouTubeIds.parse("https://youtu.be/dQw4w9WgXcQ?si=abc"));
        assertEquals("dQw4w9WgXcQ", YouTubeIds.parse("https://www.youtube.com/shorts/dQw4w9WgXcQ"));
        assertNull(YouTubeIds.parse("hello"));
        assertNull(YouTubeIds.parse(""));
        assertNull(YouTubeIds.parse(null));
        assertNull(YouTubeIds.parse("https://example.com/watch?v=dQw4w9WgXcQ"));
    }
}
