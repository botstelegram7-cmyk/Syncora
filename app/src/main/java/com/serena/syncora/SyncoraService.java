package com.serena.syncora;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.OpenableColumns;
import android.webkit.MimeTypeMap;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.Inet4Address;
import java.net.NetworkInterface;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** Playback coordinator and local-only host/client service. */
public final class SyncoraService extends Service implements LocalHttpServer.Listener, RoomDiscovery.Listener {
    public static final String ACTION_HOST = "com.serena.syncora.action.HOST";
    public static final String ACTION_CHANGE_TRACK = "com.serena.syncora.action.CHANGE_TRACK";
    public static final String ACTION_JOIN = "com.serena.syncora.action.JOIN";
    public static final String ACTION_DISCOVER = "com.serena.syncora.action.DISCOVER";
    public static final String ACTION_STOP_DISCOVER = "com.serena.syncora.action.STOP_DISCOVER";
    public static final String ACTION_TOGGLE = "com.serena.syncora.action.TOGGLE";
    public static final String ACTION_STOP = "com.serena.syncora.action.STOP";
    public static final String ACTION_STATUS = "com.serena.syncora.event.STATUS";
    public static final String ACTION_FOUND = "com.serena.syncora.event.FOUND";
    public static final String ACTION_ERROR = "com.serena.syncora.event.ERROR";
    public static final String EXTRA_URI = "audio_uri";
    public static final String EXTRA_TARGET = "room_target";

    static final String MODE_IDLE = "idle";
    static final String MODE_HOST = "host";
    static final String MODE_CLIENT = "client";
    static final String MODE_JOINING = "joining";

    private static final String CHANNEL_ID = "syncora-playback";
    private static final int NOTIFICATION_ID = 7401;
    private static final int DEFAULT_HTTP_PORT = 8787;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newFixedThreadPool(4);
    private final ConcurrentHashMap<String, String> connectedClients = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> clientLastSeen = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, RoomDiscovery.RoomInfo> discoveredRooms = new ConcurrentHashMap<>();
    private final AtomicBoolean syncRequestRunning = new AtomicBoolean(false);

    private volatile String mode = MODE_IDLE;
    private volatile String roomCode = "";
    private volatile String trackTitle = "";
    private volatile String hostName = "";
    private volatile String hostIp = "";
    private volatile int hostPort = DEFAULT_HTTP_PORT;
    private volatile int positionMs;
    private volatile int durationMs;
    private volatile boolean playing;
    private volatile long trackVersion;
    private volatile File audioFile;
    private volatile String joiningMessage = "";

    private MediaPlayer player;
    private boolean playerPrepared;
    private long remoteTrackVersion = -1;
    private int remotePositionMs;
    private boolean remotePlaying;
    private boolean joinedHost;
    private int failedSyncs;
    private String pendingJoinCode = "";
    private long pendingJoinDeadline;
    private boolean discoveryWanted;
    private boolean foreground;
    private LocalHttpServer httpServer;
    private RoomDiscovery discovery;
    private WifiManager.WifiLock wifiLock;
    private File previousAudioFile;

    private final Runnable statusTicker = new Runnable() {
        @Override public void run() {
            if (player != null && playerPrepared) {
                try {
                    positionMs = Math.max(0, player.getCurrentPosition());
                    if (mode == MODE_HOST) durationMs = Math.max(durationMs, player.getDuration());
                } catch (IllegalStateException ignored) { }
            }
            if (MODE_HOST.equals(mode)) pruneConnectedClients();
            sendStatus();
            if (!MODE_IDLE.equals(mode)) main.postDelayed(this, 850);
        }
    };

    private final Runnable clientSyncTicker = new Runnable() {
        @Override public void run() {
            if (!MODE_CLIENT.equals(mode)) return;
            if (syncRequestRunning.compareAndSet(false, true)) {
                io.execute(() -> {
                    try {
                        pollHost();
                    } finally {
                        syncRequestRunning.set(false);
                    }
                });
            }
            main.postDelayed(this, 700);
        }
    };

    private final Runnable pendingJoinTimeout = new Runnable() {
        @Override public void run() {
            if (MODE_JOINING.equals(mode) && !pendingJoinCode.isEmpty()
                    && System.currentTimeMillis() >= pendingJoinDeadline) {
                String failedCode = pendingJoinCode;
                pendingJoinCode = "";
                sendError("Could not find room " + failedCode + ". Make sure both phones are on the same Wi-Fi or hotspot.");
                stopSession();
            } else if (MODE_JOINING.equals(mode) && !pendingJoinCode.isEmpty()) {
                RoomDiscovery.RoomInfo room = findRoom(pendingJoinCode);
                if (room != null) {
                    pendingJoinCode = "";
                    startClient(room.ip, room.port, room);
                } else {
                    main.postDelayed(this, 500);
                }
            }
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        discovery = new RoomDiscovery(this, this);
        createNotificationChannel();
        main.post(statusTicker);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_HOST.equals(action)) {
            Uri uri = intent.getParcelableExtra(EXTRA_URI);
            if (uri != null) startHosting(uri);
            else sendError("Choose an audio file first.");
        } else if (ACTION_CHANGE_TRACK.equals(action)) {
            Uri uri = intent.getParcelableExtra(EXTRA_URI);
            if (uri != null && MODE_HOST.equals(mode)) loadHostTrack(uri);
        } else if (ACTION_JOIN.equals(action)) {
            String target = intent.getStringExtra(EXTRA_TARGET);
            if (target != null) joinTarget(target);
        } else if (ACTION_DISCOVER.equals(action)) {
            discoveryWanted = true;
            ensureDiscovery();
            sendStatus();
        } else if (ACTION_STOP_DISCOVER.equals(action)) {
            discoveryWanted = false;
            if (MODE_IDLE.equals(mode)) {
                discovery.stop();
                stopSelf();
            } else {
                discovery.setProbing(false);
            }
        } else if (ACTION_TOGGLE.equals(action)) {
            togglePlayback();
        } else if (ACTION_STOP.equals(action)) {
            stopSession();
            if (!discoveryWanted) stopSelf();
        }
        return START_NOT_STICKY;
    }

    private void startHosting(Uri uri) {
        if (!MODE_IDLE.equals(mode)) stopSession();
        mode = MODE_HOST;
        roomCode = createRoomCode();
        hostName = deviceName();
        hostIp = findLocalIp();
        hostPort = DEFAULT_HTTP_PORT;
        trackVersion = 0;
        connectedClients.clear();
        clientLastSeen.clear();
        joiningMessage = "";
        acquireWifiLock();
        enterForeground();
        ensureDiscovery();
        discovery.setProbing(false);
        loadHostTrack(uri);
    }

    private void loadHostTrack(Uri uri) {
        if (!MODE_HOST.equals(mode)) return;
        final long version = ++trackVersion;
        final String title = readDisplayName(uri);
        previousAudioFile = audioFile;
        audioFile = null;
        trackTitle = title;
        positionMs = 0;
        durationMs = 0;
        playing = false;
        releasePlayer();
        sendStatus();
        updateNotification();

        io.execute(() -> {
            File copied = null;
            try {
                copied = copyToCache(uri, version, title);
                final File ready = copied;
                main.post(() -> {
                    if (!MODE_HOST.equals(mode) || version != trackVersion) {
                        ready.delete();
                        return;
                    }
                    audioFile = ready;
                    hostIp = findLocalIp();
                    try {
                        if (httpServer == null) httpServer = new LocalHttpServer(this);
                        hostPort = httpServer.start();
                    } catch (IOException error) {
                        sendError("Could not start the local room: " + safeMessage(error));
                        stopSession();
                        return;
                    }
                    prepareLocalPlayer(ready);
                    if (previousAudioFile != null && !previousAudioFile.equals(ready)) previousAudioFile.delete();
                    previousAudioFile = null;
                    sendStatus();
                });
            } catch (Exception error) {
                if (copied != null) copied.delete();
                main.post(() -> {
                    if (MODE_HOST.equals(mode) && version == trackVersion) {
                        sendError("Could not read this audio file. Try another file format.");
                        stopSession();
                    }
                });
            }
        });
    }

    private void prepareLocalPlayer(File file) {
        releasePlayer();
        MediaPlayer next = new MediaPlayer();
        player = next;
        configurePlayer(next);
        next.setOnPreparedListener(mediaPlayer -> {
            if (player != mediaPlayer || !MODE_HOST.equals(mode)) return;
            playerPrepared = true;
            durationMs = Math.max(0, mediaPlayer.getDuration());
            positionMs = 0;
            try {
                mediaPlayer.start();
                playing = true;
            } catch (IllegalStateException error) {
                playing = false;
            }
            updateNotification();
            sendStatus();
        });
        next.setOnCompletionListener(mediaPlayer -> {
            if (player != mediaPlayer) return;
            playing = false;
            positionMs = durationMs;
            updateNotification();
            sendStatus();
        });
        next.setOnErrorListener((mediaPlayer, what, extra) -> {
            if (player == mediaPlayer) {
                playing = false;
                sendError("This audio format could not be played on this device.");
                sendStatus();
            }
            return true;
        });
        try {
            next.setDataSource(file.getAbsolutePath());
            next.prepareAsync();
        } catch (Exception error) {
            sendError("Could not prepare this audio file.");
            stopSession();
        }
    }

    private void joinTarget(String rawTarget) {
        String target = rawTarget.trim();
        if (target.isEmpty()) {
            sendError("Enter a room ID or IP address.");
            return;
        }
        if (!MODE_IDLE.equals(mode)) stopSession();
        enterForeground();
        mode = MODE_JOINING;
        joiningMessage = "Looking for a room on this network…";
        hostName = "Nearby room";
        acquireWifiLock();
        ensureDiscovery();
        discovery.setProbing(true);
        sendStatus();

        if (looksLikeRoomCode(target)) {
            RoomDiscovery.RoomInfo room = findRoom(target);
            if (room != null) {
                startClient(room.ip, room.port, room);
            } else {
                pendingJoinCode = target;
                pendingJoinDeadline = System.currentTimeMillis() + 18_000;
                main.removeCallbacks(pendingJoinTimeout);
                main.postDelayed(pendingJoinTimeout, 250);
            }
            return;
        }

        HostAddress address = parseHostAddress(target);
        if (address == null) {
            sendError("That doesn't look like a room ID or IP address.");
            stopSession();
            return;
        }
        pendingJoinCode = "";
        startClient(address.host, address.port, null);
    }

    private void startClient(String ip, int port, RoomDiscovery.RoomInfo room) {
        main.removeCallbacks(pendingJoinTimeout);
        pendingJoinCode = "";
        mode = MODE_CLIENT;
        hostIp = ip;
        hostPort = port;
        hostName = room == null ? "Syncora room" : room.name;
        roomCode = room == null ? "" : room.code;
        trackTitle = room == null ? "Connecting to room…" : room.title;
        joiningMessage = "";
        joinedHost = false;
        failedSyncs = 0;
        positionMs = 0;
        durationMs = 0;
        playing = false;
        remoteTrackVersion = -1;
        playerPrepared = false;
        releasePlayer();
        discovery.setProbing(false);
        ensureDiscovery();
        sendStatus();
        main.removeCallbacks(clientSyncTicker);
        main.post(clientSyncTicker);
    }

    private void pollHost() {
        try {
            String syncUrl = hostBaseUrl() + "/sync";
            if (!roomCode.isEmpty()) syncUrl += "?code=" + urlEncode(roomCode) + "&name=" + urlEncode(deviceName());
            JSONObject snapshot = new JSONObject(httpGet(syncUrl));
            if (!snapshot.optBoolean("ok", true)) throw new IOException("Host is not ready");
            String foundCode = snapshot.optString("code", "");
            if (!joinedHost && !foundCode.isEmpty()) {
                String query = "?code=" + urlEncode(foundCode) + "&name=" + urlEncode(deviceName());
                httpGet(hostBaseUrl() + "/join" + query);
                joinedHost = true;
            }
            failedSyncs = 0;
            applyHostSnapshot(snapshot);
        } catch (Exception error) {
            failedSyncs++;
            if (failedSyncs == 4) {
                main.post(() -> {
                    joiningMessage = "Reconnecting to " + hostName + "…";
                    sendStatus();
                });
            }
            if (failedSyncs == 12) {
                main.post(() -> sendError("The room is no longer reachable. Check that both devices are still on the same Wi-Fi or hotspot."));
            }
        }
    }

    private void applyHostSnapshot(JSONObject snapshot) {
        main.post(() -> {
            if (!MODE_CLIENT.equals(mode)) return;
            roomCode = snapshot.optString("code", roomCode);
            hostName = snapshot.optString("host", hostName);
            trackTitle = snapshot.optString("title", trackTitle);
            remotePositionMs = Math.max(0, snapshot.optInt("position", 0));
            remotePlaying = snapshot.optBoolean("playing", false);
            durationMs = Math.max(0, snapshot.optInt("duration", 0));
            positionMs = remotePositionMs;
            playing = remotePlaying;
            joiningMessage = "";
            long version = snapshot.optLong("version", 0);
            if (version != remoteTrackVersion || player == null) {
                remoteTrackVersion = version;
                prepareRemotePlayer(version);
            } else if (playerPrepared) {
                synchronizeClientPlayer();
            }
            updateNotification();
            sendStatus();
        });
    }

    private void prepareRemotePlayer(long version) {
        releasePlayer();
        MediaPlayer next = new MediaPlayer();
        player = next;
        configurePlayer(next);
        next.setOnPreparedListener(mediaPlayer -> {
            if (player != mediaPlayer || !MODE_CLIENT.equals(mode) || remoteTrackVersion != version) return;
            playerPrepared = true;
            durationMs = Math.max(0, mediaPlayer.getDuration());
            synchronizeClientPlayer();
            sendStatus();
        });
        next.setOnCompletionListener(mediaPlayer -> {
            if (player == mediaPlayer) {
                playing = false;
                sendStatus();
            }
        });
        next.setOnErrorListener((mediaPlayer, what, extra) -> {
            if (player == mediaPlayer) {
                playerPrepared = false;
                sendError("The host audio stream was interrupted. Syncora will keep trying to reconnect.");
            }
            return true;
        });
        try {
            next.setDataSource(hostBaseUrl() + "/audio?v=" + version);
            next.prepareAsync();
        } catch (Exception error) {
            android.util.Log.w("Syncora", "Unable to open audio stream", error);
            sendError("Could not open the host's audio stream.");
        }
    }

    private void synchronizeClientPlayer() {
        if (player == null || !playerPrepared || !MODE_CLIENT.equals(mode)) return;
        try {
            int localPosition = player.getCurrentPosition();
            int drift = remotePositionMs - localPosition;
            if (Math.abs(drift) > 450) player.seekTo(Math.max(0, remotePositionMs));
            if (remotePlaying && !player.isPlaying()) player.start();
            else if (!remotePlaying && player.isPlaying()) player.pause();
            if (!remotePlaying && Math.abs(drift) > 700) player.seekTo(Math.max(0, remotePositionMs));
        } catch (IllegalStateException ignored) { }
    }

    private void togglePlayback() {
        if (MODE_HOST.equals(mode)) {
            if (player == null || !playerPrepared) return;
            try {
                if (player.isPlaying()) {
                    player.pause();
                    playing = false;
                    positionMs = player.getCurrentPosition();
                } else {
                    player.start();
                    playing = true;
                }
                updateNotification();
                sendStatus();
            } catch (IllegalStateException ignored) { }
        } else if (MODE_CLIENT.equals(mode)) {
            final String base = hostBaseUrl();
            final String code = roomCode;
            io.execute(() -> {
                try { httpGet(base + "/command?code=" + urlEncode(code) + "&action=toggle"); }
                catch (Exception error) { android.util.Log.d("Syncora", "Unable to send playback command", error); }
            });
        }
    }

    private void stopSession() {
        final boolean wasClient = MODE_CLIENT.equals(mode);
        final String leavingBase = wasClient ? hostBaseUrl() : "";
        final String leavingCode = roomCode;
        if (wasClient && joinedHost && !leavingCode.isEmpty()) {
            io.execute(() -> {
                try { httpGet(leavingBase + "/leave?code=" + urlEncode(leavingCode)); }
                catch (Exception ignored) { }
            });
        }

        main.removeCallbacks(pendingJoinTimeout);
        main.removeCallbacks(clientSyncTicker);
        mode = MODE_IDLE;
        joiningMessage = "";
        playing = false;
        positionMs = 0;
        durationMs = 0;
        roomCode = "";
        trackTitle = "";
        hostName = "";
        hostIp = "";
        hostPort = DEFAULT_HTTP_PORT;
        pendingJoinCode = "";
        joinedHost = false;
        remoteTrackVersion = -1;
        connectedClients.clear();
        clientLastSeen.clear();
        releasePlayer();
        if (httpServer != null) {
            httpServer.stop();
            httpServer = null;
        }
        File current = audioFile;
        audioFile = null;
        if (current != null) current.delete();
        if (previousAudioFile != null) {
            previousAudioFile.delete();
            previousAudioFile = null;
        }
        releaseWifiLock();
        if (foreground) {
            stopForeground(STOP_FOREGROUND_REMOVE);
            foreground = false;
        }
        if (discovery != null) {
            if (discoveryWanted) {
                discovery.start(true);
                discovery.setProbing(true);
            } else {
                discovery.stop();
            }
        }
        sendStatus();
    }

    private void releasePlayer() {
        playerPrepared = false;
        MediaPlayer old = player;
        player = null;
        if (old != null) {
            try { old.setOnPreparedListener(null); } catch (Exception ignored) { }
            try { old.setOnCompletionListener(null); } catch (Exception ignored) { }
            try { old.setOnErrorListener(null); } catch (Exception ignored) { }
            try { old.reset(); } catch (Exception ignored) { }
            try { old.release(); } catch (Exception ignored) { }
        }
    }

    private void configurePlayer(MediaPlayer mediaPlayer) {
        try {
            mediaPlayer.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build());
            mediaPlayer.setWakeMode(this, PowerManager.PARTIAL_WAKE_LOCK);
        } catch (Exception ignored) { }
    }

    private void ensureDiscovery() {
        if (discovery == null) discovery = new RoomDiscovery(this, this);
        boolean probe = MODE_IDLE.equals(mode) || MODE_JOINING.equals(mode);
        discovery.start(probe && (discoveryWanted || MODE_JOINING.equals(mode)));
        discovery.setProbing(probe && (discoveryWanted || MODE_JOINING.equals(mode)));
    }

    private void acquireWifiLock() {
        try {
            WifiManager wifi = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wifi != null && (wifiLock == null || !wifiLock.isHeld())) {
                wifiLock = wifi.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "syncora-lan-audio");
                wifiLock.setReferenceCounted(false);
                wifiLock.acquire();
            }
        } catch (Exception error) {
            android.util.Log.d("Syncora", "Wi-Fi lock unavailable", error);
        }
    }

    private void releaseWifiLock() {
        try {
            if (wifiLock != null && wifiLock.isHeld()) wifiLock.release();
        } catch (Exception ignored) { }
        wifiLock = null;
    }

    private File copyToCache(Uri uri, long version, String title) throws IOException {
        ContentResolver resolver = getContentResolver();
        String mime = resolver.getType(uri);
        String extension = mime == null ? "" : MimeTypeMap.getSingleton().getExtensionFromMimeType(mime);
        if (extension == null || extension.trim().isEmpty()) {
            int dot = title.lastIndexOf('.');
            extension = dot >= 0 && dot + 1 < title.length() ? title.substring(dot + 1) : "audio";
        }
        extension = extension.replaceAll("[^A-Za-z0-9]", "").toLowerCase(Locale.US);
        if (extension.isEmpty()) extension = "audio";
        String safeCode = roomCode.replaceAll("[^A-Za-z0-9]", "");
        File destination = new File(getCacheDir(), "syncora-" + safeCode + "-" + version + "." + extension);
        try (InputStream input = resolver.openInputStream(uri); FileOutputStream output = new FileOutputStream(destination)) {
            if (input == null) throw new IOException("Audio provider returned no stream");
            byte[] buffer = new byte[64 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            output.getFD().sync();
        } catch (Exception error) {
            destination.delete();
            if (error instanceof IOException) throw (IOException) error;
            throw new IOException(error);
        }
        if (destination.length() == 0) {
            destination.delete();
            throw new IOException("Selected file is empty");
        }
        return destination;
    }

    private String readDisplayName(Uri uri) {
        String result = "Your audio";
        try (Cursor cursor = getContentResolver().query(uri, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (index >= 0) result = cursor.getString(index);
            }
        } catch (Exception ignored) { }
        if (result == null || result.trim().isEmpty()) result = "Your audio";
        return result;
    }

    private String hostBaseUrl() {
        String address = hostIp;
        if (address == null || address.isEmpty()) return "http://127.0.0.1:" + hostPort;
        if (address.contains(":")) address = "[" + address + "]";
        return "http://" + address + ":" + hostPort;
    }

    private String findLocalIp() {
        try {
            ArrayList<String> other = new ArrayList<>();
            ArrayList<String> wifi = new ArrayList<>();
            for (NetworkInterface network : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!network.isUp() || network.isLoopback()) continue;
                for (java.net.InetAddress address : Collections.list(network.getInetAddresses())) {
                    if (address instanceof Inet4Address && !address.isLoopbackAddress() && !address.isLinkLocalAddress()) {
                        String value = address.getHostAddress();
                        if (network.getName().toLowerCase(Locale.US).contains("wlan")
                                || network.getName().toLowerCase(Locale.US).contains("ap")) wifi.add(value);
                        else other.add(value);
                    }
                }
            }
            if (!wifi.isEmpty()) return wifi.get(0);
            if (!other.isEmpty()) return other.get(0);
        } catch (Exception ignored) { }
        try {
            WifiManager wifi = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wifi != null) {
                int ip = wifi.getConnectionInfo().getIpAddress();
                if (ip != 0) return String.format(Locale.US, "%d.%d.%d.%d", ip & 0xff, (ip >> 8) & 0xff, (ip >> 16) & 0xff, (ip >> 24) & 0xff);
            }
        } catch (Exception ignored) { }
        return "Unavailable";
    }

    private String deviceName() {
        String model = Build.MODEL == null ? "Android device" : Build.MODEL.trim();
        if (model.isEmpty()) return "Android device";
        return model;
    }

    private static String createRoomCode() {
        String alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
        String seed = UUID.randomUUID().toString().replace("-", "").toUpperCase(Locale.US);
        StringBuilder code = new StringBuilder("SYNC-");
        for (int i = 0; i < 6; i++) code.append(alphabet.charAt((seed.charAt(i) + i * 7) % alphabet.length()));
        return code.toString();
    }

    private static String safeMessage(Exception error) {
        String message = error.getMessage();
        return message == null || message.trim().isEmpty() ? "check the Wi-Fi connection" : message;
    }

    private static boolean looksLikeRoomCode(String value) {
        String compact = value.toUpperCase(Locale.US).replaceAll("[^A-Z0-9]", "");
        return compact.startsWith("SYNC") && compact.length() >= 8;
    }

    private RoomDiscovery.RoomInfo findRoom(String code) {
        String normalized = code.toUpperCase(Locale.US).replaceAll("[^A-Z0-9]", "");
        long now = System.currentTimeMillis();
        for (Map.Entry<String, RoomDiscovery.RoomInfo> entry : discoveredRooms.entrySet()) {
            RoomDiscovery.RoomInfo room = entry.getValue();
            if (now - room.seenAt > 15_000) {
                discoveredRooms.remove(entry.getKey(), room);
                continue;
            }
            if (normalizeCode(room.code).equals(normalized)) return room;
        }
        return null;
    }

    private static String normalizeCode(String code) {
        return code == null ? "" : code.toUpperCase(Locale.US).replaceAll("[^A-Z0-9]", "");
    }

    private static HostAddress parseHostAddress(String input) {
        String value = input.trim();
        if (value.startsWith("http://")) value = value.substring(7);
        if (value.startsWith("https://")) value = value.substring(8);
        int slash = value.indexOf('/');
        if (slash >= 0) value = value.substring(0, slash);
        String host = value;
        int port = DEFAULT_HTTP_PORT;
        if (value.startsWith("[")) {
            int end = value.indexOf(']');
            if (end <= 1) return null;
            host = value.substring(1, end);
            if (end + 1 < value.length() && value.charAt(end + 1) == ':') {
                try { port = Integer.parseInt(value.substring(end + 2)); } catch (NumberFormatException error) { return null; }
            }
        } else {
            int colon = value.lastIndexOf(':');
            if (colon > 0 && value.indexOf(':') == colon) {
                host = value.substring(0, colon);
                try { port = Integer.parseInt(value.substring(colon + 1)); } catch (NumberFormatException error) { return null; }
            }
        }
        if (host.isEmpty() || port < 1 || port > 65535) return null;
        if (!host.matches("[A-Za-z0-9.:-]+")) return null;
        return new HostAddress(host, port);
    }

    private static final class HostAddress {
        final String host;
        final int port;
        HostAddress(String host, int port) { this.host = host; this.port = port; }
    }

    private static String urlEncode(String value) {
        try { return URLEncoder.encode(value == null ? "" : value, "UTF-8"); }
        catch (Exception ignored) { return ""; }
    }

    private static String httpGet(String address) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(address).openConnection();
        connection.setConnectTimeout(1700);
        connection.setReadTimeout(1700);
        connection.setRequestMethod("GET");
        connection.setUseCaches(false);
        connection.setRequestProperty("Connection", "close");
        try {
            int status = connection.getResponseCode();
            InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
            if (stream == null) throw new IOException("No response from room");
            try (InputStream input = stream; java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream()) {
                byte[] buffer = new byte[4096];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    output.write(buffer, 0, count);
                    if (output.size() > 128 * 1024) throw new IOException("Room response is too large");
                }
                if (status < 200 || status >= 300) throw new IOException("Room returned " + status);
                return output.toString("UTF-8");
            }
        } finally {
            connection.disconnect();
        }
    }

    private void enterForeground() {
        createNotificationChannel();
        if (foreground) {
            updateNotification();
            return;
        }
        startForeground(NOTIFICATION_ID, buildNotification());
        foreground = true;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, getString(R.string.notification_channel), NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Playback and nearby Syncora room connection");
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) manager.createNotificationChannel(channel);
        }
    }

    private Notification buildNotification() {
        Intent openIntent = new Intent(this, MainActivity.class);
        PendingIntent content = PendingIntent.getActivity(this, 1, openIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder = new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_syncora)
                .setContentTitle(MODE_HOST.equals(mode) ? "Syncora room is live" : MODE_CLIENT.equals(mode) ? "Syncora is in sync" : "Syncora is connecting")
                .setContentText(trackTitle.isEmpty() ? getString(R.string.notification_playing) : trackTitle)
                .setContentIntent(content)
                .setOngoing(true)
                .setShowWhen(false)
                .setCategory(Notification.CATEGORY_TRANSPORT);
        Intent toggle = new Intent(this, SyncoraService.class).setAction(ACTION_TOGGLE);
        PendingIntent togglePending = PendingIntent.getService(this, 2, toggle, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        builder.addAction(new Notification.Action.Builder(playing ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play,
                playing ? "Pause" : "Play", togglePending).build());
        Intent stop = new Intent(this, SyncoraService.class).setAction(ACTION_STOP);
        PendingIntent stopPending = PendingIntent.getService(this, 3, stop, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        builder.addAction(new Notification.Action.Builder(android.R.drawable.ic_menu_close_clear_cancel, "End room", stopPending).build());
        return builder.build();
    }

    private void updateNotification() {
        if (!foreground) return;
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager != null) manager.notify(NOTIFICATION_ID, buildNotification());
    }

    private void sendStatus() {
        Intent event = new Intent(ACTION_STATUS).setPackage(getPackageName());
        event.putExtra("mode", mode);
        event.putExtra("roomCode", roomCode);
        event.putExtra("title", trackTitle);
        event.putExtra("hostName", hostName);
        event.putExtra("hostIp", hostIp);
        event.putExtra("hostPort", hostPort);
        event.putExtra("position", positionMs);
        event.putExtra("duration", durationMs);
        event.putExtra("playing", playing);
        event.putExtra("clientCount", connectedClients.size());
        event.putExtra("joiningMessage", joiningMessage);
        ArrayList<String> names = new ArrayList<>(connectedClients.values());
        Collections.sort(names, String.CASE_INSENSITIVE_ORDER);
        event.putStringArrayListExtra("devices", names);
        sendBroadcast(event);
    }

    private void sendError(String message) {
        Intent event = new Intent(ACTION_ERROR).setPackage(getPackageName());
        event.putExtra("message", message);
        sendBroadcast(event);
    }

    @Override public JSONObject syncSnapshot() {
        JSONObject state = new JSONObject();
        try {
            state.put("ok", MODE_HOST.equals(mode) && audioFile != null);
            state.put("mode", mode);
            state.put("code", roomCode);
            state.put("host", hostName);
            state.put("title", trackTitle);
            state.put("playing", playing);
            state.put("position", positionMs);
            state.put("duration", durationMs);
            state.put("version", trackVersion);
            state.put("clients", connectedClients.size());
        } catch (JSONException ignored) { }
        return state;
    }

    @Override public File currentAudioFile() { return audioFile; }

    @Override public boolean isValidRoomCode(String code) {
        return MODE_HOST.equals(mode) && code != null && roomCode.equalsIgnoreCase(code);
    }

    @Override public void onClientJoined(String remoteAddress, String name) {
        String cleanName = name == null || name.trim().isEmpty() ? "Guest" : name.trim();
        String previous = connectedClients.put(remoteAddress, cleanName);
        clientLastSeen.put(remoteAddress, System.currentTimeMillis());
        if (!cleanName.equals(previous)) main.post(this::sendStatus);
    }

    @Override public void onClientLeft(String remoteAddress) {
        connectedClients.remove(remoteAddress);
        clientLastSeen.remove(remoteAddress);
        main.post(this::sendStatus);
    }

    private void pruneConnectedClients() {
        long now = System.currentTimeMillis();
        boolean changed = false;
        for (Map.Entry<String, Long> entry : clientLastSeen.entrySet()) {
            if (now - entry.getValue() > 15_000 && clientLastSeen.remove(entry.getKey(), entry.getValue())) {
                connectedClients.remove(entry.getKey());
                changed = true;
            }
        }
        if (changed) sendStatus();
    }

    @Override public void onRemoteCommand(String command) {
        if ("toggle".equals(command)) main.post(this::togglePlayback);
    }

    @Override public RoomDiscovery.RoomInfo localRoom() {
        if (!MODE_HOST.equals(mode) || httpServer == null || audioFile == null) return null;
        return new RoomDiscovery.RoomInfo(roomCode, hostIp, hostPort, hostName, trackTitle);
    }

    @Override public void onRoomFound(RoomDiscovery.RoomInfo room) {
        main.post(() -> {
            discoveredRooms.put(room.code.toUpperCase(Locale.US), room);
            Intent event = new Intent(ACTION_FOUND).setPackage(getPackageName());
            event.putExtra("code", room.code);
            event.putExtra("ip", room.ip);
            event.putExtra("port", room.port);
            event.putExtra("name", room.name);
            event.putExtra("title", room.title);
            sendBroadcast(event);
            if (MODE_JOINING.equals(mode) && !pendingJoinCode.isEmpty()
                    && normalizeCode(room.code).equals(normalizeCode(pendingJoinCode))) {
                pendingJoinCode = "";
                startClient(room.ip, room.port, room);
            }
        });
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public void onDestroy() {
        main.removeCallbacks(statusTicker);
        main.removeCallbacks(clientSyncTicker);
        main.removeCallbacks(pendingJoinTimeout);
        stopSession();
        if (discovery != null) discovery.stop();
        io.shutdownNow();
        super.onDestroy();
    }
}
