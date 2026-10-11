package com.serena.syncora.radio;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Reads the small radio catalogs bundled with the app, so featured stations work without a directory lookup. */
public final class RadioCatalog {
    private RadioCatalog() {
    }

    /** Official All India Radio / Akashvani channels (HLS). */
    public static List<Station> akashvani(Context context) {
        return load(context, "radio/akashvani.json", true);
    }

    /** Popular Indian community and private stations, including verified MP3 streams. */
    public static List<Station> india(Context context) {
        return load(context, "radio/india.json", false);
    }

    /** Curated community-radio stations from other countries. */
    public static List<Station> world(Context context) {
        return load(context, "radio/world.json", false);
    }

    public static Station findById(List<Station> stations, String id) {
        if (stations == null || id == null) return null;
        for (Station station : stations) {
            if (id.equalsIgnoreCase(station.id)) return station;
        }
        return null;
    }

    public static Station firstByCodec(List<Station> stations, String codec) {
        if (stations == null || codec == null) return null;
        for (Station station : stations) {
            if (codec.equalsIgnoreCase(station.codec)) return station;
        }
        return null;
    }

    static List<Station> parse(String json, boolean akashvani) throws JSONException {
        JSONArray array = new JSONArray(json);
        List<Station> stations = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) {
            JSONObject item = array.optJSONObject(i);
            if (item == null) continue;
            String url = item.optString("url", "").trim();
            if (!isHttpUrl(url)) continue;
            String name = item.optString("name", "").trim();
            if (name.isEmpty()) continue;
            if (akashvani) {
                stations.add(new Station(item.optString("id", ""), name, url,
                        item.optString("language", ""), item.optString("state", "India"), "HLS", 0));
            } else {
                Station station = Station.fromJson(item);
                if (station != null) stations.add(station);
            }
        }
        return stations;
    }

    private static List<Station> load(Context context, String asset, boolean akashvani) {
        if (context == null) return new ArrayList<>();
        try (InputStream in = context.getApplicationContext().getAssets().open(asset)) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int n;
            while ((n = in.read(buffer)) > 0) bytes.write(buffer, 0, n);
            return parse(bytes.toString("UTF-8"), akashvani);
        } catch (IOException | JSONException e) {
            return new ArrayList<>();
        }
    }

    private static boolean isHttpUrl(String url) {
        String lower = url.toLowerCase(Locale.US);
        return lower.startsWith("http://") || lower.startsWith("https://");
    }
}
