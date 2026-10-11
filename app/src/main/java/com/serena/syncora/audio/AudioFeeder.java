package com.serena.syncora.audio;

import java.io.IOException;

/**
 * Cuts an audio stream into the pieces one decoder input buffer takes, and describes how that
 * decoder has to be configured. One implementation per container (raw MP3, ADTS, MPEG-TS,
 * fragmented MP4, Ogg, native FLAC, WAV) — see {@link StreamSniffer}, which picks one of them by
 * looking at the first bytes of a stream.
 */
interface AudioFeeder {

    /** Which decoder this stream needs. */
    AudioCodec codec();

    int sampleRate();

    int channels();

    /** Codec specific data for MediaCodec ("csd-0", "csd-1", ...); empty when the codec needs none. */
    byte[][] codecData();

    /**
     * The next frame to decode, or null at the end of the stream. For {@link AudioCodec#PCM16} this
     * is already interleaved 16 bit little endian audio instead of a compressed frame.
     */
    byte[] nextFrame() throws IOException;

    /** Duration of the frame {@link #nextFrame()} just returned, used to time stamp the decoder input. */
    long frameDurationUs();

    /** A short description for the status line, for example "HLS · AAC 48 kHz stereo". */
    String describe();

    /** Releases sockets and buffers. Never throws. */
    void close();
}
