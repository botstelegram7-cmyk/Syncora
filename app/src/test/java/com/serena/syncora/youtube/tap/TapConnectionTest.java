package com.serena.syncora.youtube.tap;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;

/**
 * Drives {@link TapConnection} against a mock cluster that speaks the same wire protocol (ECDH
 * negotiation, AES-256-GCM frames, RPC method packets).
 */
public class TapConnectionTest {

    @Test
    public void resolvesThroughTheEncryptedRpc() throws Exception {
        byte[] answer = "an-answer".getBytes("UTF-8");
        MockServer server = new MockServer(answer, false);
        try {
            TapConnection connection = new TapConnection("127.0.0.1", server.port(), 5000);
            byte[] got = connection.invoke("resolve", "some query".getBytes("UTF-8"));
            assertArrayEquals(answer, got);
            assertEquals("resolve", server.lastMethod);
            assertEquals("some query", server.lastQuery);
            connection.close();
        } finally {
            server.close();
        }
    }

    @Test
    public void serverErrorsAreReportedAsIoErrors() throws Exception {
        MockServer server = new MockServer("rate limited".getBytes("UTF-8"), true);
        try {
            TapConnection connection = new TapConnection("127.0.0.1", server.port(), 5000);
            try {
                connection.invoke("resolve", "q".getBytes("UTF-8"));
                fail("expected the server error to surface");
            } catch (IOException e) {
                assertTrue(e.getMessage(), e.getMessage().contains("rate limited"));
            }
            connection.close();
        } finally {
            server.close();
        }
    }

    @Test
    public void severalCallsShareOneConnection() throws Exception {
        MockServer server = new MockServer("ok".getBytes("UTF-8"), false);
        try {
            TapConnection connection = new TapConnection("127.0.0.1", server.port(), 5000);
            for (int i = 0; i < 3; i++) {
                assertArrayEquals("ok".getBytes("UTF-8"),
                        connection.invoke("resolve", ("q" + i).getBytes("UTF-8")));
            }
            assertEquals(1, server.handshakes);
            assertEquals(3, server.queries.size());
            connection.close();
        } finally {
            server.close();
        }
    }

    @Test
    public void resolveRoundTripsThroughTheClient() throws Exception {
        byte[] envelope = new byte[]{0x08, 0x01}; // success=true, no data (client will complain, fine)
        MockServer server = new MockServer(envelope, false);
        try {
            TypeTubeClient client = new TypeTubeClient("127.0.0.1", server.port(), 5000);
            try {
                client.resolve("hello");
                fail("an envelope without data must fail");
            } catch (IOException e) {
                assertTrue(e.getMessage(), e.getMessage().contains("empty"));
            }
            client.close();
        } finally {
            server.close();
        }
    }

    // ================================================================= mock cluster

    /** A tiny TAP server: negotiate once, then answer {@code resolve} calls. */
    private static final class MockServer implements Closeable {
        private final ServerSocket server;
        private final byte[] answer;
        private final boolean errorMode;
        volatile String lastMethod = "";
        volatile String lastQuery = "";
        volatile int handshakes;
        final List<String> queries = new ArrayList<>();

        MockServer(final byte[] answer, final boolean errorMode) throws IOException {
            this.answer = answer;
            this.errorMode = errorMode;
            server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
            Thread thread = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        serve();
                    } catch (IOException ignored) {
                        // the test closes the socket under us
                    }
                }
            });
            thread.setDaemon(true);
            thread.start();
        }

        int port() {
            return server.getLocalPort();
        }

        @Override
        public void close() throws IOException {
            server.close();
        }

        private void serve() throws IOException {
            Socket socket = server.accept();
            try {
                socket.setSoTimeout(10_000);
                DataInputStream in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
                DataOutputStream out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));

                // 1. negotiation: the client's public point comes in the clear
                Frame hello = readFrame(in);
                TapCrypto.Keys ours = TapCrypto.generateKeys();
                TapCrypto.Session session = TapCrypto.session(TapCrypto.sharedSecret(ours, hello.payload), true);
                writeFrame(out, 0x01, 0x00, hello.traceId, ours.publicPoint(), null);
                handshakes++;

                // 2. RPC calls until the client goes away
                while (true) {
                    Frame call = readFrame(in);
                    if (call.intent == 0x07) break; // GOAWAY
                    byte[] packet = session.decrypt(call.payload, call.header);
                    int nameLen = packet[0] & 0xff;
                    lastMethod = new String(packet, 1, nameLen, "UTF-8");
                    lastQuery = new String(packet, 1 + nameLen, packet.length - 1 - nameLen, "UTF-8");
                    synchronized (queries) {
                        queries.add(lastQuery);
                    }
                    if (errorMode) {
                        writeFrame(out, 0x10, 0x02, call.traceId, answer, session);
                    } else {
                        writeFrame(out, 0x10, 0x01, call.traceId, answer, session);
                    }
                }
            } finally {
                socket.close();
            }
        }
    }

    // ================================================================= wire helpers (independent of TapConnection)

    private static final class Frame {
        final byte[] header;
        final int intent;
        final int flags;
        final int traceId;
        final byte[] payload;

        Frame(byte[] header, int intent, int flags, int traceId, byte[] payload) {
            this.header = header;
            this.intent = intent;
            this.flags = flags;
            this.traceId = traceId;
            this.payload = payload;
        }
    }

    private static Frame readFrame(DataInputStream in) throws IOException {
        byte[] header = new byte[12];
        in.readFully(header);
        int intent = header[4] & 0xff;
        int flags = header[5] & 0xff;
        int trace = ((header[6] & 0xff) << 8) | (header[7] & 0xff);
        int len = ((header[8] & 0xff) << 24) | ((header[9] & 0xff) << 16)
                | ((header[10] & 0xff) << 8) | (header[11] & 0xff);
        byte[] payload = new byte[len];
        in.readFully(payload);
        return new Frame(header, intent, flags, trace, payload);
    }

    private static void writeFrame(DataOutputStream out, int intent, int flags, int traceId,
                                   byte[] payload, TapCrypto.Session session) throws IOException {
        byte[] data = payload;
        byte[] header = new byte[12];
        if (session != null) {
            flags |= 0x08;
            fillHeader(header, intent, flags, traceId, payload.length + 16);
            data = session.encrypt(payload, header);
        } else {
            fillHeader(header, intent, flags, traceId, payload.length);
        }
        out.write(header);
        out.write(data);
        out.flush();
    }

    private static void fillHeader(byte[] header, int intent, int flags, int traceId, int length) {
        header[0] = 0x54;
        header[1] = 0x54;
        header[2] = 0x53;
        header[3] = 0x01;
        header[4] = (byte) intent;
        header[5] = (byte) flags;
        header[6] = (byte) (traceId >>> 8);
        header[7] = (byte) traceId;
        header[8] = (byte) (length >>> 24);
        header[9] = (byte) (length >>> 16);
        header[10] = (byte) (length >>> 8);
        header[11] = (byte) length;
    }
}
