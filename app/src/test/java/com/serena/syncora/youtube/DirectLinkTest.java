package com.serena.syncora.youtube;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class DirectLinkTest {

    @Test
    public void normalizesHtmlEscapedLinks() {
        assertEquals("https://x.example/stream?id=888&d=true",
                DirectLink.normalize("  https://x.example/stream?id=888&amp;d=true  "));
    }

    @Test
    public void fileToLinksLinksSplitIntoStreamAndDownload() {
        DirectLink link = DirectLink.parse("https://telegram-file-to-links.onrender.com/stream?id=888");
        assertNotNull(link);
        assertEquals("888", link.fileId);
        assertEquals("File 888", link.title);
        assertEquals("telegram-file-to-links.onrender.com", link.host);
        assertEquals("https://telegram-file-to-links.onrender.com/stream?id=888", link.streamUrl);
        assertEquals("https://telegram-file-to-links.onrender.com/stream?id=888&d=true", link.downloadUrl);
    }

    @Test
    public void theDownloadVariantKeepsOneStreamLinkAndOneDownloadLink() {
        DirectLink link = DirectLink.parse("https://telegram-file-to-links.onrender.com/stream?id=888&d=true");
        assertNotNull(link);
        assertEquals("https://telegram-file-to-links.onrender.com/stream?id=888", link.streamUrl);
        assertEquals("https://telegram-file-to-links.onrender.com/stream?id=888&d=true", link.downloadUrl);
        assertTrue(DirectLink.isDownloadVariant(link.url));
    }

    @Test
    public void otherQueryParametersSurvive() {
        DirectLink link = DirectLink.parse("https://files.example/stream?id=12&token=abc");
        assertNotNull(link);
        assertEquals("https://files.example/stream?id=12&token=abc", link.streamUrl);
        assertEquals("https://files.example/stream?id=12&token=abc&d=true", link.downloadUrl);
    }

    @Test
    public void plainMediaLinksStreamAndDownloadFromTheSameUrl() {
        DirectLink link = DirectLink.parse("https://cdn.example.com/music/song%20name.mp3");
        assertNotNull(link);
        assertEquals("song name.mp3", link.title);
        assertEquals(link.url, link.streamUrl);
        assertEquals(link.url, link.downloadUrl);
        assertEquals("", link.fileId);
    }

    @Test
    public void nonLinksAreRejected() {
        assertNull(DirectLink.parse("dQw4w9WgXcQ"));
        assertNull(DirectLink.parse("not a url"));
        assertNull(DirectLink.parse("ftp://files.example/a.mp3"));
        assertNull(DirectLink.parse(""));
        assertNull(DirectLink.parse(null));
    }

    @Test
    public void detectsTheDownloadVariant() {
        assertTrue(DirectLink.isDownloadVariant("https://x.example/stream?id=1&d=true"));
        assertFalse(DirectLink.isDownloadVariant("https://x.example/stream?id=1"));
    }
}
