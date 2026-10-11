package com.serena.syncora.youtube;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.serena.syncora.youtube.tap.TypeTubeClient;

import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** How TypeTube answers become the video model the YouTube page plays and downloads. */
public class TypeTubeTest {

    private static TypeTubeClient.Track track() {
        List<TypeTubeClient.AudioStream> audio = new ArrayList<>();
        audio.add(new TypeTubeClient.AudioStream(140, "medium", "audio/mp4; codecs=\"mp4a.40.2\"",
                129544, "https://rr.example/140", false));
        audio.add(new TypeTubeClient.AudioStream(251, "medium", "audio/webm; codecs=\"opus\"",
                160000, "https://rr.example/251", true));
        List<TypeTubeClient.VideoStream> video = new ArrayList<>();
        video.add(new TypeTubeClient.VideoStream(137, "hd1080", "1080p",
                "video/mp4; codecs=\"avc1.640028\"", "https://rr.example/137"));
        video.add(new TypeTubeClient.VideoStream(248, "hd1080", "1080p",
                "video/webm; codecs=\"vp9\"", "https://rr.example/248"));
        return new TypeTubeClient.Track("dQw4w9WgXcQ", "Sample Title", "Sample Artist", "Sample Artist",
                215, "https://i.ytimg.com/x.jpg", audio, video);
    }

    @Test
    public void mapsAWholeTrack() throws IOException {
        YouTubeVideo v = TypeTube.toVideo(track(), "fallback");
        assertEquals("dQw4w9WgXcQ", v.id);
        assertEquals("Sample Title", v.title);
        assertEquals("Sample Artist", v.author);
        assertEquals(215000L, v.durationMs);
        assertEquals("https://i.ytimg.com/x.jpg", v.thumbnailUrl);
        assertEquals("ydQw4w9WgXcQ", v.artKey());
        assertEquals(4, v.formats.size());
    }

    @Test
    public void picksTheAacStreamForPlayback() throws IOException {
        YouTubeVideo v = TypeTube.toVideo(track(), "fallback");
        YouTubeFormat play = v.bestAudioForPlayback();
        assertEquals(140, play.itag);
        assertTrue(play.isAac());
        assertEquals("audio/mp4", play.mime);
        assertEquals("mp4a.40.2", play.codecs);

        List<YouTubeFormat> audio = v.audioFormats();
        assertEquals(2, audio.size());
        // The list is still quality-ranked; playback separately prioritizes AAC for broad Android support.
        assertEquals(251, audio.get(0).itag);
        List<YouTubeFormat> playback = v.playbackAudioFormats();
        assertEquals(140, playback.get(0).itag);
        assertEquals(251, playback.get(1).itag);
    }

    @Test
    public void videoStreamsAreMp4WhenTheContainerIs() throws IOException {
        YouTubeVideo v = TypeTube.toVideo(track(), "fallback");
        List<YouTubeFormat> mp4 = v.mp4VideoFormats();
        assertEquals(1, mp4.size());
        assertEquals(137, mp4.get(0).itag);
        assertEquals(1080, mp4.get(0).height);
        assertEquals("avc1.640028", mp4.get(0).codecs);
    }

    @Test
    public void tracksWithoutAudioAreRejected() {
        TypeTubeClient.Track empty = new TypeTubeClient.Track("x", "t", "a", "a", 1, "",
                new ArrayList<TypeTubeClient.AudioStream>(), new ArrayList<TypeTubeClient.VideoStream>());
        try {
            TypeTube.toVideo(empty, "x");
            fail("expected no-plays error");
        } catch (IOException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("no playable streams"));
        }
    }

    @Test
    public void missingMetadataFallsBackToBasics() throws IOException {
        TypeTubeClient.Track bare = new TypeTubeClient.Track("", "T", "", "", 0, "",
                Collections.singletonList(new TypeTubeClient.AudioStream(140, "", "audio/mp4", 1000,
                        "https://rr.example/140", false)), new ArrayList<TypeTubeClient.VideoStream>());
        YouTubeVideo v = TypeTube.toVideo(bare, "abcdefghijk");
        assertEquals("abcdefghijk", v.id);
        assertEquals("T", v.title);
        assertEquals("https://i.ytimg.com/vi/abcdefghijk/hqdefault.jpg", v.thumbnailUrl);
    }
}
