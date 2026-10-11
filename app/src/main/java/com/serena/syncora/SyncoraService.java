package com.serena.syncora;

import android.app.Notification;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;

import com.serena.syncora.audio.ArtLoader;
import com.serena.syncora.audio.AudioTrackSink;
import com.serena.syncora.audio.MicSource;
import com.serena.syncora.audio.PcmSource;
import com.serena.syncora.audio.RadioSource;
import com.serena.syncora.audio.StreamPump;
import com.serena.syncora.audio.TrackDecoder;
import com.serena.syncora.core.AudioProfile;
import com.serena.syncora.core.ChannelMode;
import com.serena.syncora.core.ClockSync;
import com.serena.syncora.core.InterruptWatchdog;
import com.serena.syncora.core.Json;
import com.serena.syncora.core.PcmChunk;
import com.serena.syncora.core.PlayQueue;
import com.serena.syncora.core.ScheduledPlayer;
import com.serena.syncora.core.SleepTimer;
import com.serena.syncora.core.Track;
import com.serena.syncora.dlna.DlnaRenderer;
import com.serena.syncora.dlna.SsdpAdvertiser;
import com.serena.syncora.dlna.UpnpXml;
import com.serena.syncora.net.GroupClient;
import com.serena.syncora.net.GroupHost;
import com.serena.syncora.net.MiniHttpServer;
import com.serena.syncora.net.NetUtil;
import com.serena.syncora.net.RoomDiscovery;
import com.serena.syncora.net.TimeClient;
import com.serena.syncora.net.TimeServer;
import com.serena.syncora.lyrics.Lyrics;
import com.serena.syncora.lyrics.LyricsClient;
import com.serena.syncora.radio.Station;
import com.serena.syncora.youtube.YouTubeFormat;
import com.serena.syncora.youtube.YouTubeLyrics;
import com.serena.syncora.youtube.YouTubeVideo;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.Socket;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The brain of Syncora. One service, two roles:
 * <ul>
 *   <li><b>Player</b> (sources: My Music, Online Radio, DLNA, Microphone): decodes audio, puts it on a shared timeline and
 *       sends it to every speaker in the room while playing it on its own speaker at exactly the same moment.</li>
 *   <li><b>Speaker</b>: connects to a player and plays what it sends, in sync.</li>
 * </ul>
 * All public methods must be called on the main thread.
 */
public final class SyncoraService extends Service {
    public static final String ACTION_KEEP = "com.serena.syncora.action.KEEP";
    public static final String ACTION_TOGGLE = "com.serena.syncora.action.TOGGLE";
    public static final String ACTION_NEXT = "com.serena.syncora.action.NEXT";
    public static final String ACTION_PREV = "com.serena.syncora.action.PREV";
    public static final String ACTION_STOP = "com.serena.syncora.action.STOP";

    public interface Listener {
        void onState(SessionState state);

        void onMessage(String message);
    }

    public final class LocalBinder extends Binder {
        public SyncoraService getService() {
            return SyncoraService.this;
        }
    }

    private static final int PORT_FIRST = 8787;
    private static final int PORT_LAST = 8797;
    private static final long TICK_MS = 1000;
    private static final int ART_PX = 600;

    private final IBinder binder = new LocalBinder();
    private final Handler main = new Handler(Looper.getMainLooper());
    /** Network set-up and tear-down (sockets must never be touched on the main thread). */
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final ExecutorService worker = Executors.newCachedThreadPool();
    private final List<Listener> listeners = new ArrayList<>();
    private final ArtStore artStore = new ArtStore();
    private final PlayQueue queue = new PlayQueue();
    private final SleepTimer sleepTimer = new SleepTimer();
    private boolean sleepTimerMuted;

    private Prefs prefs;
    private MediaNotifier notifier;
    private AudioFocusHelper focus;
    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;
    private boolean noisyRegistered;
    private boolean destroyed;
    private boolean debuggable;
    private boolean restored;
    private boolean foreground;
    private int uiCount;
    private int stateVersion;
    private String lastNotificationSignature = "";
    private SessionState lastState = new SessionState();

    // ---- local output
    private AudioTrackSink sink;
    private ScheduledPlayer player;
    private float outVolume = 1f;
    private boolean outMuted;
    private ChannelMode outChannel = ChannelMode.STEREO;
    private int outOffsetMs;
    private volatile boolean localOutputEnabled = true;
    private boolean focusMuted;
    private boolean speakerPaused;
    private boolean resumeAfterFocus;
    private boolean interrupted;
    /** Brings speaker mode back by itself after a call, a notification or another app took the audio focus. */
    private final InterruptWatchdog watchdog = new InterruptWatchdog();
    private volatile long lastSyncErrorNanos;

    // ---- room (player role)
    private MiniHttpServer http;
    private GroupHost host;
    private TimeServer timeServer;
    private RoomDiscovery discovery;
    private boolean roomOpen;
    private volatile String roomCode = "";
    private volatile int roomPort;
    private volatile int timePort;
    private volatile String roomIp = "";
    private volatile String stateJson = "{}";
    private boolean broadcastPending;
    private final Map<String, JSONObject> pendingSets = new HashMap<>();
    private boolean setFlushPending;

    // ---- transport (what the player is doing)
    private Source source = Source.MUSIC;
    private volatile int epoch;
    private volatile boolean playing;
    private volatile long anchorMediaMs;
    private volatile long anchorHostNanos;
    private volatile long pausedPositionMs;
    private volatile long durationMs;
    private boolean liveSource;
    private boolean buffering;
    private boolean hasMedia;
    private String title = "";
    private String subtitle = "";
    private String album = "";
    private String artKey = "";
    private Bitmap art;
    private StreamPump pump;
    private int consecutiveErrors;

    // ---- radio / mic / dlna
    private Station station;
    private String stationStatus = "";
    private String stationNowPlaying = "";
    private MicSource mic;
    private boolean micOn;
    private String micInputLabel = "";
    private AudioDeviceInfo lastMicInput;
    private boolean lastMicUnprocessed;
    private boolean dlnaOn;
    private DlnaRenderer dlna;
    private SsdpAdvertiser ssdp;
    private volatile String dlnaUri = "";
    private volatile String dlnaMeta = "";
    private volatile String dlnaState = "NO_MEDIA_PRESENT";

    // ---- speaker role
    private GroupClient client;
    private TimeClient timeClient;
    private final ClockSync clockSync = new ClockSync();
    private SessionState.Connection connection = SessionState.Connection.NONE;
    private String connectionText = "";
    private boolean userDisconnected;
    private String remoteIp = "";
    private int remotePort;
    private String remoteCode = "";
    private final Remote remote = new Remote();
    private final Map<String, RoomDiscovery.RoomInfo> rooms = new LinkedHashMap<>();
    private Bitmap remoteArt;
    private String remoteArtLoadedKey = "";
    private String remoteArtRequestedKey = "";
    private Lyrics remoteLyrics;
    private String remoteLyricsKey = "";
    private String remoteLyricsRequestedKey = "";

    // ---- lyrics of the song that is playing on the player (speakers fetch them over /lyrics)
    private Lyrics lyrics;
    private String lyricsKey = "";
    private String lyricsStatus = "";
    private LyricsClient.Request activeLyricsRequest;
    private LyricsWork currentLyricsWork;
    private long lyricsGeneration;
    private String lastIcyTitle = "";
    /** Lyrics already found, as JSON, keyed by song (most recent last). Read by the HTTP thread too. */
    private final LinkedHashMap<String, String> lyricsStore = new LinkedHashMap<String, String>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
            return size() > 24;
        }
    };
    private final LinkedHashMap<String, Long> lyricsMisses = new LinkedHashMap<String, Long>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Long> eldest) {
            return size() > 64;
        }
    };

    // ---- YouTube (audio of the loaded video is what plays)
    private YouTubeVideo youtube;
    private YouTubeFormat youtubeAudio;
    private int reconnectAttempts;
    private int connectGeneration;

    /** What the player last told this speaker. */
    private static final class Remote {
        Source source = Source.MUSIC;
        boolean playing;
        boolean hasMedia;
        long anchorMs;
        long anchorNanos;
        long pausedMs;
        long durationMs;
        boolean live;
        String title = "";
        String subtitle = "";
        String album = "";
        String artKey = "";
        boolean artReady;
        boolean shuffle;
        PlayQueue.Repeat repeat = PlayQueue.Repeat.OFF;
        boolean canSeek;
        boolean canSkip;
        int queueIndex;
        int queueSize;
        List<SessionState.QueueItem> upNext = new ArrayList<>();
        List<SessionState.Device> devices = new ArrayList<>();
        String audioMode = AudioProfile.BALANCED;
        int lookaheadMs;
        String hostName = "";
        String station = "";
        String stationStatus = "";
        String lyricsKey = "";
        boolean lyricsReady;
        String lyricsStatus = "";

        void reset() {
            Remote fresh = new Remote();
            source = fresh.source;
            playing = false;
            hasMedia = false;
            anchorMs = anchorNanos = pausedMs = durationMs = 0;
            live = false;
            title = subtitle = album = artKey = "";
            artReady = false;
            shuffle = false;
            repeat = PlayQueue.Repeat.OFF;
            canSeek = canSkip = false;
            queueIndex = queueSize = 0;
            upNext = new ArrayList<>();
            devices = new ArrayList<>();
            audioMode = AudioProfile.BALANCED;
            lookaheadMs = 0;
            station = stationStatus = "";
            lyricsKey = lyricsStatus = "";
            lyricsReady = false;
        }
    }

    // ================================================================= lifecycle

    @Override
    public void onCreate() {
        super.onCreate();
        debuggable = (getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0;
        prefs = new Prefs(this);
        sleepTimer.restore(prefs.sleepTimerDeadlineWallMs(), prefs.sleepTimerAtTrackEnd());
        sleepTimerMuted = prefs.sleepTimerMuted();
        // Keep an elapsed deadline active and schedule it immediately below. This lets expiry use the
        // saved source instead of silently losing a timer while the service was being recreated.
        queue.setShuffle(prefs.shuffle());
        queue.setRepeat(prefs.repeat());
        outVolume = prefs.volume();
        outMuted = prefs.muted();
        outChannel = prefs.channel();
        outOffsetMs = prefs.offsetMs();
        notifier = new MediaNotifier(this, new MediaNotifier.Controls() {
            @Override
            public void onPlay() {
                play();
            }

            @Override
            public void onPause() {
                pause();
            }

            @Override
            public void onNext() {
                next();
            }

            @Override
            public void onPrevious() {
                previous();
            }

            @Override
            public void onSeekTo(long positionMs) {
                seekTo(positionMs);
            }

            @Override
            public void onStop() {
                stopSession();
            }
        });
        focus = new AudioFocusHelper(this, new AudioFocusHelper.Listener() {
            @Override
            public void onFocusChange(int change) {
                handleFocus(change);
            }
        }, main);
        scheduleSleepTimer();
        main.postDelayed(tick, TICK_MS);
    }

    @Override
    public IBinder onBind(Intent intent) {
        uiCount++;
        if (!restored) {
            restored = true;
            Source last = prefs.lastSource();
            if (last != Source.MUSIC) selectSource(last);
        }
        onUiPresenceChanged();
        return binder;
    }

    @Override
    public boolean onUnbind(Intent intent) {
        uiCount = Math.max(0, uiCount - 1);
        onUiPresenceChanged();
        return true;
    }

    @Override
    public void onRebind(Intent intent) {
        uiCount++;
        onUiPresenceChanged();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_KEEP.equals(action)) {
            // startForegroundService() obliges us to call startForeground(); if nothing needs it any more, leave again.
            enterForegroundNow();
            if (!needForeground()) {
                leaveForeground();
                maybeStopSelf();
            }
        } else if (ACTION_TOGGLE.equals(action)) {
            togglePlay();
        } else if (ACTION_NEXT.equals(action)) {
            next();
        } else if (ACTION_PREV.equals(action)) {
            previous();
        } else if (ACTION_STOP.equals(action)) {
            stopSession();
        }
        return START_NOT_STICKY;
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        super.onTaskRemoved(rootIntent);
        maybeStopSelf();
    }

    @Override
    public void onDestroy() {
        destroyed = true;
        main.removeCallbacksAndMessages(null);
        stopPump();
        stopMicInternal();
        disconnectClient();
        stopDlna();
        closeRoom();
        if (player != null) player.stop();
        if (focus != null) focus.abandon();
        unregisterNoisy();
        releaseLocks();
        if (notifier != null) notifier.release();
        io.shutdown();
        worker.shutdown();
        super.onDestroy();
    }

    private void onUiPresenceChanged() {
        if (uiCount > 0) {
            if (source != Source.SPEAKER && prefs.shareRoom()) ensureRoom();
        } else {
            maybeStopSelf();
        }
        publish();
    }

    private void maybeStopSelf() {
        if (uiCount > 0 || needForeground()) return;
        stopPump();
        closeRoom();
        stopSelf();
    }

    // ================================================================= public API: listeners / state

    public void addListener(Listener l) {
        if (!listeners.contains(l)) listeners.add(l);
        l.onState(lastState);
    }

    public void removeListener(Listener l) {
        listeners.remove(l);
    }

    public SessionState state() {
        return lastState;
    }

    public Prefs prefs() {
        return prefs;
    }

    public Source source() {
        return source;
    }

    private void message(String text) {
        for (Listener l : new ArrayList<>(listeners)) l.onMessage(text);
    }

    // ================================================================= public API: sources

    public void selectSource(Source target) {
        if (target == source) {
            publish();
            return;
        }
        if (target != Source.SPEAKER && sleepTimerMuted) {
            // The local sleep-mute only silences speaker-mode playback. Switching to a player source must not leave
            // this device unexpectedly silent.
            sleepTimerMuted = false;
            prefs.setSleepTimerMuted(false);
        }
        Source old = source;
        if (old == Source.SPEAKER) {
            leaveSpeaker();
        } else {
            stopPlaybackInternal();
            if (old == Source.DLNA) stopDlna();
            if (old == Source.MIC) stopMicInternal();
        }
        source = target;
        prefs.setLastSource(target);
        resetNowPlaying();
        if (target == Source.SPEAKER) {
            enterSpeaker();
        } else {
            localOutputEnabled = true;
            applyLocalOutput();
            if (target == Source.DLNA) startDlna();
            if (target == Source.MUSIC && queue.current() != null) nowPlayingFromTrack(queue.current());
            if (target == Source.RADIO && station == null) station = Prefs.stationFromJson(prefs.lastStationJson());
            if (target == Source.RADIO && station != null) nowPlayingFromStation(station);
            if (target == Source.YOUTUBE && youtube != null) nowPlayingFromYouTube(youtube);
            if (uiCount > 0 && prefs.shareRoom()) ensureRoom();
        }
        updateForeground();
        publish();
    }

    private void resetNowPlaying() {
        title = subtitle = album = artKey = "";
        art = null;
        hasMedia = false;
        durationMs = 0;
        liveSource = false;
        buffering = false;
        playing = false;
        pausedPositionMs = 0;
        anchorMediaMs = 0;
        stationStatus = "";
        stationNowPlaying = "";
        cancelLyricsRequest();
        currentLyricsWork = null;
        lyrics = null;
        lyricsKey = "";
        lyricsStatus = "";
    }

    // ================================================================= public API: transport

    public void togglePlay() {
        if (source == Source.SPEAKER) {
            // A timer can mute this speaker without changing the room's playing state. Treat either local
            // condition as paused so the next Play tap reliably resumes only this device.
            boolean wasPausedHere = speakerPaused || sleepTimerMuted;
            clearSleepMuteForUserAction();
            speakerPaused = !wasPausedHere;
            applyLocalOutput();
            publish();
            return;
        }
        if (playing) pauseHost();
        else {
            clearSleepMuteForUserAction();
            resumeHost();
        }
    }

    public void play() {
        if (source == Source.SPEAKER) {
            clearSleepMuteForUserAction();
            speakerPaused = false;
            applyLocalOutput();
            publish();
        } else if (!playing) {
            clearSleepMuteForUserAction();
            resumeHost();
        }
    }

    public void pause() {
        if (source == Source.SPEAKER) {
            speakerPaused = true;
            applyLocalOutput();
            publish();
        } else if (playing) {
            pauseHost();
        }
    }

    private void clearSleepMuteForUserAction() {
        if (!sleepTimerMuted) return;
        sleepTimerMuted = false;
        prefs.setSleepTimerMuted(false);
    }

    private void speakerOwnerOnly() {
        message("Only the player can change the shared track, queue or playback position.");
    }

    public void next() {
        if (source == Source.SPEAKER) {
            speakerOwnerOnly();
        } else if (source == Source.MUSIC && !queue.isEmpty()) {
            if (queue.next() != null) playCurrentFrom(0);
        }
    }

    public void previous() {
        if (source == Source.SPEAKER) {
            speakerOwnerOnly();
        } else if (source == Source.MUSIC && !queue.isEmpty()) {
            if (hostPositionMs() > 3000) {
                seekHost(0);
            } else if (queue.previous() != null) {
                playCurrentFrom(0);
            }
        }
    }

    public void seekTo(long positionMs) {
        if (source == Source.SPEAKER) {
            speakerOwnerOnly();
        } else {
            seekHost(positionMs);
        }
    }

    public void setShuffle(boolean on) {
        if (source == Source.SPEAKER) {
            speakerOwnerOnly();
            return;
        }
        queue.setShuffle(on);
        prefs.setPlayMode(on, queue.getRepeat());
        publish();
    }

    public void cycleRepeat() {
        if (source == Source.SPEAKER) {
            speakerOwnerOnly();
            return;
        }
        queue.setRepeat(queue.getRepeat().next());
        prefs.setPlayMode(queue.isShuffle(), queue.getRepeat());
        publish();
    }

    public void setRepeat(PlayQueue.Repeat mode) {
        if (source == Source.SPEAKER) {
            speakerOwnerOnly();
            return;
        }
        queue.setRepeat(mode);
        prefs.setPlayMode(queue.isShuffle(), mode);
        publish();
    }

    // ================================================================= public API: sleep timer

    /** Starts a per-device countdown. On a player, expiry pauses the synchronized room; on a speaker it mutes only itself. */
    public void startSleepTimerMinutes(int minutes) {
        if (minutes <= 0) {
            cancelSleepTimer();
            return;
        }
        int safeMinutes = Math.min(24 * 60, minutes);
        sleepTimer.startAfter(System.currentTimeMillis(), safeMinutes * 60_000L);
        prefs.setSleepTimer(sleepTimer.deadlineWallTimeMs(), false);
        scheduleSleepTimer();
        message("Sleep timer set for " + safeMinutes + " minutes.");
        updateForeground();
        publish();
    }

    /** Stops at the next track boundary. Unsupported for live sources and when there is no known track. */
    public void startSleepTimerAtTrackEnd() {
        boolean knownTrack = source == Source.SPEAKER
                ? remote.hasMedia && !remote.live && remote.durationMs > 0
                : hasMedia && !liveSource && durationMs > 0
                        && (source == Source.MUSIC || source == Source.YOUTUBE || source == Source.DLNA);
        if (!knownTrack) {
            message("End-of-track timer needs a seekable music track.");
            return;
        }
        sleepTimer.endAtTrackBoundary();
        prefs.setSleepTimer(0, true);
        main.removeCallbacks(sleepTimerTick);
        message("Sleep timer will stop at the end of this track.");
        updateForeground();
        publish();
    }

    public void cancelSleepTimer() {
        sleepTimer.cancel();
        prefs.clearSleepTimer();
        main.removeCallbacks(sleepTimerTick);
        updateForeground();
        publish();
    }

    public long sleepTimerRemainingMs() {
        return sleepTimer.remainingMs(System.currentTimeMillis());
    }

    private final Runnable sleepTimerTick = new Runnable() {
        @Override
        public void run() {
            if (destroyed || !sleepTimer.active()) return;
            long now = System.currentTimeMillis();
            if (sleepTimer.consumeDeadline(now)) {
                completeSleepTimer();
                return;
            }
            long remaining = sleepTimer.remainingMs(now);
            main.postDelayed(this, Math.max(250L, Math.min(remaining, 60_000L)));
        }
    };

    private void scheduleSleepTimer() {
        main.removeCallbacks(sleepTimerTick);
        if (sleepTimer.kind() != SleepTimer.Kind.COUNTDOWN) return;
        long remaining = sleepTimer.remainingMs(System.currentTimeMillis());
        main.postDelayed(sleepTimerTick, Math.max(250L, Math.min(remaining, 60_000L)));
    }

    private void completeSleepTimer() {
        prefs.clearSleepTimer();
        // A restarted sticky service can reach an expired deadline before the UI binds and restores its saved
        // source. Preserve speaker-local mute semantics in that short window as well.
        boolean speakerSession = source == Source.SPEAKER || (!restored && prefs.lastSource() == Source.SPEAKER);
        if (speakerSession) {
            // A speaker may be using headphones; never send a group pause because of a local timer.
            sleepTimerMuted = true;
            prefs.setSleepTimerMuted(true);
            applyLocalOutput();
            message("Sleep timer finished. This speaker is muted; the room keeps playing.");
        } else {
            sleepTimerMuted = false;
            prefs.setSleepTimerMuted(false);
            if (playing) pauseHost();
            message("Sleep timer finished. Playback paused.");
        }
        updateForeground();
        publish();
    }

    // ================================================================= public API: music

    /** Starts playing {@code tracks} from {@code index} (My Music). */
    public void playTracks(List<Track> tracks, int index) {
        if (tracks.isEmpty()) return;
        if (source == Source.SPEAKER) return;
        if (source != Source.MUSIC) selectSource(Source.MUSIC);
        queue.setTracks(tracks, index);
        consecutiveErrors = 0;
        playCurrentFrom(0);
    }

    public void playQueueIndex(int playOrderIndex) {
        if (source == Source.SPEAKER) {
            speakerOwnerOnly();
            return;
        }
        if (queue.jumpTo(playOrderIndex) != null) playCurrentFrom(0);
    }

    /** The queue in play order (for the queue sheet). */
    public List<Track> queueSnapshot() {
        return queue.playOrder();
    }

    public int queueCursor() {
        return queue.cursor();
    }

    public long currentTrackId() {
        Track t = queue.current();
        return source == Source.MUSIC && t != null ? t.id : -1;
    }

    // ================================================================= public API: radio

    public void playStation(Station st) {
        if (source == Source.SPEAKER) {
            message("Leave Speaker Mode first to play a station.");
            return;
        }
        if (source != Source.RADIO) selectSource(Source.RADIO);
        station = st;
        prefs.setLastStation(st);
        startStation(st);
    }

    public Station currentStation() {
        return station;
    }

    // ================================================================= public API: YouTube

    /** Plays the audio of a YouTube video on the player (so on every speaker too), from the beginning. */
    public void playYouTube(YouTubeVideo video, YouTubeFormat audio) {
        if (source == Source.SPEAKER) {
            message("Leave Speaker Mode first to play YouTube audio.");
            return;
        }
        if (source != Source.YOUTUBE) selectSource(Source.YOUTUBE);
        youtube = video;
        youtubeAudio = audio;
        startYouTube(0);
    }

    /** The video loaded in the YouTube page, or null. */
    public YouTubeVideo currentYouTube() {
        return youtube;
    }

    // ================================================================= public API: microphone / aux

    public void startMic(AudioDeviceInfo input, boolean unprocessed, String label) {
        if (source == Source.SPEAKER) return;
        if (source != Source.MIC) selectSource(Source.MIC);
        stopPump();
        stopMicInternal();
        mic = new MicSource(input, unprocessed);
        mic.setGain(prefs.micGain());
        micOn = true;
        lastMicInput = input;
        lastMicUnprocessed = unprocessed;
        micInputLabel = label == null ? "" : label;
        localOutputEnabled = prefs.micMonitor();
        applyLocalOutput();
        title = unprocessed ? "Aux input" : "Microphone";
        subtitle = micInputLabel.isEmpty() ? "Live" : micInputLabel;
        album = "";
        artKey = "";
        art = null;
        hasMedia = true;
        durationMs = 0;
        playing = true;
        buffering = true;
        startPump(mic, 0, true, 0, true);
        requestFocus();
        updateForeground();
        publish();
    }

    public void stopMic() {
        if (!micOn) return;
        stopPump();
        stopMicInternal();
        newEpoch();
        playing = false;
        hasMedia = false;
        title = subtitle = "";
        updateForeground();
        publish();
    }

    private void stopMicInternal() {
        mic = null;
        if (micOn) {
            micOn = false;
            localOutputEnabled = true;
            applyLocalOutput();
        }
    }

    public void setMicGain(float gain) {
        prefs.setMicGain(gain);
        if (mic != null) mic.setGain(gain);
        publish();
    }

    public void setMicMonitor(boolean on) {
        prefs.setMicMonitor(on);
        if (micOn) {
            localOutputEnabled = on;
            applyLocalOutput();
        }
        publish();
    }

    public float micLevel() {
        MicSource m = mic;
        return m == null ? 0f : m.level();
    }

    // ================================================================= public API: speaker mode

    public void connectToRoom(RoomDiscovery.RoomInfo room) {
        if (source != Source.SPEAKER) selectSource(Source.SPEAKER);
        userDisconnected = false;
        connectTo(room.ip, room.port, room.code);
    }

    /** "192.168.1.5" or "192.168.1.5:8788": finds the player there and connects. */
    public void connectByAddress(final String input) {
        final String[] parsed = NetUtil.parseHostPort(input);
        if (parsed == null) {
            message("That doesn't look like an IP address.");
            return;
        }
        if (source != Source.SPEAKER) selectSource(Source.SPEAKER);
        userDisconnected = false;
        setConnection(SessionState.Connection.CONNECTING, "Looking for a player at " + parsed[0] + "\u2026");
        final int generation = ++connectGeneration;
        worker.execute(new Runnable() {
            @Override
            public void run() {
                final int[] ports = parsed[1].isEmpty() ? range(PORT_FIRST, PORT_LAST) : new int[]{Integer.parseInt(parsed[1])};
                for (int port : ports) {
                    final JSONObject hello = fetchHello(parsed[0], port);
                    if (hello == null) continue;
                    final int found = port;
                    main.post(new Runnable() {
                        @Override
                        public void run() {
                            if (destroyed || generation != connectGeneration || source != Source.SPEAKER) return;
                            connectTo(parsed[0], found, hello.optString("code", ""));
                        }
                    });
                    return;
                }
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        if (destroyed || generation != connectGeneration) return;
                        setConnection(SessionState.Connection.WAITING, "No Syncora player found at " + parsed[0]);
                        message("No Syncora player found at " + parsed[0] + ". Check the address and that both devices are on the same Wi-Fi.");
                        scheduleReconnect();
                    }
                });
            }
        });
    }

    private static int[] range(int from, int to) {
        int[] r = new int[to - from + 1];
        for (int i = 0; i < r.length; i++) r[i] = from + i;
        return r;
    }

    private static JSONObject fetchHello(String host, int port) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL("http://" + host + ":" + port + "/hello").openConnection();
            c.setConnectTimeout(900);
            c.setReadTimeout(1500);
            if (c.getResponseCode() != 200) return null;
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (InputStream in = c.getInputStream()) {
                byte[] buf = new byte[1024];
                int n;
                while ((n = in.read(buf)) > 0 && bytes.size() < 16 * 1024) bytes.write(buf, 0, n);
            }
            JSONObject o = Json.parse(bytes.toString("UTF-8"));
            return o != null && "Syncora".equals(o.optString("app")) ? o : null;
        } catch (IOException | RuntimeException e) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /** Leave the current player and stop reconnecting until the user picks one again. */
    public void disconnectSpeaker() {
        userDisconnected = true;
        main.removeCallbacks(reconnectRunnable);
        disconnectClient();
        setConnection(SessionState.Connection.WAITING, "Waiting for connection");
        if (discovery != null && source == Source.SPEAKER) discovery.setProbing(true);
        updateForeground();
        publish();
    }

    public void rescan() {
        if (source != Source.SPEAKER) return;
        rooms.clear();
        if (discovery != null) discovery.setProbing(true);
        publish();
    }

    /** After another app interrupted the audio: take it back. */
    public void reclaimAudio() {
        clearInterrupt();
        focusMuted = false;
        applyLocalOutput();
        if (focus != null && !focus.reRequest()) message("Another app is using the audio output.");
        publish();
    }

    // ================================================================= public API: group & devices

    public void setDeviceVolume(String id, float volume) {
        JSONObject f = new JSONObject();
        Json.put(f, "volume", Math.max(0f, Math.min(1f, volume)));
        changeDevice(id, f);
    }

    public void setDeviceMuted(String id, boolean muted) {
        changeDevice(id, Json.put(new JSONObject(), "muted", muted));
    }

    public void setDeviceChannel(String id, ChannelMode mode) {
        changeDevice(id, Json.put(new JSONObject(), "channel", mode.id));
    }

    public void setDeviceOffset(String id, int offsetMs) {
        changeDevice(id, Json.put(new JSONObject(), "offsetMs", Math.max(-2000, Math.min(2000, offsetMs))));
    }

    public void resetDevice(String id) {
        JSONObject f = new JSONObject();
        Json.put(f, "offsetMs", 0);
        Json.put(f, "channel", ChannelMode.STEREO.id);
        changeDevice(id, f);
    }

    public void kickDevice(String id) {
        if (source == Source.SPEAKER || host == null) return;
        host.kick(id, "removed by the player");
    }

    /** Quickly re-measures clocks and re-aligns every device (no interruption of the music). */
    public void syncNow() {
        if (source == Source.SPEAKER) {
            if (timeClient != null) timeClient.burst();
            if (player != null) player.requestResync();
            message("Re-syncing this speaker only.");
            return;
        }
        if (player != null) player.requestResync();
        if (host != null) host.sendToAll(com.serena.syncora.core.FrameCodec.T_SET, Json.put(new JSONObject(), "resync", true).toString());
        message("Re-syncing all devices\u2026");
    }

    public void setAudioMode(String mode) {
        if (source == Source.SPEAKER) {
            speakerOwnerOnly();
            return;
        }
        if (mode.equals(prefs.audioMode())) return;
        prefs.setAudioMode(mode);
        configurePlayer();
        // the new lookahead only takes effect on a new timeline: re-cue what is playing
        if (playing && (source == Source.MUSIC || source == Source.DLNA)) {
            restartCurrent();
        } else if (playing) {
            if (source == Source.RADIO && station != null) startStation(station);
        }
        publish();
    }

    public void setLowLatency(boolean on) {
        setAudioMode(on ? AudioProfile.LOW_LATENCY : AudioProfile.BALANCED);
    }

    public void setShareRoom(boolean share) {
        prefs.setShareRoom(share);
        if (source == Source.SPEAKER) return;
        if (share) {
            ensureRoom();
        } else if (!playing && (host == null || host.clientCount() == 0)) {
            closeRoom();
        }
        publish();
    }

    public void startRoom() {
        if (source != Source.SPEAKER) ensureRoom();
    }

    /** Ends the session: stops playback and disconnects every speaker (or leaves the player in speaker mode). */
    public void stopSession() {
        if (source == Source.SPEAKER) {
            disconnectSpeaker();
            return;
        }
        stopPlaybackInternal();
        if (micOn) stopMicInternal();
        if (host != null) {
            for (GroupHost.ClientInfo c : host.clients()) host.kick(c.id, "the player stopped sharing");
        }
        title = subtitle = "";
        hasMedia = false;
        updateForeground();
        publish();
    }

    /** Settings (device name, resync frequency...) changed. */
    public void settingsChanged() {
        configurePlayer();
        if (timeClient != null) timeClient.setIntervalSeconds(effectiveResyncSeconds());
        if (dlnaOn) {
            stopDlna();
            startDlna();
        }
        publish();
    }

    // ================================================================= output (this device)

    private void loadOutputFromPrefs() {
        outVolume = prefs.volume();
        outMuted = prefs.muted();
        outChannel = prefs.channel();
        outOffsetMs = prefs.offsetMs();
    }

    private void ensurePlayer() {
        if (player != null) return;
        sink = new AudioTrackSink();
        player = new ScheduledPlayer(sink, ScheduledPlayer.SYSTEM_CLOCK, new ScheduledPlayer.Listener() {
            @Override
            public void onSyncMeasured(long errorNanos, boolean timestampBased) {
                lastSyncErrorNanos = errorNanos;
            }

            @Override
            public void onSinkError(final String message) {
                android.util.Log.w("Syncora", "audio output error: " + message);
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        message("Audio output problem: " + message);
                    }
                });
            }
        });
        configurePlayer();
        applyLocalOutput();
        player.setClockOffsetNanos(0);
        player.start("syncora-audio", new Runnable() {
            @Override
            public void run() {
                try {
                    android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO);
                } catch (RuntimeException ignored) {
                    // keep default priority
                }
            }
        });
    }

    private AudioProfile profile() {
        if (source == Source.SPEAKER && connection == SessionState.Connection.CONNECTED) {
            return AudioProfile.of(remote.audioMode);
        }
        return AudioProfile.of(prefs.audioMode());
    }

    private int effectiveResyncSeconds() {
        return prefs.resyncSeconds();
    }

    private void configurePlayer() {
        if (player == null) return;
        AudioProfile p = profile();
        sink.configure(p.sinkBufferMs, p.lowLatencyTrack);
        player.setToleranceMs(p.toleranceMs);
        player.setResyncIntervalSeconds(effectiveResyncSeconds());
    }

    private void applyLocalOutput() {
        if (player == null) return;
        player.setVolume(outVolume);
        player.setMuted(outMuted || !localOutputEnabled || focusMuted || sleepTimerMuted || speakerPaused);
        player.setChannelMode(outChannel);
        player.setUserDelayMs(outOffsetMs);
    }

    private void applyOutputFields(JSONObject f) {
        if (f.has("volume")) outVolume = (float) Math.max(0, Math.min(1, f.optDouble("volume", outVolume)));
        if (f.has("muted")) outMuted = f.optBoolean("muted", outMuted);
        if (f.has("channel")) outChannel = ChannelMode.fromId(f.optString("channel", outChannel.id));
        if (f.has("offsetMs")) outOffsetMs = Math.max(-2000, Math.min(2000, f.optInt("offsetMs", outOffsetMs)));
        prefs.setOutput(outVolume, outMuted, outChannel, outOffsetMs);
        applyLocalOutput();
    }

    private void changeDevice(String id, JSONObject fields) {
        final String self = prefs.deviceId();
        if (id == null || id.isEmpty() || id.equals(self)) {
            applyOutputFields(fields);
            publish();
            return;
        }
        if (source == Source.SPEAKER) {
            Json.put(fields, "cmd", "setDevice");
            Json.put(fields, "id", id);
            sendRemote(fields);
            // optimistic: show the change right away
            for (SessionState.Device d : remote.devices) if (d.id.equals(id)) applyDeviceFields(d, fields);
            publish();
            return;
        }
        if (host == null) return;
        GroupHost.ClientInfo c = host.client(id);
        if (c == null) return;
        GroupHost.ClientInfo target = c;
        if (fields.has("volume")) target.volume = (float) fields.optDouble("volume", target.volume);
        if (fields.has("muted")) target.muted = fields.optBoolean("muted", target.muted);
        if (fields.has("channel")) target.channel = fields.optString("channel", target.channel);
        if (fields.has("offsetMs")) target.offsetMs = fields.optInt("offsetMs", target.offsetMs);
        JSONObject pending = pendingSets.get(id);
        if (pending == null) {
            pending = new JSONObject();
            pendingSets.put(id, pending);
        }
        Iterator<String> keys = fields.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            Json.put(pending, key, fields.opt(key));
        }
        scheduleSetFlush();
        publish();
    }

    private static void applyDeviceFields(SessionState.Device d, JSONObject f) {
        if (f.has("volume")) d.volume = (float) f.optDouble("volume", d.volume);
        if (f.has("muted")) d.muted = f.optBoolean("muted", d.muted);
        if (f.has("channel")) d.channel = f.optString("channel", d.channel);
        if (f.has("offsetMs")) d.offsetMs = f.optInt("offsetMs", d.offsetMs);
    }

    private void scheduleSetFlush() {
        if (setFlushPending) return;
        setFlushPending = true;
        main.postDelayed(new Runnable() {
            @Override
            public void run() {
                setFlushPending = false;
                if (host == null) {
                    pendingSets.clear();
                    return;
                }
                for (Map.Entry<String, JSONObject> e : pendingSets.entrySet()) host.sendSet(e.getKey(), e.getValue().toString());
                pendingSets.clear();
            }
        }, 40);
    }

    // ================================================================= audio focus

    private void requestFocus() {
        if (!focus.request()) message("Another app is using the audio output.");
    }

    /** Debug-only: pretend the audio focus changed, so the emulator checks can exercise the recovery. */
    void debugFocusChange(int change) {
        if (debuggable) handleFocus(change);
    }

    private void handleFocus(int change) {
        switch (change) {
            case AudioManager.AUDIOFOCUS_GAIN:
                clearInterrupt();
                focusMuted = false;
                if (player != null) player.setDuck(1f);
                applyLocalOutput();
                if (resumeAfterFocus && source != Source.SPEAKER) {
                    resumeAfterFocus = false;
                    resumeHost();
                }
                break;
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK:
                if (player != null) player.setDuck(0.3f);
                break;
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
                if (source == Source.SPEAKER) {
                    interruptSpeaker();
                } else if (playing) {
                    resumeAfterFocus = true;
                    pauseHost();
                }
                break;
            case AudioManager.AUDIOFOCUS_LOSS:
            default:
                if (source == Source.SPEAKER) {
                    interruptSpeaker();
                } else if (playing) {
                    pauseHost();
                }
                break;
        }
        publish();
    }

    /**
     * Speaker mode lost the audio focus: show the INTERRUPTED badge and take the sound back on our own. Android
     * does not always deliver the {@code AUDIOFOCUS_GAIN} callback that would resume playback, which used to leave
     * the phone silent until the user tapped play; the watchdog keeps asking for the focus instead.
     */
    private void interruptSpeaker() {
        focusMuted = true;
        interrupted = true;
        watchdog.onInterrupt(SystemClock.uptimeMillis());
        applyLocalOutput();
        scheduleFocusWatchdog(InterruptWatchdog.RETRY_MS);
    }

    /** The focus is ours again (or the user took over): forget the interruption and stop retrying. */
    private void clearInterrupt() {
        watchdog.reset();
        main.removeCallbacks(focusWatchdog);
        interrupted = false;
    }

    private void scheduleFocusWatchdog(long delayMs) {
        main.removeCallbacks(focusWatchdog);
        main.postDelayed(focusWatchdog, delayMs);
    }

    /** True while a phone or VoIP call is still running: taking the focus back would play over the call. */
    private boolean callInProgress() {
        AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        if (am == null) return false;
        int mode = am.getMode();
        return mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_IN_COMMUNICATION;
    }

    /** One automatic attempt to take the audio focus back; see {@link InterruptWatchdog}. */
    private final Runnable focusWatchdog = new Runnable() {
        @Override
        public void run() {
            if (destroyed || source != Source.SPEAKER || !watchdog.isRetrying()) return;
            long now = SystemClock.uptimeMillis();
            if (!watchdog.shouldRetry(now)) {
                scheduleFocusWatchdog(InterruptWatchdog.RETRY_MS);
                return;
            }
            if (callInProgress()) {
                // let the call finish first, then try again without spending an attempt
                watchdog.defer(now);
                scheduleFocusWatchdog(InterruptWatchdog.RETRY_MS);
                return;
            }
            boolean granted = focus != null && focus.reRequest();
            watchdog.onAttempt(granted, now);
            if (granted) {
                focusMuted = false;
                interrupted = false;
                applyLocalOutput();
                publish();
            } else if (watchdog.isRetrying()) {
                scheduleFocusWatchdog(InterruptWatchdog.RETRY_MS);
            } else {
                // out of attempts: the badge now asks the user to resume by hand
                publish();
            }
        }
    };

    private final BroadcastReceiver noisyReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!AudioManager.ACTION_AUDIO_BECOMING_NOISY.equals(intent.getAction())) return;
            if (source == Source.SPEAKER) {
                // headphones unplugged: badge, but never start playing out of the phone speaker by itself
                focusMuted = true;
                interrupted = true;
                watchdog.onManualInterrupt();
                applyLocalOutput();
                publish();
            } else if (playing) {
                pauseHost();
            }
        }
    };

    private void registerNoisy() {
        if (noisyRegistered) return;
        IntentFilter filter = new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(noisyReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(noisyReceiver, filter);
        noisyRegistered = true;
    }

    private void unregisterNoisy() {
        if (!noisyRegistered) return;
        try {
            unregisterReceiver(noisyReceiver);
        } catch (IllegalArgumentException ignored) {
            // not registered
        }
        noisyRegistered = false;
    }

    // ================================================================= room (player role)

    private void ensureRoom() {
        if (roomOpen || source == Source.SPEAKER || destroyed) return;
        roomOpen = true;
        if (roomCode.isEmpty()) roomCode = prefs.roomCode();
        String ip = NetUtil.findLocalIp();
        roomIp = ip == null ? "" : ip;
        host = new GroupHost(hostListener);
        http = new MiniHttpServer();
        timeServer = new TimeServer();
        discovery = new RoomDiscovery(this, discoveryListener);
        final GroupHost h = host;
        final MiniHttpServer server = http;
        final TimeServer time = timeServer;
        final RoomDiscovery disc = discovery;
        io.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    server.route("/hello", new MiniHttpServer.Handler() {
                        @Override
                        public boolean handle(MiniHttpServer.Request r, Socket s, InputStream in, OutputStream out) throws IOException {
                            JSONObject o = new JSONObject();
                            Json.put(o, "app", "Syncora");
                            Json.put(o, "v", 2);
                            Json.put(o, "code", roomCode);
                            Json.put(o, "name", prefs.deviceName());
                            Json.put(o, "title", title);
                            Json.put(o, "clients", h.clientCount());
                            MiniHttpServer.respondJson(out, o.toString());
                            return false;
                        }
                    });
                    server.route("/stream", new MiniHttpServer.Handler() {
                        @Override
                        public boolean handle(MiniHttpServer.Request r, Socket s, InputStream in, OutputStream out) throws IOException {
                            return h.handleStream(r, s, in, out);
                        }
                    });
                    server.route("/art", new MiniHttpServer.Handler() {
                        @Override
                        public boolean handle(MiniHttpServer.Request r, Socket s, InputStream in, OutputStream out) throws IOException {
                            byte[] jpeg = artStore.get(r.query.get("k") == null ? "" : r.query.get("k"));
                            if (jpeg == null) MiniHttpServer.respondText(out, 404, "Not Found", "No artwork");
                            else MiniHttpServer.respond(out, 200, "OK", "image/jpeg", jpeg, "Cache-Control: max-age=3600");
                            return false;
                        }
                    });
                    server.route("/lyrics", new MiniHttpServer.Handler() {
                        @Override
                        public boolean handle(MiniHttpServer.Request r, Socket s, InputStream in, OutputStream out) throws IOException {
                            String key = r.query.get("k") == null ? "" : r.query.get("k");
                            String json;
                            synchronized (lyricsStore) {
                                json = lyricsStore.get(key);
                            }
                            if (json == null) MiniHttpServer.respondText(out, 404, "Not Found", "No lyrics");
                            else MiniHttpServer.respondJson(out, json);
                            return false;
                        }
                    });
                    server.route("/dlna/", new MiniHttpServer.Handler() {
                        @Override
                        public boolean handle(MiniHttpServer.Request r, Socket s, InputStream in, OutputStream out) throws IOException {
                            DlnaRenderer renderer = dlna;
                            if (renderer == null) {
                                MiniHttpServer.respondText(out, 404, "Not Found", "DLNA is off");
                                return false;
                            }
                            return renderer.handle(r, s, in, out);
                        }
                    });
                    int port = server.start(PORT_FIRST, PORT_LAST);
                    timePort = time.start();
                    roomPort = port;
                    disc.start(false);
                    main.post(new Runnable() {
                        @Override
                        public void run() {
                            if (!destroyed) publish();
                        }
                    });
                } catch (final IOException e) {
                    main.post(new Runnable() {
                        @Override
                        public void run() {
                            roomOpen = false;
                            message("Could not open the room: " + e.getMessage());
                            publish();
                        }
                    });
                }
            }
        });
    }

    private void closeRoom() {
        if (!roomOpen && host == null) return;
        roomOpen = false;
        final GroupHost h = host;
        final MiniHttpServer server = http;
        final TimeServer time = timeServer;
        final RoomDiscovery disc = discovery;
        host = null;
        http = null;
        timeServer = null;
        discovery = null;
        roomPort = 0;
        io.execute(new Runnable() {
            @Override
            public void run() {
                if (h != null) h.shutdown();
                if (server != null) server.stop();
                if (time != null) time.stop();
                if (disc != null) disc.stop();
            }
        });
    }

    private final GroupHost.Listener hostListener = new GroupHost.Listener() {
        @Override
        public boolean acceptClient(String code) {
            return code != null && code.equalsIgnoreCase(roomCode) && source != Source.SPEAKER;
        }

        @Override
        public String welcomeJson(GroupHost.ClientInfo c) {
            JSONObject o = new JSONObject();
            Json.put(o, "v", 2);
            Json.put(o, "hostNanos", System.nanoTime());
            Json.put(o, "timePort", timePort);
            Json.put(o, "epoch", epoch);
            Json.put(o, "audioMode", prefs.audioMode());
            Json.put(o, "hostName", prefs.deviceName());
            Json.put(o, "roomCode", roomCode);
            return o.toString();
        }

        @Override
        public String stateJson() {
            return stateJson;
        }

        @Override
        public void onClientsChanged() {
            main.post(new Runnable() {
                @Override
                public void run() {
                    if (destroyed) return;
                    updateForeground();
                    publish();
                }
            });
        }

        @Override
        public void onClientCommand(final GroupHost.ClientInfo from, final JSONObject command) {
            main.post(new Runnable() {
                @Override
                public void run() {
                    if (!destroyed) handleRemoteCommand(from, command);
                }
            });
        }
    };

    private final RoomDiscovery.Listener discoveryListener = new RoomDiscovery.Listener() {
        @Override
        public RoomDiscovery.RoomInfo localRoom() {
            if (!roomOpen || source == Source.SPEAKER || roomPort == 0) return null;
            GroupHost h = host;
            return new RoomDiscovery.RoomInfo(roomCode, roomIp, roomPort, prefs.deviceName(), title, playing,
                    h == null ? 0 : h.clientCount(), source.id);
        }

        @Override
        public void onRoomFound(final RoomDiscovery.RoomInfo room) {
            main.post(new Runnable() {
                @Override
                public void run() {
                    if (!destroyed) onRoomSeen(room);
                }
            });
        }
    };

    private void handleRemoteCommand(GroupHost.ClientInfo from, JSONObject command) {
        if (source == Source.SPEAKER || from == null || command == null) return;
        // Playback ownership stays with the player. A speaker may request the read-only lyrics document to refresh,
        // but its pause/skip/seek buttons must never change the host's track or pause the rest of the room.
        if ("refreshLyrics".equals(command.optString("cmd", ""))) refreshLyrics();
    }

    // ================================================================= playback (player role)

    private int lookaheadMs(boolean live) {
        int ms = profile().lookaheadMs;
        return live ? Math.min(ms, 300) : ms;
    }

    private int newEpoch() {
        int e = epoch + 1;
        epoch = e;
        if (player != null) player.flush(e);
        GroupHost h = host;
        if (h != null) h.broadcastFlush(e);
        return e;
    }

    private void startPump(PcmSource src, long startMs, boolean live, long chainedAnchor, boolean flush) {
        android.util.Log.i("Syncora", "start " + src.getClass().getSimpleName() + " at " + startMs + " ms (live=" + live
                + ", flush=" + flush + ", lookahead=" + lookaheadMs(live) + " ms)");
        stopPump();
        ensurePlayer();
        // As the player, the timeline is this device's own clock: forget any offset left over from speaker mode.
        player.setClockOffsetNanos(0);
        player.setClockReady(true);
        lastSyncErrorNanos = 0;
        if (flush) newEpoch();
        liveSource = live;
        anchorMediaMs = startMs;
        anchorHostNanos = System.nanoTime() + lookaheadMs(live) * 1_000_000L;
        pump = new StreamPump(src, pumpOutput, ScheduledPlayer.SYSTEM_CLOCK, epoch, startMs, lookaheadMs(live), chainedAnchor);
        pump.start("syncora-pump");
    }

    private void stopPump() {
        StreamPump p = pump;
        pump = null;
        if (p != null) p.stop();
    }

    private final StreamPump.Output pumpOutput = new StreamPump.Output() {
        @Override
        public void onChunk(PcmChunk chunk) {
            if (chunk.epoch != epoch) return;
            ScheduledPlayer p = player;
            if (localOutputEnabled && p != null) p.enqueue(chunk);
            GroupHost h = host;
            if (h != null) h.broadcastAudio(chunk);
        }

        @Override
        public void onAnchor(final int e, final long mediaMs, final long hostNanos) {
            main.post(new Runnable() {
                @Override
                public void run() {
                    if (destroyed || e != epoch) return;
                    anchorMediaMs = mediaMs;
                    anchorHostNanos = hostNanos;
                    buffering = false;
                    consecutiveErrors = 0;
                    if (source == Source.DLNA && "TRANSITIONING".equals(dlnaState)) {
                        dlnaState = "PLAYING";
                        if (dlna != null) dlna.notifyStateChanged();
                    }
                    publish();
                }
            });
        }

        @Override
        public void onEnded(final int e, final long endAtHostNanos) {
            main.post(new Runnable() {
                @Override
                public void run() {
                    if (!destroyed && e == epoch) onPumpEnded(endAtHostNanos);
                }
            });
        }

        @Override
        public void onError(final int e, final String message) {
            main.post(new Runnable() {
                @Override
                public void run() {
                    if (!destroyed && e == epoch) onPumpError(message);
                }
            });
        }
    };

    private void onPumpEnded(long endAtNanos) {
        if (sleepTimer.consumeTrackBoundary()) {
            completeSleepTimer();
            return;
        }
        switch (source) {
            case MUSIC: {
                Track next = queue.onTrackEnded();
                if (next != null) {
                    // gapless: the next song is due exactly when this one finishes
                    nowPlayingFromTrack(next);
                    startPump(TrackDecoder.forUri(this, Uri.parse(next.uri)), 0, false, endAtNanos, false);
                    anchorMediaMs = 0;
                    anchorHostNanos = endAtNanos;
                    publish();
                    return;
                }
                playing = false;
                pausedPositionMs = 0;
                break;
            }
            case YOUTUBE:
                playing = false;
                pausedPositionMs = 0;
                break;
            case DLNA:
                playing = false;
                pausedPositionMs = 0;
                dlnaState = "STOPPED";
                if (dlna != null) dlna.notifyStateChanged();
                break;
            default:
                playing = false;
                break;
        }
        stopPump();
        updateForeground();
        publish();
    }

    private void onPumpError(String problem) {
        android.util.Log.w("Syncora", "source error (" + source + "): " + problem);
        stopPump();
        switch (source) {
            case MUSIC: {
                consecutiveErrors++;
                message("Can't play \"" + title + "\": " + problem);
                if (consecutiveErrors < Math.min(5, Math.max(1, queue.size())) && queue.size() > 1) {
                    if (queue.next() != null) {
                        playCurrentFrom(0);
                        return;
                    }
                }
                playing = false;
                break;
            }
            case RADIO:
                playing = false;
                stationStatus = problem;
                message(problem);
                break;
            case MIC:
                message(problem);
                stopMicInternal();
                playing = false;
                hasMedia = false;
                break;
            case YOUTUBE:
                playing = false;
                stationStatus = "";
                message("Can't play this YouTube audio: " + problem);
                break;
            case DLNA:
                playing = false;
                dlnaState = "STOPPED";
                message("Can't play the cast item: " + problem);
                if (dlna != null) dlna.notifyStateChanged();
                break;
            default:
                playing = false;
        }
        buffering = false;
        updateForeground();
        publish();
    }

    private void stopPlaybackInternal() {
        stopPump();
        playing = false;
        buffering = false;
        pausedPositionMs = 0;
        resumeAfterFocus = false;
        newEpoch();
        if (dlnaOn) {
            dlnaUri = "";
            dlnaMeta = "";
            dlnaState = "NO_MEDIA_PRESENT";
        }
    }

    private long hostPositionMs() {
        if (!playing) return pausedPositionMs;
        long p = anchorMediaMs + Math.max(0, (System.nanoTime() - anchorHostNanos) / 1_000_000L);
        long d = durationMs;
        if (d > 0 && p > d) p = d;
        return p;
    }

    private void playCurrentFrom(long startMs) {
        Track t = queue.current();
        if (t == null) return;
        nowPlayingFromTrack(t);
        playing = true;
        buffering = true;
        pausedPositionMs = startMs;
        startPump(TrackDecoder.forUri(this, Uri.parse(t.uri)), startMs, false, 0, true);
        requestFocus();
        registerNoisy();
        if (host == null && uiCount > 0 && prefs.shareRoom()) ensureRoom();
        updateForeground();
        publish();
    }

    private void startStation(Station st) {
        stopPump();
        nowPlayingFromStation(st);
        lastIcyTitle = "";
        stationStatus = "Connecting\u2026";
        playing = true;
        buffering = true;
        RadioSource radio = new RadioSource(st.url, new RadioSource.Listener() {
            @Override
            public void onStationName(String name) {
                // keep the name the user picked
            }

            @Override
            public void onTitle(final String streamTitle) {
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        if (source != Source.RADIO || station == null) return;
                        stationNowPlaying = streamTitle;
                        subtitle = streamTitle.isEmpty() ? station.subtitle() : streamTitle;
                        if (!streamTitle.isEmpty() && !streamTitle.equals(lastIcyTitle)) {
                            lastIcyTitle = streamTitle;
                            requestLyrics("s" + Integer.toHexString(streamTitle.hashCode()), new LyricsWork() {
                                @Override
                                public Lyrics load(LyricsClient.Request request) {
                                    String[] parts = YouTubeLyrics.splitTitle(streamTitle, "");
                                    return request.find(parts[1], parts[0], "", 0);
                                }
                            });
                        }
                        publish();
                    }
                });
            }

            @Override
            public void onStatus(final String status) {
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        if (source != Source.RADIO) return;
                        stationStatus = status;
                        publish();
                    }
                });
            }
        });
        startPump(radio, 0, true, 0, true);
        requestFocus();
        registerNoisy();
        if (host == null && uiCount > 0 && prefs.shareRoom()) ensureRoom();
        updateForeground();
        publish();
    }

    private void startYouTube(long startMs) {
        YouTubeVideo v = youtube;
        YouTubeFormat a = youtubeAudio;
        if (v == null || a == null) return;
        stopPump();
        nowPlayingFromYouTube(v);
        playing = true;
        buffering = true;
        pausedPositionMs = startMs;
        startPump(TrackDecoder.forUrl(this, a.url, v.userAgent), startMs, false, 0, true);
        requestFocus();
        registerNoisy();
        if (host == null && uiCount > 0 && prefs.shareRoom()) ensureRoom();
        updateForeground();
        publish();
    }

    private void pauseHost() {
        if (!playing) return;
        pausedPositionMs = hostPositionMs();
        playing = false;
        buffering = false;
        stopPump();
        newEpoch();
        if (source == Source.DLNA) {
            dlnaState = "PAUSED_PLAYBACK";
            if (dlna != null) dlna.notifyStateChanged();
        }
        if (micOn) {
            stopMicInternal();
            hasMedia = false;
        }
        updateForeground();
        publish();
    }

    private void resumeHost() {
        switch (source) {
            case MUSIC:
                if (queue.current() == null) {
                    message("Pick a song from your library first.");
                    return;
                }
                playCurrentFrom(pausedPositionMs);
                break;
            case RADIO:
                if (station == null) {
                    message("Choose a station first.");
                    return;
                }
                startStation(station);
                break;
            case YOUTUBE:
                if (youtube == null || youtubeAudio == null) {
                    message("Pick a YouTube video first.");
                    return;
                }
                startYouTube(pausedPositionMs);
                break;
            case DLNA:
                if (!dlnaUri.isEmpty()) startDlnaPlayback(pausedPositionMs);
                break;
            case MIC:
                startMic(lastMicInput, lastMicUnprocessed, micInputLabel);
                break;
            default:
                break;
        }
    }

    private void seekHost(long positionMs) {
        long target = Math.max(0, positionMs);
        long d = durationMs;
        if (d > 0 && target > d) target = d;
        if (source != Source.MUSIC && source != Source.DLNA && source != Source.YOUTUBE) return;
        if (playing && pump != null) {
            int e = newEpoch();
            anchorMediaMs = target;
            anchorHostNanos = System.nanoTime() + lookaheadMs(false) * 1_000_000L;
            pump.restart(e, target);
        }
        pausedPositionMs = target;
        publish();
    }

    /** Re-cue at the current position (new lookahead / fresh timeline). */
    private void restartCurrent() {
        long position = hostPositionMs();
        if (source == Source.MUSIC && queue.current() != null) playCurrentFrom(position);
        else if (source == Source.YOUTUBE && youtube != null) startYouTube(position);
        else if (source == Source.DLNA && !dlnaUri.isEmpty()) startDlnaPlayback(position);
    }

    // ================================================================= now playing

    private void nowPlayingFromTrack(final Track t) {
        title = t.title;
        subtitle = t.subtitle();
        album = t.album;
        artKey = t.artKey();
        art = null;
        durationMs = t.durationMs;
        hasMedia = true;
        requestLyrics("m" + t.id, new LyricsWork() {
            @Override
            public Lyrics load(LyricsClient.Request request) {
                String artist = "<unknown>".equals(t.artist) ? "" : t.artist;
                return request.find(t.title, artist, t.album, t.durationMs);
            }
        });
        loadArtAsync(t.artKey(), new ArtSource() {
            @Override
            public Bitmap load() {
                return ArtLoader.loadForTrack(SyncoraService.this, t, ART_PX);
            }
        });
    }

    private void nowPlayingFromYouTube(final YouTubeVideo v) {
        title = v.title;
        subtitle = v.author;
        album = "YouTube";
        artKey = v.artKey();
        art = null;
        durationMs = v.durationMs;
        hasMedia = true;
        stationNowPlaying = "";
        loadArtAsync(v.artKey(), new ArtSource() {
            @Override
            public Bitmap load() {
                return ArtLoader.loadFromUrl(v.thumbnailUrl, ART_PX);
            }
        });
        requestLyrics("y" + v.id, new LyricsWork() {
            @Override
            public Lyrics load(LyricsClient.Request request) {
                return YouTubeLyrics.find(v, request);
            }
        });
    }

    private void nowPlayingFromStation(Station st) {
        cancelLyricsRequest();
        currentLyricsWork = null;
        lyrics = null;
        lyricsKey = "";
        lyricsStatus = "";
        title = st.name;
        subtitle = st.subtitle();
        album = "";
        artKey = "";
        art = null;
        durationMs = 0;
        hasMedia = true;
        stationNowPlaying = "";
    }

    private interface ArtSource {
        Bitmap load();
    }

    private void loadArtAsync(final String key, final ArtSource loader) {
        worker.execute(new Runnable() {
            @Override
            public void run() {
                final Bitmap bitmap = loader.load();
                if (bitmap != null) artStore.put(key, ArtLoader.toJpeg(bitmap, 85));
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        if (destroyed || !key.equals(artKey)) return;
                        art = bitmap;
                        publish();
                    }
                });
            }
        });
    }

    // ================================================================= lyrics

    private interface LyricsWork {
        Lyrics load(LyricsClient.Request request);
    }

    /** Cancels the active connection as well as invalidating its eventual callback. */
    private void cancelLyricsRequest() {
        lyricsGeneration++;
        LyricsClient.Request request = activeLyricsRequest;
        activeLyricsRequest = null;
        if (request != null) request.cancel();
    }

    /**
     * Finds the lyrics on a worker thread. A track change cancels the old provider request and stale callbacks are
     * ignored by both key and generation. Positive and short-lived negative results are cached independently.
     */
    private void requestLyrics(final String key, final LyricsWork work) {
        requestLyrics(key, work, false);
    }

    private void requestLyrics(final String key, final LyricsWork work, boolean forceRefresh) {
        if (key == null || key.isEmpty() || work == null) return;
        if (!forceRefresh && key.equals(lyricsKey) && activeLyricsRequest != null) return;
        cancelLyricsRequest();
        final long generation = lyricsGeneration;
        final Lyrics previousLyrics = lyrics;
        currentLyricsWork = work;
        if (!forceRefresh) lyrics = null;
        lyricsKey = key;
        String cached = null;
        if (!forceRefresh) {
            synchronized (lyricsStore) {
                cached = lyricsStore.get(key);
            }
        }
        if (cached != null) {
            try {
                lyrics = Lyrics.fromJson(new JSONObject(cached));
            } catch (JSONException ignored) {
                lyrics = null;
            }
            lyricsStatus = lyrics == null ? "Lyrics cache was invalid. Tap to refresh." : "";
            if (lyrics != null) {
                publish();
                return;
            }
        }
        if (!forceRefresh) {
            synchronized (lyricsMisses) {
                Long retryAt = lyricsMisses.get(key);
                if (retryAt != null && retryAt > System.currentTimeMillis()) {
                    lyricsStatus = "No matching lyrics found. Tap Refresh to try again.";
                    publish();
                    return;
                }
                if (retryAt != null) lyricsMisses.remove(key);
            }
        }
        lyricsStatus = "Looking for matching lyrics\u2026";
        final LyricsClient.Request request = new LyricsClient.Request();
        activeLyricsRequest = request;
        try {
            worker.execute(new Runnable() {
                @Override
                public void run() {
                    Lyrics found = null;
                    try {
                        found = work.load(request);
                    } catch (RuntimeException ignored) {
                        found = null;
                    }
                    final Lyrics result = found == null || found.isEmpty() ? null : found;
                    final String failure = request.failure();
                    main.post(new Runnable() {
                        @Override
                        public void run() {
                            if (destroyed || generation != lyricsGeneration || !key.equals(lyricsKey)
                                    || activeLyricsRequest != request) return;
                            activeLyricsRequest = null;
                            lyrics = result == null && forceRefresh ? previousLyrics : result;
                            if (result == null) {
                                lyricsStatus = failure.isEmpty()
                                        ? "No matching lyrics found. Tap Refresh to try again."
                                        : failure + ". Tap Refresh to try again.";
                                if (failure.isEmpty()) {
                                    synchronized (lyricsMisses) {
                                        lyricsMisses.put(key, System.currentTimeMillis() + 120_000L);
                                    }
                                }
                            } else {
                                lyricsStatus = "";
                                synchronized (lyricsStore) {
                                    lyricsStore.put(key, result.toJson().toString());
                                }
                                synchronized (lyricsMisses) {
                                    lyricsMisses.remove(key);
                                }
                            }
                            publish();
                        }
                    });
                }
            });
        } catch (RuntimeException rejected) {
            activeLyricsRequest = null;
            lyricsStatus = "Lyrics lookup could not start. Tap Refresh to try again.";
        }
        publish();
    }

    /** Refreshes the current host/player lyrics without changing the song or its playback position. */
    public void refreshLyrics() {
        if (source == Source.SPEAKER) {
            sendRemote(command("refreshLyrics"));
            return;
        }
        if (currentLyricsWork == null || lyricsKey.isEmpty()) {
            message("Choose a track with lyrics metadata first.");
            return;
        }
        synchronized (lyricsMisses) {
            lyricsMisses.remove(lyricsKey);
        }
        requestLyrics(lyricsKey, currentLyricsWork, true);
    }

    /** Appearance changes only publish state; they never touch the player or playback timeline. */
    public void lyricsAppearanceChanged() {
        publish();
    }

    /** Speakers ask the player for the lyrics of the song it is playing. */
    private void fetchRemoteLyricsIfNeeded() {
        final Remote r = remote;
        if (r.lyricsKey.isEmpty()) {
            remoteLyrics = null;
            remoteLyricsKey = "";
            return;
        }
        if (!r.lyricsKey.equals(remoteLyricsKey)) remoteLyrics = null;
        if (!r.lyricsReady || r.lyricsKey.equals(remoteLyricsKey) || r.lyricsKey.equals(remoteLyricsRequestedKey)) return;
        remoteLyricsRequestedKey = r.lyricsKey;
        final String key = r.lyricsKey;
        final String ip = remoteIp;
        final int port = remotePort;
        worker.execute(new Runnable() {
            @Override
            public void run() {
                Lyrics result = null;
                HttpURLConnection c = null;
                try {
                    c = (HttpURLConnection) new URL("http://" + ip + ":" + port + "/lyrics?k=" + NetUtil.urlEncode(key)).openConnection();
                    c.setConnectTimeout(2500);
                    c.setReadTimeout(5000);
                    if (c.getResponseCode() == 200) {
                        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                        try (InputStream in = c.getInputStream()) {
                            byte[] buf = new byte[8192];
                            int n;
                            while ((n = in.read(buf)) > 0 && bytes.size() < 2 * 1024 * 1024) bytes.write(buf, 0, n);
                        }
                        result = Lyrics.fromJson(new JSONObject(bytes.toString("UTF-8")));
                    }
                } catch (Exception ignored) {
                    result = null;
                } finally {
                    if (c != null) c.disconnect();
                }
                final Lyrics found = result;
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        if (destroyed) return;
                        if (found != null && key.equals(remote.lyricsKey)) {
                            remoteLyrics = found;
                            remoteLyricsKey = key;
                        } else if (key.equals(remoteLyricsRequestedKey)) {
                            remoteLyricsRequestedKey = ""; // try again on the next state update
                        }
                        publish();
                    }
                });
            }
        });
    }

    // ================================================================= DLNA

    private void startDlna() {
        if (dlnaOn || destroyed) return;
        ensureRoom();
        String ip = NetUtil.findLocalIp();
        if (ip == null) {
            message("Connect to Wi-Fi to use the DLNA renderer.");
            return;
        }
        dlnaOn = true;
        dlnaUri = "";
        dlnaMeta = "";
        dlnaState = "NO_MEDIA_PRESENT";
        dlna = new DlnaRenderer(prefs.dlnaUuid(), "Syncora (" + prefs.deviceName() + ")", versionName(), dlnaController);
        ssdp = new SsdpAdvertiser(this, prefs.dlnaUuid());
        final SsdpAdvertiser advertiser = ssdp;
        final String address = ip;
        io.execute(new Runnable() {
            @Override
            public void run() {
                // wait for the HTTP server so the LOCATION in the advertisement is reachable
                for (int i = 0; i < 40 && roomPort == 0; i++) {
                    try {
                        Thread.sleep(50);
                    } catch (InterruptedException e) {
                        return;
                    }
                }
                try {
                    advertiser.start(address, roomPort);
                } catch (final IOException e) {
                    main.post(new Runnable() {
                        @Override
                        public void run() {
                            message("Could not start DLNA discovery: " + e.getMessage());
                        }
                    });
                }
            }
        });
        updateForeground();
    }

    private void stopDlna() {
        if (!dlnaOn) return;
        dlnaOn = false;
        final SsdpAdvertiser advertiser = ssdp;
        final DlnaRenderer renderer = dlna;
        ssdp = null;
        dlna = null;
        dlnaUri = "";
        dlnaMeta = "";
        dlnaState = "NO_MEDIA_PRESENT";
        io.execute(new Runnable() {
            @Override
            public void run() {
                if (advertiser != null) advertiser.stop();
                if (renderer != null) renderer.shutdown();
            }
        });
    }

    private String versionName() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "0";
        }
    }

    private void startDlnaPlayback(long startMs) {
        if (dlnaUri.isEmpty()) return;
        playing = true;
        buffering = true;
        pausedPositionMs = startMs;
        dlnaState = "TRANSITIONING";
        startPump(TrackDecoder.forUrl(this, dlnaUri), startMs, false, 0, true);
        requestFocus();
        registerNoisy();
        updateForeground();
        if (dlna != null) dlna.notifyStateChanged();
        publish();
    }

    private final DlnaRenderer.Controller dlnaController = new DlnaRenderer.Controller() {
        @Override
        public void onSetUri(final String uri, final String metadata) {
            main.post(new Runnable() {
                @Override
                public void run() {
                    if (destroyed || source != Source.DLNA) return;
                    stopPlaybackInternal();
                    dlnaUri = uri;
                    dlnaMeta = metadata;
                    UpnpXml.Didl didl = UpnpXml.parseDidl(metadata);
                    title = !didl.title.isEmpty() ? didl.title : lastSegment(uri);
                    album = didl.album;
                    subtitle = !didl.artist.isEmpty() ? (didl.album.isEmpty() ? didl.artist : didl.artist + " \u00B7 " + didl.album) : "Cast from your network";
                    durationMs = didl.durationMs;
                    hasMedia = !uri.isEmpty();
                    artKey = "d" + Integer.toHexString(uri.hashCode());
                    art = null;
                    dlnaState = hasMedia ? "STOPPED" : "NO_MEDIA_PRESENT";
                    final String artUrl = didl.albumArtUri;
                    if (!artUrl.isEmpty()) {
                        loadArtAsync(artKey, new ArtSource() {
                            @Override
                            public Bitmap load() {
                                return ArtLoader.loadFromUrl(artUrl, ART_PX);
                            }
                        });
                    }
                    publish();
                }
            });
        }

        @Override
        public void onPlay() {
            main.post(new Runnable() {
                @Override
                public void run() {
                    if (destroyed || source != Source.DLNA || dlnaUri.isEmpty()) return;
                    if (!playing) startDlnaPlayback(pausedPositionMs);
                }
            });
        }

        @Override
        public void onPause() {
            main.post(new Runnable() {
                @Override
                public void run() {
                    if (!destroyed && source == Source.DLNA) pauseHost();
                }
            });
        }

        @Override
        public void onStop() {
            main.post(new Runnable() {
                @Override
                public void run() {
                    if (destroyed || source != Source.DLNA) return;
                    stopPump();
                    playing = false;
                    pausedPositionMs = 0;
                    newEpoch();
                    dlnaState = dlnaUri.isEmpty() ? "NO_MEDIA_PRESENT" : "STOPPED";
                    if (dlna != null) dlna.notifyStateChanged();
                    updateForeground();
                    publish();
                }
            });
        }

        @Override
        public void onSeek(final long positionMs) {
            main.post(new Runnable() {
                @Override
                public void run() {
                    if (!destroyed && source == Source.DLNA) seekHost(positionMs);
                }
            });
        }

        @Override
        public void onNext() {
            // single item renderer
        }

        @Override
        public void onPrevious() {
            // single item renderer
        }

        @Override
        public void onSetVolume(final int percent) {
            main.post(new Runnable() {
                @Override
                public void run() {
                    if (destroyed) return;
                    applyOutputFields(Json.put(new JSONObject(), "volume", percent / 100.0));
                    publish();
                    if (dlna != null) dlna.notifyStateChanged();
                }
            });
        }

        @Override
        public void onSetMute(final boolean mute) {
            main.post(new Runnable() {
                @Override
                public void run() {
                    if (destroyed) return;
                    applyOutputFields(Json.put(new JSONObject(), "muted", mute));
                    publish();
                    if (dlna != null) dlna.notifyStateChanged();
                }
            });
        }

        @Override
        public DlnaRenderer.Status status() {
            DlnaRenderer.Status s = new DlnaRenderer.Status();
            s.transportState = dlnaState;
            s.uri = dlnaUri;
            s.metadata = dlnaMeta;
            s.positionMs = hostPositionMs();
            s.durationMs = durationMs;
            s.volume = Math.round(outVolume * 100f);
            s.mute = outMuted;
            return s;
        }
    };

    private static String lastSegment(String uri) {
        try {
            String path = Uri.parse(uri).getLastPathSegment();
            return path == null || path.isEmpty() ? "Cast item" : path;
        } catch (RuntimeException e) {
            return "Cast item";
        }
    }

    // ================================================================= speaker role

    private void enterSpeaker() {
        stopPlaybackInternal();
        closeRoom();
        userDisconnected = false;
        speakerPaused = false;
        remote.reset();
        remoteArt = null;
        remoteArtLoadedKey = "";
        remoteArtRequestedKey = "";
        rooms.clear();
        reconnectAttempts = 0;
        ensurePlayer();
        localOutputEnabled = true;
        loadOutputFromPrefs();
        applyLocalOutput();
        setConnection(SessionState.Connection.SCANNING, "Looking for players\u2026");
        final RoomDiscovery d = new RoomDiscovery(this, discoveryListener);
        discovery = d;
        io.execute(new Runnable() {
            @Override
            public void run() {
                d.start(true);
            }
        });
        if (prefs.autoConnect()) {
            if (!prefs.connectIp().isEmpty()) {
                connectByAddress(prefs.connectIp());
            } else if (!prefs.lastHostIp().isEmpty()) {
                // try the player we used last time right away
                connectTo(prefs.lastHostIp(), prefs.lastHostPort(), prefs.lastRoomCode());
            }
        } else {
            setConnection(SessionState.Connection.WAITING, "Waiting for connection");
        }
        updateForeground();
    }

    private void leaveSpeaker() {
        main.removeCallbacks(reconnectRunnable);
        main.removeCallbacks(autoPickRunnable);
        connectGeneration++;
        disconnectClient();
        final RoomDiscovery d = discovery;
        discovery = null;
        if (d != null) {
            io.execute(new Runnable() {
                @Override
                public void run() {
                    d.stop();
                }
            });
        }
        if (player != null) player.flush(Integer.MAX_VALUE - 1);
        remote.reset();
        remoteArt = null;
        rooms.clear();
        focusMuted = false;
        speakerPaused = false;
        clearInterrupt();
        connection = SessionState.Connection.NONE;
        connectionText = "";
        applyLocalOutput();
        epoch = 0;
        if (player != null) {
            player.flush(0);
            player.setClockOffsetNanos(0);
            player.setClockReady(true);
        }
    }

    private void setConnection(SessionState.Connection c, String text) {
        connection = c;
        connectionText = text;
    }

    private void onRoomSeen(RoomDiscovery.RoomInfo room) {
        if (source != Source.SPEAKER) return;
        rooms.put(room.code, room);
        if (connection != SessionState.Connection.CONNECTED && connection != SessionState.Connection.CONNECTING
                && prefs.autoConnect() && !userDisconnected) {
            if (room.code.equalsIgnoreCase(prefs.lastRoomCode())) {
                connectTo(room.ip, room.port, room.code);
            } else if (prefs.lastRoomCode().isEmpty() && rooms.size() == 1) {
                main.removeCallbacks(autoPickRunnable);
                main.postDelayed(autoPickRunnable, 3000);
            }
        }
        publish();
    }

    private final Runnable autoPickRunnable = new Runnable() {
        @Override
        public void run() {
            if (source != Source.SPEAKER || userDisconnected || !prefs.autoConnect()) return;
            if (connection == SessionState.Connection.CONNECTED || connection == SessionState.Connection.CONNECTING) return;
            if (rooms.size() == 1) {
                RoomDiscovery.RoomInfo only = rooms.values().iterator().next();
                connectTo(only.ip, only.port, only.code);
            }
        }
    };

    private final Runnable reconnectRunnable = new Runnable() {
        @Override
        public void run() {
            if (destroyed || source != Source.SPEAKER || userDisconnected) return;
            if (connection == SessionState.Connection.CONNECTED || connection == SessionState.Connection.CONNECTING) return;
            if (!remoteIp.isEmpty()) connectTo(remoteIp, remotePort, remoteCode);
            else if (!prefs.connectIp().isEmpty()) connectByAddress(prefs.connectIp());
        }
    };

    private void scheduleReconnect() {
        main.removeCallbacks(reconnectRunnable);
        if (source != Source.SPEAKER || userDisconnected || !prefs.autoConnect()) return;
        long delay = Math.min(10_000L, 1500L * (1L << Math.min(reconnectAttempts, 3)));
        reconnectAttempts++;
        main.postDelayed(reconnectRunnable, delay);
    }

    private void connectTo(final String ip, final int port, final String code) {
        if (ip == null || ip.isEmpty() || port <= 0) return;
        disconnectClient();
        remoteIp = ip;
        remotePort = port;
        remoteCode = code == null ? "" : code;
        remoteArtRequestedKey = "";
        setConnection(SessionState.Connection.CONNECTING, "Connecting to " + ip + "\u2026");
        ensurePlayer();
        player.setClockReady(false); // audio waits until this device knows the player's clock
        lastSyncErrorNanos = 0;
        final GroupClient c = new GroupClient(clientListener(++connectGeneration));
        client = c;
        c.connect(ip, port, remoteCode, prefs.deviceId(), prefs.deviceName());
        publish();
    }

    private void disconnectClient() {
        TimeClient t = timeClient;
        timeClient = null;
        if (t != null) t.stop();
        GroupClient c = client;
        client = null;
        if (c != null) c.close();
        if (connection == SessionState.Connection.CONNECTED && player != null) player.flush(Integer.MAX_VALUE - 1);
        clockSync.reset();
        main.removeCallbacks(clockFallbackRunnable);
    }

    private final Runnable clockFallbackRunnable = new Runnable() {
        @Override
        public void run() {
            if (player != null) player.setClockReady(true);
        }
    };

    private GroupClient.Listener clientListener(final int generation) {
        return new GroupClient.Listener() {
            @Override
            public void onConnected(final String welcomeJson) {
                final long receivedAt = System.nanoTime();
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        if (destroyed || generation != connectGeneration || source != Source.SPEAKER) return;
                        onSpeakerConnected(welcomeJson, receivedAt);
                    }
                });
            }

            @Override
            public void onState(final String json) {
                final long receivedAt = System.nanoTime();
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        if (destroyed || generation != connectGeneration || source != Source.SPEAKER) return;
                        applyRemoteState(json, receivedAt);
                    }
                });
            }

            @Override
            public void onAudio(PcmChunk chunk) {
                if (generation != connectGeneration) return;
                ScheduledPlayer p = player;
                if (p != null) p.enqueue(chunk);
            }

            @Override
            public void onFlush(int newEpoch) {
                if (generation != connectGeneration) return;
                ScheduledPlayer p = player;
                if (p != null) p.flush(newEpoch);
            }

            @Override
            public void onSet(final String json) {
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        if (destroyed || generation != connectGeneration) return;
                        JSONObject f = Json.parse(json);
                        if (f == null) return;
                        if (f.optBoolean("resync", false)) {
                            if (timeClient != null) timeClient.burst();
                            if (player != null) player.requestResync();
                        }
                        applyOutputFields(f);
                        publish();
                    }
                });
            }

            @Override
            public void onClosed(final String reason, final boolean refused) {
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        if (destroyed || generation != connectGeneration || source != Source.SPEAKER) return;
                        onSpeakerClosed(reason, refused);
                    }
                });
            }
        };
    }

    private void onSpeakerConnected(String welcomeJson, long receivedAtNanos) {
        JSONObject w = Json.parse(welcomeJson);
        if (w == null) w = new JSONObject();
        remote.hostName = w.optString("hostName", remote.hostName);
        remote.audioMode = w.optString("audioMode", remote.audioMode);
        reconnectAttempts = 0;
        configurePlayer();
        clockSync.reset();
        long hostNanos = w.optLong("hostNanos", 0);
        if (hostNanos != 0) clockSync.seedEstimate(hostNanos - receivedAtNanos);
        player.flush(w.optInt("epoch", 0));
        player.setClockOffsetNanos(clockSync.offsetNanos());
        player.setClockReady(false);
        main.postDelayed(clockFallbackRunnable, 1500);
        try {
            int timePortValue = w.optInt("timePort", 0);
            if (timePortValue > 0) {
                timeClient = new TimeClient(InetAddress.getByName(remoteIp), timePortValue, clockSync, new TimeClient.Listener() {
                    @Override
                    public void onSample(ClockSync sync) {
                        ScheduledPlayer p = player;
                        if (p == null) return;
                        p.setClockOffsetNanos(sync.offsetNanos());
                        p.setClockReady(true);
                    }
                });
                timeClient.setIntervalSeconds(effectiveResyncSeconds() > 0 ? effectiveResyncSeconds() : 30);
                timeClient.start();
            }
        } catch (java.net.UnknownHostException e) {
            // keep the handshake-based estimate
        }
        prefs.setLastHost(remoteIp, remotePort, remoteCode);
        setConnection(SessionState.Connection.CONNECTED, "Connected to " + (remote.hostName.isEmpty() ? remoteIp : remote.hostName));
        if (discovery != null) discovery.setProbing(false);
        sendClientHello();
        requestFocus();
        registerNoisy();
        updateForeground();
        publish();
    }

    private void sendClientHello() {
        GroupClient c = client;
        if (c == null) return;
        JSONObject o = new JSONObject();
        Json.put(o, "name", prefs.deviceName());
        Json.put(o, "volume", outVolume);
        Json.put(o, "muted", outMuted);
        Json.put(o, "channel", outChannel.id);
        Json.put(o, "offsetMs", outOffsetMs);
        c.sendHello(o.toString());
    }

    private void sendClientState() {
        GroupClient c = client;
        if (c == null || connection != SessionState.Connection.CONNECTED || player == null) return;
        JSONObject o = new JSONObject();
        Json.put(o, "name", prefs.deviceName());
        Json.put(o, "volume", outVolume);
        Json.put(o, "muted", outMuted);
        Json.put(o, "channel", outChannel.id);
        Json.put(o, "offsetMs", outOffsetMs);
        Json.put(o, "syncErrMs", (int) (lastSyncErrorNanos / 1_000_000L));
        Json.put(o, "queueDepth", player.queuedChunks());
        Json.put(o, "packetGaps", c.packetGaps());
        Json.put(o, "packetsReceived", c.audioPacketsReceived());
        Json.put(o, "droppedAudioChunks", player.droppedChunks());
        Json.put(o, "underruns", player.underrunCount());
        Json.put(o, "clockOffsetErrorMs", (int) (clockSync.uncertaintyNanos() / 1_000_000L));
        Json.put(o, "latencyMs", (int) (player.outputLatencyNanos() / 1_000_000L));
        Json.put(o, "status", speakerPaused || sleepTimerMuted ? "paused" : deviceStatus(player));
        c.sendState(o.toString());
    }

    private static String deviceStatus(ScheduledPlayer p) {
        if (p == null) return "connecting";
        if (p.isAligned()) return "synced";
        return p.queuedChunks() == 0 ? "buffering" : "resyncing";
    }

    private void onSpeakerClosed(String reason, boolean refused) {
        android.util.Log.i("Syncora", "speaker connection closed: " + reason + " (refused=" + refused + ")");
        disconnectClient();
        remote.playing = false;
        if (refused) {
            // the room code changed (the player restarted): forget it and look again
            prefs.clearLastHost();
            remoteIp = "";
            remoteCode = "";
        }
        if (userDisconnected) return;
        setConnection(SessionState.Connection.WAITING, "Waiting for connection");
        if (discovery != null) discovery.setProbing(true);
        message(reason);
        scheduleReconnect();
        updateForeground();
        publish();
    }

    private void applyRemoteState(String json, long receivedAtNanos) {
        JSONObject o = Json.parse(json);
        if (o == null) return;
        Remote r = remote;
        boolean wasPlaying = r.playing;
        String oldTitle = r.title;
        int oldQueueIndex = r.queueIndex;
        r.source = Source.fromId(o.optString("source", "music"));
        r.playing = o.optBoolean("playing", false);
        r.hasMedia = o.optBoolean("hasMedia", false);
        r.anchorMs = o.optLong("anchorMs", 0);
        r.anchorNanos = o.optLong("anchorNanos", 0);
        r.pausedMs = o.optLong("pausedMs", 0);
        r.durationMs = o.optLong("durationMs", 0);
        r.live = o.optBoolean("live", false);
        r.title = o.optString("title", "");
        r.subtitle = o.optString("subtitle", "");
        r.album = o.optString("album", "");
        r.artKey = o.optString("artKey", "");
        r.artReady = o.optBoolean("artReady", false);
        r.lyricsKey = o.optString("lyricsKey", "");
        r.lyricsReady = o.optBoolean("lyricsReady", false);
        r.lyricsStatus = o.optString("lyricsStatus", "");
        r.shuffle = o.optBoolean("shuffle", false);
        r.repeat = PlayQueue.Repeat.fromId(o.optString("repeat", "off"));
        r.canSeek = o.optBoolean("canSeek", false);
        r.canSkip = o.optBoolean("canSkip", false);
        r.queueIndex = o.optInt("queueIndex", 0);
        r.queueSize = o.optInt("queueSize", 0);
        r.hostName = o.optString("hostName", r.hostName);
        r.station = o.optString("station", "");
        r.stationStatus = o.optString("stationStatus", "");
        String previousMode = r.audioMode;
        r.audioMode = o.optString("audioMode", r.audioMode);
        r.lookaheadMs = o.optInt("lookaheadMs", 0);
        if (!previousMode.equals(r.audioMode)) configurePlayer();

        long hostNanos = o.optLong("hostNanos", 0);
        if (hostNanos != 0) clockSync.seedEstimate(hostNanos - receivedAtNanos);

        List<SessionState.QueueItem> up = new ArrayList<>();
        JSONArray upArray = o.optJSONArray("upNext");
        if (upArray != null) {
            for (int i = 0; i < upArray.length(); i++) {
                JSONObject q = upArray.optJSONObject(i);
                if (q == null) continue;
                SessionState.QueueItem item = new SessionState.QueueItem();
                item.index = q.optInt("i", 0);
                item.title = q.optString("t", "");
                item.artist = q.optString("a", "");
                up.add(item);
            }
        }
        r.upNext = up;

        List<SessionState.Device> devices = new ArrayList<>();
        JSONArray deviceArray = o.optJSONArray("devices");
        if (deviceArray != null) {
            for (int i = 0; i < deviceArray.length(); i++) {
                JSONObject d = deviceArray.optJSONObject(i);
                if (d == null) continue;
                SessionState.Device device = new SessionState.Device();
                device.id = d.optString("id", "");
                device.name = d.optString("name", "");
                device.address = d.optString("addr", "");
                device.host = d.optBoolean("host", false);
                device.volume = (float) d.optDouble("volume", 1.0);
                device.muted = d.optBoolean("muted", false);
                device.channel = d.optString("channel", "stereo");
                device.offsetMs = d.optInt("offsetMs", 0);
                device.syncErrMs = d.optInt("syncErrMs", 0);
                device.queueDepth = d.optInt("queueDepth", 0);
                device.peakQueueDepth = d.optInt("peakQueueDepth", device.queueDepth);
                device.packetGaps = d.optLong("packetGaps", 0);
                device.packetsReceived = d.optLong("packetsReceived", 0);
                device.droppedAudioChunks = d.optLong("droppedAudioChunks", 0);
                device.underruns = d.optLong("underruns", 0);
                device.clockOffsetErrorMs = d.optInt("clockOffsetErrorMs", 0);
                device.latencyMs = d.optInt("latencyMs", 0);
                device.status = d.optString("status", "synced");
                devices.add(device);
            }
        }
        r.devices = devices;

        if (source == Source.SPEAKER && wasPlaying
                && (!r.playing || !oldTitle.equals(r.title) || oldQueueIndex != r.queueIndex)
                && sleepTimer.consumeTrackBoundary()) {
            completeSleepTimer();
        }
        fetchRemoteArtIfNeeded();
        fetchRemoteLyricsIfNeeded();
        publish();
    }

    private void fetchRemoteArtIfNeeded() {
        final Remote r = remote;
        if (r.artKey.isEmpty()) {
            remoteArt = null;
            remoteArtLoadedKey = "";
            return;
        }
        if (!r.artReady || r.artKey.equals(remoteArtLoadedKey) || r.artKey.equals(remoteArtRequestedKey)) return;
        remoteArtRequestedKey = r.artKey;
        final String key = r.artKey;
        final String ip = remoteIp;
        final int port = remotePort;
        worker.execute(new Runnable() {
            @Override
            public void run() {
                HttpURLConnection c = null;
                Bitmap bitmap = null;
                try {
                    c = (HttpURLConnection) new URL("http://" + ip + ":" + port + "/art?k=" + NetUtil.urlEncode(key)).openConnection();
                    c.setConnectTimeout(2500);
                    c.setReadTimeout(5000);
                    if (c.getResponseCode() == 200) {
                        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                        try (InputStream in = c.getInputStream()) {
                            byte[] buf = new byte[8192];
                            int n;
                            while ((n = in.read(buf)) > 0 && bytes.size() < 4 * 1024 * 1024) bytes.write(buf, 0, n);
                        }
                        bitmap = ArtLoader.decodeSampled(bytes.toByteArray(), ART_PX);
                    }
                } catch (IOException | RuntimeException ignored) {
                    bitmap = null;
                } finally {
                    if (c != null) c.disconnect();
                }
                final Bitmap result = bitmap;
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        if (destroyed) return;
                        if (result != null && key.equals(remote.artKey)) {
                            remoteArt = result;
                            remoteArtLoadedKey = key;
                        } else if (key.equals(remoteArtRequestedKey)) {
                            remoteArtRequestedKey = ""; // allow a retry on the next state update
                        }
                        publish();
                    }
                });
            }
        });
    }

    private long remotePositionMs() {
        if (!remote.playing) return remote.pausedMs;
        long hostNow = System.nanoTime() + clockSync.offsetNanos();
        long p = remote.anchorMs + Math.max(0, (hostNow - remote.anchorNanos) / 1_000_000L);
        if (remote.durationMs > 0 && p > remote.durationMs) p = remote.durationMs;
        return p;
    }

    private void sendRemote(JSONObject command) {
        GroupClient c = client;
        if (c == null || connection != SessionState.Connection.CONNECTED) {
            message("Not connected to a player yet.");
            return;
        }
        c.sendCommand(command);
    }

    private static JSONObject command(String name) {
        return Json.put(new JSONObject(), "cmd", name);
    }

    // ================================================================= state

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (destroyed) return;
            if (source == Source.SPEAKER) {
                sendClientState();
                pruneRooms();
            } else if (roomOpen) {
                String ip = NetUtil.findLocalIp();
                roomIp = ip == null ? "" : ip;
            }
            if (uiCount > 0 || foreground) publish();
            if (debuggable && (playing || micOn || connection == SessionState.Connection.CONNECTED)) logDiagnostics();
            main.postDelayed(this, TICK_MS);
        }
    };

    /** One line per second in debuggable builds; the emulator checks read these. */
    private void logDiagnostics() {
        GroupHost h = host;
        boolean isPlaying = source == Source.SPEAKER ? remote.playing : playing;
        String stationId = source == Source.RADIO && station != null ? station.id : "-";
        android.util.Log.i("SyncoraDiag", "src=" + source + " playing=" + isPlaying + " pos=" + (source == Source.SPEAKER ? remotePositionMs() : hostPositionMs())
                + " epoch=" + epoch + " clients=" + (h == null ? 0 : h.clientCount()) + " syncErrMs=" + (lastSyncErrorNanos / 1_000_000L)
                + " aligned=" + (player != null && player.isAligned()) + " queuedChunks=" + (player == null ? -1 : player.queuedChunks())
                + " conn=" + connection + " room=" + roomPort + " buffering=" + buffering + " station=" + stationId
                + " interrupt=" + (interrupted ? 1 : 0) + " retrying=" + (watchdog.isRetrying() ? 1 : 0));
    }

    private void pruneRooms() {
        long now = System.currentTimeMillis();
        boolean changed = false;
        for (Iterator<RoomDiscovery.RoomInfo> it = rooms.values().iterator(); it.hasNext(); ) {
            if (now - it.next().seenAt > 9000) {
                it.remove();
                changed = true;
            }
        }
        if (changed) publish();
    }

    private void publish() {
        if (destroyed) return;
        SessionState s = buildState();
        lastState = s;
        for (Listener l : new ArrayList<>(listeners)) l.onState(s);
        if (roomOpen && source != Source.SPEAKER) {
            stateJson = buildStateJson(s);
            scheduleBroadcast();
        }
        updateNotification(s);
    }

    private void scheduleBroadcast() {
        GroupHost h = host;
        if (broadcastPending || h == null || h.clientCount() == 0) return;
        broadcastPending = true;
        main.postDelayed(new Runnable() {
            @Override
            public void run() {
                broadcastPending = false;
                GroupHost current = host;
                if (current != null) current.broadcastState(stateJson);
            }
        }, 60);
    }

    private SessionState buildState() {
        SessionState s = new SessionState();
        s.version = ++stateVersion;
        s.source = source;
        s.selfId = prefs.deviceId();
        s.deviceName = prefs.deviceName();
        s.autoConnect = prefs.autoConnect();
        s.shareRoom = prefs.shareRoom();
        s.positionLocalNanos = System.nanoTime();
        s.dlnaOn = dlnaOn;
        s.dlnaName = "Syncora (" + prefs.deviceName() + ")";
        s.micOn = micOn;
        s.micInput = micInputLabel;
        s.micMonitor = prefs.micMonitor();
        s.micGain = prefs.micGain();
        s.aligned = player != null && player.isAligned();
        s.syncErrMs = (int) (lastSyncErrorNanos / 1_000_000L);
        s.sleepTimerRemainingMs = sleepTimer.remainingMs(System.currentTimeMillis());
        s.sleepTimerAtTrackEnd = sleepTimer.kind() == SleepTimer.Kind.END_OF_TRACK;
        s.sleepTimerMuted = sleepTimerMuted;

        if (source == Source.SPEAKER) {
            fillSpeaker(s);
        } else {
            fillPlayer(s);
        }
        return s;
    }

    private void fillPlayer(SessionState s) {
        s.audioMode = prefs.audioMode();
        s.lookaheadMs = profile().lookaheadMs;
        s.playing = playing;
        s.buffering = buffering;
        s.hasMedia = hasMedia;
        s.title = title;
        s.subtitle = subtitle;
        s.album = album;
        s.artKey = artKey;
        s.art = art;
        s.durationMs = durationMs;
        s.live = liveSource;
        s.positionMs = hostPositionMs();
        s.canSeek = (source == Source.MUSIC || source == Source.DLNA || source == Source.YOUTUBE) && hasMedia && durationMs > 0;
        s.lyrics = lyrics;
        s.lyricsKey = lyricsKey;
        s.lyricsStatus = lyricsStatus;
        s.canSkip = source == Source.MUSIC && !queue.isEmpty();
        s.shuffle = queue.isShuffle();
        s.repeat = queue.getRepeat();
        s.queueSize = source == Source.MUSIC ? queue.size() : 0;
        s.queueIndex = source == Source.MUSIC ? Math.max(0, queue.cursor()) : 0;
        s.upNext = source == Source.MUSIC ? upNext() : Collections.<SessionState.QueueItem>emptyList();
        s.stationName = station == null ? "" : station.name;
        s.stationStatus = stationStatus;
        s.roomCode = roomCode;
        s.roomIp = roomIp;
        s.roomPort = roomPort;
        s.roomOpen = roomOpen && roomPort != 0;
        s.hostName = prefs.deviceName();
        s.connection = roomOpen ? SessionState.Connection.CONNECTED : SessionState.Connection.NONE;

        List<SessionState.Device> devices = new ArrayList<>();
        SessionState.Device self = new SessionState.Device();
        self.id = prefs.deviceId();
        self.name = prefs.deviceName();
        self.address = roomIp;
        self.self = true;
        self.host = true;
        self.volume = outVolume;
        self.muted = outMuted || !localOutputEnabled;
        self.channel = outChannel.id;
        self.offsetMs = outOffsetMs;
        self.queueDepth = player == null ? 0 : player.queuedChunks();
        self.droppedAudioChunks = player == null ? 0 : player.droppedChunks();
        self.underruns = player == null ? 0 : player.underrunCount();
        self.latencyMs = player == null ? 0 : (int) (player.outputLatencyNanos() / 1_000_000L);
        self.status = localOutputEnabled ? "synced" : "muted";
        devices.add(self);
        GroupHost h = host;
        if (h != null) {
            for (GroupHost.ClientInfo c : h.clients()) {
                SessionState.Device d = new SessionState.Device();
                d.id = c.id;
                d.name = c.name;
                d.address = c.address;
                d.volume = c.volume;
                d.muted = c.muted;
                d.channel = c.channel;
                d.offsetMs = c.offsetMs;
                d.syncErrMs = c.syncErrMs;
                d.queueDepth = c.queueDepth;
                d.peakQueueDepth = c.peakQueueDepth;
                d.packetGaps = c.packetGaps;
                d.packetsReceived = c.packetsReceived;
                d.droppedAudioChunks = c.droppedAudioChunks;
                d.underruns = c.underruns;
                d.clockOffsetErrorMs = c.clockOffsetErrorMs;
                d.latencyMs = c.latencyMs;
                d.status = c.status;
                devices.add(d);
            }
        }
        s.devices = devices;
        s.rooms = Collections.emptyList();
    }

    private List<SessionState.QueueItem> upNext() {
        List<Track> order = queue.playOrder();
        int cursor = queue.cursor();
        List<SessionState.QueueItem> list = new ArrayList<>();
        for (int i = cursor + 1; i < order.size() && list.size() < 25; i++) {
            SessionState.QueueItem item = new SessionState.QueueItem();
            item.index = i;
            item.title = order.get(i).title;
            item.artist = order.get(i).artist;
            list.add(item);
        }
        return list;
    }

    private void fillSpeaker(SessionState s) {
        Remote r = remote;
        boolean connected = connection == SessionState.Connection.CONNECTED;
        s.audioMode = connected ? r.audioMode : prefs.audioMode();
        s.lookaheadMs = r.lookaheadMs;
        s.connection = connection;
        s.connectionText = connectionText;
        s.hostName = r.hostName;
        s.roomIp = remoteIp;
        s.roomPort = remotePort;
        s.roomCode = remoteCode;
        s.playing = connected && r.playing;
        s.localPaused = speakerPaused || sleepTimerMuted;
        s.hasMedia = connected && r.hasMedia;
        s.title = connected ? r.title : "";
        s.subtitle = connected ? r.subtitle : "";
        s.album = r.album;
        s.artKey = r.artKey;
        s.art = remoteArtLoadedKey.equals(r.artKey) ? remoteArt : null;
        s.lyrics = connected && r.lyricsKey.equals(remoteLyricsKey) ? remoteLyrics : null;
        s.lyricsKey = r.lyricsKey;
        s.lyricsStatus = connected ? r.lyricsStatus : "";
        s.durationMs = r.durationMs;
        s.live = r.live;
        s.positionMs = connected ? remotePositionMs() : 0;
        // A speaker can mute/pause its own output, but only the player owns shared transport and queue controls.
        s.canSeek = false;
        s.canSkip = false;
        s.shuffle = r.shuffle;
        s.repeat = r.repeat;
        s.queueIndex = r.queueIndex;
        s.queueSize = r.queueSize;
        s.upNext = r.upNext;
        s.stationName = r.station;
        s.stationStatus = r.stationStatus;
        s.buffering = connected && r.playing && player != null && !player.isAligned();

        List<SessionState.Device> devices = new ArrayList<>();
        boolean sawSelf = false;
        for (SessionState.Device d : r.devices) {
            SessionState.Device copy = new SessionState.Device();
            copy.id = d.id;
            copy.name = d.name;
            copy.address = d.address;
            copy.host = d.host;
            copy.volume = d.volume;
            copy.muted = d.muted;
            copy.channel = d.channel;
            copy.offsetMs = d.offsetMs;
            copy.syncErrMs = d.syncErrMs;
            copy.queueDepth = d.queueDepth;
            copy.peakQueueDepth = d.peakQueueDepth;
            copy.packetGaps = d.packetGaps;
            copy.packetsReceived = d.packetsReceived;
            copy.droppedAudioChunks = d.droppedAudioChunks;
            copy.underruns = d.underruns;
            copy.clockOffsetErrorMs = d.clockOffsetErrorMs;
            copy.latencyMs = d.latencyMs;
            copy.status = d.status;
            if (d.id.equals(prefs.deviceId())) {
                copy.self = true;
                copy.name = prefs.deviceName();
                copy.volume = outVolume;
                copy.muted = outMuted;
                copy.channel = outChannel.id;
                copy.offsetMs = outOffsetMs;
                copy.syncErrMs = (int) (lastSyncErrorNanos / 1_000_000L);
                copy.queueDepth = player == null ? 0 : player.queuedChunks();
                copy.packetGaps = client == null ? 0 : client.packetGaps();
                copy.packetsReceived = client == null ? 0 : client.audioPacketsReceived();
                copy.droppedAudioChunks = player == null ? 0 : player.droppedChunks();
                copy.underruns = player == null ? 0 : player.underrunCount();
                copy.clockOffsetErrorMs = (int) (clockSync.uncertaintyNanos() / 1_000_000L);
                copy.latencyMs = player == null ? 0 : (int) (player.outputLatencyNanos() / 1_000_000L);
                copy.status = speakerPaused || sleepTimerMuted ? "paused" : deviceStatus(player);
                sawSelf = true;
            }
            devices.add(copy);
        }
        if (!sawSelf) {
            SessionState.Device self = new SessionState.Device();
            self.id = prefs.deviceId();
            self.name = prefs.deviceName();
            self.self = true;
            self.volume = outVolume;
            self.muted = outMuted;
            self.channel = outChannel.id;
            self.offsetMs = outOffsetMs;
            self.queueDepth = player == null ? 0 : player.queuedChunks();
            self.packetGaps = client == null ? 0 : client.packetGaps();
            self.packetsReceived = client == null ? 0 : client.audioPacketsReceived();
            self.droppedAudioChunks = player == null ? 0 : player.droppedChunks();
            self.underruns = player == null ? 0 : player.underrunCount();
            self.clockOffsetErrorMs = (int) (clockSync.uncertaintyNanos() / 1_000_000L);
            self.latencyMs = player == null ? 0 : (int) (player.outputLatencyNanos() / 1_000_000L);
            self.status = connected ? (speakerPaused || sleepTimerMuted ? "paused" : deviceStatus(player)) : "connecting";
            devices.add(self);
        }
        s.devices = devices;
        List<RoomDiscovery.RoomInfo> list = new ArrayList<>(rooms.values());
        Collections.sort(list, new Comparator<RoomDiscovery.RoomInfo>() {
            @Override
            public int compare(RoomDiscovery.RoomInfo a, RoomDiscovery.RoomInfo b) {
                return a.name.compareToIgnoreCase(b.name);
            }
        });
        s.rooms = list;
        s.interrupted = interrupted;
        s.interruptRetrying = watchdog.isRetrying();
    }

    private String buildStateJson(SessionState s) {
        JSONObject o = new JSONObject();
        Json.put(o, "v", 2);
        Json.put(o, "source", source.id);
        Json.put(o, "playing", playing);
        Json.put(o, "hasMedia", hasMedia);
        Json.put(o, "anchorMs", anchorMediaMs);
        Json.put(o, "anchorNanos", anchorHostNanos);
        Json.put(o, "pausedMs", pausedPositionMs);
        Json.put(o, "hostNanos", System.nanoTime());
        Json.put(o, "durationMs", durationMs);
        Json.put(o, "live", liveSource);
        Json.put(o, "title", title);
        Json.put(o, "subtitle", subtitle);
        Json.put(o, "album", album);
        Json.put(o, "artKey", artKey);
        Json.put(o, "artReady", !artKey.isEmpty() && artStore.get(artKey) != null);
        Json.put(o, "lyricsKey", lyricsKey);
        Json.put(o, "lyricsReady", lyrics != null);
        Json.put(o, "lyricsStatus", lyricsStatus);
        Json.put(o, "shuffle", s.shuffle);
        Json.put(o, "repeat", s.repeat.id);
        Json.put(o, "canSeek", s.canSeek);
        Json.put(o, "canSkip", s.canSkip);
        Json.put(o, "queueIndex", s.queueIndex);
        Json.put(o, "queueSize", s.queueSize);
        Json.put(o, "audioMode", prefs.audioMode());
        Json.put(o, "lookaheadMs", s.lookaheadMs);
        Json.put(o, "hostName", prefs.deviceName());
        Json.put(o, "roomCode", roomCode);
        Json.put(o, "station", s.stationName);
        Json.put(o, "stationStatus", s.stationStatus);
        JSONArray up = new JSONArray();
        for (SessionState.QueueItem item : s.upNext) {
            up.put(Json.put(Json.put(Json.put(new JSONObject(), "i", item.index), "t", item.title), "a", item.artist));
        }
        Json.put(o, "upNext", up);
        JSONArray devices = new JSONArray();
        for (SessionState.Device d : s.devices) {
            JSONObject j = new JSONObject();
            Json.put(j, "id", d.id);
            Json.put(j, "name", d.name);
            Json.put(j, "addr", d.address);
            Json.put(j, "host", d.host);
            Json.put(j, "volume", d.volume);
            Json.put(j, "muted", d.muted);
            Json.put(j, "channel", d.channel);
            Json.put(j, "offsetMs", d.offsetMs);
            Json.put(j, "syncErrMs", d.syncErrMs);
            Json.put(j, "queueDepth", d.queueDepth);
            Json.put(j, "peakQueueDepth", d.peakQueueDepth);
            Json.put(j, "packetGaps", d.packetGaps);
            Json.put(j, "packetsReceived", d.packetsReceived);
            Json.put(j, "droppedAudioChunks", d.droppedAudioChunks);
            Json.put(j, "underruns", d.underruns);
            Json.put(j, "clockOffsetErrorMs", d.clockOffsetErrorMs);
            Json.put(j, "latencyMs", d.latencyMs);
            Json.put(j, "status", d.status);
            devices.put(j);
        }
        Json.put(o, "devices", devices);
        return o.toString();
    }

    // ================================================================= foreground, locks, notification

    private boolean needForeground() {
        if (playing || micOn || dlnaOn || sleepTimer.active()) return true;
        if (source == Source.SPEAKER && connection != SessionState.Connection.NONE && !userDisconnected) return true;
        GroupHost h = host;
        return h != null && h.clientCount() > 0;
    }

    private void updateForeground() {
        if (destroyed) return;
        if (needForeground()) {
            if (!foreground) requestForeground();
            else enterForegroundNow(); // refreshes the foreground type (microphone on / off)
        } else if (foreground) {
            leaveForeground();
        }
        maybeStopSelf();
    }

    private void requestForeground() {
        try {
            startForegroundService(new Intent(this, SyncoraService.class).setAction(ACTION_KEEP));
        } catch (RuntimeException e) {
            // Not allowed to start from the background: keep running as a bound service.
            android.util.Log.w("Syncora", "could not start foreground service", e);
        }
    }

    private void enterForegroundNow() {
        if (destroyed) return;
        try {
            Notification n = notifier.build(lastState);
            if (Build.VERSION.SDK_INT >= 29) {
                int type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK;
                if (micOn && Build.VERSION.SDK_INT >= 30) type |= ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE;
                startForeground(MediaNotifier.NOTIFICATION_ID, n, type);
            } else {
                startForeground(MediaNotifier.NOTIFICATION_ID, n);
            }
            foreground = true;
            acquireLocks();
        } catch (RuntimeException e) {
            android.util.Log.w("Syncora", "startForeground failed", e);
            foreground = false;
        }
    }

    private void leaveForeground() {
        foreground = false;
        stopForeground(STOP_FOREGROUND_REMOVE);
        releaseLocks();
        unregisterNoisy();
        focus.abandon();
        lastNotificationSignature = "";
    }

    private void updateNotification(SessionState s) {
        if (!foreground) return;
        // The system media controls draw the seekbar from the media session: keep its position current.
        notifier.refreshSession(s);
        String signature = s.title + '|' + s.subtitle + '|' + s.playing + '|' + s.artKey + '|' + (s.art != null) + '|'
                + s.connection + '|' + s.canSkip + '|' + s.source + '|' + s.connectedSpeakers() + '|' + s.hasMedia + '|'
                + s.connectionText + '|' + s.durationMs + '|' + s.canSeek + '|' + s.buffering;
        if (signature.equals(lastNotificationSignature)) return;
        lastNotificationSignature = signature;
        notifier.update(s);
    }

    private void acquireLocks() {
        try {
            if (wakeLock == null) {
                PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "syncora:session");
                wakeLock.setReferenceCounted(false);
            }
            if (!wakeLock.isHeld()) wakeLock.acquire(6 * 60 * 60 * 1000L);
            if (wifiLock == null) {
                WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                if (wm != null) {
                    int mode = Build.VERSION.SDK_INT >= 29 ? WifiManager.WIFI_MODE_FULL_LOW_LATENCY : WifiManager.WIFI_MODE_FULL_HIGH_PERF;
                    wifiLock = wm.createWifiLock(mode, "syncora:lan");
                    wifiLock.setReferenceCounted(false);
                }
            }
            if (wifiLock != null && !wifiLock.isHeld()) wifiLock.acquire();
        } catch (RuntimeException e) {
            android.util.Log.w("Syncora", "could not acquire locks", e);
        }
    }

    private void releaseLocks() {
        try {
            if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
            if (wifiLock != null && wifiLock.isHeld()) wifiLock.release();
        } catch (RuntimeException ignored) {
            // nothing to do
        }
    }
}
