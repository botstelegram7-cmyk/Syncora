package com.serena.syncora.radio;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.List;
import org.junit.Test;

public class RadioCatalogTest {

    @Test
    public void bundledAkashvaniEntriesBecomeHlsStations() throws Exception {
        String json = "[{\"id\":\"air-5\",\"name\":\"Live News 24x7\","
                + "\"url\":\"https://radio.example/live.m3u8\",\"state\":\"NATIONAL\","
                + "\"language\":\"Hindi, English\"}]";
        List<Station> stations = RadioCatalog.parse(json, true);
        assertEquals(1, stations.size());
        Station station = stations.get(0);
        assertEquals("air-5", station.id);
        assertEquals("HLS", station.codec);
        assertEquals("NATIONAL", station.country);
        assertEquals("Hindi, English", station.tags);
    }

    @Test
    public void bundledIndiaStationsPreserveMp3Metadata() throws Exception {
        String json = "[{\"id\":\"bollywood\",\"name\":\"Bollywood Gaane Purane\","
                + "\"url\":\"https://radio.example/live\",\"tags\":\"bollywood\","
                + "\"country\":\"India\",\"codec\":\"MP3\",\"bitrate\":128}]";
        List<Station> stations = RadioCatalog.parse(json, false);
        Station station = RadioCatalog.firstByCodec(stations, "mp3");
        assertNotNull(station);
        assertEquals("India", station.country);
        assertEquals(128, station.bitrate);
        assertEquals(null, RadioCatalog.findById(stations, "missing"));
        assertTrue(station.url.startsWith("https://"));
    }

    @Test
    public void invalidCatalogRowsAreSkipped() throws Exception {
        String json = "[{\"name\":\"Not a stream\",\"url\":\"file:///tmp/radio\"},"
                + "{\"name\":\"\",\"url\":\"https://radio.example/live\"},"
                + "{\"id\":\"ok\",\"name\":\"Good station\",\"url\":\"https://radio.example/live\","
                + "\"codec\":\"MP3\"}]";
        List<Station> stations = RadioCatalog.parse(json, false);
        assertEquals(1, stations.size());
        assertEquals("Good station", stations.get(0).name);
        assertNull(RadioCatalog.firstByCodec(stations, "AAC"));
    }
}
