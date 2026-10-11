package com.serena.syncora.youtube.tap;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Arrays;

/**
 * One TAP connection to a TypeTube cluster, following the typetube package's {@code tap/connection.ts}:
 * a 12-byte frame header, an ECDH negotiation that turns on AES-256-GCM, then RPC calls
 * ({@code method + payload}) one at a time. Responses are not compressed (the client never asks for it).
 */
final class TapConnection implements Closeable {

    static final String DEFAULT_HOST = "sg.clusters.typetube.xyz";
    static final int DEFAULT_PORT = 8443;

    /** Frame header: origin u32, intent u8, flags u8, traceId u16, length u32. */
    private static final int HEADER = 12;
    private static final int ORIGIN = 0x54545301; // "TTS\x01" — the typetube client origin
    private static final int MAX_PAYLOAD = 1024 * 1024;

    private static final int INTENT_NEGOTIATE = 0x01;
    private static final int INTENT_GOAWAY = 0x07;
    private static final int INTENT_RPC_CALL = 0x10;
    private static final int INTENT_DISCONNECT = 0x7e;

    private static final int FLAG_REQUEST = 0x00;
    private static final int FLAG_ERROR = 0x02;
    private static final int FLAG_COMPRESSED = 0x04;
    private static final int FLAG_ENCRYPTED = 0x08;
    private static final int TAG_BYTES = 16;

    private final String host;
    private final int port;
    private final int timeoutMs;

    private Socket socket;
    private DataInputStream in;
    private DataOutputStream out;
    private TapCrypto.Session session;
    private int traceCounter;

    TapConnection(String host, int port, int timeoutMs) {
        this.host = host;
        this.port = port;
        this.timeoutMs = timeoutMs;
    }

    synchronized boolean isConnected() {
        return socket != null && socket.isConnected() && !socket.isClosed() && session != null;
    }

    synchronized void connect() throws IOException {
        if (isConnected()) return;
        close();
        Socket s = new Socket();
        try {
            s.connect(new InetSocketAddress(host, port), timeoutMs);
            s.setTcpNoDelay(true);
            s.setSoTimeout(timeoutMs);
            in = new DataInputStream(new BufferedInputStream(s.getInputStream(), 16 * 1024));
            out = new DataOutputStream(new BufferedOutputStream(s.getOutputStream(), 16 * 1024));
            socket = s;
            negotiate();
        } catch (IOException e) {
            close();
            throw e;
        }
    }

    /** One RPC round trip: the response payload for {@code method}. */
    synchronized byte[] invoke(String method, byte[] payload) throws IOException {
        if (!isConnected()) connect();
        byte[] name = method.getBytes("UTF-8");
        if (name.length == 0 || name.length > 255) throw new IOException("Bad TAP method name");
        byte[] packet = new byte[1 + name.length + payload.length];
        packet[0] = (byte) name.length;
        System.arraycopy(name, 0, packet, 1, name.length);
        System.arraycopy(payload, 0, packet, 1 + name.length, payload.length);
        int trace = nextTrace();
        // No FLAG_ACCEPT_COMPRESSED: this client reads plain protobuf responses only.
        writeFrame(INTENT_RPC_CALL, trace, FLAG_REQUEST, packet);
        while (true) {
            Frame frame = readFrame();
            if (frame.intent == INTENT_GOAWAY) {
                close();
                throw new IOException("The TypeTube server is going away");
            }
            if (frame.intent == INTENT_DISCONNECT) {
                String why = new String(frame.payload, "UTF-8");
                close();
                throw new IOException("The TypeTube server closed the connection" + (why.isEmpty() ? "" : ": " + why));
            }
            if (frame.traceId != trace) continue; // not for us (a ping reply, for example)
            if ((frame.flags & FLAG_COMPRESSED) != 0) {
                throw new IOException("The TypeTube server sent a compressed response this version cannot read");
            }
            if ((frame.flags & FLAG_ERROR) != 0) {
                throw new IOException("TypeTube: " + new String(frame.payload, "UTF-8"));
            }
            return frame.payload;
        }
    }

    // ================================================================= framing

    private static final class Frame {
        final int intent;
        final int flags;
        final int traceId;
        final byte[] payload;

        Frame(int intent, int flags, int traceId, byte[] payload) {
            this.intent = intent;
            this.flags = flags;
            this.traceId = traceId;
            this.payload = payload;
        }
    }

    private void writeFrame(int intent, int traceId, int flags, byte[] payload) throws IOException {
        byte[] data = payload;
        if (session != null) {
            flags |= FLAG_ENCRYPTED;
            // The AAD is the final header, whose length covers the ciphertext and its 16-byte tag.
            byte[] header = header(intent, flags, traceId, payload.length + TAG_BYTES);
            data = session.encrypt(payload, header);
            out.write(header);
        } else {
            out.write(header(intent, flags, traceId, payload.length));
        }
        out.write(data);
        out.flush();
    }

    private Frame readFrame() throws IOException {
        byte[] header = new byte[HEADER];
        in.readFully(header);
        int intent = header[4] & 0xff;
        int flags = header[5] & 0xff;
        int traceId = ((header[6] & 0xff) << 8) | (header[7] & 0xff);
        int length = ((header[8] & 0xff) << 24) | ((header[9] & 0xff) << 16) | ((header[10] & 0xff) << 8) | (header[11] & 0xff);
        if (length < 0 || length > MAX_PAYLOAD) throw new IOException("Bad TAP frame length " + length);
        byte[] payload = new byte[length];
        in.readFully(payload);
        if ((flags & FLAG_ENCRYPTED) != 0) {
            if (session == null) throw new IOException("Encrypted TAP frame before the negotiation finished");
            payload = session.decrypt(payload, header);
        }
        return new Frame(intent, flags, traceId, payload);
    }

    private static byte[] header(int intent, int flags, int traceId, int length) {
        byte[] h = new byte[HEADER];
        System.arraycopy(intToBytes(ORIGIN), 0, h, 0, 4);
        h[4] = (byte) intent;
        h[5] = (byte) flags;
        h[6] = (byte) (traceId >>> 8);
        h[7] = (byte) traceId;
        System.arraycopy(intToBytes(length), 0, h, 8, 4);
        return h;
    }

    private static byte[] intToBytes(int v) {
        return new byte[]{(byte) (v >>> 24), (byte) (v >>> 16), (byte) (v >>> 8), (byte) v};
    }

    private int nextTrace() {
        traceCounter++;
        if (traceCounter > 0xffff) traceCounter = 1;
        return traceCounter;
    }

    // ================================================================= negotiation

    private void negotiate() throws IOException {
        TapCrypto.Keys ours = TapCrypto.generateKeys();
        byte[] point = ours.publicPoint();
        int trace = nextTrace();
        writeFrame(INTENT_NEGOTIATE, trace, FLAG_REQUEST, point);
        Frame reply;
        do {
            reply = readFrame();
        } while (reply.traceId != trace && reply.intent != INTENT_DISCONNECT && reply.intent != INTENT_GOAWAY);
        if (reply.traceId != trace) throw new IOException("The TypeTube server stopped the negotiation");
        if ((reply.flags & FLAG_ERROR) != 0) {
            throw new IOException("TypeTube: " + new String(reply.payload, "UTF-8"));
        }
        byte[] serverPoint = parseNegotiation(reply.payload);
        byte[] shared = TapCrypto.sharedSecret(ours, serverPoint);
        session = TapCrypto.session(shared, false);
    }

    /**
     * The server answer is its public point, optionally length-prefixed and followed by a signature
     * and a 32-byte handshake proof (see executeNegotiation in connection.ts).
     */
    private static byte[] parseNegotiation(byte[] payload) throws IOException {
        if (payload.length > 70 && u16(payload, 0) == 65) {
            return Arrays.copyOfRange(payload, 2, 67);
        }
        if (payload.length == 65 + 32) {
            return Arrays.copyOfRange(payload, 0, 65);
        }
        if (payload.length == 65) {
            return payload;
        }
        throw new IOException("Unexpected TAP negotiation reply (" + payload.length + " bytes)");
    }

    private static int u16(byte[] b, int at) {
        return ((b[at] & 0xff) << 8) | (b[at + 1] & 0xff);
    }

    @Override
    public synchronized void close() {
        session = null;
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException ignored) {
                // nothing to do
            }
            socket = null;
        }
        in = null;
        out = null;
    }
}
