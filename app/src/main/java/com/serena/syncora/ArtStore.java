package com.serena.syncora;

import java.util.LinkedHashMap;
import java.util.Map;

/** The few most recent cover images (JPEG) the host serves to speakers at /art. */
final class ArtStore {
    private final Map<String, byte[]> map = new LinkedHashMap<String, byte[]>(8, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, byte[]> eldest) {
            return size() > 6;
        }
    };

    synchronized void put(String key, byte[] jpeg) {
        map.put(key, jpeg);
    }

    synchronized byte[] get(String key) {
        return map.get(key);
    }
}
