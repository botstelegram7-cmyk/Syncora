package com.serena.syncora;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.provider.Settings;

import com.serena.syncora.core.AudioProfile;
import com.serena.syncora.core.ChannelMode;
import com.serena.syncora.core.PlayQueue;
import com.serena.syncora.lyrics.LyricsAppearance;
import com.serena.syncora.radio.Station;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** Everything the user can configure, kept in SharedPreferences. */
public final class Prefs {
    public static final String THEME_DARK = "dark";
    public static final String THEME_LIGHT = "light";
    public static final String THEME_AMOLED = "amoled";
    public static final String THEME_SYSTEM = "system";

    private final SharedPreferences sp;
    private final Context context;

    public Prefs(Context context) {
        this.context = context.getApplicationContext();
        this.sp = this.context.getSharedPreferences("syncora", Context.MODE_PRIVATE);
    }

    // ---------------------------------------------------------------- identity

    public synchronized String deviceId() {
        String id = sp.getString("deviceId", null);
        if (id == null) {
            id = UUID.randomUUID().toString();
            sp.edit().putString("deviceId", id).apply();
        }
        return id;
    }

    public synchronized String dlnaUuid() {
        String id = sp.getString("dlnaUuid", null);
        if (id == null) {
            id = UUID.randomUUID().toString();
            sp.edit().putString("dlnaUuid", id).apply();
        }
        return id;
    }

    /** The room ID this device uses whenever it is the player; stable so speakers can reconnect after a restart. */
    public synchronized String roomCode() {
        String code = sp.getString("roomCode", null);
        if (code == null || !code.matches("SYNC-[A-Z2-9]{6}")) {
            code = com.serena.syncora.net.NetUtil.createRoomCode();
            sp.edit().putString("roomCode", code).apply();
        }
        return code;
    }

    public String deviceName() {
        String saved = sp.getString("deviceName", "");
        if (saved != null && !saved.trim().isEmpty()) return saved.trim();
        return defaultDeviceName();
    }

    public String defaultDeviceName() {
        try {
            String name = Settings.Global.getString(context.getContentResolver(), "device_name");
            if (name != null && !name.trim().isEmpty()) return name.trim();
        } catch (RuntimeException ignored) {
            // fall back to the model
        }
        String manufacturer = Build.MANUFACTURER == null ? "" : Build.MANUFACTURER;
        String model = Build.MODEL == null ? "Android" : Build.MODEL;
        if (!manufacturer.isEmpty() && !model.toLowerCase(Locale.US).startsWith(manufacturer.toLowerCase(Locale.US))) {
            return manufacturer.substring(0, 1).toUpperCase(Locale.US) + manufacturer.substring(1) + " " + model;
        }
        return model;
    }

    public void setDeviceName(String name) {
        sp.edit().putString("deviceName", name == null ? "" : name.trim()).apply();
    }

    // ---------------------------------------------------------------- connection

    public String connectIp() {
        return sp.getString("connectIp", "");
    }

    public void setConnectIp(String ip) {
        sp.edit().putString("connectIp", ip == null ? "" : ip.trim()).apply();
    }

    public boolean autoConnect() {
        return sp.getBoolean("autoConnect", true);
    }

    public void setAutoConnect(boolean value) {
        sp.edit().putBoolean("autoConnect", value).apply();
    }

    /** How often a speaker re-checks its alignment with the player, in seconds (0 = only when it drifts a lot). */
    public int resyncSeconds() {
        return sp.getInt("resyncSeconds", 15);
    }

    public void setResyncSeconds(int seconds) {
        sp.edit().putInt("resyncSeconds", seconds).apply();
    }

    public String lastHostIp() {
        return sp.getString("lastHostIp", "");
    }

    public int lastHostPort() {
        return sp.getInt("lastHostPort", 0);
    }

    public String lastRoomCode() {
        return sp.getString("lastRoomCode", "");
    }

    public void setLastHost(String ip, int port, String code) {
        sp.edit().putString("lastHostIp", ip).putInt("lastHostPort", port).putString("lastRoomCode", code).apply();
    }

    public void clearLastHost() {
        sp.edit().remove("lastHostIp").remove("lastHostPort").remove("lastRoomCode").apply();
    }

    public boolean shareRoom() {
        return sp.getBoolean("shareRoom", true);
    }

    public void setShareRoom(boolean value) {
        sp.edit().putBoolean("shareRoom", value).apply();
    }

    // ---------------------------------------------------------------- look & audio

    public String theme() {
        return sp.getString("theme", THEME_DARK);
    }

    public void setTheme(String theme) {
        sp.edit().putString("theme", theme).apply();
    }

    public int accent() {
        return sp.getInt("accent", 0);
    }

    public void setAccent(int index) {
        sp.edit().putInt("accent", index).apply();
    }

    public boolean animations() {
        return sp.getBoolean("animations", true);
    }

    public void setAnimations(boolean value) {
        sp.edit().putBoolean("animations", value).apply();
    }

    // ---------------------------------------------------------------- lyrics & appearance

    /** Independent, version-tolerant lyric preferences; unrelated app settings are never reset with them. */
    public LyricsAppearance lyricsAppearance() {
        LyricsAppearance appearance = new LyricsAppearance();
        appearance.mode = sp.getString("lyricsMode", LyricsAppearance.KARAOKE);
        if (!LyricsAppearance.CLASSIC.equals(appearance.mode) && !LyricsAppearance.KARAOKE.equals(appearance.mode)
                && !LyricsAppearance.CINEMATIC.equals(appearance.mode) && !LyricsAppearance.MINIMAL.equals(appearance.mode)
                && !LyricsAppearance.DUAL.equals(appearance.mode) && !LyricsAppearance.COMPACT.equals(appearance.mode)) {
            appearance.mode = LyricsAppearance.KARAOKE;
        }
        appearance.themeId = sp.getString("lyricsTheme", "dynamic");
        appearance.themeName = sp.getString("lyricsThemeName", "");
        if (!"custom".equals(appearance.themeId)) appearance.themeId = appearance.preset().id;
        appearance.fontFamily = sp.getString("lyricsFont", LyricsAppearance.SANS);
        if (!LyricsAppearance.SERIF.equals(appearance.fontFamily) && !LyricsAppearance.CONDENSED.equals(appearance.fontFamily)
                && !LyricsAppearance.MONO.equals(appearance.fontFamily)) appearance.fontFamily = LyricsAppearance.SANS;
        appearance.background = sp.getString("lyricsBackground", "");
        if (!appearance.background.isEmpty() && !LyricsAppearance.BACKGROUND_ALBUM.equals(appearance.background)
                && !LyricsAppearance.BACKGROUND_GRADIENT.equals(appearance.background)
                && !LyricsAppearance.BACKGROUND_SOLID.equals(appearance.background)
                && !LyricsAppearance.BACKGROUND_CLEAR.equals(appearance.background)) appearance.background = "";
        appearance.fontSizeSp = clamp(sp.getInt("lyricsFontSize", 21), 14, 44);
        appearance.lineSpacingPercent = clamp(sp.getInt("lyricsLineSpacing", 140), 90, 220);
        appearance.letterSpacingPercent = clamp(sp.getInt("lyricsLetterSpacing", 0), -5, 20);
        appearance.activeColor = sp.getInt("lyricsActiveColor", 0);
        appearance.inactiveColor = sp.getInt("lyricsInactiveColor", 0);
        appearance.backgroundColor = sp.getInt("lyricsBackgroundColor", 0);
        appearance.animations = sp.getBoolean("lyricsAnimations", true);
        appearance.reducedMotion = sp.getBoolean("lyricsReducedMotion", false);
        appearance.boldActive = sp.getBoolean("lyricsBoldActive", true);
        appearance.uppercase = sp.getBoolean("lyricsUppercase", false);
        appearance.showTranslation = sp.getBoolean("lyricsShowTranslation", false);
        appearance.showRomanization = sp.getBoolean("lyricsShowRomanization", false);
        appearance.highContrast = sp.getBoolean("lyricsHighContrast", false);
        return appearance;
    }

    public void setLyricsAppearance(LyricsAppearance appearance) {
        if (appearance == null) return;
        sp.edit()
                .putString("lyricsMode", appearance.mode)
                .putString("lyricsTheme", appearance.themeId)
                .putString("lyricsThemeName", appearance.themeName == null ? "" : appearance.themeName)
                .putString("lyricsFont", appearance.fontFamily)
                .putString("lyricsBackground", appearance.background == null ? "" : appearance.background)
                .putInt("lyricsFontSize", clamp(appearance.fontSizeSp, 14, 44))
                .putInt("lyricsLineSpacing", clamp(appearance.lineSpacingPercent, 90, 220))
                .putInt("lyricsLetterSpacing", clamp(appearance.letterSpacingPercent, -5, 20))
                .putInt("lyricsActiveColor", appearance.activeColor)
                .putInt("lyricsInactiveColor", appearance.inactiveColor)
                .putInt("lyricsBackgroundColor", appearance.backgroundColor)
                .putBoolean("lyricsAnimations", appearance.animations)
                .putBoolean("lyricsReducedMotion", appearance.reducedMotion)
                .putBoolean("lyricsBoldActive", appearance.boldActive)
                .putBoolean("lyricsUppercase", appearance.uppercase)
                .putBoolean("lyricsShowTranslation", appearance.showTranslation)
                .putBoolean("lyricsShowRomanization", appearance.showRomanization)
                .putBoolean("lyricsHighContrast", appearance.highContrast)
                .apply();
    }

    public void resetLyricsAppearance() {
        sp.edit().remove("lyricsMode").remove("lyricsTheme").remove("lyricsThemeName").remove("lyricsFont").remove("lyricsBackground")
                .remove("lyricsFontSize").remove("lyricsLineSpacing").remove("lyricsLetterSpacing")
                .remove("lyricsActiveColor").remove("lyricsInactiveColor").remove("lyricsBackgroundColor")
                .remove("lyricsAnimations").remove("lyricsReducedMotion").remove("lyricsBoldActive")
                .remove("lyricsUppercase").remove("lyricsShowTranslation").remove("lyricsShowRomanization")
                .remove("lyricsHighContrast").apply();
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    public String audioMode() {
        return sp.getString("audioMode", AudioProfile.BALANCED);
    }

    public void setAudioMode(String mode) {
        sp.edit().putString("audioMode", mode).apply();
    }

    // ---------------------------------------------------------------- this device's output

    public float volume() {
        return sp.getFloat("volume", 1f);
    }

    public boolean muted() {
        return sp.getBoolean("muted", false);
    }

    public ChannelMode channel() {
        return ChannelMode.fromId(sp.getString("channel", "stereo"));
    }

    public int offsetMs() {
        return sp.getInt("offsetMs", 0);
    }

    public void setOutput(float volume, boolean muted, ChannelMode channel, int offsetMs) {
        sp.edit().putFloat("volume", volume).putBoolean("muted", muted).putString("channel", channel.id)
                .putInt("offsetMs", offsetMs).apply();
    }

    // ---------------------------------------------------------------- playback

    public Source lastSource() {
        return Source.fromId(sp.getString("source", "music"));
    }

    public void setLastSource(Source source) {
        sp.edit().putString("source", source.id).apply();
    }

    public boolean shuffle() {
        return sp.getBoolean("shuffle", false);
    }

    public PlayQueue.Repeat repeat() {
        return PlayQueue.Repeat.fromId(sp.getString("repeat", "off"));
    }

    public void setPlayMode(boolean shuffle, PlayQueue.Repeat repeat) {
        sp.edit().putBoolean("shuffle", shuffle).putString("repeat", repeat.id).apply();
    }

    /** Persisted wall-clock deadline so a running service can restore its timer after process recreation. */
    public long sleepTimerDeadlineWallMs() {
        return Math.max(0, sp.getLong("sleepTimerDeadlineWallMs", 0));
    }

    public boolean sleepTimerAtTrackEnd() {
        return sp.getBoolean("sleepTimerAtTrackEnd", false);
    }

    public boolean sleepTimerMuted() {
        return sp.getBoolean("sleepTimerMuted", false);
    }

    public void setSleepTimerMuted(boolean muted) {
        sp.edit().putBoolean("sleepTimerMuted", muted).apply();
    }

    public void setSleepTimer(long deadlineWallMs, boolean atTrackEnd) {
        sp.edit().putLong("sleepTimerDeadlineWallMs", Math.max(0, deadlineWallMs))
                .putBoolean("sleepTimerAtTrackEnd", atTrackEnd).apply();
    }

    public void clearSleepTimer() {
        sp.edit().remove("sleepTimerDeadlineWallMs").putBoolean("sleepTimerAtTrackEnd", false).apply();
    }

    public float micGain() {
        return sp.getFloat("micGain", 1f);
    }

    public void setMicGain(float gain) {
        sp.edit().putFloat("micGain", gain).apply();
    }

    public boolean micMonitor() {
        return sp.getBoolean("micMonitor", false);
    }

    public void setMicMonitor(boolean value) {
        sp.edit().putBoolean("micMonitor", value).apply();
    }

    public int micInputId() {
        return sp.getInt("micInput", 0);
    }

    public void setMicInputId(int id) {
        sp.edit().putInt("micInput", id).apply();
    }

    public String librarySort() {
        return sp.getString("librarySort", "title");
    }

    public void setLibrarySort(String sort) {
        sp.edit().putString("librarySort", sort).apply();
    }

    public String lastStationJson() {
        return sp.getString("lastStation", "");
    }

    public void setLastStation(Station station) {
        sp.edit().putString("lastStation", station == null ? "" : station.toJson().toString()).apply();
    }

    // ---------------------------------------------------------------- radio favourites

    public List<Station> favoriteStations() {
        List<Station> list = new ArrayList<>();
        try {
            JSONArray array = new JSONArray(sp.getString("favoriteStations", "[]"));
            for (int i = 0; i < array.length(); i++) {
                Station s = Station.fromJson(array.getJSONObject(i));
                if (s != null) list.add(s);
            }
        } catch (JSONException ignored) {
            // start with an empty list
        }
        return list;
    }

    public boolean isFavorite(Station station) {
        for (Station s : favoriteStations()) if (s.url.equals(station.url)) return true;
        return false;
    }

    /** Adds or removes the station; returns true when it is a favourite afterwards. */
    public boolean toggleFavorite(Station station) {
        List<Station> list = favoriteStations();
        boolean removed = false;
        for (int i = list.size() - 1; i >= 0; i--) {
            if (list.get(i).url.equals(station.url)) {
                list.remove(i);
                removed = true;
            }
        }
        if (!removed) list.add(0, station);
        JSONArray array = new JSONArray();
        for (Station s : list) array.put(s.toJson());
        sp.edit().putString("favoriteStations", array.toString()).apply();
        return !removed;
    }

    /** Parses what JSONObject.toString() produced for a station. */
    public static Station stationFromJson(String json) {
        if (json == null || json.isEmpty()) return null;
        try {
            return Station.fromJson(new JSONObject(json));
        } catch (JSONException e) {
            return null;
        }
    }
}
