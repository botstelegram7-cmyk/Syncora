package com.serena.syncora;

import android.webkit.MimeTypeMap;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Small, dependency-free HTTP server used only on the local Wi-Fi network. */
final class LocalHttpServer {
    interface Listener {
        JSONObject syncSnapshot();
        File currentAudioFile();
        boolean isValidRoomCode(String code);
        void onClientJoined(String remoteAddress, String name);
        void onClientLeft(String remoteAddress);
        void onRemoteCommand(String command);
    }

    private static final int FIRST_PORT = 8787;
    private static final int LAST_PORT = 8797;

    private final Listener listener;
    private final ExecutorService clients = Executors.newCachedThreadPool();
    private volatile boolean running;
    private volatile ServerSocket serverSocket;
    private volatile int port = FIRST_PORT;
    private Thread acceptThread;

    LocalHttpServer(Listener listener) {
        this.listener = listener;
    }

    synchronized int start() throws IOException {
        if (running && serverSocket != null) return port;
        IOException lastError = null;
        ServerSocket bound = null;
        for (int candidate = FIRST_PORT; candidate <= LAST_PORT; candidate++) {
            try {
                ServerSocket socket = new ServerSocket();
                socket.setReuseAddress(true);
                socket.bind(new InetSocketAddress("0.0.0.0", candidate), 24);
                bound = socket;
                port = candidate;
                break;
            } catch (IOException error) {
                lastError = error;
            }
        }
        if (bound == null) {
            throw lastError != null ? lastError : new IOException("No local audio port is available");
        }

        serverSocket = bound;
        running = true;
        acceptThread = new Thread(() -> {
            while (running) {
                try {
                    Socket socket = serverSocket.accept();
                    socket.setSoTimeout(7000);
                    clients.execute(() -> handle(socket));
                } catch (IOException error) {
                    if (running) android.util.Log.w("Syncora", "LAN accept failed", error);
                }
            }
        }, "syncora-http-accept");
        acceptThread.start();
        return port;
    }

    synchronized void stop() {
        running = false;
        ServerSocket socket = serverSocket;
        serverSocket = null;
        if (socket != null) {
            try { socket.close(); } catch (IOException ignored) { }
        }
        clients.shutdownNow();
    }

    private void handle(Socket socket) {
        try (Socket client = socket) {
            BufferedReader reader = new BufferedReader(new InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8));
            String request = reader.readLine();
            if (request == null || request.trim().isEmpty()) return;
            String[] requestParts = request.split(" ");
            if (requestParts.length < 2) {
                writeText(client, 400, "Bad Request", "text/plain; charset=utf-8", "Bad request");
                return;
            }

            String method = requestParts[0].toUpperCase(Locale.US);
            URI target;
            try {
                target = URI.create(requestParts[1]);
            } catch (IllegalArgumentException error) {
                writeText(client, 400, "Bad Request", "text/plain; charset=utf-8", "Bad request");
                return;
            }
            Map<String, String> headers = new HashMap<>();
            String line;
            while ((line = reader.readLine()) != null && !line.isEmpty()) {
                int split = line.indexOf(':');
                if (split > 0) headers.put(line.substring(0, split).trim().toLowerCase(Locale.US), line.substring(split + 1).trim());
            }

            String path = target.getPath() == null ? "/" : target.getPath();
            Map<String, String> query = decodeQuery(target.getRawQuery());
            String remote = client.getInetAddress() == null ? "unknown" : client.getInetAddress().getHostAddress();

            if ("/sync".equals(path)) {
                if (listener.isValidRoomCode(query.get("code"))) {
                    listener.onClientJoined(remote, query.getOrDefault("name", "Guest"));
                }
                JSONObject snapshot = listener.syncSnapshot();
                writeText(client, 200, "OK", "application/json; charset=utf-8", snapshot == null ? "{}" : snapshot.toString());
            } else if ("/join".equals(path)) {
                if (!listener.isValidRoomCode(query.get("code"))) {
                    writeText(client, 403, "Forbidden", "text/plain; charset=utf-8", "Room code does not match");
                } else {
                    listener.onClientJoined(remote, query.getOrDefault("name", "Guest"));
                    writeText(client, 200, "OK", "text/plain; charset=utf-8", "OK");
                }
            } else if ("/leave".equals(path)) {
                if (listener.isValidRoomCode(query.get("code"))) listener.onClientLeft(remote);
                writeText(client, 200, "OK", "text/plain; charset=utf-8", "OK");
            } else if ("/command".equals(path)) {
                if (!listener.isValidRoomCode(query.get("code"))) {
                    writeText(client, 403, "Forbidden", "text/plain; charset=utf-8", "Room code does not match");
                } else {
                    listener.onRemoteCommand(query.getOrDefault("action", ""));
                    writeText(client, 200, "OK", "text/plain; charset=utf-8", "OK");
                }
            } else if ("/audio".equals(path) && ("GET".equals(method) || "HEAD".equals(method))) {
                serveAudio(client, method, headers.get("range"));
            } else {
                writeText(client, 404, "Not Found", "text/plain; charset=utf-8", "Syncora local audio service");
            }
        } catch (IOException error) {
            // MediaPlayer may close a ranged request as soon as it has enough data.
            android.util.Log.d("Syncora", "LAN client closed", error);
        } catch (RuntimeException error) {
            android.util.Log.w("Syncora", "Unable to serve LAN request", error);
        }
    }

    private void serveAudio(Socket client, String method, String rangeHeader) throws IOException {
        File file = listener.currentAudioFile();
        if (file == null || !file.isFile()) {
            writeText(client, 404, "Not Found", "text/plain; charset=utf-8", "No audio selected");
            return;
        }

        long fileLength = file.length();
        long start = 0;
        long end = Math.max(0, fileLength - 1);
        boolean partial = false;
        if (rangeHeader != null && rangeHeader.startsWith("bytes=") && fileLength > 0) {
            try {
                String value = rangeHeader.substring("bytes=".length()).split(",", 2)[0].trim();
                int dash = value.indexOf('-');
                String left = dash >= 0 ? value.substring(0, dash).trim() : value;
                String right = dash >= 0 ? value.substring(dash + 1).trim() : "";
                if (left.isEmpty()) {
                    long suffix = Long.parseLong(right);
                    start = Math.max(0, fileLength - suffix);
                } else {
                    start = Long.parseLong(left);
                    if (!right.isEmpty()) end = Math.min(fileLength - 1, Long.parseLong(right));
                }
                if (start >= fileLength || end < start) {
                    writeRangeError(client, fileLength);
                    return;
                }
                partial = true;
            } catch (NumberFormatException error) {
                writeRangeError(client, fileLength);
                return;
            }
        }

        long length = fileLength == 0 ? 0 : end - start + 1;
        String contentType = mimeType(file.getName());
        OutputStream output = client.getOutputStream();
        StringBuilder response = new StringBuilder();
        response.append(partial ? "HTTP/1.1 206 Partial Content\r\n" : "HTTP/1.1 200 OK\r\n");
        response.append("Content-Type: ").append(contentType).append("\r\n");
        response.append("Content-Length: ").append(length).append("\r\n");
        response.append("Accept-Ranges: bytes\r\n");
        response.append("Cache-Control: no-store\r\n");
        if (partial) response.append("Content-Range: bytes ").append(start).append('-').append(end).append('/').append(fileLength).append("\r\n");
        response.append("Connection: close\r\n\r\n");
        output.write(response.toString().getBytes(StandardCharsets.US_ASCII));
        output.flush();
        if ("HEAD".equals(method) || length == 0) return;

        try (RandomAccessFile audio = new RandomAccessFile(file, "r")) {
            audio.seek(start);
            byte[] buffer = new byte[16 * 1024];
            long remaining = length;
            while (remaining > 0) {
                int count = audio.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                if (count < 0) break;
                output.write(buffer, 0, count);
                remaining -= count;
            }
            output.flush();
        }
    }

    private static String mimeType(String fileName) {
        int dot = fileName.lastIndexOf('.');
        if (dot >= 0 && dot + 1 < fileName.length()) {
            String type = MimeTypeMap.getSingleton().getMimeTypeFromExtension(fileName.substring(dot + 1).toLowerCase(Locale.US));
            if (type != null) return type;
        }
        return "audio/mpeg";
    }

    private static void writeRangeError(Socket client, long length) throws IOException {
        byte[] body = "Range not satisfiable".getBytes(StandardCharsets.UTF_8);
        OutputStream output = client.getOutputStream();
        String header = "HTTP/1.1 416 Range Not Satisfiable\r\nContent-Range: bytes */" + length + "\r\nContent-Length: " + body.length + "\r\nConnection: close\r\n\r\n";
        output.write(header.getBytes(StandardCharsets.US_ASCII));
        output.write(body);
        output.flush();
    }

    private static void writeText(Socket client, int status, String reason, String contentType, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        OutputStream output = client.getOutputStream();
        String header = "HTTP/1.1 " + status + " " + reason + "\r\n"
                + "Content-Type: " + contentType + "\r\n"
                + "Content-Length: " + bytes.length + "\r\n"
                + "Cache-Control: no-store\r\n"
                + "Connection: close\r\n\r\n";
        output.write(header.getBytes(StandardCharsets.US_ASCII));
        output.write(bytes);
        output.flush();
    }

    private static Map<String, String> decodeQuery(String rawQuery) {
        Map<String, String> result = new HashMap<>();
        if (rawQuery == null || rawQuery.isEmpty()) return result;
        for (String pair : rawQuery.split("&")) {
            int equals = pair.indexOf('=');
            String key = equals < 0 ? pair : pair.substring(0, equals);
            String value = equals < 0 ? "" : pair.substring(equals + 1);
            try {
                result.put(URLDecoder.decode(key, "UTF-8"), URLDecoder.decode(value, "UTF-8"));
            } catch (Exception ignored) { }
        }
        return result;
    }
}
