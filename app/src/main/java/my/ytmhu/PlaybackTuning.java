// SPDX-License-Identifier: GPL-3.0-only
package my.ytmhu;

import android.media.MediaPlayer;
import android.media.PlaybackParams;
import android.media.audiofx.Equalizer;

final class PlaybackTuning {
    static final int[] BANDS = {31, 62, 125, 250, 500, 1000, 2000, 4000, 8000, 16000};
    private final AppSettings prefs;
    private Equalizer equalizer;
    private boolean changedSpeed;

    PlaybackTuning(AppSettings prefs) {
        this.prefs = prefs;
    }

    void applyVolume(MediaPlayer player, double loudness, float envelope) {
        String normalize = prefs.text("normalization", "off");
        double reduction = "off".equals(normalize) || !Double.isFinite(loudness) ? 0 :
                Math.max(0, loudness + ("quiet".equals(normalize) ? 6 : 0));
        float gain = envelope * (float) Math.pow(10.0, -reduction / 20.0);
        float balance = prefs.number("balance", 0) / 100f;
        player.setVolume(gain * (1f - Math.max(balance, 0f)), gain * (1f + Math.min(balance, 0f)));
    }

    void applySpeed(MediaPlayer player) {
        boolean enabled = prefs.flag("speedEnabled", false);
        if (!enabled && !changedSpeed) return;
        float speed = prefs.speed();
        float pitch = prefs.flag("keepPitch", true) ? 1f : speed;
        try {
            player.setPlaybackParams(new PlaybackParams().allowDefaults()
                    .setAudioFallbackMode(PlaybackParams.AUDIO_FALLBACK_MODE_DEFAULT).setSpeed(speed).setPitch(pitch));
            changedSpeed = enabled;
        } catch (RuntimeException unsupported) {
            // Older devices can lack a supported rate or stretch implementation.
        }
    }

    void applyEqualizer(MediaPlayer player) {
        if (!prefs.flag("equalizer", false)) {
            releaseEqualizer();
            return;
        }
        try {
            if (equalizer == null) equalizer = new Equalizer(0, player.getAudioSessionId());
            short[] range = equalizer.getBandLevelRange();
            for (short band = 0; band < equalizer.getNumberOfBands(); band++) {
                int frequency = equalizer.getCenterFreq(band) / 1000;
                int level = bandGain(frequency) * 100;
                equalizer.setBandLevel(band, (short) Math.max(range[0], Math.min(range[1], level)));
            }
            equalizer.setEnabled(true);
        } catch (RuntimeException unsupported) {
            releaseEqualizer();
        }
    }

    private int bandGain(int frequency) {
        int closest = nearestBand(frequency);
        switch (prefs.text("eqPreset", "custom")) {
            case "flat": return 0;
            case "bass": return closest <= 125 ? 5 : closest == 250 ? 2 : 0;
            case "vocal": return closest >= 500 && closest <= 4000 ? 3 : 0;
            case "treble": return closest >= 4000 ? 5 : closest == 2000 ? 2 : 0;
            default: return prefs.number("eqBand" + closest, 0);
        }
    }

    static int nearestBand(int frequency) {
        for (int i = 1; i < BANDS.length; i++) {
            if (frequency <= Math.sqrt((double) BANDS[i - 1] * BANDS[i])) return BANDS[i - 1];
        }
        return BANDS[BANDS.length - 1];
    }

    void release() {
        changedSpeed = false;
        releaseEqualizer();
    }

    private void releaseEqualizer() {
        if (equalizer == null) return;
        try {
            equalizer.release();
        } catch (RuntimeException ignored) {
            // Failure to release the device effect must not prevent player cleanup.
        } finally {
            equalizer = null;
        }
    }
}
