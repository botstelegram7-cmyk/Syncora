package com.serena.syncora.core;

/** Which channel(s) of the stream this device plays. Used to build stereo pairs out of two phones. */
public enum ChannelMode {
    STEREO("stereo", "Stereo"),
    MONO("mono", "Mono"),
    LEFT("left", "Left"),
    RIGHT("right", "Right");

    public final String id;
    public final String label;

    ChannelMode(String id, String label) {
        this.id = id;
        this.label = label;
    }

    public static ChannelMode fromId(String id) {
        if (id != null) {
            for (ChannelMode mode : values()) {
                if (mode.id.equals(id)) return mode;
            }
        }
        return STEREO;
    }

    /**
     * Copies {@code frames} interleaved PCM16 frames from {@code src} to {@code dst} while applying this mode.
     * Mono sources are copied unchanged. {@code src} is never modified because chunks are shared between outputs.
     */
    public void apply(short[] src, int srcOffset, short[] dst, int dstOffset, int frames, int channels) {
        if (channels != 2 || this == STEREO) {
            System.arraycopy(src, srcOffset, dst, dstOffset, frames * channels);
            return;
        }
        int s = srcOffset;
        int d = dstOffset;
        switch (this) {
            case MONO:
                for (int i = 0; i < frames; i++) {
                    short m = (short) ((src[s] + src[s + 1]) >> 1);
                    dst[d] = m;
                    dst[d + 1] = m;
                    s += 2;
                    d += 2;
                }
                break;
            case LEFT:
                for (int i = 0; i < frames; i++) {
                    short l = src[s];
                    dst[d] = l;
                    dst[d + 1] = l;
                    s += 2;
                    d += 2;
                }
                break;
            case RIGHT:
                for (int i = 0; i < frames; i++) {
                    short r = src[s + 1];
                    dst[d] = r;
                    dst[d + 1] = r;
                    s += 2;
                    d += 2;
                }
                break;
            default:
                System.arraycopy(src, srcOffset, dst, dstOffset, frames * channels);
        }
    }
}
