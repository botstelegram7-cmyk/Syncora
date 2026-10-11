package com.serena.syncora.lyrics;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.Arrays;
import java.util.List;

public class LyricsTest {

    private static final String LRC = "[ar:Someone]\n[00:12.00]Second line\n[00:01.50]First line\n[00:20.250]Third";

    @Test
    public void lrcLinesAreSortedAndTimed() {
        Lyrics l = LrcParser.parse(LRC, "test");
        assertTrue(l.synced());
        assertEquals(3, l.lines.size());
        assertEquals(1500, l.lines.get(0).timeMs);
        assertEquals("First line", l.lines.get(0).text);
        assertEquals(12000, l.lines.get(1).timeMs);
        assertEquals(20250, l.lines.get(2).timeMs);
    }

    @Test
    public void lrcLineWithSeveralStampsRepeatsTheText() {
        Lyrics l = LrcParser.parse("[00:05.00][00:30.00]Chorus", "test");
        assertEquals(2, l.lines.size());
        assertEquals(5000, l.lines.get(0).timeMs);
        assertEquals(30000, l.lines.get(1).timeMs);
        assertEquals("Chorus", l.lines.get(1).text);
    }

    @Test
    public void lrcOffsetShiftsEveryLine() {
        Lyrics l = LrcParser.parse("[offset:+500]\n[00:01.00]Hi", "test");
        assertEquals(1, l.lines.size());
        assertEquals(1500, l.lines.get(0).timeMs);
    }

    @Test
    public void enhancedLrcUsesOnlyTheWordTimestampsThatWereActuallyProvided() {
        Lyrics lyrics = LrcParser.parse("[00:10.00]<00:10.00>Hello <00:10.45>world", "enhanced LRC");
        assertTrue(lyrics.synced());
        assertTrue(lyrics.wordSynced());
        assertEquals("Hello world", lyrics.lines.get(0).text);
        assertEquals(2, lyrics.lines.get(0).words.size());
        assertEquals(10000, lyrics.lines.get(0).words.get(0).timeMs);
        assertEquals(10450, lyrics.lines.get(0).words.get(1).timeMs);
        assertEquals(-1, lyrics.lines.get(0).wordIndexAt(9999));
        assertEquals(0, lyrics.lines.get(0).wordIndexAt(10000));
        assertEquals(1, lyrics.lines.get(0).wordIndexAt(10450));

        Lyrics lineOnly = LrcParser.parse("[00:10.00]Hello world", "line LRC");
        assertTrue(lineOnly.synced());
        assertFalse(lineOnly.wordSynced());
        assertEquals(0, lineOnly.lines.get(0).words.size());
    }

    @Test
    public void wordTimestampsAndLineOrderSurviveJsonRoundTrip() {
        Lyrics original = LrcParser.parse("[00:03.00]<00:03.00>Third\n[00:01.00]<00:01.00>First", "LRCLIB");
        Lyrics copy = Lyrics.fromJson(original.toJson());
        assertTrue(copy.wordSynced());
        assertEquals("First", copy.lines.get(0).text);
        assertEquals(1000, copy.lines.get(0).words.get(0).timeMs);
        assertEquals(0, copy.lines.get(0).wordIndexAt(1000));
    }

    @Test
    public void duplicateAndOutOfOrderLineTimestampsAreHandledDeterministically() {
        Lyrics lyrics = LrcParser.parse("[00:02.00]Later\n[00:01.00]First\n[00:01.00]First\n[00:01.00]Second", "test");
        assertEquals(3, lyrics.lines.size());
        assertEquals("First", lyrics.lines.get(0).text);
        assertEquals("Second", lyrics.lines.get(1).text);
        assertEquals("Later", lyrics.lines.get(2).text);
        assertEquals(1, lyrics.indexAt(1000));
        assertEquals(2, lyrics.indexAt(2000));
    }

    @Test
    public void unrelatedSearchResultsAreNotPresentedAsMatchingLyrics() throws Exception {
        JSONArray results = new JSONArray("[{\"trackName\":\"Unrelated song\",\"artistName\":\"Other artist\","
                + "\"duration\":181,\"syncedLyrics\":\"[00:01.00]plausible words\"}]");
        assertNull(LyricsClient.pickBest(results, "My song", "My artist", 181000));
    }

    @Test
    public void plainTextIsUnsynchronised() {
        Lyrics l = LrcParser.parse("Line one\n\nLine two", "test");
        assertFalse(l.synced());
        assertEquals(2, l.lines.size());
        assertEquals(-1, l.lines.get(0).timeMs);
        assertEquals(-1, l.indexAt(5000));
    }

    @Test
    public void indexAtFollowsThePosition() {
        Lyrics l = LrcParser.parse(LRC, "test");
        assertEquals(-1, l.indexAt(0));
        assertEquals(0, l.indexAt(1500));
        assertEquals(0, l.indexAt(11999));
        assertEquals(1, l.indexAt(12000));
        assertEquals(2, l.indexAt(99999));
    }

    @Test
    public void lyricsSurviveAJsonRoundTrip() {
        Lyrics original = LrcParser.parse(LRC, "LRCLIB");
        Lyrics copy = Lyrics.fromJson(original.toJson());
        assertEquals(original.lines.size(), copy.lines.size());
        assertEquals(original.source, copy.source);
        assertEquals(original.indexAt(12000), copy.indexAt(12000));
    }

    @Test
    public void cleanTitleDropsDecorations() {
        assertEquals("Song Name", LyricsClient.cleanTitle("Song Name (Official Video)"));
        assertEquals("Track", LyricsClient.cleanTitle("Track [HD] (Lyrics)"));
        assertEquals("Plain Title", LyricsClient.cleanTitle("Plain  Title"));
        assertEquals("", LyricsClient.cleanTitle(null));
    }

    @Test
    public void pickBestPrefersTheSyncedRecordClosestInLength() throws Exception {
        JSONArray results = new JSONArray(
                "[{\"duration\":200.0,\"syncedLyrics\":\"[00:01.00]long version\"},"
                + "{\"duration\":180.0,\"syncedLyrics\":\"[00:01.00]right length\"}]");
        Lyrics best = LyricsClient.pickBest(results, 181000);
        assertTrue(best.synced());
        assertEquals("right length", best.lines.get(0).text);
    }

    @Test
    public void pickBestFallsBackToPlainLyrics() throws Exception {
        JSONArray results = new JSONArray("[{\"duration\":180.0,\"plainLyrics\":\"Just words\"}]");
        Lyrics best = LyricsClient.pickBest(results, 180000);
        assertFalse(best.synced());
        assertEquals("Just words", best.lines.get(0).text);
    }

    @Test
    public void pickBestReturnsNullWithoutLyrics() throws Exception {
        assertNull(LyricsClient.pickBest(new JSONArray("[]"), 1000));
        assertNull(LyricsClient.pickBest(new JSONArray("[{\"plainLyrics\":\"  \"}]"), 1000));
    }

    @Test
    public void captionsParseFromJson3() {
        String json = "{\"events\":["
                + "{\"tStartMs\":1000,\"dDurationMs\":2000,\"segs\":[{\"utf8\":\"Hi \"},{\"utf8\":\"there\"}]},"
                + "{\"tStartMs\":5000,\"segs\":[{\"utf8\":\"\\n\"}]}]}";
        List<SubtitleParser.Cue> cues = SubtitleParser.parseJson3(json);
        assertEquals(1, cues.size());
        assertEquals(1000, cues.get(0).startMs);
        assertEquals(3000, cues.get(0).endMs);
        assertEquals("Hi there", cues.get(0).text);
    }

    @Test
    public void captionsParseFromWebVtt() {
        String vtt = "WEBVTT\n\n00:00:01.000 --> 00:00:03.000\nHello <c>world</c>\n\n"
                + "00:00:03.500 --> 00:00:05.000\nSecond &amp; last\n";
        List<SubtitleParser.Cue> cues = SubtitleParser.parseVtt(vtt);
        assertEquals(2, cues.size());
        assertEquals("Hello world", cues.get(0).text);
        assertEquals(3000, cues.get(0).endMs);
        assertEquals("Second & last", cues.get(1).text);
    }

    @Test
    public void captionsParseFromTtmlAndSrv3Xml() {
        String ttml = "<tt><body><div><p begin=\"00:00:01.250\" end=\"00:00:03.500\">"
                + "<span>Hello</span> &amp; <span>world</span></p></div></body></tt>";
        List<SubtitleParser.Cue> ttmlCues = SubtitleParser.parseTtml(ttml);
        assertEquals(1, ttmlCues.size());
        assertEquals(1250, ttmlCues.get(0).startMs);
        assertEquals(3500, ttmlCues.get(0).endMs);
        assertEquals("Hello & world", ttmlCues.get(0).text);

        String srv3 = "<transcript><text start=\"4.25\" dur=\"1.5\">It&#39;s fine</text></transcript>";
        List<SubtitleParser.Cue> srv3Cues = SubtitleParser.parseTtml(srv3);
        assertEquals(1, srv3Cues.size());
        assertEquals(4250, srv3Cues.get(0).startMs);
        assertEquals(5750, srv3Cues.get(0).endMs);
        assertEquals("It's fine", srv3Cues.get(0).text);
    }

    @Test
    public void rollingDuplicateCaptionsMerge() {
        String vtt = "WEBVTT\n\n00:00:01.000 --> 00:00:02.000\nSame\n\n00:00:02.000 --> 00:00:03.000\nSame\n";
        List<SubtitleParser.Cue> cues = SubtitleParser.parseVtt(vtt);
        assertEquals(1, cues.size());
        assertEquals(1000, cues.get(0).startMs);
        assertEquals(3000, cues.get(0).endMs);
    }

    @Test
    public void subtitleFilesHaveTheRightHeaders() {
        List<SubtitleParser.Cue> cues = Arrays.asList(new SubtitleParser.Cue(1000, 3000, "Hello"));
        assertTrue(SubtitleParser.toSrt(cues).contains("00:00:01,000 --> 00:00:03,000"));
        assertTrue(SubtitleParser.toVtt(cues).startsWith("WEBVTT"));
        assertTrue(SubtitleParser.toVtt(cues).contains("00:00:01.000 --> 00:00:03.000"));
        assertTrue(SubtitleParser.toLyrics(cues, "CC").synced());
    }

    @Test
    public void recordWithoutLyricsGivesNull() throws Exception {
        // a record with neither lyrics field gives no lyrics
        assertNull(LyricsClient.fromRecord(new JSONObject("{\"title\":\"x\"}"), "LRCLIB"));
    }
}
