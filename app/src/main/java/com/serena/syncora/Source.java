package com.serena.syncora;

/** What the group is listening to ("current source"). */
public enum Source {
    MUSIC("music", "My Music", "Songs stored on this device"),
    RADIO("radio", "Online Radio", "Stream stations from the internet"),
    DLNA("dlna", "DLNA Renderer", "Let other apps cast to this device"),
    MIC("mic", "Microphone / Aux", "Share a live input with every speaker"),
    YOUTUBE("youtube", "YouTube", "Play or download YouTube audio and video"),
    SPEAKER("speaker", "Speaker Mode", "Play what another device is playing");

    public final String id;
    public final String label;
    public final String description;

    Source(String id, String label, String description) {
        this.id = id;
        this.label = label;
        this.description = description;
    }

    public static Source fromId(String id) {
        if (id != null) {
            for (Source s : values()) if (s.id.equals(id)) return s;
        }
        return MUSIC;
    }
}
