package com.serena.syncora.audio;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.serena.syncora.core.PcmChunk;
import com.serena.syncora.core.ScheduledPlayer;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

public class StreamPumpTest {
    private static final long MS = 1_000_000L;

    static final class SimClock implements ScheduledPlayer.Clock {
        long now = 1_000_000_000L;

        @Override
        public long nanoTime() {
            return now;
        }

        @Override
        public void sleepNanos(long nanos) {
            if (nanos > 0) now += nanos;
        }
    }

    /** Produces `blocks` blocks of `framesPerBlock` frames; optional hook runs before each read. */
    static class FakeSource implements PcmSource {
        final int rate;
        final int framesPerBlock;
        int blocks;
        int reads;
        long positionFrames;
        boolean opened;
        boolean closed;
        long seekedTo = -1;
        Runnable beforeRead;
        long stallNanos;
        SimClock clock;
        int stallAtRead = -1;

        FakeSource(int rate, int framesPerBlock, int blocks) {
            this.rate = rate;
            this.framesPerBlock = framesPerBlock;
            this.blocks = blocks;
        }

        @Override
        public void open() throws IOException {
            opened = true;
        }

        @Override
        public Pcm read() {
            if (beforeRead != null) beforeRead.run();
            if (reads == stallAtRead && clock != null) clock.now += stallNanos;
            reads++;
            if (blocks-- <= 0) return null;
            short[] data = new short[framesPerBlock * 2];
            for (int i = 0; i < data.length; i++) data[i] = (short) (reads);
            Pcm pcm = new Pcm(data, framesPerBlock, rate, 2, positionFrames * 1_000_000L / rate);
            positionFrames += framesPerBlock;
            return pcm;
        }

        @Override
        public void seekTo(long positionMs) {
            seekedTo = positionMs;
            positionFrames = positionMs * rate / 1000;
        }

        @Override
        public long durationMs() {
            return -1;
        }

        @Override
        public boolean isLive() {
            return false;
        }

        @Override
        public void abort() {
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    static final class Recorder implements StreamPump.Output {
        final SimClock clock;
        final List<PcmChunk> chunks = new ArrayList<>();
        final List<Long> sentAt = new ArrayList<>();
        final List<long[]> anchors = new ArrayList<>(); // epoch, mediaMs, hostNanos
        long endedAt = -1;
        int endedEpoch = -1;
        String error;

        Recorder(SimClock clock) {
            this.clock = clock;
        }

        @Override
        public void onChunk(PcmChunk chunk) {
            chunks.add(chunk);
            sentAt.add(clock.now);
        }

        @Override
        public void onAnchor(int epoch, long mediaMs, long hostNanos) {
            anchors.add(new long[]{epoch, mediaMs, hostNanos});
        }

        @Override
        public void onEnded(int epoch, long endAtHostNanos) {
            endedEpoch = epoch;
            endedAt = endAtHostNanos;
        }

        @Override
        public void onError(int epoch, String message) {
            error = message;
        }
    }

    @Test
    public void chunksAreContiguousAndReleasedOneLookaheadBeforeTheyAreDue() {
        SimClock clock = new SimClock();
        Recorder out = new Recorder(clock);
        FakeSource source = new FakeSource(44100, 4410, 5); // 100 ms blocks, 0.5 s in total
        long t0 = clock.now;
        StreamPump pump = new StreamPump(source, out, clock, 7, 0, 450, 0);
        pump.runLoop();

        assertTrue(source.opened);
        assertTrue(source.closed);
        assertEquals(1, out.anchors.size());
        assertEquals(7, out.anchors.get(0)[0]);
        assertEquals(0, out.anchors.get(0)[1]);
        assertEquals(t0 + 450 * MS, out.anchors.get(0)[2]);
        assertEquals("each 4410-frame block is cut into 1764 + 1764 + 882 frames", 5 * 3, out.chunks.size());

        long expected = t0 + 450 * MS;
        for (int i = 0; i < out.chunks.size(); i++) {
            PcmChunk c = out.chunks.get(i);
            assertEquals(7, c.epoch);
            assertEquals(i, c.seq);
            assertEquals("chunk " + i + " due time", expected, c.playAtNanos);
            assertEquals("chunk " + i + " released exactly one lookahead early", c.playAtNanos - 450 * MS, out.sentAt.get(i).longValue());
            expected += c.durationNanos();
        }
        assertEquals(7, out.endedEpoch);
        assertEquals(expected, out.endedAt);
    }

    @Test
    public void startPositionSeeksTheSourceFirst() {
        SimClock clock = new SimClock();
        Recorder out = new Recorder(clock);
        FakeSource source = new FakeSource(48000, 4800, 2);
        StreamPump pump = new StreamPump(source, out, clock, 1, 61_500, 300, 0);
        pump.runLoop();
        assertEquals(61_500, source.seekedTo);
        assertEquals(61_500, out.anchors.get(0)[1]);
    }

    @Test
    public void gaplessChainStartsExactlyWhereThePreviousTrackEnds() {
        SimClock clock = new SimClock();
        Recorder out = new Recorder(clock);
        FakeSource source = new FakeSource(44100, 1764, 3);
        long previousEnd = clock.now + 380 * MS; // still comfortably in the future
        StreamPump pump = new StreamPump(source, out, clock, 4, 0, 450, previousEnd);
        pump.runLoop();
        assertEquals(previousEnd, out.chunks.get(0).playAtNanos);
        assertEquals(previousEnd, out.anchors.get(0)[2]);
    }

    @Test
    public void staleChainAnchorFallsBackToANormalStart() {
        SimClock clock = new SimClock();
        Recorder out = new Recorder(clock);
        FakeSource source = new FakeSource(44100, 1764, 1);
        long tooLate = clock.now + 20 * MS; // less than a quarter of the lookahead left
        StreamPump pump = new StreamPump(source, out, clock, 4, 0, 450, tooLate);
        long t0 = clock.now;
        pump.runLoop();
        assertEquals(t0 + 450 * MS, out.chunks.get(0).playAtNanos);
    }

    @Test
    public void aSourceStallStartsANewTimelineInsteadOfSendingLateAudio() {
        SimClock clock = new SimClock();
        Recorder out = new Recorder(clock);
        FakeSource source = new FakeSource(44100, 1764, 10); // 40 ms blocks
        source.clock = clock;
        source.stallAtRead = 4;
        source.stallNanos = 700 * MS; // e.g. a radio stream that buffered
        StreamPump pump = new StreamPump(source, out, clock, 1, 0, 450, 0);
        pump.runLoop();
        assertEquals("a second anchor was announced", 2, out.anchors.size());
        for (int i = 0; i < out.chunks.size(); i++) {
            assertTrue("chunk " + i + " is never sent after it was due",
                    out.sentAt.get(i) <= out.chunks.get(i).playAtNanos);
        }
        // playAt jumps forward at the stall, but stays contiguous afterwards
        PcmChunk before = out.chunks.get(3);
        PcmChunk after = out.chunks.get(4);
        assertTrue(after.playAtNanos - before.playAtNanos > 40 * MS);
        for (int i = 5; i < out.chunks.size(); i++) {
            assertEquals(out.chunks.get(i - 1).playAtNanos + out.chunks.get(i - 1).durationNanos(), out.chunks.get(i).playAtNanos);
        }
    }

    @Test
    public void restartSeeksAndSwitchesEpoch() {
        SimClock clock = new SimClock();
        final Recorder out = new Recorder(clock);
        FakeSource source = new FakeSource(44100, 1764, 20);
        final StreamPump[] holder = new StreamPump[1];
        source.beforeRead = new Runnable() {
            int n;

            @Override
            public void run() {
                if (++n == 6) holder[0].restart(9, 120_000);
            }
        };
        StreamPump pump = new StreamPump(source, out, clock, 2, 0, 450, 0);
        holder[0] = pump;
        pump.runLoop();
        assertEquals(120_000, source.seekedTo);
        boolean sawNewEpoch = false;
        for (PcmChunk c : out.chunks) if (c.epoch == 9) sawNewEpoch = true;
        assertTrue(sawNewEpoch);
        // the anchor for the new epoch points at the seek position
        long[] last = out.anchors.get(out.anchors.size() - 1);
        assertEquals(9, last[0]);
        assertTrue("anchor media position near the seek target: " + last[1], Math.abs(last[1] - 120_000) < 100);
    }

    static final class FailingSource extends FakeSource {
        FailingSource() {
            super(44100, 1764, 1);
        }

        @Override
        public Pcm read() {
            throw new RuntimeException("decoder exploded");
        }
    }

    @Test
    public void errorsAreReportedAndSourceIsClosed() {
        SimClock clock = new SimClock();
        Recorder out = new Recorder(clock);
        FailingSource source = new FailingSource();
        new StreamPump(source, out, clock, 3, 0, 450, 0).runLoop();
        assertEquals("decoder exploded", out.error);
        assertTrue(source.closed);
    }

    static final class OpenFailsSource extends FakeSource {
        OpenFailsSource() {
            super(44100, 1764, 1);
        }

        @Override
        public void open() throws IOException {
            throw new IOException("no such file");
        }
    }

    @Test
    public void openFailureIsReported() {
        SimClock clock = new SimClock();
        Recorder out = new Recorder(clock);
        new StreamPump(new OpenFailsSource(), out, clock, 3, 0, 450, 0).runLoop();
        assertEquals("no such file", out.error);
    }
}
