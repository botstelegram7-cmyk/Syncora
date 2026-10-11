package com.serena.syncora;

import android.graphics.Bitmap;

import com.serena.syncora.core.PlayQueue;
import com.serena.syncora.lyrics.Lyrics;
import com.serena.syncora.net.RoomDiscovery;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Everything the UI needs to draw itself, published by {@link SyncoraService}. Instances are never modified after
 * they have been published.
 */
public final class SessionState {
    public enum Connection { NONE, SCANNING, CONNECTING, CONNECTED, WAITING }

    public static final class Device {
        public String id = "";
        public String name = "";
        public String address = "";
        public boolean self;
        public boolean host;
        public float volume = 1f;
        public boolean muted;
        public String channel = "stereo";
        public int offsetMs;
        public int syncErrMs;
        public int queueDepth;
        public int peakQueueDepth;
        public long packetGaps;
        public long packetsReceived;
        public long droppedAudioChunks;
        public long underruns;
        public int clockOffsetErrorMs;
        public int latencyMs;
        /** synced, buffering, resyncing, connecting */
        public String status = "synced";
    }

    public static final class QueueItem {
        public int index;
        public String title = "";
        public String artist = "";
        public boolean current;
    }

    // what is playing
    public Source source = Source.MUSIC;
    public boolean playing;
    /** On a speaker, true when this device alone is locally paused/muted; it does not pause the host. */
    public boolean localPaused;
    public boolean buffering;
    public boolean hasMedia;
    public String title = "";
    public String subtitle = "";
    public String album = "";
    public String artKey = "";
    public Bitmap art;
    public long durationMs;
    public boolean live;
    public long positionMs;
    /** System.nanoTime() on this device at which {@link #positionMs} was valid. */
    public long positionLocalNanos;
    public boolean canSeek;
    public boolean canSkip;
    public boolean shuffle;
    public PlayQueue.Repeat repeat = PlayQueue.Repeat.OFF;
    public int queueIndex;
    public int queueSize;
    public List<QueueItem> upNext = Collections.emptyList();

    /** Lyrics (or captions) of the current song; null when none were found. */
    public Lyrics lyrics;
    /** Identifies the song the lyrics belong to. */
    public String lyricsKey = "";
    /** A line for the lyrics page while the lyrics are missing (e.g. "Looking for lyrics…"). */
    public String lyricsStatus = "";

    // group
    public String selfId = "";
    public String deviceName = "";
    public String hostName = "";
    public String roomCode = "";
    public String roomIp = "";
    public int roomPort;
    public boolean roomOpen;
    public Connection connection = Connection.NONE;
    public String connectionText = "";
    public List<Device> devices = Collections.emptyList();
    public List<RoomDiscovery.RoomInfo> rooms = Collections.emptyList();
    public String audioMode = "balanced";
    public int lookaheadMs;
    public boolean aligned;
    public int syncErrMs;
    public boolean autoConnect;
    public boolean shareRoom = true;
    /** Another app took the audio focus (speaker mode): sound is muted until the focus is back. */
    public boolean interrupted;
    /** While interrupted: true when the app is still taking the focus back by itself, false when the user must. */
    public boolean interruptRetrying;
    public long sleepTimerRemainingMs;
    public boolean sleepTimerAtTrackEnd;
    public boolean sleepTimerMuted;

    // radio / dlna / mic
    public String stationName = "";
    public String stationStatus = "";
    public boolean dlnaOn;
    public String dlnaName = "";
    public boolean micOn;
    public String micInput = "";
    public boolean micMonitor;
    public float micGain = 1f;

    public int version;

    public boolean isSpeaker() {
        return source == Source.SPEAKER;
    }

    public boolean lowLatency() {
        return "low".equals(audioMode);
    }

    /** Current playback position, extrapolated from the snapshot. */
    public long positionNow() {
        long p = positionMs;
        if (playing) p += Math.max(0, (System.nanoTime() - positionLocalNanos) / 1_000_000L);
        if (durationMs > 0 && p > durationMs) p = durationMs;
        return Math.max(0, p);
    }

    public Device self() {
        for (Device d : devices) if (d.self) return d;
        return null;
    }

    /** Devices other than this one. */
    public List<Device> others() {
        List<Device> list = new ArrayList<>();
        for (Device d : devices) if (!d.self) list.add(d);
        return list;
    }

    public int connectedSpeakers() {
        int n = 0;
        for (Device d : devices) if (!d.host && !d.self) n++;
        return n;
    }

    /** How many devices play together (including this one) while a session is active. */
    public int groupSize() {
        return Math.max(1, devices.size());
    }
}
