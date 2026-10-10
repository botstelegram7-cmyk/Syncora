package com.serena.syncora.net;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;

/** Answers clock probes from speakers: receives T|seq|t0 and replies R|seq|t0|t1|t2 (host monotonic nanos). */
public final class TimeServer {
    static final byte REQUEST = 'T';
    static final byte REPLY = 'R';

    private volatile boolean running;
    private DatagramSocket socket;
    private Thread thread;

    public synchronized int start() throws IOException {
        if (running) return socket.getLocalPort();
        socket = new DatagramSocket(0);
        socket.setSoTimeout(400);
        running = true;
        final DatagramSocket s = socket;
        thread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO);
                } catch (Throwable ignored) {
                    // not on Android
                }
                loop(s);
            }
        }, "syncora-time-server");
        thread.setDaemon(true);
        thread.start();
        return s.getLocalPort();
    }

    public synchronized void stop() {
        running = false;
        if (socket != null) socket.close();
        socket = null;
    }

    private void loop(DatagramSocket s) {
        byte[] buffer = new byte[64];
        while (running) {
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
            try {
                s.receive(packet);
                long t1 = System.nanoTime();
                if (packet.getLength() < 13 || buffer[0] != REQUEST) continue;
                ByteBuffer in = ByteBuffer.wrap(buffer, 1, 12);
                int seq = in.getInt();
                long t0 = in.getLong();
                ByteBuffer out = ByteBuffer.allocate(29);
                out.put(REPLY).putInt(seq).putLong(t0).putLong(t1);
                out.putLong(System.nanoTime());
                s.send(new DatagramPacket(out.array(), out.capacity(), packet.getAddress(), packet.getPort()));
            } catch (SocketTimeoutException ignored) {
                // check running
            } catch (SocketException e) {
                if (running) android.util.Log.w("Syncora", "time server socket error", e);
                return;
            } catch (IOException e) {
                // keep serving
            }
        }
    }
}
