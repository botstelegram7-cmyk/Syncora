package com.serena.syncora.audio;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;
import android.util.Size;

import com.serena.syncora.core.Track;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/** Finds cover art for songs and turns it into compact JPEGs the host can send to the speakers. */
public final class ArtLoader {
    private ArtLoader() {
    }

    /** Blocking. Returns null when the song has no artwork. */
    public static Bitmap loadForTrack(Context context, Track track, int sizePx) {
        Uri uri = Uri.parse(track.uri);
        ContentResolver resolver = context.getContentResolver();
        Bitmap bitmap = null;
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                bitmap = resolver.loadThumbnail(uri, new Size(sizePx, sizePx), null);
            } catch (IOException | RuntimeException ignored) {
                bitmap = null;
            }
        } else if (track.albumId > 0) {
            Uri albumArt = ContentUris.withAppendedId(Uri.parse("content://media/external/audio/albumart"), track.albumId);
            try (InputStream in = resolver.openInputStream(albumArt)) {
                if (in != null) bitmap = decodeStream(in, sizePx);
            } catch (IOException | RuntimeException ignored) {
                bitmap = null;
            }
        }
        if (bitmap == null) bitmap = embedded(context, uri, sizePx);
        return bitmap;
    }

    private static Bitmap embedded(Context context, Uri uri, int sizePx) {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(context, uri);
            byte[] picture = retriever.getEmbeddedPicture();
            return picture == null ? null : decodeSampled(picture, sizePx);
        } catch (RuntimeException e) {
            return null;
        } finally {
            try {
                retriever.release();
            } catch (IOException | RuntimeException ignored) {
                // nothing to do
            }
        }
    }

    /** Downloads artwork from an http(s) address (DLNA controllers provide these). */
    public static Bitmap loadFromUrl(String address, int sizePx) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(address).openConnection();
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(8000);
            if (connection.getResponseCode() / 100 != 2) return null;
            try (InputStream in = connection.getInputStream()) {
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                int n;
                while ((n = in.read(buffer)) > 0 && bytes.size() < 6 * 1024 * 1024) bytes.write(buffer, 0, n);
                return decodeSampled(bytes.toByteArray(), sizePx);
            }
        } catch (IOException | RuntimeException e) {
            return null;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    public static Bitmap decodeSampled(byte[] data, int maxPx) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(data, 0, data.length, bounds);
        int sample = 1;
        while (bounds.outWidth / (sample * 2) >= maxPx && bounds.outHeight / (sample * 2) >= maxPx) sample *= 2;
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sample;
        return BitmapFactory.decodeByteArray(data, 0, data.length, options);
    }

    private static Bitmap decodeStream(InputStream in, int maxPx) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int n;
        while ((n = in.read(buffer)) > 0) bytes.write(buffer, 0, n);
        return decodeSampled(bytes.toByteArray(), maxPx);
    }

    public static byte[] toJpeg(Bitmap bitmap, int quality) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out);
        return out.toByteArray();
    }
}
