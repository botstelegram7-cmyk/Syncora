package com.serena.syncora.audio;

import android.media.AudioFormat;
import android.media.MediaCodec;
import android.media.MediaFormat;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.ShortBuffer;

/** Thin wrapper around a MediaCodec audio decoder that hands out interleaved PCM16 (mono or stereo). */
final class PcmCodec {
    private final MediaCodec codec;
    private final MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
    private int sampleRate;
    private int channels;
    private int encoding = AudioFormat.ENCODING_PCM_16BIT;
    private long skipUntilUs = -1;
    private boolean outputEos;

    private PcmCodec(MediaCodec codec, int sampleRate, int channels) {
        this.codec = codec;
        this.sampleRate = sampleRate;
        this.channels = channels;
    }

    static PcmCodec create(MediaFormat format) throws IOException {
        String mime = format.getString(MediaFormat.KEY_MIME);
        MediaCodec codec = null;
        try {
            codec = MediaCodec.createDecoderByType(mime);
            codec.configure(format, null, null, 0);
            codec.start();
        } catch (Exception e) {
            if (codec != null) {
                try {
                    codec.release();
                } catch (RuntimeException ignored) {
                    // nothing to do
                }
            }
            throw new IOException("This device cannot decode " + mime + " audio", e);
        }
        int rate = format.containsKey(MediaFormat.KEY_SAMPLE_RATE) ? format.getInteger(MediaFormat.KEY_SAMPLE_RATE) : 44100;
        int ch = format.containsKey(MediaFormat.KEY_CHANNEL_COUNT) ? format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) : 2;
        return new PcmCodec(codec, rate, ch);
    }

    int dequeueInputBuffer(long timeoutUs) {
        return codec.dequeueInputBuffer(timeoutUs);
    }

    ByteBuffer inputBuffer(int index) {
        ByteBuffer buffer = codec.getInputBuffer(index);
        if (buffer != null) buffer.clear();
        return buffer;
    }

    void queueInput(int index, int size, long ptsUs) {
        codec.queueInputBuffer(index, 0, size, ptsUs, 0);
    }

    void queueEndOfStream(int index) {
        codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
    }

    void skipUntil(long positionUs) {
        skipUntilUs = positionUs;
    }

    void flush() {
        codec.flush();
        outputEos = false;
    }

    boolean isOutputEos() {
        return outputEos;
    }

    /** Returns decoded audio or null when nothing is ready (check {@link #isOutputEos()}). */
    PcmSource.Pcm dequeueOutput(long timeoutUs) {
        int index = codec.dequeueOutputBuffer(info, timeoutUs);
        if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
            readFormat(codec.getOutputFormat());
            return null;
        }
        if (index < 0) return null;
        PcmSource.Pcm result = null;
        try {
            if (info.size > 0 && (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                ByteBuffer buffer = codec.getOutputBuffer(index);
                if (buffer != null) result = convert(buffer);
            }
        } finally {
            codec.releaseOutputBuffer(index, false);
        }
        if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outputEos = true;
        return result;
    }

    void release() {
        try {
            codec.stop();
        } catch (RuntimeException ignored) {
            // may already be stopped
        }
        try {
            codec.release();
        } catch (RuntimeException ignored) {
            // nothing to do
        }
    }

    private void readFormat(MediaFormat format) {
        if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE);
        if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
        encoding = AudioFormat.ENCODING_PCM_16BIT;
        if (format.containsKey(MediaFormat.KEY_PCM_ENCODING)) encoding = format.getInteger(MediaFormat.KEY_PCM_ENCODING);
    }

    private PcmSource.Pcm convert(ByteBuffer buffer) {
        buffer.position(info.offset);
        buffer.limit(info.offset + info.size);
        buffer.order(ByteOrder.nativeOrder());
        final boolean isFloat = encoding == AudioFormat.ENCODING_PCM_FLOAT;
        final int inChannels = Math.max(1, channels);
        final int bytesPerSample = isFloat ? 4 : 2;
        int frames = info.size / bytesPerSample / inChannels;
        if (frames <= 0 || sampleRate <= 0) return null;

        long ptsUs = info.presentationTimeUs;
        int skip = 0;
        if (skipUntilUs >= 0) {
            long endUs = ptsUs + frames * 1_000_000L / sampleRate;
            if (endUs <= skipUntilUs) return null;
            if (ptsUs < skipUntilUs) skip = (int) ((skipUntilUs - ptsUs) * sampleRate / 1_000_000L);
            skip = Math.min(skip, frames - 1);
            ptsUs += skip * 1_000_000L / sampleRate;
            skipUntilUs = -1;
        }
        int outFrames = frames - skip;
        final int outChannels = inChannels == 1 ? 1 : 2;
        short[] out = new short[outFrames * outChannels];

        if (isFloat) {
            FloatBuffer floats = buffer.asFloatBuffer();
            floats.position(skip * inChannels);
            float[] frame = new float[inChannels];
            for (int i = 0; i < outFrames; i++) {
                floats.get(frame);
                mixFloat(frame, inChannels, out, i * outChannels, outChannels);
            }
        } else {
            ShortBuffer shorts = buffer.asShortBuffer();
            shorts.position(skip * inChannels);
            if (inChannels <= 2) {
                shorts.get(out, 0, outFrames * outChannels);
            } else {
                short[] frame = new short[inChannels];
                float[] f = new float[inChannels];
                for (int i = 0; i < outFrames; i++) {
                    shorts.get(frame);
                    for (int k = 0; k < inChannels; k++) f[k] = frame[k] / 32768f;
                    mixFloat(f, inChannels, out, i * outChannels, outChannels);
                }
            }
        }
        return new PcmSource.Pcm(out, outFrames, sampleRate, outChannels, ptsUs);
    }

    /** Down-mixes one frame (SMPTE order: L R C LFE Ls Rs ...) to mono/stereo PCM16. */
    private static void mixFloat(float[] in, int inChannels, short[] out, int outIndex, int outChannels) {
        if (inChannels == 1) {
            out[outIndex] = clip(in[0]);
        } else if (inChannels == 2) {
            out[outIndex] = clip(in[0]);
            out[outIndex + 1] = clip(in[1]);
        } else {
            float center = inChannels > 2 ? in[2] * 0.7071f : 0f;
            float left = in[0] + center + (inChannels > 4 ? in[4] * 0.7071f : 0f);
            float right = in[1] + center + (inChannels > 5 ? in[5] * 0.7071f : 0f);
            out[outIndex] = clip(left * 0.6f);
            out[outIndex + 1] = clip(right * 0.6f);
        }
    }

    private static short clip(float v) {
        int s = Math.round(v * 32767f);
        if (s > 32767) s = 32767;
        if (s < -32768) s = -32768;
        return (short) s;
    }
}
