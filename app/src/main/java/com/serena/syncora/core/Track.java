package com.serena.syncora.core;

/** One playable item (a song on the device or a DLNA item). */
public final class Track {
    public final long id;
    /** Content or http URI that MediaExtractor can open. */
    public final String uri;
    public final String title;
    public final String artist;
    public final String album;
    public final long albumId;
    public final int durationMs;

    public Track(long id, String uri, String title, String artist, String album, long albumId, int durationMs) {
        this.id = id;
        this.uri = uri;
        this.title = title == null ? "" : title;
        this.artist = artist == null ? "" : artist;
        this.album = album == null ? "" : album;
        this.albumId = albumId;
        this.durationMs = durationMs;
    }

    /** Identifies the artwork of this track on the wire. */
    public String artKey() {
        return "t" + id;
    }

    public String subtitle() {
        if (!artist.isEmpty() && !album.isEmpty()) return artist + " \u00B7 " + album;
        if (!artist.isEmpty()) return artist;
        return album;
    }
}
