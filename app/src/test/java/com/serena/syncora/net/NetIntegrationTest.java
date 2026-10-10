package com.serena.syncora.net;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.serena.syncora.core.ClockSync;
import com.serena.syncora.core.PcmChunk;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

public class NetIntegrationTest {

    interface Condition {
        boolean ok();
    }

    private static boolean waitFor(Condition c, long timeoutMs) throws InterruptedException {
        long end = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < end) {
            if (c.ok()) return true;
            Thread.sleep(10);
        }
        return c.ok();
    }

    private final MiniHttpServer http = new MiniHttpServer();
    private final TimeServer timeServer = new TimeServer();
    private TimeClient timeClient;
    private GroupClient client;
    private GroupHost host;

    final List<String> hostCommands = Collections.synchronizedList(new ArrayList<String>());
    final AtomicInteger clientChanges = new AtomicInteger();

    @After
    public void tearDown() {
        if (client != null) client.close();
        if (timeClient != null) timeClient.stop();
        timeServer.stop();
        if (host != null) host.shutdown();
        http.stop();
    }

    private int startHost() throws IOException {
        host = new GroupHost(new GroupHost.Listener() {
            @Override
            public boolean acceptClient(String code) {
                return "SYNC-TEST01".equals(code);
            }

            @Override
            public String welcomeJson(GroupHost.ClientInfo c) {
                return "{\"hello\":\"" + c.name + "\",\"epoch\":3}";
            }

            @Override
            public String stateJson() {
                return "{\"playing\":true}";
            }

            @Override
            public void onClientsChanged() {
                clientChanges.incrementAndGet();
            }

            @Override
            public void onClientCommand(GroupHost.ClientInfo c, JSONObject command) {
                hostCommands.add(c.name + ":" + command.optString("cmd"));
            }
        });
        http.route("/stream", new MiniHttpServer.Handler() {
            @Override
            public boolean handle(MiniHttpServer.Request request, Socket socket, InputStream in, OutputStream out) throws IOException {
                return host.handleStream(request, socket, in, out);
            }
        });
        http.route("/hello", new MiniHttpServer.Handler() {
            @Override
            public boolean handle(MiniHttpServer.Request request, Socket socket, InputStream in, OutputStream out) throws IOException {
                MiniHttpServer.respondJson(out, "{\"app\":\"Syncora\",\"q\":\"" + request.query.get("x") + "\"}");
                return false;
            }
        });
        return http.start(38000, 38100);
    }

    static final class Received implements GroupClient.Listener {
        final List<PcmChunk> audio = Collections.synchronizedList(new ArrayList<PcmChunk>());
        final List<String> states = Collections.synchronizedList(new ArrayList<String>());
        final List<Integer> flushes = Collections.synchronizedList(new ArrayList<Integer>());
        final List<String> sets = Collections.synchronizedList(new ArrayList<String>());
        volatile String welcome;
        volatile String closedReason;
        volatile boolean refused;

        @Override
        public void onConnected(String welcomeJson) {
            welcome = welcomeJson;
        }

        @Override
        public void onState(String json) {
            states.add(json);
        }

        @Override
        public void onAudio(PcmChunk chunk) {
            audio.add(chunk);
        }

        @Override
        public void onFlush(int epoch) {
            flushes.add(epoch);
        }

        @Override
        public void onSet(String json) {
            sets.add(json);
        }

        @Override
        public void onClosed(String reason, boolean refused) {
            this.closedReason = reason;
            this.refused = refused;
        }
    }

    @Test
    public void speakerJoinsReceivesAudioStateAndCanSendCommands() throws Exception {
        int port = startHost();
        final Received rx = new Received();
        client = new GroupClient(rx);
        client.connect("127.0.0.1", port, "SYNC-TEST01", "dev-1", "Kitchen phone");

        assertTrue("welcome arrives", waitFor(new Condition() {
            public boolean ok() {
                return rx.welcome != null;
            }
        }, 5000));
        assertTrue(rx.welcome.contains("Kitchen phone"));
        assertTrue("initial state follows", waitFor(new Condition() {
            public boolean ok() {
                return !rx.states.isEmpty();
            }
        }, 3000));
        assertEquals("{\"playing\":true}", rx.states.get(0));
        assertEquals(1, host.clientCount());
        assertEquals("Kitchen phone", host.clients().get(0).name);

        // audio, in order, intact
        short[] pcm = new short[882 * 2];
        for (int i = 0; i < pcm.length; i++) pcm[i] = (short) (i * 7);
        for (int i = 0; i < 100; i++) {
            host.broadcastAudio(new PcmChunk(3, i, 1_000_000L * i, 44100, 2, 882, pcm));
        }
        assertTrue("audio arrives", waitFor(new Condition() {
            public boolean ok() {
                return rx.audio.size() == 100;
            }
        }, 5000));
        for (int i = 0; i < 100; i++) {
            PcmChunk c = rx.audio.get(i);
            assertEquals(i, c.seq);
            assertEquals(3, c.epoch);
            assertEquals(1_000_000L * i, c.playAtNanos);
            assertEquals(pcm[1000], c.samples[1000]);
        }

        host.broadcastFlush(4);
        host.broadcastState("{\"playing\":false}");
        assertTrue("flush and state arrive", waitFor(new Condition() {
            public boolean ok() {
                return rx.flushes.size() == 1 && rx.states.size() >= 2;
            }
        }, 3000));
        assertEquals(4, rx.flushes.get(0).intValue());
        assertEquals("{\"playing\":false}", rx.states.get(rx.states.size() - 1));

        // speaker -> host
        client.sendHello("{\"name\":\"Kitchen\",\"volume\":0.4,\"muted\":true,\"channel\":\"left\",\"offsetMs\":-30}");
        client.sendCommand(new JSONObject().put("cmd", "next"));
        assertTrue("hello applied", waitFor(new Condition() {
            public boolean ok() {
                GroupHost.ClientInfo c = host.client("dev-1");
                return c != null && c.offsetMs == -30;
            }
        }, 3000));
        GroupHost.ClientInfo info = host.client("dev-1");
        assertEquals(0.4f, info.volume, 0.001);
        assertTrue(info.muted);
        assertEquals("left", info.channel);
        assertEquals("Kitchen", info.name);
        assertTrue("command delivered", waitFor(new Condition() {
            public boolean ok() {
                return hostCommands.contains("Kitchen:next");
            }
        }, 3000));

        // host -> speaker
        host.sendSet("dev-1", "{\"volume\":0.9}");
        assertTrue("set arrives", waitFor(new Condition() {
            public boolean ok() {
                return rx.sets.size() == 1;
            }
        }, 3000));
        assertEquals("{\"volume\":0.9}", rx.sets.get(0));
    }

    @Test
    public void wrongRoomCodeIsRefused() throws Exception {
        int port = startHost();
        final Received rx = new Received();
        client = new GroupClient(rx);
        client.connect("127.0.0.1", port, "SYNC-WRONG1", "dev-2", "Intruder");
        assertTrue("closed", waitFor(new Condition() {
            public boolean ok() {
                return rx.closedReason != null;
            }
        }, 5000));
        assertTrue(rx.refused);
        assertEquals(0, host.clientCount());
        assertEquals(null, rx.welcome);
    }

    @Test
    public void kickedSpeakerIsToldWhy() throws Exception {
        int port = startHost();
        final Received rx = new Received();
        client = new GroupClient(rx);
        client.connect("127.0.0.1", port, "SYNC-TEST01", "dev-3", "Guest");
        assertTrue(waitFor(new Condition() {
            public boolean ok() {
                return host.clientCount() == 1;
            }
        }, 5000));
        host.kick("dev-3", "removed by the player");
        assertTrue(waitFor(new Condition() {
            public boolean ok() {
                return rx.closedReason != null;
            }
        }, 5000));
        assertTrue(rx.closedReason, rx.closedReason.contains("removed by the player"));
        assertTrue(waitFor(new Condition() {
            public boolean ok() {
                return host.clientCount() == 0;
            }
        }, 3000));
    }

    @Test
    public void reconnectWithSameIdReplacesTheOldConnection() throws Exception {
        int port = startHost();
        Received first = new Received();
        GroupClient a = new GroupClient(first);
        a.connect("127.0.0.1", port, "SYNC-TEST01", "same", "Phone");
        assertTrue(waitFor(new Condition() {
            public boolean ok() {
                return host.clientCount() == 1;
            }
        }, 5000));
        final Received second = new Received();
        client = new GroupClient(second);
        client.connect("127.0.0.1", port, "SYNC-TEST01", "same", "Phone");
        assertTrue(waitFor(new Condition() {
            public boolean ok() {
                return second.welcome != null;
            }
        }, 5000));
        assertEquals(1, host.clientCount());
        a.close();
    }

    @Test
    public void plainHttpRoutesStillWork() throws Exception {
        int port = startHost();
        Socket s = new Socket("127.0.0.1", port);
        s.setSoTimeout(3000);
        s.getOutputStream().write("GET /hello?x=a%20b HTTP/1.1\r\nHost: x\r\n\r\n".getBytes("UTF-8"));
        java.io.ByteArrayOutputStream all = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[1024];
        int n;
        while ((n = s.getInputStream().read(buf)) > 0) all.write(buf, 0, n);
        String response = all.toString("UTF-8");
        assertTrue(response, response.startsWith("HTTP/1.1 200 OK"));
        assertTrue(response, response.contains("\"q\":\"a b\""));
        s.close();

        Socket s2 = new Socket("127.0.0.1", port);
        s2.setSoTimeout(3000);
        s2.getOutputStream().write("GET /nothing HTTP/1.1\r\n\r\n".getBytes("UTF-8"));
        all.reset();
        while ((n = s2.getInputStream().read(buf)) > 0) all.write(buf, 0, n);
        assertTrue(all.toString("UTF-8").startsWith("HTTP/1.1 404"));
        s2.close();
    }

    @Test
    public void udpClockSyncOnLoopbackFindsZeroOffset() throws Exception {
        int port = timeServer.start();
        final ClockSync sync = new ClockSync();
        timeClient = new TimeClient(InetAddress.getByName("127.0.0.1"), port, sync, null);
        timeClient.start();
        assertTrue("measured", waitFor(new Condition() {
            public boolean ok() {
                return sync.sampleCount() >= 6;
            }
        }, 5000));
        assertNotNull(sync);
        assertEquals("same machine, same clock", 0, sync.offsetNanos(), 10_000_000.0);
        assertTrue("rtt is small on loopback: " + sync.bestRttNanos(), sync.bestRttNanos() < 100_000_000L);
        assertFalse(sync.bestRttNanos() < 0);
    }

    @Test
    public void hostParsesAddresses() {
        String[] a = NetUtil.parseHostPort("192.168.1.20");
        assertEquals("192.168.1.20", a[0]);
        assertEquals("", a[1]);
        String[] b = NetUtil.parseHostPort(" http://192.168.1.20:8790/ ");
        assertEquals("192.168.1.20", b[0]);
        assertEquals("8790", b[1]);
        assertEquals(null, NetUtil.parseHostPort("bad host!"));
        assertEquals(null, NetUtil.parseHostPort("10.0.0.1:99999"));
        assertEquals(null, NetUtil.parseHostPort(""));
        assertTrue(NetUtil.createRoomCode().matches("SYNC-[A-Z2-9]{6}"));
    }
}
