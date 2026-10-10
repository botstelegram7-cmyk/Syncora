package com.serena.syncora;

import android.content.Context;
import android.net.wifi.WifiManager;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/** Broadcast-based room discovery. It works on a Wi-Fi router and on a phone hotspot without internet. */
final class RoomDiscovery {
    static final int DISCOVERY_PORT = 8788;
    private static final String REQUEST = "SYNCORA_DISCOVER|1";

    static final class RoomInfo {
        final String code;
        final String ip;
        final int port;
        final String name;
        final String title;
        final long seenAt;

        RoomInfo(String code, String ip, int port, String name, String title) {
            this.code = code;
            this.ip = ip;
            this.port = port;
            this.name = name;
            this.title = title;
            this.seenAt = System.currentTimeMillis();
        }
    }

    interface Listener {
        RoomInfo localRoom();
        void onRoomFound(RoomInfo room);
    }

    private final Context context;
    private final Listener listener;
    private volatile boolean running;
    private volatile boolean probing;
    private volatile long generation;
    private volatile DatagramSocket activeSocket;
    private Thread worker;
    private WifiManager.MulticastLock multicastLock;

    RoomDiscovery(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
    }

    synchronized void start(boolean shouldProbe) {
        probing = shouldProbe;
        if (running) return;
        running = true;
        long token = ++generation;
        acquireMulticastLock();
        worker = new Thread(() -> run(token), "syncora-room-discovery");
        worker.start();
    }

    void setProbing(boolean shouldProbe) {
        probing = shouldProbe;
    }

    synchronized void stop() {
        running = false;
        generation++;
        Thread thread = worker;
        worker = null;
        DatagramSocket socket = activeSocket;
        activeSocket = null;
        if (socket != null) socket.close();
        if (thread != null) thread.interrupt();
        releaseMulticastLock();
    }

    private void run(long token) {
        DatagramSocket socket = null;
        try {
            socket = new DatagramSocket(null);
            socket.setReuseAddress(true);
            socket.setBroadcast(true);
            socket.bind(new InetSocketAddress(DISCOVERY_PORT));
            activeSocket = socket;
            socket.setSoTimeout(350);
            byte[] buffer = new byte[2048];
            long lastProbe = 0;

            while (running && token == generation) {
                long now = System.currentTimeMillis();
                if (probing && now - lastProbe >= 1600) {
                    sendProbe(socket);
                    lastProbe = now;
                }

                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                try {
                    socket.receive(packet);
                    String message = new String(packet.getData(), packet.getOffset(), packet.getLength(), StandardCharsets.UTF_8);
                    if (REQUEST.equals(message)) {
                        replyWithLocalRoom(socket, packet);
                    } else if (message.startsWith("SYNCORA_ROOM|")) {
                        parseRoom(message, packet.getAddress());
                    }
                } catch (java.net.SocketTimeoutException ignored) {
                    // Wake regularly to notice stop/probe changes.
                }
            }
        } catch (SocketException error) {
            if (running && token == generation) android.util.Log.w("Syncora", "LAN discovery socket failed", error);
        } catch (IOException error) {
            if (running && token == generation) android.util.Log.w("Syncora", "LAN discovery failed", error);
        } finally {
            if (socket != null) socket.close();
            synchronized (this) {
                if (token == generation) {
                    if (worker == Thread.currentThread()) worker = null;
                    activeSocket = null;
                    running = false;
                    releaseMulticastLock();
                }
            }
        }
    }

    private void sendProbe(DatagramSocket socket) {
        byte[] bytes = REQUEST.getBytes(StandardCharsets.UTF_8);
        Set<InetAddress> targets = new HashSet<>();
        try {
            targets.add(InetAddress.getByName("255.255.255.255"));
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            if (interfaces != null) {
                for (NetworkInterface network : Collections.list(interfaces)) {
                    try {
                        if (!network.isUp() || network.isLoopback()) continue;
                        network.getInterfaceAddresses().forEach(address -> {
                            if (address.getBroadcast() != null) targets.add(address.getBroadcast());
                        });
                    } catch (Exception ignored) { }
                }
            }
        } catch (Exception error) {
            android.util.Log.d("Syncora", "Unable to enumerate Wi-Fi broadcast addresses", error);
        }

        for (InetAddress target : targets) {
            try {
                socket.send(new DatagramPacket(bytes, bytes.length, target, DISCOVERY_PORT));
            } catch (IOException ignored) { }
        }
    }

    private void replyWithLocalRoom(DatagramSocket socket, DatagramPacket request) {
        RoomInfo room = listener.localRoom();
        if (room == null) return;
        String message = "SYNCORA_ROOM|1|" + room.code + "|" + room.port + "|"
                + encode(room.name) + "|" + encode(room.title);
        byte[] bytes = message.getBytes(StandardCharsets.UTF_8);
        try {
            socket.send(new DatagramPacket(bytes, bytes.length, request.getAddress(), request.getPort()));
        } catch (IOException error) {
            android.util.Log.d("Syncora", "Unable to answer room discovery", error);
        }
    }

    private void parseRoom(String message, InetAddress address) {
        String[] parts = message.split("\\|", 6);
        if (parts.length < 6 || !"1".equals(parts[1])) return;
        try {
            int port = Integer.parseInt(parts[3]);
            String ip = address.getHostAddress();
            if (ip == null || ip.isEmpty() || ip.contains(":")) return;
            RoomInfo room = new RoomInfo(parts[2].toUpperCase(Locale.US), ip, port, decode(parts[4]), decode(parts[5]));
            RoomInfo local = listener.localRoom();
            if (local == null || !local.code.equalsIgnoreCase(room.code)) listener.onRoomFound(room);
        } catch (NumberFormatException ignored) { }
    }

    private void acquireMulticastLock() {
        try {
            WifiManager wifi = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
            if (wifi != null) {
                multicastLock = wifi.createMulticastLock("syncora-discovery");
                multicastLock.setReferenceCounted(false);
                multicastLock.acquire();
            }
        } catch (Exception error) {
            android.util.Log.d("Syncora", "Multicast lock is unavailable", error);
        }
    }

    private synchronized void releaseMulticastLock() {
        try {
            if (multicastLock != null && multicastLock.isHeld()) multicastLock.release();
        } catch (Exception ignored) { }
        multicastLock = null;
    }

    private static String encode(String value) {
        try { return URLEncoder.encode(value == null ? "" : value, "UTF-8"); }
        catch (Exception ignored) { return ""; }
    }

    private static String decode(String value) {
        try { return URLDecoder.decode(value, "UTF-8"); }
        catch (Exception ignored) { return value; }
    }
}
