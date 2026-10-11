package com.serena.syncora.youtube;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** A public YouTube playlist and the playable video entries returned by its browse page. */
public final class YouTubePlaylist {

    /** Lightweight playlist row. The media stream is resolved only when the user taps Play. */
    public static final class Entry {
        public final String videoId;
        public final String title;
        public final String author;
        public final long durationMs;
        public final String thumbnailUrl;

        public Entry(String videoId, String title, String author, long durationMs, String thumbnailUrl) {
            this.videoId = videoId == null ? "" : videoId;
            this.title = title == null || title.trim().isEmpty() ? "YouTube video" : title.trim();
            this.author = author == null ? "" : author.trim();
            this.durationMs = Math.max(0, durationMs);
            this.thumbnailUrl = thumbnailUrl == null ? "" : thumbnailUrl;
        }
    }

    public final String id;
    public final String title;
    public final List<Entry> entries;
    /** True when YouTube returned another page after Syncora's bounded first 200 entries. */
    public final boolean hasMore;

    public YouTubePlaylist(String id, String title, List<Entry> entries, boolean hasMore) {
        this.id = id == null ? "" : id;
        this.title = title == null || title.trim().isEmpty() ? "YouTube playlist" : title.trim();
        this.entries = Collections.unmodifiableList(new ArrayList<>(entries));
        this.hasMore = hasMore;
    }
}
