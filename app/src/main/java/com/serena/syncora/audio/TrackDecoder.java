package com.serena.syncora.audio;

import android.content.Context;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;

/** Decodes a local song (content URI) or an http(s) file (DLNA) to PCM. */
public final class TrackDecoder implements PcmSource {
    private final Context context;
    private final Uri uri;
    private final String url;
    private final Map<String, String> headers;
    private MediaExtractor extractor;
    private PcmCodec codec;
    private boolean inputDone;
    private long durationMs = -1;
    private volatile boolean aborted;

    private TrackDecoder(Context context, Uri uri, String url, Map<String, String> headers) {
        this.context = context.getApplicationContext();
        this.uri = uri;
        this.url = url;
        this.headers = headers;
    }

    public static TrackDecoder forUri(Context context, Uri uri) {
        return new TrackDecoder(context, uri, null, null);
    }

    public static TrackDecoder forUrl(Context context, String url) {
        return new TrackDecoder(context, null, url, null);
    }

    /** An http(s) file that needs request headers (YouTube wants the User-Agent of the app it was asked as). */
    public static TrackDecoder forUrl(Context context, String url, String userAgent) {
        Map<String, String> headers = new HashMap<>();
        if (userAgent != null && !userAgent.isEmpty()) headers.put("User-Agent", userAgent);
        return new TrackDecoder(context, null, url, headers);
    }

    @Override
    public void open() throws IOException {
        extractor = new MediaExtractor();
        try {
            if (uri != null) {
                extractor.setDataSource(context, uri, null);
            } else if (headers != null && !headers.isEmpty()) {
                extractor.setDataSource(url, headers);
            } else {
                extractor.setDataSource(url);
            }
        } catch (RuntimeException e) {
            throw new IOException("Could not open this audio file", e);
        }
        int index = -1;
        MediaFormat format = null;
        for (int i = 0; i < extractor.getTrackCount(); i++) {
            MediaFormat candidate = extractor.getTrackFormat(i);
            String mime = candidate.getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith("audio/")) {
                index = i;
                format = candidate;
                break;
            }
        }
        if (index < 0) throw new IOException("No audio found in this file");
        extractor.selectTrack(index);
        if (format.containsKey(MediaFormat.KEY_DURATION)) durationMs = format.getLong(MediaFormat.KEY_DURATION) / 1000L;
        codec = PcmCodec.create(format);
    }

    @Override
    public Pcm read() throws IOException, InterruptedException {
        try {
            while (true) {
                if (aborted || Thread.interrupted()) throw new InterruptedException();
                if (!inputDone) {
                    int index = codec.dequeueInputBuffer(0);
                    if (index >= 0) {
                        ByteBuffer buffer = codec.inputBuffer(index);
                        int size = buffer == null ? -1 : extractor.readSampleData(buffer, 0);
                        if (size < 0) {
                            codec.queueEndOfStream(index);
                            inputDone = true;
                        } else {
                            codec.queueInput(index, size, extractor.getSampleTime());
                            extractor.advance();
                        }
                    }
                }
                Pcm out = codec.dequeueOutput(inputDone ? 20_000 : 5_000);
                if (out != null) return out;
                if (codec.isOutputEos()) return null;
            }
        } catch (IllegalStateException e) {
            throw new IOException("Decoder error", e);
        }
    }

    @Override
    public void seekTo(long positionMs) throws IOException {
        try {
            long us = Math.max(0, positionMs) * 1000L;
            extractor.seekTo(us, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
            codec.flush();
            codec.skipUntil(us);
            inputDone = false;
        } catch (IllegalStateException e) {
            throw new IOException("Seek failed", e);
        }
    }

    @Override
    public long durationMs() {
        return durationMs;
    }

    @Override
    public boolean isLive() {
        return false;
    }

    @Override
    public void abort() {
        aborted = true;
    }

    @Override
    public void close() {
        if (codec != null) codec.release();
        codec = null;
        if (extractor != null) {
            try {
                extractor.release();
            } catch (RuntimeException ignored) {
                // nothing to do
            }
        }
        extractor = null;
    }
}
