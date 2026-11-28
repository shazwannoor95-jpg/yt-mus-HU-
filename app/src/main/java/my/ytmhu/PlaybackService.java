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
    private MusicApi api;
    private AppSettings prefs;
    private PlaybackTuning tuning;
    private ListenReporter reporter;
    private double loudness;
    private int fade;
    private boolean fading;
    private List<MusicApi.Track> queue = new ArrayList<>();
    private int index = -1;
    private int generation;
    private boolean shuffle;
    private boolean repeat;
    private List<MusicApi.Track> originalQueue = new ArrayList<>();
    private MediaSession session;
    private MediaPlayer player;
    private AudioManager audio;
    private AudioFocusRequest focus;
    private AudioAttributes attributes;
    private PendingIntent openApp;
    private PendingIntent stopCommand;
    private Future<?> request;
    private boolean prepared;
    private int pendingSeek = -1;
    private int inFlightSeek = -1;
    private boolean signInRequired;
    private boolean foreground;
    private boolean started;
    private boolean destroyed;
    private int state = PlaybackState.STATE_NONE;
    private String message = "";
    Runnable changed;

    final class LocalBinder extends Binder {
        PlaybackService service() {
            return PlaybackService.this;
        }
    }

    @Override public void onCreate() {
        super.onCreate();
        prefs = new AppSettings(this);
        api = new MusicApi(prefs);
        tuning = new PlaybackTuning(prefs);
        reporter = new ListenReporter(prefs);
        attributes = new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build();
        audio = getSystemService(AudioManager.class);
        focus = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(attributes).setWillPauseWhenDucked(true)
                .setOnAudioFocusChangeListener(change -> {
                    if (change < 0 && state == PlaybackState.STATE_PLAYING) pauseNow();
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
            @Override public void onSeekTo(long position) { seekTo((int) Math.min(Integer.MAX_VALUE, position)); }
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
        if (selected < 0 || selected >= tracks.size()) return;
        MusicApi.Track chosen = tracks.get(selected);
        if (prefs.flag("hideExplicit", false) && chosen.explicit) return;
        originalQueue = new ArrayList<>(tracks);
        if (prefs.flag("hideExplicit", false)) originalQueue.removeIf(song -> song.explicit);
        queue = new ArrayList<>(originalQueue);
        selected = queue.indexOf(chosen);
        if (shuffle) {
            MusicApi.Track first = queue.remove(selected);
            Collections.shuffle(queue);
            queue.add(0, first);
            select(0);
        } else {
            select(selected);
        }
    }

    List<MusicApi.Track> queueSnapshot() { return new ArrayList<>(queue); }
    int queueIndex() { return index; }
    MusicApi.Track currentTrack() { return index >= 0 ? queue.get(index) : null; }

    void playAt(int position) {
        if (position >= 0 && position < queue.size()
                && (!prefs.flag("hideExplicit", false) || !queue.get(position).explicit)) select(position);
    }

    void enqueue(MusicApi.Track track, boolean next) {
        if (prefs.flag("hideExplicit", false) && track.explicit) return;
        MusicApi.Track entry = new MusicApi.Track(track.id, track.title, track.artist, track.artwork, track.explicit, track.artists);
        int at = next && index >= 0 ? index + 1 : queue.size();
        queue.add(at, entry);
        int original = next && index >= 0 ? originalQueue.indexOf(queue.get(index)) + 1 : originalQueue.size();
        originalQueue.add(original, entry);
        publish(state, message);
    }

    void removeAt(int position) {
        if (position < 0 || position >= queue.size() || position == index) return;
        originalQueue.remove(queue.remove(position));
        if (position < index) index--;
        publish(state, message);
    }

    void moveAt(int position, int delta) {
        int target = position + delta;
        if (position < 0 || position >= queue.size() || target < 0 || target >= queue.size()) return;
        Collections.swap(queue, position, target);
        if (!shuffle) Collections.swap(originalQueue, position, target);
        if (index == position) index = target;
        else if (index == target) index = position;
        publish(state, message);
    }

    void skip(int delta) {
        int next = adjacent(delta);
        if (next >= 0) select(next);
    }

    void toggle() {
        if (state == PlaybackState.STATE_PLAYING) pause();
        else if (index < 0 && !queue.isEmpty()) playAt(0);
        else resume();
    }

    boolean requiresSignIn() { return signInRequired; }
    boolean playing() { return state == PlaybackState.STATE_PLAYING; }
    boolean canPlay() { return !queue.isEmpty() && state != PlaybackState.STATE_BUFFERING; }
    boolean canSkip(int delta) { return adjacent(delta) >= 0; }

    private int adjacent(int delta) {
        if (index < 0 || queue.size() < 2) return -1;
        for (int i = 1; i < queue.size(); i++) {
            int candidate = index + i * delta;
            if (!repeat && (candidate < 0 || candidate >= queue.size())) return -1;
            candidate = Math.floorMod(candidate, queue.size());
            if (!prefs.flag("hideExplicit", false) || !queue.get(candidate).explicit) return candidate;
        }
        return -1;
    }

    void settingsChanged() {
        if (prepared) {
            tuning.applyVolume(player, loudness, 1f);
            tuning.applyEqualizer(player);
            if (playing()) tuning.applySpeed(player);
        }
        publish(state, message);
    }

    String title() { return index < 0 ? "Now playing" : queue.get(index).title; }
    String status() { return message; }
    boolean isShuffle() { return shuffle; }
    boolean isRepeat() { return repeat; }
    int duration() { return prepared ? player.getDuration() : 0; }
    int position() {
        if (!prepared) return 0;
        return pendingSeek >= 0 ? pendingSeek : player.getCurrentPosition();
    }

    boolean buffering() { return state == PlaybackState.STATE_BUFFERING; }

    void seekTo(int where) {
        if (!prepared) return;
        pendingSeek = Math.max(0, Math.min(where, duration()));
        if (inFlightSeek < 0) startSeek();
        publish(state, message);
    }

    private void startSeek() {
        inFlightSeek = pendingSeek;
        player.setOnSeekCompleteListener(this::seekCompleted);
        player.seekTo(inFlightSeek, MediaPlayer.SEEK_CLOSEST);
    }

    private void seekCompleted(MediaPlayer source) {
        if (source != player || inFlightSeek < 0) return;
        if (pendingSeek != inFlightSeek) {
            startSeek();
            return;
        }
        pendingSeek = inFlightSeek = -1;
        publish(state, message);
    }

    void toggleShuffle() {
        shuffle = !shuffle;
        if (index >= 0) {
            MusicApi.Track current = queue.get(index);
            queue = new ArrayList<>(originalQueue);
            if (shuffle) {
                queue.remove(current);
                Collections.shuffle(queue);
                queue.add(0, current);
            }
            index = queue.indexOf(current);
        }
        publish(state, message);
    }

    void toggleRepeat() {
        repeat = !repeat;
        publish(state, message);
    }

    void stopForSignOut() {
        stopPlayback();
        queue.clear();
        originalQueue.clear();
        publish(state, message);
    }

    private void select(int selected) {
        reporter.reset();
        index = selected;
        signInRequired = false;
        int current = ++generation;
        if (request != null) request.cancel(true);
        releasePlayer();
        MusicApi.Track track = queue.get(index);
        session.setMetadata(new MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, track.title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, track.artist).build());
        session.setActive(true);
        publish(PlaybackState.STATE_BUFFERING, "Loading…");
        if (!started) startService(new Intent(this, PlaybackService.class));
        String cookies = GoogleSignIn.cookies();
        request = network.submit(() -> {
            try {
                api.setSession(cookies);
                List<MusicApi.AudioSource> urls = api.audio(track);
                main.post(() -> {
                    if (!destroyed && generation == current) prepare(urls, 0, current);
                });
            } catch (Exception error) {
                main.post(() -> {
                    if (destroyed || generation != current) return;
                    signInRequired = error instanceof MusicApi.SignInRequired;
                    fail(error.getMessage());
                });
            }
        });
    }


    private final android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());

    private void prepare(List<MusicApi.AudioSource> urls, int source, int current) {
        releasePlayer();
        if (source == urls.size()) {
            fail("Audio stream failed.");
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
            else if (repeat && (!prefs.flag("hideExplicit", false) || !queue.get(index).explicit)) select(index);
            else if (prefs.flag("autoplay", false)) continueWithRadio(current);
            else stopPlayback();
        });
        next.setOnErrorListener((broken, what, extra) -> {
            if (broken == player && generation == current) {
                publish(PlaybackState.STATE_BUFFERING, "Trying another audio source…");
                prepare(urls, source + 1, current);
            }
            return true;
        });
        try {
            MusicApi.AudioSource audioSource = urls.get(source);
            loudness = audioSource.loudness;
            next.setDataSource(this, android.net.Uri.parse(audioSource.url),
                    Collections.singletonMap("User-Agent", audioSource.agent));
            next.prepareAsync();
        } catch (Exception error) {
            prepare(urls, source + 1, current);
        }
    }

    private void continueWithRadio(int current) {
        MusicApi.Track seed = currentTrack();
        reporter.pause();
        releasePlayer();
        publish(PlaybackState.STATE_BUFFERING, "Loading autoplay…");
        String cookies = GoogleSignIn.cookies();
        request = network.submit(() -> {
            List<MusicApi.Track> similar;
            try {
                api.setSession(cookies);
                similar = api.radio(seed);
                similar.removeIf(song -> song.id.equals(seed.id) || (prefs.flag("hideExplicit", false) && song.explicit));
            } catch (Exception unavailable) {
                similar = Collections.emptyList();
            }
            List<MusicApi.Track> ready = similar;
            main.post(() -> {
                if (destroyed || current != generation) return;
                if (ready.isEmpty() || !prefs.flag("autoplay", false)) stopPlayback();
                else play(ready, 0);
            });
        });
    }

    private void resume() {
        if (playing()) return;
        if (index < 0) {
            if (!queue.isEmpty()) playAt(0);
            return;
        }
        if (!prepared) {
            if (state == PlaybackState.STATE_ERROR) {
                select(index);
            }
            return;
        }
        if (audio.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            publish(PlaybackState.STATE_PAUSED, "Another app is using audio.");
            return;
        }
        tuning.applyVolume(player, loudness, 1f);
        tuning.applyEqualizer(player);
        tuning.applySpeed(player);
        player.start();
        reporter.play(currentTrack(), duration());
        publish(PlaybackState.STATE_PLAYING, queue.get(index).artist);
    }

    private void pause() {
        if (!playing() || !prepared || fading) return;
        if (!prefs.flag("fadePause", false)) {
            pauseNow();
            return;
        }
        fading = true;
        int token = ++fade;
        for (int i = 1; i <= 5; i++) {
            final int step = i;
            main.postDelayed(() -> {
                if (!fading || fade != token || player == null || !prepared) return;
                tuning.applyVolume(player, loudness, 1f - step / 5f);
                if (step == 5) pauseNow();
            }, step * 40L);
        }
    }

    private void pauseNow() {
        fading = false;
        ++fade;
        if (!playing() || !prepared) return;
        reporter.pause();
        player.pause();
        audio.abandonAudioFocusRequest(focus);
        publish(PlaybackState.STATE_PAUSED, queue.get(index).artist);
    }

    private void fail(String detail) {
        releasePlayer();
        audio.abandonAudioFocusRequest(focus);
        if (signInRequired) session.setActive(false);
        publish(PlaybackState.STATE_ERROR, signInRequired ? detail :
                "Playback failed" + (detail == null ? "." : " · " + detail));
        endForeground();
    }

    private void stopPlayback() {
        reporter.reset();
        ++generation;
        if (request != null) request.cancel(true);
        releasePlayer();
        audio.abandonAudioFocusRequest(focus);
        index = -1;
        signInRequired = false;
        session.setActive(false);
        publish(PlaybackState.STATE_STOPPED, "");
        endForeground();
    }

    private void publish(int nextState, String text) {
        state = nextState;
        message = text;
        long actions = PlaybackState.ACTION_STOP;
        if (canPlay()) actions |= PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE | PlaybackState.ACTION_PLAY_PAUSE;
        if (prepared) actions |= PlaybackState.ACTION_SEEK_TO;
        if (canSkip(-1)) actions |= PlaybackState.ACTION_SKIP_TO_PREVIOUS;
        if (canSkip(1)) actions |= PlaybackState.ACTION_SKIP_TO_NEXT;
        session.setPlaybackState(new PlaybackState.Builder().setActions(actions)
                .setState(state, position(), playing() ? prefs.speed() : 0f).build());
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
        pendingSeek = inFlightSeek = -1;
        fading = false;
        ++fade;
        reporter.pause();
        tuning.release();
        prepared = false;
        if (player != null) player.release();
        player = null;
    }

    @Override public void onDestroy() {
        destroyed = true;
        network.shutdownNow();
        releasePlayer();
        reporter.close();
        audio.abandonAudioFocusRequest(focus);
        session.release();
        super.onDestroy();
    }
}
