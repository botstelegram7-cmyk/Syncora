package com.serena.syncora.audio;

/**
 * The audio codecs Syncora can decode itself, with the MediaCodec mime name of each.
 * Kept free of Android types so the readers around it can be unit tested on a desktop JVM.
 */
enum AudioCodec {
    MP3("audio/mpeg", "MP3"),
    AAC("audio/mp4a-latm", "AAC"),
    VORBIS("audio/vorbis", "Vorbis"),
    OPUS("audio/opus", "Opus"),
    FLAC("audio/flac", "FLAC"),
    /** Uncompressed 16 bit little endian PCM (a WAV stream): no decoder needed. */
    PCM16("audio/raw", "PCM");

    final String mime;
    final String label;

    AudioCodec(String mime, String label) {
        this.mime = mime;
        this.label = label;
    }
}
