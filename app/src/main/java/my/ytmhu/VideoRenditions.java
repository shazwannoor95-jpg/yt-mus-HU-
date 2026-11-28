// SPDX-License-Identifier: GPL-3.0-only
package my.ytmhu;

import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.os.Build;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** Ranks verified renditions before offering one to the platform player. */
final class VideoRenditions {
    enum Decoder { HARDWARE, UNKNOWN, SOFTWARE, UNSUPPORTED }

    interface Decoders {
        Decoder forFormat(MediaFormat format);
    }

    static List<MediaFormat> rank(List<MediaFormat> formats, PerformancePolicy.Profile profile,
                                  int outputShortEdge, int bandwidthBits, Decoders decoders) {
        List<Choice> choices = new ArrayList<>();
        int ceiling;
        switch (profile) {
            case ULTRA_LITE: ceiling = 240; break;
            case LITE: ceiling = 360; break;
            case BALANCED: ceiling = 480; break;
            default: ceiling = outputShortEdge >= 1080 ? 1080 : 720;
        }
        if (outputShortEdge > 0) ceiling = Math.min(ceiling, outputShortEdge);
        for (MediaFormat format : formats) {
            String mime = format.getString(MediaFormat.KEY_MIME);
            int width = integer(format, MediaFormat.KEY_WIDTH);
            int height = integer(format, MediaFormat.KEY_HEIGHT);
            int edge = Math.min(width, height);
            int fps = frameRate(format);
            int bitrate = integer(format, MediaFormat.KEY_BIT_RATE);
            if (mime == null || !mime.startsWith("video/") || edge <= 0 || edge > ceiling
                    || Math.max(width, height) > ceiling * 3 || fps > 60
                    || (profile.ordinal() <= PerformancePolicy.Profile.LITE.ordinal() && fps > 30)
                    || (bandwidthBits > 0 && (bitrate <= 0 || bitrate > bandwidthBits * 0.7))) continue;
            Decoder decoder = decoders.forFormat(format);
            if (decoder == Decoder.UNSUPPORTED || (decoder == Decoder.SOFTWARE && edge > 360)
                    || (decoder == Decoder.UNKNOWN && edge > 480)) continue;
            choices.add(new Choice(format, decoder, "video/avc".equals(mime), edge));
        }
        choices.sort(Comparator.comparingInt((Choice it) -> it.decoder.ordinal())
                .thenComparing(it -> !it.avc).thenComparing((left, right) -> Integer.compare(right.edge, left.edge)));
        List<MediaFormat> ordered = new ArrayList<>(choices.size());
        for (Choice choice : choices) ordered.add(choice.format);
        return ordered;
    }

    private static int integer(MediaFormat format, String key) {
        try {
            return format.containsKey(key) ? format.getInteger(key) : 0;
        } catch (ClassCastException invalid) {
            return 0;
        }
    }

    private static int frameRate(MediaFormat format) {
        if (!format.containsKey(MediaFormat.KEY_FRAME_RATE)) return 0;
        try {
            return Math.round(format.getFloat(MediaFormat.KEY_FRAME_RATE));
        } catch (ClassCastException integer) {
            return integer(format, MediaFormat.KEY_FRAME_RATE);
        }
    }

    static Decoder platformDecoder(MediaFormat format) {
        String mime = format.getString(MediaFormat.KEY_MIME);
        Decoder best = Decoder.UNSUPPORTED;
        try {
            for (MediaCodecInfo codec : new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos()) {
                if (codec.isEncoder()) continue;
                try {
                    MediaCodecInfo.CodecCapabilities capabilities = codec.getCapabilitiesForType(mime);
                    if (capabilities.isFeatureRequired(MediaCodecInfo.CodecCapabilities.FEATURE_SecurePlayback)
                            || capabilities.isFeatureRequired(MediaCodecInfo.CodecCapabilities.FEATURE_TunneledPlayback)
                            || !capabilities.isFormatSupported(format)) continue;
                    Decoder decoder = classify(codec);
                    if (decoder == Decoder.HARDWARE) return decoder;
                    if (decoder.ordinal() < best.ordinal()) best = decoder;
                } catch (IllegalArgumentException unsupported) {
                    // This decoder does not advertise support for the MIME type.
                }
            }
        } catch (RuntimeException unavailable) {
            return Decoder.UNSUPPORTED;
        }
        return best;
    }

    private static Decoder classify(MediaCodecInfo codec) {
        if (Build.VERSION.SDK_INT >= 29) {
            if (codec.isHardwareAccelerated()) return Decoder.HARDWARE;
            if (codec.isSoftwareOnly()) return Decoder.SOFTWARE;
        }
        String name = codec.getName().toLowerCase(Locale.ROOT);
        if (name.startsWith("omx.google.") || name.startsWith("c2.android.") || name.startsWith("c2.google.")
                || name.startsWith("omx.ffmpeg.") || name.contains(".sw.")) return Decoder.SOFTWARE;
        return Decoder.UNKNOWN;
    }

    private static final class Choice {
        final MediaFormat format;
        final Decoder decoder;
        final boolean avc;
        final int edge;

        Choice(MediaFormat format, Decoder decoder, boolean avc, int edge) {
            this.format = format;
            this.decoder = decoder;
            this.avc = avc;
            this.edge = edge;
        }
    }
}
