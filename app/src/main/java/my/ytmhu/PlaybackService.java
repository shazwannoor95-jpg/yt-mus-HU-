// SPDX-License-Identifier: GPL-3.0-only
package my.ytmhu;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.res.AssetFileDescriptor;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.ImageReader;
import android.media.MediaMetadata;
import android.media.MediaPlayer;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Binder;
import android.os.IBinder;
import android.os.PowerManager;
import android.view.Surface;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public final class PlaybackService extends Service {
    private static final String CHANNEL = "music";
    private static final int NOTIFICATION = 1;
    private static final int REPEAT_OFF = 0, REPEAT_ALL = 1, REPEAT_ONE = 2;
    private static final int POSITION_CHECKPOINT_MS = 15_000;
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
    private int repeat = REPEAT_OFF;
    private QueueStore queueStore;
    private boolean restoredQueue;
    private int savedPosition, resumeGeneration = -1;
    private final SleepTimer sleepTimer = new SleepTimer();
    private int sleepPauseGeneration = -1;
    private final AbLoop loop = new AbLoop();
    private int loopGeneration = -1;
    private List<MusicApi.Track> originalQueue = new ArrayList<>();
    private MediaSession session;
    private MediaPlayer player;
    private VideoSwitch video;
    private boolean restoringSong;
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
        queueStore = new QueueStore(this);
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
        restoreQueue();
        publish(state, message);
    }

    private void restoreQueue() {
        QueueStore.Snapshot snapshot = queueStore.load(prefs.flag("hideExplicit", false));
        if (snapshot == null) return;
        queue = snapshot.queue;
        originalQueue = snapshot.original;
        shuffle = snapshot.shuffle;
        repeat = snapshot.repeat;
        index = snapshot.index;
        if (index < 0) return;
        restoredQueue = true;
        savedPosition = snapshot.position;
        state = PlaybackState.STATE_PAUSED;
        message = "Ready to resume";
        publishRestoredTrack();
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
            SmartShuffle.spread(queue, first, prefs.flag("hideExplicit", false), new java.util.Random());
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
        MusicApi.Track entry = new MusicApi.Track(track.id, track.title, track.artist, track.artwork, track.explicit, track.artists, track.album);
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
            if (repeat != REPEAT_ALL && (candidate < 0 || candidate >= queue.size())) return -1;
            candidate = Math.floorMod(candidate, queue.size());
            if (!prefs.flag("hideExplicit", false) || !queue.get(candidate).explicit) return candidate;
        }
        return -1;
    }

    void settingsChanged() {
        if (prepared) {
            applyVolume(1f);
            tuning.applyEqualizer(player);
            if (playing()) tuning.applySpeed(player);
        }
        publish(state, message);
    }

    String title() { return index < 0 ? "Now playing" : queue.get(index).title; }
    String status() { return message; }
    boolean isShuffle() { return shuffle; }
    boolean isRepeat() { return repeat != REPEAT_OFF; }
    boolean isRepeatOne() { return repeat == REPEAT_ONE; }
    int duration() { return prepared ? player.getDuration() : 0; }
    int position() {
        if (!prepared) return index >= 0 && (restoredQueue || resumeGeneration == generation) ? savedPosition : 0;
        if (pendingSeek >= 0) return pendingSeek;
        int position = player.getCurrentPosition();
        if (videoActive()) video.lastPosition = position;
        return position;
    }

    boolean buffering() { return state == PlaybackState.STATE_BUFFERING; }

    private int seekRevision;

    int lyricSeekRevision() { return seekRevision; }

    void seekTo(int where) {
        if (!prepared) return;
        seekRevision++;
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
        if (resumeGeneration == generation) {
            resumeGeneration = -1;
            savedPosition = 0;
            resume();
            return;
        }
        if (restoringSong) {
            restoringSong = false;
            try {
                if (playing()) startPreparedPlayer();
            } catch (IllegalStateException unavailable) {
                fail("Could not resume the song.");
                return;
            }
        }
        publish(state, message);
    }

    /**
     * Starts a candidate for a source whose recording identity has already been verified.
     * ImageReader keeps this path off-screen until a supported display policy is available.
     */
    boolean offerOffscreenVideo(String recordingId, AssetFileDescriptor source, ImageReader output) {
        MusicApi.Track track = currentTrack();
        if (!prepared || !playing() || fading || restoringSong || pendingSeek >= 0 || video != null
                || track == null || !track.id.equals(recordingId) || source.getDeclaredLength() <= 0) return false;
        Surface surface;
        try {
            surface = output.getSurface();
        } catch (IllegalStateException closed) {
            return false;
        }
        if (surface == null || !surface.isValid()) return false;
        VideoSwitch candidate = new VideoSwitch(player, generation, surface);
        video = candidate;
        try {
            MediaPlayer next = candidate.player;
            next.setAudioAttributes(attributes);
            next.setWakeMode(this, PowerManager.PARTIAL_WAKE_LOCK);
            next.setDataSource(source.getFileDescriptor(), source.getStartOffset(), source.getDeclaredLength());
            next.setSurface(surface);
            next.setOnPreparedListener(ready -> {
                if (!candidate.waiting()) return;
                if (ready.getVideoWidth() <= 0 || ready.getVideoHeight() <= 0
                        || Math.abs((long) ready.getDuration() - duration()) > 1000) {
                    discardVideo();
                    return;
                }
                ready.setOnSeekCompleteListener(done -> activateVideo(candidate));
                alignVideo(candidate);
            });
            next.setOnErrorListener((broken, what, extra) -> {
                if (video == candidate) endVideo();
                return true;
            });
            next.setOnCompletionListener(done -> {
                if (video == candidate && videoActive()) trackCompleted(done, candidate.generation);
            });
            main.postDelayed(candidate.timeout, 8000);
            next.prepareAsync();
            return true;
        } catch (Exception failed) {
            discardVideo();
            return false;
        }
    }

    private void alignVideo(VideoSwitch candidate) {
        if (!candidate.waiting()) return;
        if (!playing() || pendingSeek >= 0 || !candidate.surface.isValid()) {
            discardVideo();
            return;
        }
        candidate.attempts++;
        try {
            candidate.player.seekTo(Math.min(position(), candidate.player.getDuration()), MediaPlayer.SEEK_CLOSEST);
        } catch (IllegalStateException unavailable) {
            discardVideo();
        }
    }

    private void activateVideo(VideoSwitch candidate) {
        if (!candidate.waiting()) return;
        if (!playing() || fading || pendingSeek >= 0 || !candidate.surface.isValid()) {
            discardVideo();
            return;
        }
        if (Math.abs(position() - candidate.player.getCurrentPosition()) > 400) {
            if (candidate.attempts < 2) alignVideo(candidate);
            else discardVideo();
            return;
        }
        main.removeCallbacks(candidate.timeout);
        try {
            candidate.song.pause();
        } catch (IllegalStateException unavailable) {
            discardVideo();
            return;
        }
        player = candidate.player;
        candidate.lastPosition = player.getCurrentPosition();
        try {
            startPreparedPlayer();
        } catch (IllegalStateException failed) {
            endVideo();
            return;
        }
        main.postDelayed(candidate.checkSurface, 500);
        loop.clear();
        seekRevision++;
        publish(state, message);
    }

    boolean videoActive() { return video != null && player == video.player; }

    void endVideo() {
        if (video == null) return;
        if (!videoActive()) {
            discardVideo();
            return;
        }
        VideoSwitch active = video;
        int position = pendingSeek >= 0 ? pendingSeek : active.lastPosition;
        try {
            if (pendingSeek < 0) position = active.player.getCurrentPosition();
        } catch (IllegalStateException decoderFailed) {
            // MediaPlayer can lose its clock in the error state.
        }
        player = active.song;
        discardVideo();
        fading = false;
        ++fade;
        restoringSong = true;
        inFlightSeek = -1;
        try {
            seekTo(position);
        } catch (IllegalStateException unavailable) {
            fail("Could not resume the song.");
        }
    }

    private void discardVideo() {
        if (video == null) return;
        VideoSwitch discarded = video;
        video = null;
        main.removeCallbacks(discarded.timeout);
        main.removeCallbacks(discarded.checkSurface);
        discarded.player.release();
    }

    private final class VideoSwitch {
        final MediaPlayer player = new MediaPlayer();
        final MediaPlayer song;
        final Surface surface;
        final int generation;
        final Runnable timeout = () -> { if (waiting()) discardVideo(); };
        final Runnable checkSurface = new Runnable() {
            @Override public void run() {
                if (video != VideoSwitch.this || !videoActive()) return;
                if (!surface.isValid()) endVideo();
                else main.postDelayed(this, 500);
            }
        };
        int attempts, lastPosition;

        VideoSwitch(MediaPlayer song, int generation, Surface surface) {
            this.song = song;
            this.generation = generation;
            this.surface = surface;
        }

        boolean waiting() {
            return video == this && PlaybackService.this.generation == generation
                    && PlaybackService.this.player == song && prepared;
        }
    }

    void toggleShuffle() {
        shuffle = !shuffle;
        if (index >= 0) {
            MusicApi.Track current = queue.get(index);
            queue = new ArrayList<>(originalQueue);
            if (shuffle) {
                queue.remove(current);
                SmartShuffle.spread(queue, current, prefs.flag("hideExplicit", false), new java.util.Random());
                queue.add(0, current);
            }
            index = queue.indexOf(current);
        }
        publish(state, message);
    }

    void toggleRepeat() {
        repeat = (repeat + 1) % 3;
        if (prepared) player.setLooping(repeatsCurrent());
        publish(state, message);
    }

    private boolean repeatsCurrent() {
        MusicApi.Track track = currentTrack();
        return !sleepTimer.songEnd() && wantsRepeatOne(track);
    }

    private boolean wantsRepeatOne(MusicApi.Track track) {
        return repeat == REPEAT_ONE && track != null && (!prefs.flag("hideExplicit", false) || !track.explicit);
    }

    int sleepSelection() { return sleepTimer.minutesLeft(android.os.SystemClock.elapsedRealtime()); }

    void setSleepTimer(int minutes) {
        clearSleepTimer();
        sleepTimer.set(minutes, android.os.SystemClock.elapsedRealtime());
        if (prepared) {
            player.setLooping(repeatsCurrent());
            if (!fading) applyVolume(1f);
        }
        if (sleepTimer.timed()) scheduleSleepStep();
        publish(state, message);
    }

    private void clearSleepTimer() {
        sleepTimer.set(SleepTimer.OFF, 0);
        main.removeCallbacks(sleepStep);
    }

    private void scheduleSleepStep() {
        main.postDelayed(sleepStep, sleepTimer.delayToNextStep(android.os.SystemClock.elapsedRealtime()));
    }

    private final Runnable sleepStep = () -> {
        if (!sleepTimer.timed()) return;
        if (sleepTimer.due(android.os.SystemClock.elapsedRealtime())) {
            if (prepared) applyVolume(1f);
            clearSleepTimer();
            if (playing()) pauseNow();
            else if (buffering()) sleepPauseGeneration = generation;
            return;
        }
        if (playing() && prepared && !fading) applyVolume(1f);
        scheduleSleepStep();
    };

    private void applyVolume(float envelope) {
        tuning.applyVolume(player, loudness, envelope * sleepTimer.gain(android.os.SystemClock.elapsedRealtime()));
    }

    private void holdAfterSong() {
        boolean repeatOne = wantsRepeatOne(currentTrack());
        int following = repeatOne ? -1 : adjacent(1);
        clearSleepTimer();
        releasePlayer();
        audio.abandonAudioFocusRequest(focus);
        if (following >= 0) index = following;
        resumeGeneration = -1;
        savedPosition = 0;
        restoredQueue = true;
        publishRestoredTrack();
        publish(PlaybackState.STATE_PAUSED, "Sleep timer ended");
        endForeground();
    }

    private void publishRestoredTrack() {
        MusicApi.Track track = currentTrack();
        session.setMetadata(new MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, track.title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, track.artist).build());
    }

    boolean canSetLoop() { return prepared && !videoActive() && duration() >= 2000 + Math.round(500 * prefs.speed()); }
    int loopStart() { return loop.hasStart() || loopEnd() >= 0 ? loop.start() : -1; }
    int loopEnd() { return prepared ? loop.end(duration(), prefs.speed()) : -1; }

    void setLoopStart() {
        if (!canSetLoop()) return;
        loopGeneration = generation;
        loop.setStart(position());
        scheduleLoop();
    }

    boolean setLoopEnd() {
        if (!canSetLoop()) return false;
        loopGeneration = generation;
        boolean valid = loop.setEnd(position(), duration(), prefs.speed());
        scheduleLoop();
        return valid;
    }

    void clearLoop() {
        loop.clear();
        main.removeCallbacks(loopTick);
    }

    private void scheduleLoop() {
        main.removeCallbacks(loopTick);
        if (loopGeneration != generation) {
            loop.clear();
            return;
        }
        int end = loopEnd();
        if (end < 0 || !playing() || pendingSeek >= 0 || videoActive()) return;
        long delay = (long) Math.ceil((end - position()) / Math.max(0.1f, prefs.speed()));
        main.postDelayed(loopTick, Math.max(5, delay));
    }

    private final Runnable loopTick = () -> {
        int end = loopEnd();
        if (!playing() || pendingSeek >= 0 || end < 0 || loopGeneration != generation || videoActive()) return;
        if (position() >= end) seekTo(loop.start());
        else scheduleLoop();
    };

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
                if (sleepPauseGeneration == current) {
                    sleepPauseGeneration = -1;
                    // Retain a requested resume position for the next Play action.
                    if (resumeGeneration == current && savedPosition > 0) {
                        resumeGeneration = -1;
                        seekTo(savedPosition);
                    }
                    publish(PlaybackState.STATE_PAUSED, "Sleep timer ended");
                    return;
                }
                if (resumeGeneration == current) {
                    int target = Math.min(savedPosition, Math.max(0, duration() - 500));
                    if (target > 0) {
                        seekTo(target);
                        return;
                    }
                    resumeGeneration = -1;
                    savedPosition = 0;
                }
                resume();
            }
        });
        next.setOnCompletionListener(done -> trackCompleted(done, current));
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

    private void trackCompleted(MediaPlayer done, int current) {
        if (done != player || generation != current) return;
        if (sleepTimer.songEnd()) {
            holdAfterSong();
            return;
        }
        if (repeatsCurrent()) select(index);
        else if (canSkip(1)) skip(1);
        else if (repeat == REPEAT_ALL && (!prefs.flag("hideExplicit", false) || !queue.get(index).explicit)) select(index);
        else if (prefs.flag("autoplay", false)) continueWithRadio(current);
        else stopPlayback();
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
            if (restoredQueue || state == PlaybackState.STATE_ERROR) {
                if (restoredQueue && prefs.flag("hideExplicit", false) && currentTrack().explicit) {
                    publish(PlaybackState.STATE_PAUSED, "This song is hidden by the content filter.");
                    return;
                }
                if (restoredQueue || resumeGeneration == generation) resumeGeneration = generation + 1;
                restoredQueue = false;
                select(index);
            }
            return;
        }
        if (resumeGeneration == generation && pendingSeek >= 0) return;
        if (audio.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            publish(PlaybackState.STATE_PAUSED, "Another app is using audio.");
            return;
        }
        if (!restoringSong) startPreparedPlayer();
        reporter.play(currentTrack(), duration());
        publish(PlaybackState.STATE_PLAYING, queue.get(index).artist);
    }

    private void startPreparedPlayer() {
        applyVolume(1f);
        tuning.applyEqualizer(player);
        tuning.applySpeed(player);
        player.setLooping(repeatsCurrent());
        player.start();
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
                applyVolume(1f - step / 5f);
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
        clearSleepTimer();
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
        if (nextState == PlaybackState.STATE_BUFFERING) restoredQueue = false;
        long actions = PlaybackState.ACTION_STOP;
        if (canPlay()) actions |= PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE | PlaybackState.ACTION_PLAY_PAUSE;
        if (prepared) actions |= PlaybackState.ACTION_SEEK_TO;
        if (canSkip(-1)) actions |= PlaybackState.ACTION_SKIP_TO_PREVIOUS;
        if (canSkip(1)) actions |= PlaybackState.ACTION_SKIP_TO_NEXT;
        session.setPlaybackState(new PlaybackState.Builder().setActions(actions)
                .setState(state, position(), playing() ? prefs.speed() : 0f).build());
        if (foreground) promote();
        writeQueueCheckpoint();
        scheduleLoop();
        main.removeCallbacks(positionCheckpoint);
        if (playing()) main.postDelayed(positionCheckpoint, POSITION_CHECKPOINT_MS);
        if (changed != null) changed.run();
    }

    private final Runnable positionCheckpoint = new Runnable() {
        @Override public void run() {
            if (!playing() || destroyed) return;
            writeQueueCheckpoint();
            main.postDelayed(this, POSITION_CHECKPOINT_MS);
        }
    };

    private void writeQueueCheckpoint() {
        if (queueStore == null) return;
        try {
            queueStore.save(queue, originalQueue, index, position(), repeat, shuffle);
        } catch (IllegalStateException playerChanged) {
            // Keep the last checkpoint if a failing decoder can no longer report its position.
        }
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
        main.removeCallbacks(loopTick);
        if (videoActive()) player = video.song;
        discardVideo();
        restoringSong = false;
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
        clearSleepTimer();
        main.removeCallbacks(positionCheckpoint);
        writeQueueCheckpoint();
        destroyed = true;
        network.shutdownNow();
        releasePlayer();
        reporter.close();
        audio.abandonAudioFocusRequest(focus);
        session.release();
        super.onDestroy();
    }
}
