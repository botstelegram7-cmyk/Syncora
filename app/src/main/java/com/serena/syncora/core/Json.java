package com.serena.syncora.core;

import org.json.JSONException;
import org.json.JSONObject;

/** Small helpers so building JSON does not need a try/catch around every put. */
public final class Json {
    private Json() {
    }

    public static JSONObject put(JSONObject object, String key, Object value) {
        try {
            object.put(key, value);
        } catch (JSONException ignored) {
            // only happens for non-finite numbers
        }
        return object;
    }

    public static JSONObject parse(String text) {
        try {
            return new JSONObject(text);
        } catch (JSONException e) {
            return null;
        }
    }
}
