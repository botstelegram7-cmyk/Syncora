package com.serena.syncora.net;

import com.serena.syncora.core.ClockSync;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;

/** Measures the clock offset to the host with a burst of probes at start and a few probes every interval. */
public final class TimeClient {
    public interface Listener {
        void onSample(ClockSync sync);
    }

    private final InetAddress host;
    private final int port;
    private final ClockSync sync;
    private final Listener listener;
    private final Object lock = new Object();
    private volatile boolean running;
    private volatile long intervalMs = 10_000;
    private int pendingProbes = 14;
    private Thread thread;
    private DatagramSocket socket;

    public TimeClient(InetAddress host, int port, ClockSync sync, Listener listener) {
        this.host = host;
        this.port = port;
        this.sync = sync;
        this.listener = listener;
    }

    public synchronized void start() {
        if (running) return;
        running = true;
        thread = new Thread(new Runnable() {
            @Override
            public void run() {
                loop();
            }
        }, "syncora-time-client");
        thread.setDaemon(true);
        thread.start();
    }

    public synchronized void stop() {
        running = false;
        if (socket != null) socket.close();
        synchronized (lock) {
            lock.notifyAll();
        }
    }

    public void setIntervalSeconds(int seconds) {
        intervalMs = Math.max(2, seconds) * 1000L;
        synchronized (lock) {
            lock.notifyAll();
        }
    }

    /** Immediately take a fresh set of measurements. */
    public void burst() {
        synchronized (lock) {
            pendingProbes = 14;
            lock.notifyAll();
        }
    }

    private void loop() {
        try {
            DatagramSocket s = new DatagramSocket();
            synchronized (this) {
                socket = s;
                if (!running) {
                    s.close();
                    return;
                }
            }
            s.setSoTimeout(120);
            byte[] reply = new byte[64];
            int seq = 0;
            long nextRegular = System.nanoTime() + intervalMs * 1_000_000L;
            while (running) {
                int probes;
                synchronized (lock) {
                    probes = pendingProbes;
                    if (probes == 0) {
                        long waitMs = (nextRegular - System.nanoTime()) / 1_000_000L;
                        if (waitMs > 0) {
                            lock.wait(Math.min(waitMs, 1000));
                            continue;
                        }
                        pendingProbes = 4;
                        nextRegular = System.nanoTime() + intervalMs * 1_000_000L;
                        continue;
                    }
                    pendingProbes--;
                }
                long t0 = System.nanoTime();
                ByteBuffer out = ByteBuffer.allocate(13);
                out.put(TimeServer.REQUEST).putInt(++seq).putLong(t0);
                try {
                    s.send(new DatagramPacket(out.array(), out.capacity(), host, port));
                    long deadline = t0 + 150_000_000L;
                    while (running && System.nanoTime() < deadline) {
                        DatagramPacket packet = new DatagramPacket(reply, reply.length);
                        try {
                            s.receive(packet);
                        } catch (SocketTimeoutException timeout) {
                            break;
                        }
                        long t3 = System.nanoTime();
                        if (packet.getLength() < 29 || reply[0] != TimeServer.REPLY) continue;
                        ByteBuffer in = ByteBuffer.wrap(reply, 1, 28);
                        int echoedSeq = in.getInt();
                        long echoedT0 = in.getLong();
                        long t1 = in.getLong();
                        long t2 = in.getLong();
                        if (echoedSeq != seq || echoedT0 != t0) continue;
                        sync.addSample(t0, t1, t2, t3);
                        if (listener != null) listener.onSample(sync);
                        break;
                    }
                } catch (IOException e) {
                    if (!running) return;
                }
                long gap = 35_000_000L - (System.nanoTime() - t0);
                if (gap > 0) Thread.sleep(gap / 1_000_000L);
            }
        } catch (InterruptedException ignored) {
            // stopping
        } catch (IOException e) {
            android.util.Log.w("Syncora", "time client failed", e);
        }
    }
}
