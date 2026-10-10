package com.serena.syncora.dlna;

import android.content.Context;
import android.net.wifi.WifiManager;

import com.serena.syncora.net.NetUtil;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MulticastSocket;
import java.net.NetworkInterface;
import java.net.SocketTimeoutException;
import java.nio.charset.Charset;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.TimeZone;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** SSDP: makes this device show up as a DLNA "MediaRenderer" in controller apps (BubbleUPnP, VLC, Windows...). */
public final class SsdpAdvertiser {
    private static final String GROUP = "239.255.255.250";
    private static final int PORT = 1900;
    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final String SERVER = "Android UPnP/1.0 Syncora/0.2";

    private final Context context;
    private final String uuid;
    private final Random random = new Random();
    private volatile boolean running;
    private MulticastSocket socket;
    private Thread listener;
    private ScheduledExecutorService scheduler;
    private WifiManager.MulticastLock lock;
    private String location;

    public SsdpAdvertiser(Context context, String uuid) {
        this.context = context.getApplicationContext();
        this.uuid = uuid;
    }

    public synchronized void start(String ip, int httpPort) throws IOException {
        if (running) return;
        location = "http://" + ip + ":" + httpPort + "/dlna/desc.xml";
        acquireLock();
        try {
            MulticastSocket s = new MulticastSocket(null);
            s.setReuseAddress(true);
            s.bind(new InetSocketAddress(PORT));
            s.setTimeToLive(4);
            s.setSoTimeout(1000);
            NetworkInterface network = NetUtil.interfaceFor(ip);
            InetSocketAddress group = new InetSocketAddress(InetAddress.getByName(GROUP), PORT);
            if (network != null) {
                s.setNetworkInterface(network);
                s.joinGroup(group, network);
            } else {
                s.joinGroup(InetAddress.getByName(GROUP));
            }
            socket = s;
        } catch (IOException e) {
            releaseLock();
            throw e;
        }
        running = true;
        scheduler = Executors.newSingleThreadScheduledExecutor();
        final MulticastSocket active = socket;
        listener = new Thread(new Runnable() {
            @Override
            public void run() {
                listen(active);
            }
        }, "syncora-ssdp");
        listener.setDaemon(true);
        listener.start();
        scheduler.scheduleWithFixedDelay(new Runnable() {
            @Override
            public void run() {
                announce("ssdp:alive");
            }
        }, 0, 45, TimeUnit.SECONDS);
        scheduler.schedule(new Runnable() {
            @Override
            public void run() {
                announce("ssdp:alive");
            }
        }, 400, TimeUnit.MILLISECONDS);
    }

    public synchronized void stop() {
        if (!running) return;
        announce("ssdp:byebye");
        running = false;
        if (scheduler != null) scheduler.shutdownNow();
        scheduler = null;
        if (socket != null) socket.close();
        socket = null;
        releaseLock();
    }

    private List<String[]> targets() {
        List<String[]> list = new ArrayList<>();
        list.add(new String[]{"upnp:rootdevice", "uuid:" + uuid + "::upnp:rootdevice"});
        list.add(new String[]{"uuid:" + uuid, "uuid:" + uuid});
        list.add(new String[]{UpnpXml.DEVICE_TYPE, "uuid:" + uuid + "::" + UpnpXml.DEVICE_TYPE});
        list.add(new String[]{UpnpXml.AVT, "uuid:" + uuid + "::" + UpnpXml.AVT});
        list.add(new String[]{UpnpXml.RCS, "uuid:" + uuid + "::" + UpnpXml.RCS});
        list.add(new String[]{UpnpXml.CMS, "uuid:" + uuid + "::" + UpnpXml.CMS});
        return list;
    }

    private void announce(String nts) {
        MulticastSocket s = socket;
        if (s == null) return;
        try {
            InetAddress group = InetAddress.getByName(GROUP);
            for (String[] t : targets()) {
                byte[] bytes = notifyMessage(nts, location, t[0], t[1]).getBytes(UTF8);
                s.send(new DatagramPacket(bytes, bytes.length, group, PORT));
            }
        } catch (IOException ignored) {
            // network may be changing
        }
    }

    /** One NOTIFY advertisement ({@code ssdp:alive} or {@code ssdp:byebye}). */
    static String notifyMessage(String nts, String location, String nt, String usn) {
        StringBuilder m = new StringBuilder();
        m.append("NOTIFY * HTTP/1.1\r\nHOST: ").append(GROUP).append(':').append(PORT).append("\r\n");
        if ("ssdp:alive".equals(nts)) {
            m.append("CACHE-CONTROL: max-age=1800\r\nLOCATION: ").append(location).append("\r\n");
        }
        m.append("NT: ").append(nt).append("\r\nNTS: ").append(nts).append("\r\nSERVER: ").append(SERVER)
                .append("\r\nUSN: ").append(usn).append("\r\n\r\n");
        return m.toString();
    }

    /** The unicast answer to an M-SEARCH. */
    static String searchResponse(String location, String st, String usn, String httpDate) {
        return "HTTP/1.1 200 OK\r\nCACHE-CONTROL: max-age=1800\r\nDATE: " + httpDate + "\r\nEXT:\r\nLOCATION: " + location
                + "\r\nSERVER: " + SERVER + "\r\nST: " + st + "\r\nUSN: " + usn + "\r\n\r\n";
    }

    private void listen(MulticastSocket s) {
        byte[] buffer = new byte[2048];
        while (running) {
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
            try {
                s.receive(packet);
            } catch (SocketTimeoutException timeout) {
                continue;
            } catch (IOException e) {
                return;
            }
            String text = new String(packet.getData(), 0, packet.getLength(), UTF8);
            if (!text.startsWith("M-SEARCH")) continue;
            String st = header(text, "ST");
            String man = header(text, "MAN");
            if (st == null || man == null || !man.toLowerCase(Locale.US).contains("ssdp:discover")) continue;
            int mx = 1;
            try {
                String v = header(text, "MX");
                if (v != null) mx = Math.max(1, Math.min(5, Integer.parseInt(v.trim())));
            } catch (NumberFormatException ignored) {
                mx = 1;
            }
            respond(packet.getAddress(), packet.getPort(), st.trim(), mx);
        }
    }

    private void respond(final InetAddress address, final int port, final String st, int mx) {
        ScheduledExecutorService exec = scheduler;
        if (exec == null) return;
        for (final String[] target : targets()) {
            if (!"ssdp:all".equals(st) && !st.equals(target[0])) continue;
            long delay = random.nextInt(Math.max(100, Math.min(mx * 1000, 1500)));
            try {
                exec.schedule(new Runnable() {
                    @Override
                    public void run() {
                        MulticastSocket s = socket;
                        if (s == null) return;
                        SimpleDateFormat format = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US);
                        format.setTimeZone(TimeZone.getTimeZone("GMT"));
                        byte[] bytes = searchResponse(location, target[0], target[1], format.format(new Date())).getBytes(UTF8);
                        try {
                            s.send(new DatagramPacket(bytes, bytes.length, address, port));
                        } catch (IOException ignored) {
                            // controller went away
                        }
                    }
                }, delay, TimeUnit.MILLISECONDS);
            } catch (RuntimeException ignored) {
                // scheduler already shut down
            }
        }
    }

    static String header(String message, String name) {
        for (String line : message.split("\r\n")) {
            int colon = line.indexOf(':');
            if (colon > 0 && line.substring(0, colon).trim().equalsIgnoreCase(name)) return line.substring(colon + 1).trim();
        }
        return null;
    }

    private void acquireLock() {
        try {
            WifiManager wifi = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
            if (wifi != null) {
                lock = wifi.createMulticastLock("syncora-ssdp");
                lock.setReferenceCounted(false);
                lock.acquire();
            }
        } catch (RuntimeException e) {
            android.util.Log.d("Syncora", "multicast lock unavailable", e);
        }
    }

    private void releaseLock() {
        try {
            if (lock != null && lock.isHeld()) lock.release();
        } catch (RuntimeException ignored) {
            // nothing to do
        }
        lock = null;
    }
}
