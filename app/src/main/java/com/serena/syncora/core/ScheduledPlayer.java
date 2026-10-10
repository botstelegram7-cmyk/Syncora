package com.serena.syncora.core;

import java.io.IOException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Plays timestamped PCM so that every chunk is heard at the moment the host said it should be heard.
 *
 * <p>The host stamps each chunk with the (host clock) time at which it must come out of the speaker. This class
 * converts that to local time using the clock offset, then predicts when the next frame it writes would actually
 * be rendered (using the real audio timestamps of the output) and nudges the stream by inserting silence or
 * dropping a few frames when the prediction is off by more than the tolerance. The same class drives the host's
 * own speaker (with an offset of zero) so all devices share one timeline.
 */
public final class ScheduledPlayer {

    /** Audio output. Implemented with AudioTrack on Android and with a simulation in tests. */
    public interface Sink {
        void open(int sampleRate, int channels) throws IOException;

        void start();

        /** Non-blocking write. Returns the number of shorts accepted (may be 0) or a negative value on error. */
        int write(short[] data, int offset, int length);

        /** out[0] = frames rendered so far, out[1] = System.nanoTime() at which that was true. */
        boolean timestamp(long[] out);

        /** Frames the hardware has consumed so far (coarse; used when timestamps are not available). */
        long renderedFrames();

        long fallbackLatencyNanos();

        void setVolume(float gain);

        /** Best effort and thread safe: make the output silent right now. */
        void pause();

        void release();
    }

    public interface Clock {
        long nanoTime();

        void sleepNanos(long nanos) throws InterruptedException;
    }

    public interface Listener {
        void onSyncMeasured(long errorNanos, boolean timestampBased);

        void onSinkError(String message);
    }

    public static final Clock SYSTEM_CLOCK = new Clock() {
        @Override
        public long nanoTime() {
            return System.nanoTime();
        }

        @Override
        public void sleepNanos(long nanos) throws InterruptedException {
            if (nanos > 0) Thread.sleep(nanos / 1_000_000L, (int) (nanos % 1_000_000L));
        }
    };

    private static final int MAX_QUEUE = 400;
    /** Misalignment that is always corrected at once, whatever the re-sync interval says. */
    private static final long MIN_IMMEDIATE_ERROR_NANOS = 50_000_000L;
    private static final long IDLE_RELEASE_NANOS = 3_000_000_000L;
    private static final long REPORT_INTERVAL_NANOS = 500_000_000L;
    private static final int MAX_SILENCE_SECONDS = 3;

    private final Sink sink;
    private final Clock clock;
    private final Listener listener;
    private final LinkedBlockingQueue<PcmChunk> queue = new LinkedBlockingQueue<>();

    private volatile boolean running;
    private volatile int minEpoch = Integer.MIN_VALUE;
    private volatile boolean releaseRequested;
    private volatile boolean clockReady = true;
    private volatile long hostMinusLocalNanos;
    private volatile long userDelayNanos;
    private volatile ChannelMode channelMode = ChannelMode.STEREO;
    private volatile float volume = 1f;
    private volatile boolean muted;
    private volatile float duck = 1f;
    private volatile long toleranceNanos = 20_000_000L;
    private volatile long resyncIntervalNanos = 15_000_000_000L;
    private volatile boolean forceResync;
    private volatile long lastErrorNanos;
    private volatile boolean aligned;

    // Only touched by the player thread.
    private boolean opened;
    private boolean started;
    private boolean fadeIn;
    private int openRate;
    private int openChannels = 2;
    private long shortsWritten;
    private boolean everAligned;
    /** A correction is under way: keep going (across chunks) until the output is back within tolerance. */
    private boolean correcting;
    private long lastCorrectionNanos;
    private long lastReportNanos;
    private final long[] ts = new long[2];
    private short[] scratch = new short[4096];
    private short[] silence = new short[0];
    private boolean lastTimestampValid;
    private Thread thread;

    public ScheduledPlayer(Sink sink, Clock clock, Listener listener) {
        this.sink = sink;
        this.clock = clock;
        this.listener = listener;
    }

    // ---------------------------------------------------------------- lifecycle

    public synchronized void start(final String name, final Runnable onThreadStart) {
        if (running) return;
        running = true;
        thread = new Thread(new Runnable() {
            @Override
            public void run() {
                if (onThreadStart != null) onThreadStart.run();
                loop();
            }
        }, name);
        thread.start();
    }

    public synchronized void stop() {
        running = false;
        queue.clear();
        Thread t = thread;
        thread = null;
        if (t != null) t.interrupt();
    }

    public boolean isRunning() {
        return running;
    }

    // ---------------------------------------------------------------- input (any thread)

    public void enqueue(PcmChunk chunk) {
        if (chunk.epoch < minEpoch) return;
        queue.offer(chunk);
        while (queue.size() > MAX_QUEUE) queue.poll();
    }

    /** Drops everything that is queued, silences the output and ignores chunks older than {@code newEpoch}. */
    public void flush(int newEpoch) {
        minEpoch = newEpoch;
        queue.clear();
        releaseRequested = true;
        try {
            sink.pause();
        } catch (RuntimeException ignored) {
            // best effort
        }
    }

    // ---------------------------------------------------------------- settings (any thread)

    /** hostTime = localTime + offset. */
    public void setClockOffsetNanos(long offset) {
        hostMinusLocalNanos = offset;
    }

    /** While false, chunks are queued but not played (waiting for the first clock measurement). */
    public void setClockReady(boolean ready) {
        clockReady = ready;
    }

    /** Positive values play later than the group, negative earlier. */
    public void setUserDelayMs(int ms) {
        userDelayNanos = ms * 1_000_000L;
    }

    public void setChannelMode(ChannelMode mode) {
        channelMode = mode == null ? ChannelMode.STEREO : mode;
    }

    public ChannelMode getChannelMode() {
        return channelMode;
    }

    public void setVolume(float v) {
        volume = Math.max(0f, Math.min(1f, v));
        applyGain();
    }

    public void setMuted(boolean m) {
        muted = m;
        applyGain();
    }

    /** Temporary attenuation (audio focus ducking). 1 = none. */
    public void setDuck(float factor) {
        duck = Math.max(0f, Math.min(1f, factor));
        applyGain();
    }

    public void setToleranceMs(int ms) {
        toleranceNanos = Math.max(2, ms) * 1_000_000L;
    }

    /** 0 disables periodic correction (only the initial alignment and big errors are corrected). */
    public void setResyncIntervalSeconds(int seconds) {
        resyncIntervalNanos = seconds <= 0 ? 0 : seconds * 1_000_000_000L;
    }

    /** Correct the next measurable misalignment immediately, whatever the re-sync interval says. */
    public void requestResync() {
        forceResync = true;
    }

    public long lastErrorNanos() {
        return lastErrorNanos;
    }

    public boolean isAligned() {
        return aligned;
    }

    public int queuedChunks() {
        return queue.size();
    }

    private void applyGain() {
        float gain = muted ? 0f : volume * volume * duck;
        try {
            sink.setVolume(gain);
        } catch (RuntimeException ignored) {
            // output may not be open yet
        }
    }

    // ---------------------------------------------------------------- player thread

    private void loop() {
        long idleSince = clock.nanoTime();
        try {
            while (running) {
                if (releaseRequested) {
                    releaseRequested = false;
                    releaseSink();
                }
                if (!clockReady) {
                    clock.sleepNanos(10_000_000L);
                    continue;
                }
                PcmChunk chunk = queue.poll(40, TimeUnit.MILLISECONDS);
                if (chunk == null) {
                    if (opened && clock.nanoTime() - idleSince > IDLE_RELEASE_NANOS) releaseSink();
                    continue;
                }
                idleSince = clock.nanoTime();
                try {
                    handle(chunk);
                } catch (IOException e) {
                    if (listener != null) listener.onSinkError(String.valueOf(e.getMessage()));
                    releaseSink();
                }
            }
        } catch (InterruptedException ignored) {
            // stopping
        } finally {
            releaseSink();
        }
    }

    /** Synchronous variant used by tests: processes one chunk on the calling thread. */
    void processForTest(PcmChunk chunk) throws IOException, InterruptedException {
        running = true;
        handle(chunk);
    }

    private void handle(PcmChunk c) throws IOException, InterruptedException {
        if (releaseRequested) {
            releaseRequested = false;
            releaseSink();
        }
        if (c.epoch < minEpoch) return;
        if (c.epoch > minEpoch) minEpoch = c.epoch;
        ensureOpen(c.sampleRate, c.channels);

        final int rate = c.sampleRate;
        final int channels = c.channels;
        final int frames = c.frames;
        final long baseTarget = c.playAtNanos - hostMinusLocalNanos + userDelayNanos;
        int done = 0;
        int guard = 0;

        while (done < frames) {
            if (!running || releaseRequested || c.epoch < minEpoch) return;
            if (++guard > 64) throw new IOException("audio alignment did not settle");

            long now = clock.nanoTime();
            long target = baseTarget + framesToNanos(done, rate);
            long error = predictRender(now, rate) - target;
            long tolerance = lastTimestampValid ? toleranceNanos : toleranceNanos * 3;
            lastErrorNanos = error;
            boolean within = Math.abs(error) <= tolerance;
            aligned = within && lastTimestampValid;
            if (aligned) everAligned = true;
            if (within) correcting = false;
            report(now, error);

            if (!within && mayCorrect(now, error)) {
                correcting = true;
                lastCorrectionNanos = now;
                forceResync = false;
                if (error > 0) {
                    // This part would be heard too late: skip ahead.
                    int drop = (int) Math.min(nanosToFrames(error, rate), (long) (frames - done));
                    done += Math.max(1, drop);
                    fadeIn = true;
                } else {
                    // Too early: let silence play first.
                    long silenceFrames = Math.min(nanosToFrames(-error, rate), (long) rate * MAX_SILENCE_SECONDS);
                    if (!writeSilence(silenceFrames, channels, rate, c.epoch)) return;
                    fadeIn = true;
                }
                continue;
            }

            if (!writeAudio(c, done, frames - done)) return;
            done = frames;
        }
    }

    private boolean mayCorrect(long now, long error) {
        if (correcting) return true;
        if (!everAligned) return true;
        if (forceResync) return true;
        if (Math.abs(error) > Math.max(MIN_IMMEDIATE_ERROR_NANOS, toleranceNanos * 3)) return true;
        long interval = resyncIntervalNanos;
        return interval > 0 && now - lastCorrectionNanos >= interval;
    }

    private long predictRender(long now, int rate) {
        long frames = shortsWritten / openChannels;
        if (started && sink.timestamp(ts) && ts[1] > 0 && ts[1] <= now + 50_000_000L) {
            lastTimestampValid = true;
            return ts[1] + framesToNanos(frames - ts[0], rate);
        }
        lastTimestampValid = false;
        long rendered = started ? sink.renderedFrames() : 0;
        long buffered = Math.max(0, frames - rendered);
        return now + framesToNanos(buffered, rate) + sink.fallbackLatencyNanos();
    }

    private void report(long now, long error) {
        if (listener == null || now - lastReportNanos < REPORT_INTERVAL_NANOS) return;
        lastReportNanos = now;
        listener.onSyncMeasured(error, lastTimestampValid);
    }

    private void ensureOpen(int rate, int channels) throws IOException {
        if (opened && rate == openRate && channels == openChannels) return;
        releaseSink();
        sink.open(rate, channels);
        opened = true;
        started = false;
        openRate = rate;
        openChannels = channels;
        shortsWritten = 0;
        everAligned = false;
        correcting = false;
        lastTimestampValid = false;
        fadeIn = true;
        applyGain();
    }

    private void releaseSink() {
        if (!opened) return;
        opened = false;
        started = false;
        shortsWritten = 0;
        everAligned = false;
        correcting = false;
        aligned = false;
        lastTimestampValid = false;
        try {
            sink.release();
        } catch (RuntimeException ignored) {
            // already gone
        }
    }

    private boolean writeSilence(long frames, int channels, int rate, int epoch) throws IOException, InterruptedException {
        int slice = Math.max(1, rate / 50);
        if (silence.length < slice * channels) silence = new short[slice * channels];
        long left = frames;
        while (left > 0) {
            int n = (int) Math.min(left, (long) slice);
            if (!writeRaw(silence, 0, n * channels, epoch)) return false;
            left -= n;
        }
        return true;
    }

    private boolean writeAudio(PcmChunk c, int from, int count) throws IOException, InterruptedException {
        final int ch = c.channels;
        final ChannelMode mode = channelMode;
        int pos = from;
        final int end = from + count;
        while (pos < end) {
            int n = Math.min(2048, end - pos);
            if (scratch.length < n * ch) scratch = new short[n * ch];
            mode.apply(c.samples, pos * ch, scratch, 0, n, ch);
            if (fadeIn) {
                fadeIn = false;
                int ramp = Math.min(n, Math.max(8, c.sampleRate / 400));
                for (int i = 0; i < ramp; i++) {
                    float g = (i + 1) / (float) ramp;
                    for (int k = 0; k < ch; k++) scratch[i * ch + k] = (short) (scratch[i * ch + k] * g);
                }
            }
            if (!writeRaw(scratch, 0, n * ch, c.epoch)) return false;
            pos += n;
        }
        return true;
    }

    private boolean writeRaw(short[] buffer, int offset, int length, int epoch) throws IOException, InterruptedException {
        int stalls = 0;
        while (length > 0) {
            if (!running || releaseRequested || epoch < minEpoch) return false;
            int written = sink.write(buffer, offset, length);
            if (written < 0) throw new IOException("audio output write failed (" + written + ")");
            if (written > 0) {
                offset += written;
                length -= written;
                shortsWritten += written;
                stalls = 0;
                if (!started) {
                    sink.start();
                    started = true;
                }
            } else {
                if (!started) {
                    sink.start();
                    started = true;
                }
                if (++stalls > 1500) throw new IOException("audio output stalled");
                clock.sleepNanos(4_000_000L);
            }
        }
        return true;
    }

    static long framesToNanos(long frames, int rate) {
        return frames * 1_000_000_000L / rate;
    }

    static long nanosToFrames(long nanos, int rate) {
        return nanos * rate / 1_000_000_000L;
    }
}
