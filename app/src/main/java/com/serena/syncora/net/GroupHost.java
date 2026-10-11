package com.serena.syncora.net;

import com.serena.syncora.core.FrameCodec;
import com.serena.syncora.core.PcmChunk;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** The host's side of a room: accepts speakers, fans audio and state out to them and receives their reports. */
public final class GroupHost {

    public interface Listener {
        /** Room code check for a new speaker. */
        boolean acceptClient(String code);

        /** JSON sent to a speaker right after it connected. */
        String welcomeJson(ClientInfo client);

        /** Current session state, sent to a speaker right after it connected. */
        String stateJson();

        void onClientsChanged();

        /** A speaker (or remote control) asked for something. */
        void onClientCommand(ClientInfo client, JSONObject command);
    }

    public static final class ClientInfo {
        public final String id;
        public final String address;
        public final long connectedAt = System.currentTimeMillis();
        public volatile String name;
        public volatile float volume = 1f;
        public volatile boolean muted;
        public volatile String channel = "stereo";
        public volatile int offsetMs;
        public volatile int syncErrMs;
        public volatile String status = "connecting";
        public volatile long lastSeen = System.currentTimeMillis();

        private final Socket socket;
        private final LinkedBlockingQueue<byte[]> queue = new LinkedBlockingQueue<>();
        private final AtomicInteger queuedBytes = new AtomicInteger();
        private volatile boolean closed;

        ClientInfo(String id, String address, String name, Socket socket) {
            this.id = id;
            this.address = address;
            this.name = name;
            this.socket = socket;
        }

        void enqueue(byte[] frame) {
            if (closed) return;
            queuedBytes.addAndGet(frame.length);
            queue.offer(frame);
        }

        /** Removes audio that has not been written yet (used when the timeline is flushed). */
        void dropQueuedAudio() {
            Iterator<byte[]> it = queue.iterator();
            while (it.hasNext()) {
                byte[] frame = it.next();
                if (frame[0] == (byte) FrameCodec.T_AUDIO && queue.remove(frame)) queuedBytes.addAndGet(-frame.length);
            }
        }

        int queuedBytes() {
            return queuedBytes.get();
        }

        void close() {
            closed = true;
            try {
                socket.close();
            } catch (IOException ignored) {
                // nothing to do
            }
        }

        public boolean isClosed() {
            return closed;
        }
    }

    /** High-water mark for pending audio (about 3.5 s of CD-quality stereo at 44.1 kHz). */
    private static final int MAX_QUEUED_BYTES = 640 * 1024;

    private final Listener listener;
    private final Map<String, ClientInfo> clients = new ConcurrentHashMap<>();

    public GroupHost(Listener listener) {
        this.listener = listener;
    }

    public List<ClientInfo> clients() {
        List<ClientInfo> list = new ArrayList<>(clients.values());
        java.util.Collections.sort(list, new java.util.Comparator<ClientInfo>() {
            @Override
            public int compare(ClientInfo a, ClientInfo b) {
                return Long.compare(a.connectedAt, b.connectedAt);
            }
        });
        return list;
    }

    public int clientCount() {
        return clients.size();
    }

    public ClientInfo client(String id) {
        return id == null ? null : clients.get(id);
    }

    /** HTTP route handler for {@code GET /stream}. Blocks for the lifetime of the connection. */
    public boolean handleStream(MiniHttpServer.Request request, Socket socket, java.io.InputStream in, OutputStream out)
            throws IOException {
        String code = request.query.get("code");
        if (!listener.acceptClient(code)) {
            MiniHttpServer.respondText(out, 403, "Forbidden", "Wrong room code");
            return false;
        }
        String id = request.query.get("id");
        if (id == null || id.isEmpty()) id = request.remoteIp + ":" + request.remotePort;
        String name = request.query.get("name");
        if (name == null || name.trim().isEmpty()) name = "Speaker";
        final ClientInfo client = new ClientInfo(id, request.remoteIp, name.trim(), socket);

        out.write(("HTTP/1.1 200 OK\r\nContent-Type: application/x-syncora-stream\r\nCache-Control: no-store\r\n"
                + "Connection: close\r\n\r\n").getBytes(MiniHttpServer.UTF8));
        out.flush();
        socket.setSoTimeout(15_000);
        socket.setKeepAlive(true);
        socket.setSendBufferSize(512 * 1024);

        client.enqueue(FrameCodec.encodeText(FrameCodec.T_WELCOME, listener.welcomeJson(client)));
        client.enqueue(FrameCodec.encodeText(FrameCodec.T_STATE, listener.stateJson()));
        ClientInfo previous = clients.put(id, client);
        if (previous != null) previous.close();

        Thread writer = new Thread(new Runnable() {
            @Override
            public void run() {
                writeLoop(client);
            }
        }, "syncora-host-writer");
        writer.setDaemon(true);
        writer.start();
        listener.onClientsChanged();

        try {
            FrameCodec.Frame frame;
            while (!client.closed && (frame = FrameCodec.read(in)) != null) {
                client.lastSeen = System.currentTimeMillis();
                handleFrame(client, frame);
            }
        } catch (IOException ignored) {
            // speaker went away
        } finally {
            client.close();
            if (clients.remove(id, client)) listener.onClientsChanged();
        }
        return true;
    }

    private void writeLoop(ClientInfo client) {
        long lastWrite = System.currentTimeMillis();
        try {
            OutputStream out = client.socket.getOutputStream();
            while (!client.closed) {
                byte[] frame = client.queue.poll(1000, TimeUnit.MILLISECONDS);
                if (frame == null) {
                    if (System.currentTimeMillis() - lastWrite > 2000) {
                        out.write(FrameCodec.encode(FrameCodec.T_PING, new byte[0]));
                        out.flush();
                        lastWrite = System.currentTimeMillis();
                    }
                    continue;
                }
                client.queuedBytes.addAndGet(-frame.length);
                out.write(frame);
                lastWrite = System.currentTimeMillis();
            }
        } catch (IOException | InterruptedException ignored) {
            // closed
        } finally {
            client.close();
        }
    }

    private void handleFrame(ClientInfo client, FrameCodec.Frame frame) {
        switch (frame.type) {
            case FrameCodec.T_HELLO:
            case FrameCodec.T_CLIENT_STATE: {
                JSONObject json = parse(frame.text());
                if (json == null) return;
                applyClientFields(client, json);
                if (frame.type == FrameCodec.T_HELLO) listener.onClientsChanged();
                break;
            }
            case FrameCodec.T_COMMAND: {
                JSONObject json = parse(frame.text());
                if (json != null) listener.onClientCommand(client, json);
                break;
            }
            case FrameCodec.T_PING:
                client.enqueue(FrameCodec.encode(FrameCodec.T_PONG, new byte[0]));
                break;
            case FrameCodec.T_PONG:
                // The peer answered our keep-alive probe; lastSeen was refreshed by handleStream.
                break;
            default:
                break;
        }
    }

    static void applyClientFields(ClientInfo client, JSONObject json) {
        String name = json.optString("name", "");
        if (!name.trim().isEmpty()) client.name = name.trim();
        if (json.has("volume")) client.volume = (float) Math.max(0, Math.min(1, json.optDouble("volume", client.volume)));
        if (json.has("muted")) client.muted = json.optBoolean("muted", client.muted);
        if (json.has("channel")) client.channel = json.optString("channel", client.channel);
        if (json.has("offsetMs")) client.offsetMs = json.optInt("offsetMs", client.offsetMs);
        if (json.has("syncErrMs")) client.syncErrMs = json.optInt("syncErrMs", client.syncErrMs);
        if (json.has("status")) client.status = json.optString("status", client.status);
    }

    private static JSONObject parse(String text) {
        try {
            return new JSONObject(text);
        } catch (JSONException e) {
            return null;
        }
    }

    // ---------------------------------------------------------------- fan-out

    public void broadcastAudio(PcmChunk chunk) {
        if (clients.isEmpty()) return;
        byte[] frame = FrameCodec.encodeAudio(chunk);
        for (ClientInfo client : clients.values()) {
            if (client.queuedBytes() > MAX_QUEUED_BYTES) {
                // The audio chunks are timestamped independently, so queued audio that is already late is
                // less useful than keeping the room connection alive. Drop that backlog and continue with
                // the live edge; ScheduledPlayer will skip any stale chunks already in the socket buffer.
                client.dropQueuedAudio();
                if (client.queuedBytes() > MAX_QUEUED_BYTES) {
                    // Control frames should never reach this limit, but keep a hard bound if they do.
                    client.close();
                    continue;
                }
            }
            client.enqueue(frame);
        }
    }

    public void broadcastFlush(int epoch) {
        byte[] frame = FrameCodec.encodeFlush(epoch);
        for (ClientInfo client : clients.values()) {
            client.dropQueuedAudio();
            client.enqueue(frame);
        }
    }

    public void broadcastState(String json) {
        if (clients.isEmpty()) return;
        byte[] frame = FrameCodec.encodeText(FrameCodec.T_STATE, json);
        for (ClientInfo client : clients.values()) client.enqueue(frame);
    }

    public void sendSet(String clientId, String json) {
        ClientInfo client = clients.get(clientId);
        if (client != null) client.enqueue(FrameCodec.encodeText(FrameCodec.T_SET, json));
    }

    public void sendToAll(int type, String json) {
        byte[] frame = FrameCodec.encodeText(type, json);
        for (ClientInfo client : clients.values()) client.enqueue(frame);
    }

    public void kick(String clientId, String reason) {
        ClientInfo client = clients.get(clientId);
        if (client == null) return;
        try {
            client.enqueue(FrameCodec.encodeText(FrameCodec.T_BYE, new JSONObject().put("reason", reason).toString()));
        } catch (JSONException ignored) {
            // cannot happen
        }
        final ClientInfo target = client;
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Thread.sleep(250);
                } catch (InterruptedException ignored) {
                    // closing anyway
                }
                target.close();
            }
        }, "syncora-kick").start();
    }

    public void shutdown() {
        for (ClientInfo client : clients.values()) client.close();
        clients.clear();
    }
}
