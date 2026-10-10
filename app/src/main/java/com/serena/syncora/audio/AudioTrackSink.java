package com.serena.syncora.audio;

import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioTimestamp;
import android.media.AudioTrack;

import com.serena.syncora.core.ScheduledPlayer;

import java.io.IOException;

/** {@link ScheduledPlayer.Sink} backed by a streaming AudioTrack with non-blocking writes. */
public final class AudioTrackSink implements ScheduledPlayer.Sink {
    private volatile AudioTrack track;
    private volatile int bufferMs = 160;
    private volatile boolean lowLatency;
    private volatile float gain = 1f;
    private int channels = 2;
    private final AudioTimestamp timestamp = new AudioTimestamp();

    /** Applies to the next {@link #open}. */
    public void configure(int bufferMs, boolean lowLatency) {
        this.bufferMs = Math.max(40, bufferMs);
        this.lowLatency = lowLatency;
    }

    @Override
    public synchronized void open(int sampleRate, int channelCount) throws IOException {
        release();
        channels = channelCount == 1 ? 1 : 2;
        int mask = channels == 1 ? AudioFormat.CHANNEL_OUT_MONO : AudioFormat.CHANNEL_OUT_STEREO;
        int minBuffer = AudioTrack.getMinBufferSize(sampleRate, mask, AudioFormat.ENCODING_PCM_16BIT);
        if (minBuffer <= 0) throw new IOException("Audio output does not support " + sampleRate + " Hz");
        int frameBytes = channels * 2;
        int wanted = (int) ((long) sampleRate * frameBytes * bufferMs / 1000L);
        int size = Math.max(minBuffer, wanted);
        size += (frameBytes - size % frameBytes) % frameBytes;

        AudioAttributes attributes = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build();
        AudioFormat format = new AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(sampleRate)
                .setChannelMask(mask)
                .build();
        AudioTrack created = build(attributes, format, size, lowLatency);
        if (created == null && lowLatency) created = build(attributes, format, size, false); // not every device offers the fast path
        if (created == null) throw new IOException("Could not open the audio output");
        created.setVolume(gain);
        track = created;
    }

    private static AudioTrack build(AudioAttributes attributes, AudioFormat format, int size, boolean fast) {
        try {
            AudioTrack t = new AudioTrack.Builder()
                    .setAudioAttributes(attributes)
                    .setAudioFormat(format)
                    .setBufferSizeInBytes(size)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .setPerformanceMode(fast ? AudioTrack.PERFORMANCE_MODE_LOW_LATENCY : AudioTrack.PERFORMANCE_MODE_NONE)
                    .build();
            if (t.getState() == AudioTrack.STATE_INITIALIZED) return t;
            t.release();
        } catch (RuntimeException ignored) {
            // fall through: the caller tries the next option
        }
        return null;
    }

    @Override
    public void start() {
        AudioTrack t = track;
        if (t != null) {
            try {
                t.play();
            } catch (IllegalStateException ignored) {
                // released concurrently
            }
        }
    }

    @Override
    public int write(short[] data, int offset, int length) {
        AudioTrack t = track;
        if (t == null) return AudioTrack.ERROR_DEAD_OBJECT;
        try {
            return t.write(data, offset, length, AudioTrack.WRITE_NON_BLOCKING);
        } catch (IllegalStateException e) {
            return AudioTrack.ERROR_DEAD_OBJECT;
        }
    }

    @Override
    public boolean timestamp(long[] out) {
        AudioTrack t = track;
        if (t == null) return false;
        try {
            if (!t.getTimestamp(timestamp)) return false;
            out[0] = timestamp.framePosition;
            out[1] = timestamp.nanoTime;
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    @Override
    public long renderedFrames() {
        AudioTrack t = track;
        if (t == null) return 0;
        try {
            return t.getPlaybackHeadPosition() & 0xFFFFFFFFL;
        } catch (RuntimeException e) {
            return 0;
        }
    }

    @Override
    public long fallbackLatencyNanos() {
        AudioTrack t = track;
        try {
            if (t != null) {
                AudioDeviceInfo device = t.getRoutedDevice();
                if (device != null) {
                    int type = device.getType();
                    if (type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP) return 220_000_000L;
                    if (type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO) return 150_000_000L;
                }
            }
        } catch (RuntimeException ignored) {
            // fall through
        }
        return 60_000_000L;
    }

    @Override
    public void setVolume(float volume) {
        gain = volume;
        AudioTrack t = track;
        if (t != null) {
            try {
                t.setVolume(volume);
            } catch (RuntimeException ignored) {
                // released concurrently
            }
        }
    }

    @Override
    public void pause() {
        AudioTrack t = track;
        if (t != null) {
            try {
                t.pause();
            } catch (RuntimeException ignored) {
                // released concurrently
            }
        }
    }

    @Override
    public synchronized void release() {
        AudioTrack t = track;
        track = null;
        if (t == null) return;
        try {
            t.pause();
            t.flush();
        } catch (RuntimeException ignored) {
            // best effort
        }
        try {
            t.release();
        } catch (RuntimeException ignored) {
            // best effort
        }
    }
}
