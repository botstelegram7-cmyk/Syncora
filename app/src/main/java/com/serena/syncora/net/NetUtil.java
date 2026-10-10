package com.serena.syncora.net;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.security.SecureRandom;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

public final class NetUtil {
    private static final String CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";

    private NetUtil() {
    }

    /** Best guess at this device's LAN address (Wi-Fi, hotspot or ethernet); null when there is none. */
    public static String findLocalIp() {
        String best = null;
        int bestScore = -1;
        try {
            for (NetworkInterface network : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!network.isUp() || network.isLoopback() || network.isVirtual()) continue;
                int base = interfaceScore(network.getName().toLowerCase(Locale.US));
                if (base < 0) continue;
                for (InetAddress address : Collections.list(network.getInetAddresses())) {
                    if (!(address instanceof Inet4Address) || address.isLoopbackAddress() || address.isLinkLocalAddress()) continue;
                    int score = base + (address.isSiteLocalAddress() ? 10 : 0);
                    if (score > bestScore) {
                        bestScore = score;
                        best = address.getHostAddress();
                    }
                }
            }
        } catch (Exception ignored) {
            // fall through
        }
        return best;
    }

    private static int interfaceScore(String name) {
        if (name.startsWith("rmnet") || name.startsWith("ccmni") || name.startsWith("tun") || name.startsWith("dummy")
                || name.startsWith("p2p") || name.startsWith("v4-") || name.startsWith("clat") || name.contains("lo")) return -1;
        if (name.startsWith("wlan") || name.startsWith("swlan") || name.startsWith("ap") || name.startsWith("softap")) return 30;
        if (name.startsWith("eth") || name.startsWith("en")) return 20;
        return 5;
    }

    public static NetworkInterface interfaceFor(String ip) {
        try {
            return NetworkInterface.getByInetAddress(InetAddress.getByName(ip));
        } catch (Exception e) {
            return null;
        }
    }

    public static String createRoomCode() {
        SecureRandom random = new SecureRandom();
        StringBuilder code = new StringBuilder("SYNC-");
        for (int i = 0; i < 6; i++) code.append(CODE_ALPHABET.charAt(random.nextInt(CODE_ALPHABET.length())));
        return code.toString();
    }

    public static String urlEncode(String value) {
        try {
            return URLEncoder.encode(value == null ? "" : value, "UTF-8");
        } catch (Exception e) {
            return "";
        }
    }

    public static String urlDecode(String value) {
        try {
            return URLDecoder.decode(value, "UTF-8");
        } catch (Exception e) {
            return value;
        }
    }

    public static Map<String, String> parseQuery(String query) {
        Map<String, String> map = new HashMap<>();
        if (query == null || query.isEmpty()) return map;
        for (String pair : query.split("&")) {
            if (pair.isEmpty()) continue;
            int eq = pair.indexOf('=');
            if (eq < 0) map.put(urlDecode(pair), "");
            else map.put(urlDecode(pair.substring(0, eq)), urlDecode(pair.substring(eq + 1)));
        }
        return map;
    }

    /** Accepts "192.168.1.5", "192.168.1.5:8787" or "http://192.168.1.5:8787/". Returns {host, port or ""} or null. */
    public static String[] parseHostPort(String input) {
        if (input == null) return null;
        String value = input.trim();
        if (value.isEmpty()) return null;
        int scheme = value.indexOf("://");
        if (scheme >= 0) value = value.substring(scheme + 3);
        int slash = value.indexOf('/');
        if (slash >= 0) value = value.substring(0, slash);
        String host = value;
        String port = "";
        int colon = value.lastIndexOf(':');
        if (colon > 0) {
            host = value.substring(0, colon);
            port = value.substring(colon + 1);
            try {
                int p = Integer.parseInt(port);
                if (p < 1 || p > 65535) return null;
            } catch (NumberFormatException e) {
                return null;
            }
        }
        if (host.isEmpty() || !host.matches("[A-Za-z0-9._-]+")) return null;
        return new String[]{host, port};
    }
}
