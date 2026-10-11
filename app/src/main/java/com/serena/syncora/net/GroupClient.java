package com.serena.syncora.net;

import com.serena.syncora.core.FrameCodec;
import com.serena.syncora.core.PcmChunk;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** A speaker's connection to a room's host. All callbacks run on the connection thread. */
public final class GroupClient {

    public interface Listener {
        void onConnected(String welcomeJson);

        void onState(String json);

        void onAudio(PcmChunk chunk);

        void onFlush(int epoch);

        void onSet(String json);

        void onClosed(String reason, boolean refused);

        /** Cumulative audio transport counters, sampled at most once per second. */
        default void onTransportMetrics(long packetsReceived, long packetGaps) {
        }
    }

    private final Listener listener;
    private final Object writeLock = new Object();
    /** Socket writes must never happen on the caller's (possibly main) thread. */
    private final ExecutorService sender = Executors.newSingleThreadExecutor();
    private volatile boolean closedByUs;
    private volatile Socket socket;
    private volatile OutputStream out;
    private volatile long audioPacketsReceived;
    private volatile long packetGaps;
    private int lastAudioEpoch = Integer.MIN_VALUE;
    private int lastAudioSequence;
    private boolean haveAudioSequence;
    private long lastMetricsCallbackNanos;
    private Thread thread;

    public GroupClient(Listener listener) {
        this.listener = listener;
    }

    public synchronized void connect(final String host, final int port, final String code, final String id, final String name) {
        if (thread != null) throw new IllegalStateException("already started");
        thread = new Thread(new Runnable() {
            @Override
            public void run() {
                runConnection(host, port, code, id, name);
            }
        }, "syncora-group-client");
        thread.setDaemon(true);
        thread.start();
    }

    public boolean isConnected() {
        Socket s = socket;
        return s != null && s.isConnected() && !s.isClosed() && !closedByUs;
    }

    public long audioPacketsReceived() {
        return audioPacketsReceived;
    }

    public long packetGaps() {
        return packetGaps;
    }

    public void sendHello(String json) {
        send(FrameCodec.T_HELLO, json);
    }

    public void sendState(String json) {
        send(FrameCodec.T_CLIENT_STATE, json);
    }

    public void sendCommand(JSONObject command) {
        send(FrameCodec.T_COMMAND, command.toString());
    }

    private void send(int type, final String json) {
        final int frameType = type;
        try {
            sender.execute(new Runnable() {
                @Override
                public void run() {
                    OutputStream o = out;
                    if (o == null || closedByUs) return;
                    byte[] frame = FrameCodec.encodeText(frameType, json);
                    synchronized (writeLock) {
                        try {
                            o.write(frame);
                            o.flush();
                        } catch (IOException ignored) {
                            // the read side notices the broken connection
                        }
                    }
                }
            });
        } catch (RuntimeException ignored) {
            // already shut down
        }
    }

    public void close() {
        closedByUs = true;
        sender.shutdown();
        final Socket s = socket;
        if (s != null) {
            // closing from a helper thread keeps this safe to call from the UI thread
            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        s.close();
                    } catch (IOException ignored) {
                        // nothing to do
                    }
                }
            }, "syncora-client-close").start();
        }
    }

    private void recordAudio(PcmChunk chunk) {
        if (chunk.epoch != lastAudioEpoch) {
            lastAudioEpoch = chunk.epoch;
            haveAudioSequence = false;
        }
        if (haveAudioSequence) {
            long distance = (long) chunk.seq - (long) lastAudioSequence;
            // A source restart can reset seq without changing epoch on a gapless track boundary; that is not a gap.
            if (distance > 1 && distance < 1_000_000L) packetGaps += distance - 1;
        }
        lastAudioSequence = chunk.seq;
        haveAudioSequence = true;
        audioPacketsReceived++;
        long now = System.nanoTime();
        if (lastMetricsCallbackNanos == 0 || now - lastMetricsCallbackNanos >= 1_000_000_000L) {
            lastMetricsCallbackNanos = now;
            listener.onTransportMetrics(audioPacketsReceived, packetGaps);
        }
    }

    private void runConnection(String host, int port, String code, String id, String name) {
        String reason = "Connection lost";
        boolean refused = false;
        Socket s = new Socket();
        try {
            s.connect(new InetSocketAddress(host, port), 5000);
            s.setTcpNoDelay(true);
            s.setKeepAlive(true);
            s.setSoTimeout(10_000);
            s.setReceiveBufferSize(256 * 1024);
            socket = s;
            if (closedByUs) {
                s.close();
                return;
            }
            out = s.getOutputStream();
            InputStream in = new BufferedInputStream(s.getInputStream(), 64 * 1024);
            String request = "GET /stream?code=" + NetUtil.urlEncode(code) + "&id=" + NetUtil.urlEncode(id) + "&name="
                    + NetUtil.urlEncode(name) + "&v=2 HTTP/1.1\r\nHost: " + host + ":" + port
                    + "\r\nUser-Agent: Syncora/0.2\r\nConnection: keep-alive\r\n\r\n";
            synchronized (writeLock) {
                out.write(request.getBytes(MiniHttpServer.UTF8));
                out.flush();
            }
            String status = MiniHttpServer.readLine(in);
            if (status == null || !status.contains(" 200")) {
                refused = status != null && status.contains(" 403");
                throw new IOException(refused ? "Wrong room code" : "The player refused the connection");
            }
            String line;
            while ((line = MiniHttpServer.readLine(in)) != null && !line.isEmpty()) {
                // skip headers
            }
            FrameCodec.Frame frame;
            boolean ended = false;
            reason = "The player closed the connection";
            while (!ended && !closedByUs && (frame = FrameCodec.read(in)) != null) {
                switch (frame.type) {
                    case FrameCodec.T_WELCOME:
                        listener.onConnected(frame.text());
                        break;
                    case FrameCodec.T_STATE:
                        listener.onState(frame.text());
                        break;
                    case FrameCodec.T_AUDIO: {
                        PcmChunk chunk = FrameCodec.decodeAudio(frame.payload);
                        recordAudio(chunk);
                        listener.onAudio(chunk);
                        break;
                    }
                    case FrameCodec.T_FLUSH: {
                        int newEpoch = FrameCodec.decodeFlush(frame.payload);
                        if (newEpoch != lastAudioEpoch) {
                            lastAudioEpoch = newEpoch;
                            haveAudioSequence = false;
                        }
                        listener.onFlush(newEpoch);
                        break;
                    }
                    case FrameCodec.T_SET:
                        listener.onSet(frame.text());
                        break;
                    case FrameCodec.T_PING:
                        send(FrameCodec.T_PONG, "");
                        break;
                    case FrameCodec.T_BYE:
                        reason = byeReason(frame.text());
                        ended = true;
                        break;
                    default:
                        break;
                }
            }
        } catch (SocketTimeoutException e) {
            reason = "The player stopped responding";
        } catch (IOException e) {
            if (closedByUs) return;
            String message = e.getMessage();
            reason = message == null || message.isEmpty() ? "Could not reach the player" : message;
        } finally {
            try {
                s.close();
            } catch (IOException ignored) {
                // nothing to do
            }
            out = null;
            sender.shutdown();
            if (!closedByUs) listener.onClosed(reason, refused);
        }
    }

    private static String byeReason(String json) {
        try {
            String r = new JSONObject(json).optString("reason", "");
            return r.isEmpty() ? "The player ended the session" : "The player ended the session: " + r;
        } catch (JSONException e) {
            return "The player ended the session";
        }
    }
}
