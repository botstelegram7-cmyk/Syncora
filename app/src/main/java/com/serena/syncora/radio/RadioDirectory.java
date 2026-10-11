package com.serena.syncora.radio;

import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Finds stations: a built-in featured list, plus search in the free community directory radio-browser.info. */
public final class RadioDirectory {
    public interface Callback {
        /** {@code error} is null on success. Always called on the main thread. */
        void onResult(List<Station> stations, String error);
    }

    private static final String[] SERVERS = {
            "https://de1.api.radio-browser.info",
            "https://fi1.api.radio-browser.info",
            "https://nl1.api.radio-browser.info",
            "https://at1.api.radio-browser.info",
            "https://all.api.radio-browser.info"
    };

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    private static Handler mainHandler;

    private static synchronized Handler main() {
        if (mainHandler == null) mainHandler = new Handler(Looper.getMainLooper());
        return mainHandler;
    }

    private RadioDirectory() {
    }

    private static Station soma(String id, String name, String tags) {
        return new Station("soma-" + id, name, "https://ice1.somafm.com/" + id + "-128-mp3", tags, "USA", "MP3", 128);
    }

    /** Stations that need no directory lookup. */
    public static List<Station> featured() {
        List<Station> list = new ArrayList<>();
        list.add(soma("groovesalad", "SomaFM Groove Salad", "ambient,downtempo"));
        list.add(new Station("rp-main", "Radio Paradise", "https://stream.radioparadise.com/mp3-128", "eclectic,rock", "USA", "MP3", 128));
        list.add(soma("lush", "SomaFM Lush", "vocals,chill"));
        list.add(soma("deepspaceone", "SomaFM Deep Space One", "ambient,space"));
        list.add(new Station("rp-mellow", "Radio Paradise Mellow Mix", "https://stream.radioparadise.com/mellow-128", "mellow,chill", "USA", "MP3", 128));
        list.add(soma("indiepop", "SomaFM Indie Pop Rocks", "indie,pop"));
        list.add(new Station("kexp", "KEXP 90.3 Seattle", "https://kexp.streamguys1.com/kexp160.aac", "indie,alternative", "USA", "AAC", 160));
        list.add(soma("secretagent", "SomaFM Secret Agent", "lounge,spy"));
        list.add(new Station("rp-rock", "Radio Paradise Rock Mix", "https://stream.radioparadise.com/rock-128", "rock", "USA", "MP3", 128));
        list.add(new Station("classicfm", "Classic FM", "https://media-ice.musicradio.com/ClassicFMMP3", "classical", "UK", "MP3", 128));
        list.add(soma("beatblender", "SomaFM Beat Blender", "electronic,beats"));
        list.add(new Station("rsj", "Radio Swiss Jazz", "http://stream.srg-ssr.ch/m/rsj/mp3_128", "jazz", "Switzerland", "MP3", 128));
        list.add(new Station("rsp", "Radio Swiss Pop", "http://stream.srg-ssr.ch/m/rsp/mp3_128", "pop", "Switzerland", "MP3", 128));
        list.add(soma("u80s", "SomaFM Underground 80s", "80s,synth"));
        list.add(soma("defcon", "SomaFM DEF CON Radio", "electronic,hacker"));
        list.add(soma("dronezone", "SomaFM Drone Zone", "ambient,drone"));
        return list;
    }

    /** Most popular working stations, optionally filtered. Any argument may be empty. */
    public static void search(String name, String tag, String countryCode, Callback callback) {
        StringBuilder path = new StringBuilder("/json/stations/search?limit=150&hidebroken=true&order=votes&reverse=true");
        if (name != null && !name.trim().isEmpty()) path.append("&name=").append(encode(name.trim()));
        if (tag != null && !tag.trim().isEmpty()) path.append("&tag=").append(encode(tag.trim())).append("&tagExact=true");
        if (countryCode != null && !countryCode.trim().isEmpty()) path.append("&countrycode=").append(encode(countryCode.trim()));
        fetch(path.toString(), callback);
    }

    private static String encode(String value) {
        try {
            return java.net.URLEncoder.encode(value, "UTF-8");
        } catch (java.io.UnsupportedEncodingException e) {
            return value;
        }
    }

    private static void fetch(final String path, final Callback callback) {
        EXECUTOR.execute(new Runnable() {
            @Override
            public void run() {
                String error = "Could not reach the radio directory. Check your internet connection.";
                for (String server : SERVERS) {
                    try {
                        String json = download(server + path);
                        final List<Station> stations = parse(json);
                        main().post(new Runnable() {
                            @Override
                            public void run() {
                                callback.onResult(stations, null);
                            }
                        });
                        return;
                    } catch (IOException | JSONException e) {
                        // try the next mirror
                    }
                }
                final String message = error;
                main().post(new Runnable() {
                    @Override
                    public void run() {
                        callback.onResult(new ArrayList<Station>(), message);
                    }
                });
            }
        });
    }

    private static String download(String address) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(address).openConnection();
        try {
            connection.setConnectTimeout(6000);
            connection.setReadTimeout(10000);
            connection.setRequestProperty("User-Agent", "Syncora/0.2");
            connection.setRequestProperty("Accept", "application/json");
            if (connection.getResponseCode() / 100 != 2) throw new IOException("HTTP " + connection.getResponseCode());
            try (InputStream in = connection.getInputStream()) {
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                int n;
                while ((n = in.read(buffer)) > 0 && bytes.size() < 4 * 1024 * 1024) bytes.write(buffer, 0, n);
                return bytes.toString("UTF-8");
            }
        } finally {
            connection.disconnect();
        }
    }

    /** Parses a radio-browser response and keeps working MP3/AAC and HLS stations with HTTP(S) stream URLs. */
    static List<Station> parse(String json) throws JSONException {
        JSONArray array = new JSONArray(json);
        List<Station> list = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (int i = 0; i < array.length(); i++) {
            JSONObject o = array.optJSONObject(i);
            if (o == null) continue;
            if (o.optInt("lastcheckok", 1) != 1) continue;
            String codec = o.optString("codec", "").toUpperCase(Locale.US);
            String url = o.optString("url_resolved", "");
            if (url.isEmpty()) url = o.optString("url", "");
            String lower = url.toLowerCase(Locale.US);
            String path = lower;
            int query = path.indexOf('?');
            if (query >= 0) path = path.substring(0, query);
            boolean hls = o.optInt("hls", 0) == 1 || path.endsWith(".m3u8");
            if (!(hls || codec.equals("MP3") || codec.startsWith("AAC") || codec.isEmpty())) continue;
            if (!(lower.startsWith("http://") || lower.startsWith("https://"))) continue;
            String name = o.optString("name", "").trim();
            if (name.isEmpty() || !seen.add(name.toLowerCase(Locale.US))) continue;
            list.add(new Station(o.optString("stationuuid", ""), name, url, o.optString("tags", ""),
                    o.optString("country", ""), codec, o.optInt("bitrate", 0)));
        }
        return list;
    }
}
