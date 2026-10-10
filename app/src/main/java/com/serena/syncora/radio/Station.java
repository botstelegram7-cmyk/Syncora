package com.serena.syncora.radio;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Locale;

/** An internet radio station. */
public final class Station {
    public final String id;
    public final String name;
    public final String url;
    public final String tags;
    public final String country;
    public final String codec;
    public final int bitrate;

    public Station(String id, String name, String url, String tags, String country, String codec, int bitrate) {
        this.id = id == null || id.isEmpty() ? Integer.toHexString(url.hashCode()) : id;
        this.name = name == null ? "" : name.trim();
        this.url = url;
        this.tags = tags == null ? "" : tags;
        this.country = country == null ? "" : country;
        this.codec = codec == null ? "" : codec;
        this.bitrate = bitrate;
    }

    public String artKey() {
        return "r" + id;
    }

    /** "Pop · India · 128 kbps" style description. */
    public String subtitle() {
        StringBuilder s = new StringBuilder();
        String firstTag = firstTag();
        if (!firstTag.isEmpty()) s.append(firstTag);
        if (!country.isEmpty()) append(s, country);
        if (bitrate > 0) append(s, bitrate + " kbps");
        if (s.length() == 0 && !codec.isEmpty()) s.append(codec);
        return s.toString();
    }

    private static void append(StringBuilder s, String part) {
        if (s.length() > 0) s.append(" \u00B7 ");
        s.append(part);
    }

    public String firstTag() {
        if (tags.isEmpty()) return "";
        String first = tags.split(",")[0].trim();
        if (first.isEmpty()) return "";
        return first.substring(0, 1).toUpperCase(Locale.getDefault()) + first.substring(1);
    }

    public String initials() {
        String[] words = name.split("[\\s\\-_.]+");
        StringBuilder s = new StringBuilder();
        for (String w : words) {
            if (w.isEmpty() || !Character.isLetterOrDigit(w.charAt(0))) continue;
            s.append(Character.toUpperCase(w.charAt(0)));
            if (s.length() == 2) break;
        }
        return s.length() == 0 ? "\u266A" : s.toString();
    }

    /** Stable index used to pick the station's avatar colours. */
    public int colorIndex() {
        return Math.abs(name.toLowerCase(Locale.US).hashCode() % 8);
    }

    public JSONObject toJson() {
        try {
            return new JSONObject().put("id", id).put("name", name).put("url", url).put("tags", tags)
                    .put("country", country).put("codec", codec).put("bitrate", bitrate);
        } catch (JSONException e) {
            return new JSONObject();
        }
    }

    public static Station fromJson(JSONObject o) {
        String url = o.optString("url", "");
        if (url.isEmpty()) return null;
        return new Station(o.optString("id", ""), o.optString("name", url), url, o.optString("tags", ""),
                o.optString("country", ""), o.optString("codec", ""), o.optInt("bitrate", 0));
    }
}
