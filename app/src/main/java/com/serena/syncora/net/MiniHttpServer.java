package com.serena.syncora.net;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.BindException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.Charset;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * A very small HTTP/1.1 server (one request per connection) that serves the room's REST-ish endpoints,
 * the artwork, the DLNA/UPnP services and, by taking over the socket, the binary audio stream.
 */
public final class MiniHttpServer {
    public static final Charset UTF8 = Charset.forName("UTF-8");
    private static final Charset LATIN1 = Charset.forName("ISO-8859-1");
    private static final int MAX_HEADER_BYTES = 32 * 1024;
    private static final int MAX_BODY_BYTES = 1024 * 1024;

    public static final class Request {
        public final String method;
        public final String path;
        public final String rawQuery;
        public final Map<String, String> query;
        public final Map<String, String> headers;
        public final byte[] body;
        public final String remoteIp;
        public final int remotePort;

        Request(String method, String path, String rawQuery, Map<String, String> headers, byte[] body, String remoteIp,
                int remotePort) {
            this.method = method;
            this.path = path;
            this.rawQuery = rawQuery;
            this.query = NetUtil.parseQuery(rawQuery);
            this.headers = headers;
            this.body = body;
            this.remoteIp = remoteIp;
            this.remotePort = remotePort;
        }

        public String header(String name) {
            return headers.get(name.toLowerCase(Locale.US));
        }

        public String bodyText() {
            return new String(body, UTF8);
        }
    }

    public interface Handler {
        /**
         * Answer the request on {@code out}. Return true when the handler took over the socket for good and
         * will close it itself; return false to have the server close it.
         */
        boolean handle(Request request, Socket socket, InputStream in, OutputStream out) throws IOException;
    }

    private final Map<String, Handler> routes = new ConcurrentHashMap<>();
    private final ExecutorService pool = Executors.newCachedThreadPool();
    private volatile ServerSocket server;
    private volatile boolean running;
    private Thread acceptThread;
    private int port;

    public void route(String pathPrefix, Handler handler) {
        routes.put(pathPrefix, handler);
    }

    public int port() {
        return port;
    }

    public boolean isRunning() {
        return running;
    }

    /** Binds the first free port in the range on all interfaces. */
    public synchronized int start(int firstPort, int lastPort) throws IOException {
        if (running) return port;
        IOException last = null;
        for (int candidate = firstPort; candidate <= lastPort; candidate++) {
            try {
                ServerSocket s = new ServerSocket();
                s.setReuseAddress(true);
                s.bind(new InetSocketAddress(candidate), 32);
                server = s;
                port = candidate;
                running = true;
                final ServerSocket accepting = s;
                acceptThread = new Thread(new Runnable() {
                    @Override
                    public void run() {
                        acceptLoop(accepting);
                    }
                }, "syncora-http-accept");
                acceptThread.setDaemon(true);
                acceptThread.start();
                return port;
            } catch (BindException e) {
                last = e;
            }
        }
        throw last != null ? last : new IOException("No free port");
    }

    public synchronized void stop() {
        running = false;
        ServerSocket s = server;
        server = null;
        if (s != null) {
            try {
                s.close();
            } catch (IOException ignored) {
                // nothing to do
            }
        }
    }

    private void acceptLoop(ServerSocket s) {
        while (running) {
            try {
                final Socket socket = s.accept();
                pool.execute(new Runnable() {
                    @Override
                    public void run() {
                        serve(socket);
                    }
                });
            } catch (IOException e) {
                if (!running) return;
            } catch (RuntimeException e) {
                if (!running) return;
            }
        }
    }

    private void serve(Socket socket) {
        boolean kept = false;
        try {
            socket.setSoTimeout(8000);
            socket.setTcpNoDelay(true);
            InputStream in = new BufferedInputStream(socket.getInputStream(), 16 * 1024);
            OutputStream out = socket.getOutputStream();
            Request request = readRequest(in, socket);
            if (request == null) return;
            Handler handler = find(request.path);
            if (handler == null) {
                respond(out, 404, "Not Found", "text/plain; charset=utf-8", "Not found".getBytes(UTF8));
                return;
            }
            kept = handler.handle(request, socket, in, out);
        } catch (IOException | RuntimeException e) {
            // client went away or sent garbage
        } finally {
            if (!kept) {
                try {
                    socket.close();
                } catch (IOException ignored) {
                    // nothing to do
                }
            }
        }
    }

    private Handler find(String path) {
        Handler best = null;
        int bestLength = -1;
        for (Map.Entry<String, Handler> entry : routes.entrySet()) {
            String prefix = entry.getKey();
            if (path.startsWith(prefix) && prefix.length() > bestLength) {
                best = entry.getValue();
                bestLength = prefix.length();
            }
        }
        return best;
    }

    private static Request readRequest(InputStream in, Socket socket) throws IOException {
        String requestLine = readLine(in);
        if (requestLine == null || requestLine.isEmpty()) return null;
        String[] parts = requestLine.split(" ");
        if (parts.length < 2) return null;
        Map<String, String> headers = new HashMap<>();
        int total = requestLine.length();
        while (true) {
            String line = readLine(in);
            if (line == null) return null;
            if (line.isEmpty()) break;
            total += line.length();
            if (total > MAX_HEADER_BYTES) throw new IOException("headers too large");
            int colon = line.indexOf(':');
            if (colon > 0) headers.put(line.substring(0, colon).trim().toLowerCase(Locale.US), line.substring(colon + 1).trim());
        }
        String target = parts[1];
        String path = target;
        String query = "";
        int q = target.indexOf('?');
        if (q >= 0) {
            path = target.substring(0, q);
            query = target.substring(q + 1);
        }
        // absolute-form request targets ("http://host/path")
        if (path.startsWith("http://") || path.startsWith("https://")) {
            int slash = path.indexOf('/', path.indexOf("//") + 2);
            path = slash < 0 ? "/" : path.substring(slash);
        }
        byte[] body = new byte[0];
        String length = headers.get("content-length");
        if (length != null) {
            int n;
            try {
                n = Integer.parseInt(length.trim());
            } catch (NumberFormatException e) {
                throw new IOException("bad content length");
            }
            if (n < 0 || n > MAX_BODY_BYTES) throw new IOException("body too large");
            body = new byte[n];
            int done = 0;
            while (done < n) {
                int r = in.read(body, done, n - done);
                if (r < 0) throw new IOException("body cut short");
                done += r;
            }
        }
        String ip = socket.getInetAddress() == null ? "" : socket.getInetAddress().getHostAddress();
        return new Request(parts[0].toUpperCase(Locale.US), NetUtil.urlDecode(path), query, headers, body, ip, socket.getPort());
    }

    /** Reads one CRLF (or LF) terminated line without buffering beyond it. */
    static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream(96);
        int b;
        while ((b = in.read()) >= 0) {
            if (b == '\n') break;
            if (b != '\r') line.write(b);
            if (line.size() > 16 * 1024) throw new IOException("line too long");
        }
        if (b < 0 && line.size() == 0) return null;
        return new String(line.toByteArray(), LATIN1);
    }

    // ---------------------------------------------------------------- responses

    public static void respond(OutputStream out, int status, String reason, String contentType, byte[] body,
                               String... extraHeaders) throws IOException {
        StringBuilder head = new StringBuilder(256);
        head.append("HTTP/1.1 ").append(status).append(' ').append(reason).append("\r\n");
        head.append("Content-Type: ").append(contentType).append("\r\n");
        head.append("Content-Length: ").append(body.length).append("\r\n");
        head.append("Connection: close\r\n");
        head.append("Cache-Control: no-store\r\n");
        for (String header : extraHeaders) head.append(header).append("\r\n");
        head.append("\r\n");
        out.write(head.toString().getBytes(LATIN1));
        if (body.length > 0) out.write(body);
        out.flush();
    }

    public static void respondJson(OutputStream out, String json) throws IOException {
        respond(out, 200, "OK", "application/json; charset=utf-8", json.getBytes(UTF8));
    }

    public static void respondText(OutputStream out, int status, String reason, String text) throws IOException {
        respond(out, status, reason, "text/plain; charset=utf-8", text.getBytes(UTF8));
    }
}
