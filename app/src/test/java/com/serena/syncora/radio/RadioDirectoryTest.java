package com.serena.syncora.radio;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.List;
import org.json.JSONObject;
import org.junit.Test;

public class RadioDirectoryTest {
    @Test
    public void parsingKeepsOnlyWorkingPlayableStationsAndDeduplicates() throws Exception {
        String json = "[" +
                "{\"stationuuid\":\"a\",\"name\":\"Radio One\",\"url_resolved\":\"http://one.example/stream\",\"codec\":\"MP3\",\"bitrate\":128,\"tags\":\"pop,hits\",\"country\":\"India\",\"lastcheckok\":1,\"hls\":0}," +
                "{\"stationuuid\":\"b\",\"name\":\"Broken\",\"url_resolved\":\"http://b.example/s\",\"codec\":\"MP3\",\"lastcheckok\":0}," +
                "{\"stationuuid\":\"c\",\"name\":\"HLS station\",\"url_resolved\":\"http://c.example/live.m3u8\",\"codec\":\"AAC\",\"lastcheckok\":1,\"hls\":1}," +
                "{\"stationuuid\":\"d\",\"name\":\"Ogg station\",\"url_resolved\":\"http://d.example/s\",\"codec\":\"OGG\",\"lastcheckok\":1}," +
                "{\"stationuuid\":\"e\",\"name\":\"AAC plus\",\"url_resolved\":\"https://e.example/s\",\"codec\":\"AAC+\",\"bitrate\":64,\"lastcheckok\":1}," +
                "{\"stationuuid\":\"f\",\"name\":\"radio one\",\"url_resolved\":\"http://f.example/s\",\"codec\":\"MP3\",\"lastcheckok\":1}," +
                "{\"stationuuid\":\"g\",\"name\":\"Fallback to url\",\"url_resolved\":\"\",\"url\":\"http://g.example/s\",\"codec\":\"\",\"lastcheckok\":1}," +
                "{\"stationuuid\":\"h\",\"name\":\"\",\"url_resolved\":\"http://h.example/s\",\"codec\":\"MP3\",\"lastcheckok\":1}," +
                "{\"stationuuid\":\"i\",\"name\":\"Not http\",\"url_resolved\":\"rtsp://i.example/s\",\"codec\":\"MP3\",\"lastcheckok\":1}" +
                "]";
        List<Station> list = RadioDirectory.parse(json);
        assertEquals(3, list.size());
        assertEquals("Radio One", list.get(0).name);
        assertEquals("http://one.example/stream", list.get(0).url);
        assertEquals("Pop \u00B7 India \u00B7 128 kbps", list.get(0).subtitle());
        assertEquals("AAC plus", list.get(1).name);
        assertEquals("Fallback to url", list.get(2).name);
        assertEquals("http://g.example/s", list.get(2).url);
    }

    @Test
    public void featuredStationsAreWellFormed() {
        List<Station> featured = RadioDirectory.featured();
        assertTrue(featured.size() >= 12);
        java.util.Set<String> ids = new java.util.HashSet<>();
        for (Station s : featured) {
            assertTrue(s.name, s.url.startsWith("http"));
            assertTrue(s.name, !s.name.isEmpty());
            assertTrue("unique id " + s.id, ids.add(s.id));
        }
    }

    @Test
    public void stationJsonRoundTripAndLabels() throws Exception {
        Station s = new Station("x1", "Radio Mirchi 98.3", "http://m.example/live", "bollywood,hindi", "India", "MP3", 96);
        Station back = Station.fromJson(new JSONObject(s.toJson().toString()));
        assertNotNull(back);
        assertEquals(s.name, back.name);
        assertEquals(s.url, back.url);
        assertEquals(96, back.bitrate);
        assertEquals("RM", s.initials());
        assertEquals("Bollywood", s.firstTag());
        assertTrue(s.colorIndex() >= 0 && s.colorIndex() < 8);
        assertEquals(null, Station.fromJson(new JSONObject("{\"name\":\"no url\"}")));
        assertEquals("\u266A", new Station("", "!!!", "http://x", "", "", "", 0).initials());
    }
}
