package com.serena.syncora.youtube;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Locale;

/**
 * Puts finished downloads where the user can find them: <i>Download/Syncora/YouTube</i> through MediaStore on
 * Android 10 and newer, and the app's own Download folder on older Android versions (no storage permission needed).
 */
public final class DownloadStore {

    public static final String FOLDER = "Syncora/YouTube";

    public static final class Saved {
        public final Uri uri;
        public final String displayName;
        public final String location;

        Saved(Uri uri, String displayName, String location) {
            this.uri = uri;
            this.displayName = displayName;
            this.location = location;
        }
    }

    private DownloadStore() {
    }

    /** Where the files go, for the user. */
    public static String locationHint() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ? "Downloads / " + FOLDER : "Android/data/com.serena.syncora/files/Download/" + FOLDER;
    }

    /** Copies {@code source} into the download folder under {@code displayName}, then deletes {@code source}. */
    public static Saved save(Context context, File source, String displayName, String mime) throws IOException {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) return saveWithMediaStore(context, source, displayName, mime);
            File dir = new File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), FOLDER);
            if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("Could not create the download folder");
            File target = uniqueFile(dir, displayName);
            copy(new FileInputStream(source), new java.io.FileOutputStream(target));
            return new Saved(Uri.fromFile(target), target.getName(), target.getAbsolutePath());
        } finally {
            //noinspection ResultOfMethodCallIgnored
            source.delete();
        }
    }

    private static Saved saveWithMediaStore(Context context, File source, String displayName, String mime) throws IOException {
        ContentResolver resolver = context.getContentResolver();
        ContentValues values = new ContentValues();
        values.put(MediaStore.Downloads.DISPLAY_NAME, displayName);
        values.put(MediaStore.Downloads.MIME_TYPE, mime);
        values.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + FOLDER);
        values.put(MediaStore.Downloads.IS_PENDING, 1);
        Uri uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
        if (uri == null) throw new IOException("Could not create the file in Downloads");
        try {
            try (OutputStream out = resolver.openOutputStream(uri)) {
                if (out == null) throw new IOException("Could not write the file");
                copy(new FileInputStream(source), out);
            }
            ContentValues done = new ContentValues();
            done.put(MediaStore.Downloads.IS_PENDING, 0);
            resolver.update(uri, done, null, null);
        } catch (IOException | RuntimeException e) {
            resolver.delete(uri, null, null);
            throw e instanceof IOException ? (IOException) e : new IOException("Could not save the file", e);
        }
        return new Saved(uri, displayName, Environment.DIRECTORY_DOWNLOADS + "/" + FOLDER);
    }

    /** Writes text (captions, lyrics) straight into the download folder. */
    public static Saved saveText(Context context, String text, String displayName, String mime) throws IOException {
        File temp = File.createTempFile("syncora-text", ".tmp", context.getCacheDir());
        try (OutputStream out = new java.io.FileOutputStream(temp)) {
            out.write(text.getBytes("UTF-8"));
        }
        return save(context, temp, displayName, mime);
    }

    /** Makes a file name safe for every file system. */
    public static String safeName(String title, String id, String extension) {
        String base = title == null ? "" : title.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").replaceAll("\\s+", " ").trim();
        if (base.length() > 80) base = base.substring(0, 80).trim();
        if (base.isEmpty()) base = "YouTube video";
        return base + " [" + id + "]." + extension.toLowerCase(Locale.US);
    }

    private static File uniqueFile(File dir, String name) {
        File candidate = new File(dir, name);
        int n = 2;
        String base = name;
        String ext = "";
        int dot = name.lastIndexOf('.');
        if (dot > 0) {
            base = name.substring(0, dot);
            ext = name.substring(dot);
        }
        while (candidate.exists()) {
            candidate = new File(dir, base + " (" + n++ + ")" + ext);
        }
        return candidate;
    }

    private static void copy(InputStream in, OutputStream out) throws IOException {
        try (InputStream src = in; OutputStream dst = out) {
            byte[] buffer = new byte[64 * 1024];
            int n;
            while ((n = src.read(buffer)) > 0) dst.write(buffer, 0, n);
        }
    }
}
