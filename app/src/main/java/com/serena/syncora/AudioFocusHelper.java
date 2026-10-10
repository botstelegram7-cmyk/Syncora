package com.serena.syncora;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.os.Handler;

/** Requests audio focus so calls and other music apps can interrupt (and later resume) playback. */
final class AudioFocusHelper {
    interface Listener {
        /** One of the AudioManager.AUDIOFOCUS_* constants. */
        void onFocusChange(int change);
    }

    private final AudioManager audioManager;
    private final Listener listener;
    private final Handler handler;
    private AudioFocusRequest request;
    private boolean held;

    AudioFocusHelper(Context context, Listener listener, Handler handler) {
        this.audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        this.listener = listener;
        this.handler = handler;
    }

    boolean request() {
        if (audioManager == null) return true;
        if (held) return true;
        AudioAttributes attributes = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build();
        request = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(attributes)
                .setOnAudioFocusChangeListener(new AudioManager.OnAudioFocusChangeListener() {
                    @Override
                    public void onAudioFocusChange(int change) {
                        if (change == AudioManager.AUDIOFOCUS_LOSS) held = false;
                        listener.onFocusChange(change);
                    }
                }, handler)
                .build();
        held = audioManager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
        return held;
    }

    void abandon() {
        if (audioManager != null && request != null) audioManager.abandonAudioFocusRequest(request);
        request = null;
        held = false;
    }
}
