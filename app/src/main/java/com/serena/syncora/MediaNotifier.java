package com.serena.syncora;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.graphics.drawable.Icon;
import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.SystemClock;

/** The ongoing "now playing" notification and the MediaSession behind lock-screen / Bluetooth controls. */
final class MediaNotifier {
    interface Controls {
        void onPlay();

        void onPause();

        void onNext();

        void onPrevious();

        void onSeekTo(long positionMs);

        void onStop();
    }

    static final int NOTIFICATION_ID = 21;
    private static final String CHANNEL_ID = "syncora_playback";

    private final Service service;
    private final NotificationManager manager;
    private final MediaSession session;

    MediaNotifier(Service service, final Controls controls) {
        this.service = service;
        this.manager = (NotificationManager) service.getSystemService(Service.NOTIFICATION_SERVICE);
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID, service.getString(R.string.notification_channel),
                NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Playback controls and sync status");
        channel.setShowBadge(false);
        channel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        manager.createNotificationChannel(channel);

        session = new MediaSession(service, "Syncora");
        session.setCallback(new MediaSession.Callback() {
            @Override
            public void onPlay() {
                controls.onPlay();
            }

            @Override
            public void onPause() {
                controls.onPause();
            }

            @Override
            public void onSkipToNext() {
                controls.onNext();
            }

            @Override
            public void onSkipToPrevious() {
                controls.onPrevious();
            }

            @Override
            public void onSeekTo(long pos) {
                controls.onSeekTo(pos);
            }

            @Override
            public void onStop() {
                controls.onStop();
            }
        });
        session.setActive(true);
    }

    Notification build(SessionState s) {
        Notification.Builder b = new Notification.Builder(service, CHANNEL_ID);
        b.setSmallIcon(R.drawable.ic_stat_syncora);
        b.setContentTitle(s.hasMedia && !s.title.isEmpty() ? s.title : service.getString(R.string.app_name));
        b.setContentText(contentText(s));
        b.setSubText(s.source.label);
        if (s.art != null) b.setLargeIcon(s.art);
        b.setContentIntent(PendingIntent.getActivity(service, 0,
                new Intent(service, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
        b.setOnlyAlertOnce(true);
        b.setOngoing(true);
        b.setShowWhen(false);
        b.setVisibility(Notification.VISIBILITY_PUBLIC);
        b.setCategory(Notification.CATEGORY_TRANSPORT);

        int compact;
        if (s.canSkip) {
            b.addAction(action(R.drawable.ic_prev, "Previous", SyncoraService.ACTION_PREV));
            b.addAction(action(s.playing ? R.drawable.ic_pause : R.drawable.ic_play, s.playing ? "Pause" : "Play",
                    SyncoraService.ACTION_TOGGLE));
            b.addAction(action(R.drawable.ic_next, "Next", SyncoraService.ACTION_NEXT));
            compact = 3;
        } else {
            b.addAction(action(s.playing ? R.drawable.ic_pause : R.drawable.ic_play, s.playing ? "Pause" : "Play",
                    SyncoraService.ACTION_TOGGLE));
            compact = 1;
        }
        b.addAction(action(R.drawable.ic_close, s.isSpeaker() ? "Leave" : "Stop", SyncoraService.ACTION_STOP));
        int[] indices = new int[compact];
        for (int i = 0; i < compact; i++) indices[i] = i;
        b.setStyle(new Notification.MediaStyle().setMediaSession(session.getSessionToken()).setShowActionsInCompactView(indices));
        return b.build();
    }

    private static String contentText(SessionState s) {
        if (s.isSpeaker()) {
            if (s.connection == SessionState.Connection.CONNECTED) {
                String from = s.hostName.isEmpty() ? "player" : s.hostName;
                return s.hasMedia && !s.subtitle.isEmpty() ? s.subtitle : "Speaker for " + from;
            }
            return s.connectionText.isEmpty() ? "Waiting for connection" : s.connectionText;
        }
        if (s.hasMedia && !s.subtitle.isEmpty()) return s.subtitle;
        int speakers = s.connectedSpeakers();
        if (speakers > 0) return "Sharing with " + speakers + (speakers == 1 ? " speaker" : " speakers");
        return s.source == Source.DLNA ? "Ready to cast" : "Ready";
    }

    private Notification.Action action(int icon, String title, String intentAction) {
        Intent intent = new Intent(service, SyncoraService.class).setAction(intentAction);
        PendingIntent pending = PendingIntent.getService(service, intentAction.hashCode(), intent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Action.Builder(Icon.createWithResource(service, icon), title, pending).build();
    }

    void update(SessionState s) {
        updateSession(s);
        try {
            manager.notify(NOTIFICATION_ID, build(s));
        } catch (RuntimeException ignored) {
            // notifications can be blocked by the user
        }
    }

    private void updateSession(SessionState s) {
        MediaMetadata.Builder meta = new MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, s.title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, s.subtitle)
                .putString(MediaMetadata.METADATA_KEY_ALBUM, s.album)
                .putLong(MediaMetadata.METADATA_KEY_DURATION, s.live || s.durationMs <= 0 ? -1 : s.durationMs);
        if (s.art != null) meta.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, s.art);
        session.setMetadata(meta.build());

        long actions = PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE | PlaybackState.ACTION_PLAY_PAUSE
                | PlaybackState.ACTION_STOP;
        if (s.canSkip) actions |= PlaybackState.ACTION_SKIP_TO_NEXT | PlaybackState.ACTION_SKIP_TO_PREVIOUS;
        if (s.canSeek) actions |= PlaybackState.ACTION_SEEK_TO;
        int state = s.playing ? PlaybackState.STATE_PLAYING : (s.hasMedia ? PlaybackState.STATE_PAUSED : PlaybackState.STATE_STOPPED);
        if (s.buffering) state = PlaybackState.STATE_BUFFERING;
        session.setPlaybackState(new PlaybackState.Builder().setActions(actions)
                .setState(state, s.positionNow(), s.playing ? 1f : 0f, SystemClock.elapsedRealtime()).build());
    }

    /** Re-publishes the playback position to the media session without re-posting the notification. */
    void refreshSession(SessionState s) {
        try {
            updateSession(s);
        } catch (RuntimeException ignored) {
            // the session may be released while the service stops
        }
    }

    void cancel() {
        try {
            manager.cancel(NOTIFICATION_ID);
        } catch (RuntimeException ignored) {
            // nothing to do
        }
    }

    void release() {
        cancel();
        try {
            session.setActive(false);
            session.release();
        } catch (RuntimeException ignored) {
            // nothing to do
        }
    }
}
