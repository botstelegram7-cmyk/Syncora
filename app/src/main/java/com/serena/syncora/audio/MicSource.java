package com.serena.syncora.audio;

import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;

import java.io.IOException;

/** Live capture from the microphone or a wired / USB aux input. */
public final class MicSource implements PcmSource {
    public static final int SAMPLE_RATE = 48000;
    private static final int BLOCK_FRAMES = SAMPLE_RATE / 50; // 20 ms

    private final AudioDeviceInfo preferred;
    private final boolean unprocessed;
    private AudioRecord record;
    private volatile boolean aborted;
    private volatile float gain = 1f;
    private volatile float level;

    /**
     * @param preferred   input to capture from, or null for the default
     * @param unprocessed true for line-level aux inputs (no AGC / noise suppression)
     */
    public MicSource(AudioDeviceInfo preferred, boolean unprocessed) {
        this.preferred = preferred;
        this.unprocessed = unprocessed;
    }

    public void setGain(float gain) {
        this.gain = Math.max(0f, Math.min(4f, gain));
    }

    /** 0..1 loudness of the last block. */
    public float level() {
        return level;
    }

    @Override
    public void open() throws IOException {
        int minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        if (minBuffer <= 0) throw new IOException("This device cannot record at " + SAMPLE_RATE + " Hz");
        int size = Math.max(minBuffer, SAMPLE_RATE * 2 / 5);
        int source = unprocessed ? MediaRecorder.AudioSource.UNPROCESSED : MediaRecorder.AudioSource.MIC;
        try {
            record = new AudioRecord(source, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, size);
            if (record.getState() != AudioRecord.STATE_INITIALIZED && unprocessed) {
                record.release();
                record = new AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT, size);
            }
        } catch (SecurityException e) {
            throw new IOException("Microphone permission is required", e);
        } catch (RuntimeException e) {
            throw new IOException("Could not open the microphone: " + e.getMessage(), e);
        }
        if (record.getState() != AudioRecord.STATE_INITIALIZED) {
            record.release();
            record = null;
            throw new IOException("The microphone is busy or unavailable");
        }
        if (preferred != null) record.setPreferredDevice(preferred);
        try {
            record.startRecording();
        } catch (IllegalStateException e) {
            throw new IOException("Could not start recording", e);
        }
    }

    @Override
    public Pcm read() throws IOException, InterruptedException {
        AudioRecord r = record;
        if (r == null || aborted) throw new InterruptedException();
        short[] buffer = new short[BLOCK_FRAMES];
        int done = 0;
        while (done < BLOCK_FRAMES) {
            if (aborted || Thread.interrupted()) throw new InterruptedException();
            int n = r.read(buffer, done, BLOCK_FRAMES - done, AudioRecord.READ_BLOCKING);
            if (n < 0) throw new IOException("Microphone read failed (" + n + ")");
            done += n;
        }
        float g = gain;
        int peak = 0;
        for (int i = 0; i < done; i++) {
            int v = Math.round(buffer[i] * g);
            if (v > 32767) v = 32767;
            if (v < -32768) v = -32768;
            buffer[i] = (short) v;
            int a = Math.abs(v);
            if (a > peak) peak = a;
        }
        level = peak / 32768f;
        return new Pcm(buffer, done, SAMPLE_RATE, 1, 0);
    }

    @Override
    public void seekTo(long positionMs) {
        // live
    }

    @Override
    public long durationMs() {
        return -1;
    }

    @Override
    public boolean isLive() {
        return true;
    }

    @Override
    public void abort() {
        aborted = true;
        AudioRecord r = record;
        if (r != null) {
            try {
                r.stop();
            } catch (RuntimeException ignored) {
                // already stopped
            }
        }
    }

    @Override
    public void close() {
        AudioRecord r = record;
        record = null;
        if (r != null) {
            try {
                r.stop();
            } catch (RuntimeException ignored) {
                // already stopped
            }
            r.release();
        }
        level = 0;
    }
}
