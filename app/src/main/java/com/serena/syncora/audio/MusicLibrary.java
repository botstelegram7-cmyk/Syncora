package com.serena.syncora.audio;

import android.Manifest;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;

import com.serena.syncora.core.Track;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** All the music on the device, read from MediaStore (no file picker). */
public final class MusicLibrary {
    private MusicLibrary() {
    }

    public static String permission() {
        return Build.VERSION.SDK_INT >= 33 ? Manifest.permission.READ_MEDIA_AUDIO : Manifest.permission.READ_EXTERNAL_STORAGE;
    }

    public static boolean hasPermission(Context context) {
        return context.checkSelfPermission(permission()) == PackageManager.PERMISSION_GRANTED;
    }

    public static Uri collection() {
        return Build.VERSION.SDK_INT >= 29
                ? MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
                : MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
    }

    /** Blocking. Call from a background thread. Sorted by title. */
    public static List<Track> loadAll(Context context) {
        List<Track> tracks = new ArrayList<>();
        if (!hasPermission(context)) return tracks;
        ContentResolver resolver = context.getContentResolver();
        Uri collection = collection();
        String[] projection = {
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.ARTIST,
                MediaStore.Audio.Media.ALBUM,
                MediaStore.Audio.Media.ALBUM_ID,
                MediaStore.Audio.Media.DURATION,
                MediaStore.Audio.Media.DISPLAY_NAME
        };
        String selection = MediaStore.Audio.Media.IS_RINGTONE + "=0 AND "
                + MediaStore.Audio.Media.IS_NOTIFICATION + "=0 AND "
                + MediaStore.Audio.Media.IS_ALARM + "=0 AND ("
                + MediaStore.Audio.Media.DURATION + " IS NULL OR "
                + MediaStore.Audio.Media.DURATION + "=0 OR "
                + MediaStore.Audio.Media.DURATION + ">=8000)";
        try (Cursor cursor = resolver.query(collection, projection, selection, null, null)) {
            if (cursor == null) return tracks;
            int idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID);
            int titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE);
            int artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST);
            int albumCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM);
            int albumIdCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID);
            int durationCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION);
            int nameCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME);
            while (cursor.moveToNext()) {
                long id = cursor.getLong(idCol);
                String title = clean(cursor.getString(titleCol));
                if (title.isEmpty()) title = stripExtension(cursor.getString(nameCol));
                if (title.isEmpty()) title = "Unknown song";
                Uri uri = ContentUris.withAppendedId(collection, id);
                tracks.add(new Track(id, uri.toString(), title, clean(cursor.getString(artistCol)),
                        clean(cursor.getString(albumCol)), cursor.getLong(albumIdCol), (int) cursor.getLong(durationCol)));
            }
        } catch (RuntimeException e) {
            android.util.Log.w("Syncora", "Could not read the music library", e);
        }
        final Collator collator = Collator.getInstance(Locale.getDefault());
        collator.setStrength(Collator.PRIMARY);
        Collections.sort(tracks, new Comparator<Track>() {
            @Override
            public int compare(Track a, Track b) {
                return collator.compare(a.title, b.title);
            }
        });
        return tracks;
    }

    private static String clean(String value) {
        if (value == null) return "";
        String trimmed = value.trim();
        return "<unknown>".equalsIgnoreCase(trimmed) ? "" : trimmed;
    }

    private static String stripExtension(String name) {
        if (name == null) return "";
        int dot = name.lastIndexOf('.');
        return (dot > 0 ? name.substring(0, dot) : name).trim();
    }
}
