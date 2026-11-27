// SPDX-License-Identifier: GPL-3.0-only
package my.ytmhu;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.MediaPlayer;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Binder;
import android.os.IBinder;
import android.os.PowerManager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public final class PlaybackService extends Service {
    private static final String CHANNEL = "music";
    private static final int NOTIFICATION = 1;
    private final Binder binder = new LocalBinder();
    private final ExecutorService network = Executors.newSingleThreadExecutor();
    private final MusicApi api = new MusicApi();
    private List<MusicApi.Track> queue = Collections.emptyList();
    private int index = -1;
    private int generation;
    private MediaSession session;
    private MediaPlayer player;
    private AudioManager audio;
    private AudioFocusRequest focus;
    private AudioAttributes attributes;
    private PendingIntent openApp;
    private PendingIntent stopCommand;
    private Future<?> request;
    private boolean prepared;
    private boolean foreground;
    private boolean started;
    private boolean destroyed;
    private int state = PlaybackState.STATE_NONE;
    private String message = "Pilih lagu untuk main.";
    Runnable changed;

    final class LocalBinder extends Binder {
        PlaybackService service() {
            return PlaybackService.this;
        }
    }

    @Override public void onCreate() {
        super.onCreate();
        attributes = new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build();
        audio = getSystemService(AudioManager.class);
        focus = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(attributes).setWillPauseWhenDucked(true)
                .setOnAudioFocusChangeListener(change -> {
                    if (change < 0 && state == PlaybackState.STATE_PLAYING) pause();
                }).build();
        getSystemService(NotificationManager.class).createNotificationChannel(
                new NotificationChannel(CHANNEL, "Muzik", NotificationManager.IMPORTANCE_LOW));
        int flags = PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT;
        openApp = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), flags);
        stopCommand = PendingIntent.getService(this, 1, new Intent(this, PlaybackService.class).setAction("stop"), flags);
        session = new MediaSession(this, "YTM Head Unit");
        session.setSessionActivity(openApp);
        session.setCallback(new MediaSession.Callback() {
            @Override public void onPlay() { resume(); }
            @Override public void onPause() { pause(); }
            @Override public void onSkipToNext() { skip(1); }
            @Override public void onSkipToPrevious() { skip(-1); }
            @Override public void onStop() { stopPlayback(); }
        });
        publish(state, message);
    }

    @Override public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && "stop".equals(intent.getAction())) {
            stopPlayback();
        } else {
            started = true;
            if (state == PlaybackState.STATE_BUFFERING || state == PlaybackState.STATE_PLAYING) promote();
            else endForeground();
        }
        return START_NOT_STICKY;
    }

    void play(List<MusicApi.Track> tracks, int selected) {
        queue = new ArrayList<>(tracks);
        select(selected);
    }

    void skip(int delta) {
        int next = index + delta;
        if (next >= 0 && next < queue.size()) select(next);
    }

    void toggle() {
        if (state == PlaybackState.STATE_PLAYING) pause();
        else resume();
    }

    boolean playing() { return state == PlaybackState.STATE_PLAYING; }
    boolean canPlay() { return index >= 0 && state != PlaybackState.STATE_BUFFERING; }
    boolean canSkip(int delta) { return index >= 0 && index + delta >= 0 && index + delta < queue.size(); }
    String status() { return message; }

    private void select(int selected) {
        index = selected;
        int current = ++generation;
        if (request != null) request.cancel(true);
        releasePlayer();
        MusicApi.Track track = queue.get(index);
        session.setMetadata(new MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, track.title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, track.artist).build());
        session.setActive(true);
        publish(PlaybackState.STATE_BUFFERING, "Memuatkan · " + track.title);
        if (!started) startService(new Intent(this, PlaybackService.class));
        request = network.submit(() -> {
            try {
                List<String> urls = api.audio(track);
                main.post(() -> {
                    if (!destroyed && generation == current) prepare(urls, 0, current);
                });
            } catch (Exception error) {
                main.post(() -> {
                    if (!destroyed && generation == current) fail(error.getMessage());
                });
            }
        });
    }


    private final android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());

    private void prepare(List<String> urls, int source, int current) {
        releasePlayer();
        if (source == urls.size()) {
            fail("Penstriman gagal.");
            return;
        }
        MediaPlayer next = new MediaPlayer();
        player = next;
        next.setAudioAttributes(attributes);
        next.setWakeMode(this, PowerManager.PARTIAL_WAKE_LOCK);
        next.setOnPreparedListener(ready -> {
            if (ready == player && generation == current) {
                prepared = true;
                resume();
            }
        });
        next.setOnCompletionListener(done -> {
            if (done != player || generation != current) return;
            if (canSkip(1)) skip(1);
            else stopPlayback();
        });
        next.setOnErrorListener((broken, what, extra) -> {
            if (broken == player && generation == current) {
                publish(PlaybackState.STATE_BUFFERING, "Mencuba sumber audio lain…");
                prepare(urls, source + 1, current);
            }
            return true;
        });
        try {
            next.setDataSource(this, android.net.Uri.parse(urls.get(source)),
                    Collections.singletonMap("User-Agent", MusicApi.PLAYER_UA));
            next.prepareAsync();
        } catch (Exception error) {
            prepare(urls, source + 1, current);
        }
    }

    private void resume() {
        if (index < 0 || playing()) return;
        if (!prepared) {
            if (state == PlaybackState.STATE_ERROR) {
                select(index);
            }
            return;
        }
        if (audio.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            publish(PlaybackState.STATE_PAUSED, "Sumber audio sedang digunakan.");
            return;
        }
        player.start();
        publish(PlaybackState.STATE_PLAYING, queue.get(index).title);
    }

    private void pause() {
        if (!playing() || !prepared) return;
        player.pause();
        audio.abandonAudioFocusRequest(focus);
        publish(PlaybackState.STATE_PAUSED, "Dijeda · " + queue.get(index).title);
    }

    private void fail(String detail) {
        releasePlayer();
        audio.abandonAudioFocusRequest(focus);
        publish(PlaybackState.STATE_ERROR, "Gagal main" + (detail == null ? "." : " · " + detail));
        endForeground();
    }

    private void stopPlayback() {
        ++generation;
        if (request != null) request.cancel(true);
        releasePlayer();
        audio.abandonAudioFocusRequest(focus);
        index = -1;
        session.setActive(false);
        publish(PlaybackState.STATE_STOPPED, "Pilih lagu untuk main.");
        endForeground();
    }

    private void publish(int nextState, String text) {
        state = nextState;
        message = text;
        long actions = PlaybackState.ACTION_STOP;
        if (canPlay()) actions |= PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE | PlaybackState.ACTION_PLAY_PAUSE;
        if (canSkip(-1)) actions |= PlaybackState.ACTION_SKIP_TO_PREVIOUS;
        if (canSkip(1)) actions |= PlaybackState.ACTION_SKIP_TO_NEXT;
        session.setPlaybackState(new PlaybackState.Builder().setActions(actions)
                .setState(state, prepared ? player.getCurrentPosition() : 0, playing() ? 1f : 0f).build());
        if (foreground) promote();
        if (changed != null) changed.run();
    }

    private void promote() {
        Notification notification = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_media_play).setContentTitle("YTM Head Unit")
                .setContentText(message).setContentIntent(openApp).setOnlyAlertOnce(true)
                .setStyle(new Notification.MediaStyle().setMediaSession(session.getSessionToken())
                        .setShowActionsInCompactView(0))
                .addAction(new Notification.Action.Builder(android.graphics.drawable.Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel), "Henti", stopCommand).build()).build();
        startForeground(NOTIFICATION, notification);
        foreground = true;
    }

    private void endForeground() {
        if (foreground) stopForeground(STOP_FOREGROUND_REMOVE);
        foreground = false;
        started = false;
        stopSelf();
    }

    private void releasePlayer() {
        prepared = false;
        if (player != null) player.release();
        player = null;
    }

    @Override public void onDestroy() {
        destroyed = true;
        network.shutdownNow();
        releasePlayer();
        audio.abandonAudioFocusRequest(focus);
        session.release();
        super.onDestroy();
    }
}
