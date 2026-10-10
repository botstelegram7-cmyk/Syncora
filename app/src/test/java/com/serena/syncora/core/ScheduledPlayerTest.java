package com.serena.syncora.core;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

public class ScheduledPlayerTest {

    private static final long MS = 1_000_000L;

    /** Virtual clock: sleeping just moves time forward. */
    static final class SimClock implements ScheduledPlayer.Clock {
        long now = 5_000_000_000L;

        @Override
        public long nanoTime() {
            return now;
        }

        @Override
        public void sleepNanos(long nanos) {
            now += nanos;
        }
    }

    /** A pretend sound card: consumes frames at rate*scale once started, renders them hwLatency later. */
    static class SimSink implements ScheduledPlayer.Sink {
        static final class Write {
            final long startFrame;
            final int frames;
            final boolean audio;

            Write(long startFrame, int frames, boolean audio) {
                this.startFrame = startFrame;
                this.frames = frames;
                this.audio = audio;
            }
        }

        final SimClock clock;
        final int capacityFrames;
        final long hwLatencyNanos;
        final long fallbackLatencyNanos;
        final double rateScale;
        final boolean timestamps;
        int rate;
        int channels;
        boolean started;
        long startNanos;
        long writtenShorts;
        int opens;
        int releases;
        final List<Write> writes = new ArrayList<>();
        final List<short[]> audioBlocks = new ArrayList<>();
        float gain = 1f;

        SimSink(SimClock clock, int capacityFrames, long hwLatencyNanos, long fallbackLatencyNanos, double rateScale,
                boolean timestamps) {
            this.clock = clock;
            this.capacityFrames = capacityFrames;
            this.hwLatencyNanos = hwLatencyNanos;
            this.fallbackLatencyNanos = fallbackLatencyNanos;
            this.rateScale = rateScale;
            this.timestamps = timestamps;
        }

        private long consumed() {
            if (!started) return 0;
            long c = (long) ((clock.now - startNanos) * (double) rate * rateScale / 1e9);
            return Math.min(c, writtenShorts / channels);
        }

        @Override
        public void open(int sampleRate, int channels) {
            this.rate = sampleRate;
            this.channels = channels;
            started = false;
            writtenShorts = 0;
            opens++;
        }

        @Override
        public void start() {
            started = true;
            startNanos = clock.now;
        }

        @Override
        public int write(short[] data, int offset, int length) {
            long bufferedShorts = writtenShorts - consumed() * channels;
            long free = (long) capacityFrames * channels - bufferedShorts;
            int n = (int) Math.min(length, free);
            n -= n % channels;
            if (n <= 0) return 0;
            boolean audio = false;
            for (int i = 0; i < n; i++) {
                if (data[offset + i] != 0) {
                    audio = true;
                    break;
                }
            }
            writes.add(new Write(writtenShorts / channels, n / channels, audio));
            if (audio) {
                short[] copy = new short[n];
                System.arraycopy(data, offset, copy, 0, n);
                audioBlocks.add(copy);
            }
            writtenShorts += n;
            return n;
        }

        @Override
        public boolean timestamp(long[] out) {
            if (!timestamps || !started) return false;
            long quantized = (clock.now / (20 * MS)) * (20 * MS);
            long since = quantized - startNanos - hwLatencyNanos;
            if (since < 0) return false;
            long frames = Math.min((long) (since * (double) rate * rateScale / 1e9), writtenShorts / channels);
            out[0] = frames;
            out[1] = quantized;
            return true;
        }

        @Override
        public long renderedFrames() {
            long hwFrames = (long) (hwLatencyNanos * (double) rate / 1e9);
            return Math.max(0, consumed() - hwFrames);
        }

        @Override
        public long fallbackLatencyNanos() {
            return fallbackLatencyNanos;
        }

        @Override
        public void setVolume(float g) {
            gain = g;
        }

        @Override
        public void pause() {
        }

        @Override
        public void release() {
            releases++;
            started = false;
        }

        /** Local time at which sink frame {@code frame} comes out of the speaker. */
        long renderTime(long frame) {
            return startNanos + hwLatencyNanos + (long) (frame * 1e9 / (rate * rateScale));
        }
    }

    static final class Result {
        int index;
        long target;
        int dropped;
        long error;
        boolean played;
    }

    /** Feeds a stream of 40 ms chunks the way the host would and measures where each one really lands. */
    static final class Scenario {
        final SimClock clock = new SimClock();
        final SimSink sink;
        final ScheduledPlayer player;
        final int rate = 44100;
        final int chunkFrames = 1764; // 40 ms
        final long lookaheadNanos;
        final long hostMinusLocal;
        final long userDelayNanos;
        final long hostT0;
        final List<Result> results = new ArrayList<>();

        Scenario(long hostMinusLocal, int userDelayMs, long lookaheadMs, long hwLatencyMs, long fallbackMs,
                 double rateScale, boolean timestamps, int toleranceMs, int resyncSeconds) {
            this.hostMinusLocal = hostMinusLocal;
            this.userDelayNanos = userDelayMs * MS;
            this.lookaheadNanos = lookaheadMs * MS;
            sink = new SimSink(clock, 4410, hwLatencyMs * MS, fallbackMs * MS, rateScale, timestamps);
            player = new ScheduledPlayer(sink, clock, null);
            player.setClockOffsetNanos(hostMinusLocal);
            player.setUserDelayMs(userDelayMs);
            player.setToleranceMs(toleranceMs);
            player.setResyncIntervalSeconds(resyncSeconds);
            hostT0 = clock.now + hostMinusLocal + lookaheadNanos;
        }

        void run(int chunks, long firstArrivalExtraDelayNanos) throws Exception {
            short[] pcm = new short[chunkFrames * 2];
            for (int i = 0; i < pcm.length; i++) pcm[i] = 5000;
            for (int k = 0; k < chunks; k++) {
                long playAtHost = hostT0 + k * 40 * MS;
                long arrivalLocal = playAtHost - hostMinusLocal - lookaheadNanos + (k == 0 ? firstArrivalExtraDelayNanos : 0);
                clock.now = Math.max(clock.now, arrivalLocal);
                int writesBefore = sink.writes.size();
                player.processForTest(new PcmChunk(1, k, playAtHost, rate, 2, chunkFrames, pcm));

                Result r = new Result();
                r.index = k;
                r.target = playAtHost - hostMinusLocal + userDelayNanos;
                int audioFrames = 0;
                long firstAudioFrame = -1;
                for (int w = writesBefore; w < sink.writes.size(); w++) {
                    SimSink.Write write = sink.writes.get(w);
                    if (write.audio) {
                        if (firstAudioFrame < 0) firstAudioFrame = write.startFrame;
                        audioFrames += write.frames;
                    }
                }
                r.played = audioFrames > 0;
                if (r.played) {
                    r.dropped = chunkFrames - audioFrames;
                    long expected = r.target + r.dropped * 1_000_000_000L / rate;
                    r.error = sink.renderTime(firstAudioFrame) - expected;
                }
                results.add(r);
            }
        }

        long maxAbsError(int fromChunk) {
            long max = 0;
            for (int i = fromChunk; i < results.size(); i++) {
                Result r = results.get(i);
                assertTrue("chunk " + i + " should be played", r.played);
                max = Math.max(max, Math.abs(r.error));
            }
            return max;
        }
    }

    @Test
    public void firstChunkIsAlignedToItsDueTimeEvenWithAWrongLatencyGuess() throws Exception {
        // Real hardware latency is 35 ms, the player first assumes 80 ms until real timestamps show up.
        Scenario s = new Scenario(0, 0, 450, 35, 80, 1.0, true, 20, 15);
        s.run(60, 0);
        assertTrue("settled alignment within 20 ms, was " + s.maxAbsError(15) / MS + " ms", s.maxAbsError(15) <= 20 * MS);
        // The 390 ms of lead-in silence takes real time to play, so real timestamps exist before the first
        // audible frame is written and the wrong 80 ms guess is already corrected for chunk 0.
        assertTrue("first chunk error " + s.results.get(0).error / MS + " ms", Math.abs(s.results.get(0).error) <= 20 * MS);
        assertEquals(1, s.sink.opens);
    }

    @Test
    public void clockOffsetAndUserDelayAreApplied() throws Exception {
        long offset = 123_456_789_012L; // host clock is far ahead of this device
        Scenario s = new Scenario(offset, 30, 450, 40, 60, 1.0, true, 20, 15);
        s.run(60, 0);
        for (int i = 15; i < 60; i++) {
            Result r = s.results.get(i);
            // target already includes the +30 ms user delay, so measured error is relative to it
            assertTrue("chunk " + i + " error " + r.error / MS, Math.abs(r.error) <= 20 * MS);
        }
        long firstDue = s.hostT0 - offset + 30 * MS;
        long firstRender = s.sink.renderTime(s.sink.writes.get(0).startFrame); // sanity: a render time exists
        assertTrue(firstRender > 0 && firstDue > s.clock.now - 10_000_000_000L);
    }

    @Test
    public void chunksThatArriveLateAreSkippedForward() throws Exception {
        // The very first chunk shows up 220 ms after it was supposed to be audible.
        Scenario s = new Scenario(0, 0, 450, 35, 35, 1.0, true, 20, 15);
        s.run(40, 450 * MS + 220 * MS);
        Result first = s.results.get(0);
        assertTrue("late audio is dropped, not played late", first.dropped > 0 || !first.played);
        assertTrue("alignment recovers", s.maxAbsError(12) <= 20 * MS);
    }

    @Test
    public void clockDriftBetweenDevicesIsCorrectedAtTheResyncInterval() throws Exception {
        // Speaker's sound card runs 0.4% fast: it drifts ~4 ms per second.
        Scenario s = new Scenario(0, 0, 450, 35, 35, 1.004, true, 10, 3);
        s.run(400, 0); // 16 seconds
        long worst = 0;
        for (int i = 30; i < 400; i++) {
            Result r = s.results.get(i);
            if (r.played) worst = Math.max(worst, Math.abs(r.error));
        }
        assertTrue("drift stays bounded, worst was " + worst / MS + " ms", worst <= 30 * MS);
    }

    @Test
    public void withoutTimestampsPlaybackStillGetsRoughlyAligned() throws Exception {
        Scenario s = new Scenario(0, 0, 450, 40, 40, 1.0, false, 20, 15);
        s.run(60, 0);
        assertTrue("fallback alignment within 60 ms, was " + s.maxAbsError(10) / MS, s.maxAbsError(10) <= 60 * MS);
    }

    @Test
    public void flushIgnoresOldEpochsAndReopensOutput() throws Exception {
        SimClock clock = new SimClock();
        SimSink sink = new SimSink(clock, 4410, 30 * MS, 30 * MS, 1.0, true);
        ScheduledPlayer player = new ScheduledPlayer(sink, clock, null);
        short[] pcm = new short[1764 * 2];
        for (int i = 0; i < pcm.length; i++) pcm[i] = 4000;
        long due = clock.now + 100 * MS;
        player.processForTest(new PcmChunk(1, 0, due, 44100, 2, 1764, pcm));
        assertEquals(1, sink.opens);

        player.flush(2);
        int writesBefore = sink.writes.size();
        player.enqueue(new PcmChunk(1, 1, due + 40 * MS, 44100, 2, 1764, pcm)); // old epoch: ignored
        assertEquals(0, player.queuedChunks());
        player.processForTest(new PcmChunk(1, 2, due + 80 * MS, 44100, 2, 1764, pcm));
        assertEquals("stale chunk is not written", writesBefore, sink.writes.size());

        player.processForTest(new PcmChunk(2, 3, clock.now + 100 * MS, 44100, 2, 1764, pcm));
        assertEquals("output was reopened for the new epoch", 2, sink.opens);
        assertEquals(1, sink.releases);
        assertTrue(sink.writes.size() > writesBefore);
    }

    @Test
    public void channelModesRouteTheRightSamplesAndNeverTouchTheSharedChunk() throws Exception {
        SimClock clock = new SimClock();
        short[] pcm = new short[]{1000, -1000, 2000, -2000, 3000, -3000, 4000, -4000};
        short[] original = pcm.clone();
        PcmChunk chunk = new PcmChunk(1, 0, clock.now, 8000, 2, 4, pcm);

        short[] dst = new short[8];
        ChannelMode.LEFT.apply(pcm, 0, dst, 0, 4, 2);
        assertArrayEquals(new short[]{1000, 1000, 2000, 2000, 3000, 3000, 4000, 4000}, dst);
        ChannelMode.RIGHT.apply(pcm, 0, dst, 0, 4, 2);
        assertArrayEquals(new short[]{-1000, -1000, -2000, -2000, -3000, -3000, -4000, -4000}, dst);
        ChannelMode.MONO.apply(pcm, 0, dst, 0, 4, 2);
        assertArrayEquals(new short[]{0, 0, 0, 0, 0, 0, 0, 0}, dst);
        ChannelMode.STEREO.apply(pcm, 0, dst, 0, 4, 2);
        assertArrayEquals(original, dst);
        assertArrayEquals(original, chunk.samples);

        // and through the player: left mode on a stereo chunk reaches the sink as dual mono
        SimSink sink = new SimSink(clock, 44100, 10 * MS, 10 * MS, 1.0, true);
        ScheduledPlayer player = new ScheduledPlayer(sink, clock, null);
        player.setChannelMode(ChannelMode.LEFT);
        player.processForTest(new PcmChunk(1, 0, clock.now + 50 * MS, 8000, 2, 4, pcm));
        short[] played = null;
        for (short[] block : sink.audioBlocks) {
            played = block;
        }
        assertTrue(played != null);
        // first frames are faded in; the last frame is at full level
        assertEquals(4000, played[played.length - 2]);
        assertEquals(4000, played[played.length - 1]);
        assertArrayEquals(original, pcm);
    }

    @Test
    public void volumeCurveAndMute() throws Exception {
        SimClock clock = new SimClock();
        SimSink sink = new SimSink(clock, 44100, 10 * MS, 10 * MS, 1.0, true);
        ScheduledPlayer player = new ScheduledPlayer(sink, clock, null);
        player.setVolume(0.5f);
        assertEquals(0.25f, sink.gain, 1e-6);
        player.setMuted(true);
        assertEquals(0f, sink.gain, 1e-6);
        player.setMuted(false);
        player.setDuck(0.5f);
        assertEquals(0.125f, sink.gain, 1e-6);
    }

    @Test
    public void mismatchedRatesReopenTheOutput() throws Exception {
        SimClock clock = new SimClock();
        SimSink sink = new SimSink(clock, 44100, 10 * MS, 10 * MS, 1.0, true);
        ScheduledPlayer player = new ScheduledPlayer(sink, clock, null);
        short[] a = new short[882 * 2];
        short[] b = new short[960 * 2];
        for (int i = 0; i < a.length; i++) a[i] = 1000;
        for (int i = 0; i < b.length; i++) b[i] = 1000;
        player.processForTest(new PcmChunk(1, 0, clock.now + 50 * MS, 44100, 2, 882, a));
        player.processForTest(new PcmChunk(1, 1, clock.now + 100 * MS, 48000, 2, 960, b));
        assertEquals(2, sink.opens);
        assertEquals(48000, sink.rate);
    }

    @Test(expected = IOException.class)
    public void sinkWriteErrorsSurface() throws Exception {
        SimClock clock = new SimClock();
        SimSink sink = new SimSink(clock, 44100, 10 * MS, 10 * MS, 1.0, true) {
            @Override
            public int write(short[] data, int offset, int length) {
                return -6;
            }
        };
        ScheduledPlayer player = new ScheduledPlayer(sink, clock, null);
        player.processForTest(new PcmChunk(1, 0, clock.now + 50 * MS, 44100, 2, 100, new short[200]));
    }
}
