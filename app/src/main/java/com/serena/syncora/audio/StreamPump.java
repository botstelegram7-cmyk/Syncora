package com.serena.syncora.audio;

import com.serena.syncora.core.PcmChunk;
import com.serena.syncora.core.ScheduledPlayer;

/**
 * Reads PCM from a {@link PcmSource} on its own thread and puts it on the group timeline.
 *
 * <p>Every chunk gets a play time on the host clock: the first chunk is due {@code lookahead} after it was read and
 * every following chunk follows without gaps. Chunks are released exactly one lookahead before they are due, so the
 * speakers always have that much audio buffered and a fast source (a file) is throttled to real time.
 */
public final class StreamPump {

    public interface Output {
        /** A chunk is ready to be distributed (pump thread). */
        void onChunk(PcmChunk chunk);

        /** From now on {@code mediaMs} of the media is due at {@code hostNanos}. */
        void onAnchor(int epoch, long mediaMs, long hostNanos);

        /** The source is exhausted; the last frame finishes playing at {@code endAtHostNanos}. */
        void onEnded(int epoch, long endAtHostNanos);

        void onError(int epoch, String message);
    }

    private static final int CHUNK_MS = 40;

    private final PcmSource source;
    private final Output output;
    private final ScheduledPlayer.Clock clock;
    private final long lookaheadNanos;
    private final long minLeadNanos;
    private final long chainedAnchorNanos;
    private final Object lock = new Object();

    private int epoch;
    private long startPositionMs;
    private volatile boolean stopRequested;
    private boolean restartPending;
    private int restartEpoch;
    private long restartPositionMs;
    private Thread thread;

    // pump-thread state
    private boolean anchored;
    private long nextPlayAt;
    private int seq;
    private boolean first = true;

    /**
     * @param chainedAnchorNanos when &gt; 0 the first chunk is due exactly then (gapless continuation of the
     *                           previous track), as long as that is still in the future
     */
    public StreamPump(PcmSource source, Output output, ScheduledPlayer.Clock clock, int epoch, long startPositionMs,
                      int lookaheadMs, long chainedAnchorNanos) {
        this.source = source;
        this.output = output;
        this.clock = clock;
        this.epoch = epoch;
        this.startPositionMs = startPositionMs;
        this.lookaheadNanos = lookaheadMs * 1_000_000L;
        this.minLeadNanos = lookaheadNanos / 4;
        this.chainedAnchorNanos = chainedAnchorNanos;
    }

    public synchronized void start(String name) {
        if (thread != null) return;
        thread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO);
                } catch (Throwable ignored) {
                    // not on Android (unit tests)
                }
                runLoop();
            }
        }, name);
        thread.start();
    }

    public void stop() {
        stopRequested = true;
        source.abort();
        Thread t;
        synchronized (this) {
            t = thread;
        }
        if (t != null) t.interrupt();
    }

    /** Jump to {@code positionMs} and continue on a new epoch (seek). Returns immediately. */
    public void restart(int newEpoch, long positionMs) {
        synchronized (lock) {
            restartPending = true;
            restartEpoch = newEpoch;
            restartPositionMs = positionMs;
        }
        Thread t;
        synchronized (this) {
            t = thread;
        }
        if (t != null) t.interrupt();
    }

    public int epoch() {
        return epoch;
    }

    // ---------------------------------------------------------------- pump thread

    void runLoop() {
        try {
            source.open();
            if (startPositionMs > 0 && !source.isLive()) source.seekTo(startPositionMs);
            while (!stopRequested) {
                try {
                    if (consumeRestart()) continue;
                    PcmSource.Pcm pcm = source.read();
                    if (stopRequested) break;
                    if (pcm == null) {
                        output.onEnded(epoch, anchored ? nextPlayAt : clock.nanoTime());
                        break;
                    }
                    emit(pcm);
                } catch (InterruptedException e) {
                    if (stopRequested) break;
                    // restart requested: handled at the top of the loop
                }
            }
        } catch (Exception e) {
            if (!stopRequested) {
                String message = e.getMessage();
                output.onError(epoch, message == null || message.isEmpty() ? e.getClass().getSimpleName() : message);
            }
        } finally {
            try {
                source.close();
            } catch (RuntimeException ignored) {
                // nothing to do
            }
        }
    }

    private boolean consumeRestart() throws java.io.IOException {
        int newEpoch;
        long position;
        synchronized (lock) {
            if (!restartPending) return false;
            restartPending = false;
            newEpoch = restartEpoch;
            position = restartPositionMs;
        }
        Thread.interrupted(); // clear a late interrupt that belongs to this restart
        epoch = newEpoch;
        anchored = false;
        first = false; // a seek never chains
        source.seekTo(position);
        return true;
    }

    private void emit(PcmSource.Pcm pcm) throws InterruptedException {
        final int rate = pcm.sampleRate;
        final int channels = pcm.channels;
        final int chunkFrames = Math.max(1, rate * CHUNK_MS / 1000);
        int offset = 0;
        while (offset < pcm.frames) {
            if (stopRequested) return;
            synchronized (lock) {
                if (restartPending) return; // drop the rest of this block, the restart takes over
            }
            int n = Math.min(chunkFrames, pcm.frames - offset);
            long mediaMs = pcm.mediaTimeUs / 1000L + offset * 1000L / rate;
            long now = clock.nanoTime();
            if (!anchored) {
                long start = now + lookaheadNanos;
                if (first && chainedAnchorNanos > 0 && chainedAnchorNanos - now >= minLeadNanos) {
                    start = chainedAnchorNanos;
                }
                first = false;
                nextPlayAt = start;
                anchored = true;
                output.onAnchor(epoch, mediaMs, nextPlayAt);
            } else if (nextPlayAt - now < minLeadNanos) {
                // The source could not keep up, this audio can no longer be heard on time: start a new timeline.
                nextPlayAt = now + lookaheadNanos;
                output.onAnchor(epoch, mediaMs, nextPlayAt);
            }
            long wait = nextPlayAt - lookaheadNanos - clock.nanoTime();
            if (wait > 0) clock.sleepNanos(wait);

            short[] samples = new short[n * channels];
            System.arraycopy(pcm.samples, offset * channels, samples, 0, n * channels);
            output.onChunk(new PcmChunk(epoch, seq++, nextPlayAt, rate, channels, n, samples));
            nextPlayAt += n * 1_000_000_000L / rate;
            offset += n;
        }
    }
}
