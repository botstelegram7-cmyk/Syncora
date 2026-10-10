package com.serena.syncora.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;

import com.serena.syncora.audio.ArtLoader;
import com.serena.syncora.core.Track;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Loads and caches small cover thumbnails for list rows. */
public final class ThumbCache {
    public interface Callback {
        void onLoaded(Bitmap bitmap);
    }

    private static final LruCache<String, Bitmap> CACHE = new LruCache<String, Bitmap>(10 * 1024 * 1024) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
            return value.getByteCount();
        }
    };
    private static final Set<String> NONE = Collections.synchronizedSet(new HashSet<String>());
    private static final ExecutorService POOL = Executors.newFixedThreadPool(3);
    private static Handler main;

    private ThumbCache() {
    }

    private static synchronized Handler main() {
        if (main == null) main = new Handler(Looper.getMainLooper());
        return main;
    }

    public static Bitmap peek(String key) {
        return CACHE.get(key);
    }

    public static boolean known(String key) {
        return CACHE.get(key) != null || NONE.contains(key);
    }

    public static void load(final Context context, final Track track, final int sizePx, final Callback callback) {
        final String key = track.artKey();
        Bitmap cached = CACHE.get(key);
        if (cached != null) {
            callback.onLoaded(cached);
            return;
        }
        if (NONE.contains(key)) return;
        POOL.execute(new Runnable() {
            @Override
            public void run() {
                final Bitmap bitmap = ArtLoader.loadForTrack(context, track, sizePx);
                if (bitmap == null) {
                    NONE.add(key);
                    return;
                }
                CACHE.put(key, bitmap);
                main().post(new Runnable() {
                    @Override
                    public void run() {
                        callback.onLoaded(bitmap);
                    }
                });
            }
        });
    }
}
