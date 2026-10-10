package com.serena.syncora.youtube;

import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicBoolean;

/** Downloads YouTube streams to files and muxes a video-only stream with its audio into one MP4. */
public final class YouTubeDownloader {

    public interface Progress {
        /** 0..1 */
        void onProgress(float fraction);
    }

    /** Ranged requests of this size: YouTube throttles long single responses. */
    private static final long CHUNK = 9L * 1024 * 1024;

    private YouTubeDownloader() {
    }

    /** Downloads {@code url} into {@code out}. {@code totalBytes} may be 0 when unknown. */
    public static void download(String url, String userAgent, long totalBytes, File out, Progress progress,
                                AtomicBoolean cancelled) throws IOException {
        try (OutputStream fileOut = new FileOutputStream(out)) {
            if (totalBytes <= 0) {
                Opened whole = open(url, userAgent, null);
                copy(whole.in, fileOut, 0, 0, progress, cancelled);
                return;
            }
            long position = 0;
            while (position < totalBytes) {
                checkCancelled(cancelled);
                long end = Math.min(totalBytes - 1, position + CHUNK - 1);
                Opened part = open(url, userAgent, "bytes=" + position + "-" + end);
                if (part.code == 200) {
                    // The server ignored the Range header and is sending the whole file. Skip what is already on
                    // disk, write the rest, and stop: this response covers everything that is left.
                    skipFully(part.in, position);
                    copy(part.in, fileOut, position, totalBytes, progress, cancelled);
                    break;
                }
                long copied = copy(part.in, fileOut, position, totalBytes, progress, cancelled);
                if (copied == 0) break;
                position += copied;
            }
            if (position < totalBytes) {
                throw new IOException("Download ended early (" + position + " of " + totalBytes + " bytes)");
            }
        }
    }

    /** A response to a download request: its HTTP status and the body. */
    private static final class Opened {
        final int code;
        final InputStream in;

        Opened(int code, InputStream in) {
            this.code = code;
            this.in = in;
        }
    }

    private static Opened open(String url, String userAgent, String range) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15_000);
        c.setReadTimeout(30_000);
        c.setRequestProperty("User-Agent", userAgent);
        if (range != null) c.setRequestProperty("Range", range);
        int code = c.getResponseCode();
        if (code / 100 != 2) {
            c.disconnect();
            throw new IOException("YouTube answered HTTP " + code + " while downloading");
        }
        return new Opened(code, c.getInputStream());
    }

    private static void skipFully(InputStream in, long count) throws IOException {
        byte[] buffer = new byte[64 * 1024];
        long left = count;
        while (left > 0) {
            int n = in.read(buffer, 0, (int) Math.min(buffer.length, left));
            if (n < 0) throw new IOException("Download ended early while skipping");
            left -= n;
        }
    }

    /** Copies one response; returns the number of bytes written. */
    private static long copy(InputStream in, OutputStream out, long done, long total, Progress progress,
                             AtomicBoolean cancelled) throws IOException {
        long written = 0;
        try (InputStream src = in) {
            byte[] buffer = new byte[64 * 1024];
            int n;
            while ((n = src.read(buffer)) > 0) {
                checkCancelled(cancelled);
                out.write(buffer, 0, n);
                written += n;
                if (progress != null && total > 0) progress.onProgress(Math.min(1f, (done + written) / (float) total));
            }
        }
        return written;
    }

    private static void checkCancelled(AtomicBoolean cancelled) throws IOException {
        if (cancelled != null && cancelled.get()) throw new IOException("Cancelled");
    }

    /**
     * Writes an MP4 with the video track of {@code videoFile} and the audio track of {@code audioFile}. Samples are
     * interleaved by time so the file plays and seeks properly.
     */
    public static void mux(File videoFile, File audioFile, File out) throws IOException {
        MediaExtractor video = new MediaExtractor();
        MediaExtractor audio = new MediaExtractor();
        MediaMuxer muxer = null;
        try {
            video.setDataSource(videoFile.getAbsolutePath());
            audio.setDataSource(audioFile.getAbsolutePath());
            int vi = findTrack(video, "video/");
            int ai = findTrack(audio, "audio/");
            if (vi < 0 || ai < 0) throw new IOException("The downloaded streams do not contain video and audio");
            video.selectTrack(vi);
            audio.selectTrack(ai);
            muxer = new MediaMuxer(out.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            Pending v = new Pending(video, muxer.addTrack(video.getTrackFormat(vi)));
            Pending a = new Pending(audio, muxer.addTrack(audio.getTrackFormat(ai)));
            muxer.start();
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            while (true) {
                boolean hasV = v.fill();
                boolean hasA = a.fill();
                if (!hasV && !hasA) break;
                Pending pick = hasV && (!hasA || v.timeUs <= a.timeUs) ? v : a;
                info.offset = 0;
                info.size = pick.size;
                info.presentationTimeUs = pick.timeUs;
                info.flags = pick.keyFrame ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0;
                pick.buffer.limit(pick.size);
                pick.buffer.position(0);
                muxer.writeSampleData(pick.track, pick.buffer, info);
                pick.pending = false;
            }
            muxer.stop();
        } catch (RuntimeException e) {
            throw new IOException("Could not combine the video and audio", e);
        } finally {
            video.release();
            audio.release();
            if (muxer != null) {
                try {
                    muxer.release();
                } catch (RuntimeException ignored) {
                    // nothing to do
                }
            }
        }
    }

    private static int findTrack(MediaExtractor extractor, String prefix) {
        for (int i = 0; i < extractor.getTrackCount(); i++) {
            String mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith(prefix)) return i;
        }
        return -1;
    }

    /** One track with a sample read ahead, so the two tracks can be interleaved. */
    private static final class Pending {
        final MediaExtractor extractor;
        final int track;
        final ByteBuffer buffer = ByteBuffer.allocate(4 * 1024 * 1024);
        int size;
        long timeUs;
        boolean keyFrame;
        boolean pending;
        boolean ended;

        Pending(MediaExtractor extractor, int track) {
            this.extractor = extractor;
            this.track = track;
        }

        /** Makes a sample ready; false when the track has no more samples. */
        boolean fill() {
            if (pending) return true;
            if (ended) return false;
            buffer.clear();
            int n = extractor.readSampleData(buffer, 0);
            if (n < 0) {
                ended = true;
                return false;
            }
            size = n;
            timeUs = extractor.getSampleTime();
            keyFrame = (extractor.getSampleFlags() & MediaExtractor.SAMPLE_FLAG_SYNC) != 0;
            extractor.advance();
            pending = true;
            return true;
        }
    }
}
