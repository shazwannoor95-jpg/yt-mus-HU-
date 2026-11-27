// SPDX-License-Identifier: GPL-3.0-only
package my.ytmhu;

final class PerformancePolicy {
    enum Profile {
        ULTRA_LITE("Ultra Lite", 1, 0, 128, 256, 0, 1, 500),
        LITE("Lite", 2, 8, 192, 384, 0, 1, 500),
        BALANCED("Balanced", 4, 16, 256, 576, 12, 2, 250),
        HIGH_QUALITY("High Quality", 8, 32, 320, 768, 18, 2, 250);

        final String label;
        final int memoryMiB, diskMiB, thumbnailPx, detailPx, blurSample, workers, clockMs;

        Profile(String label, int memoryMiB, int diskMiB, int thumbnailPx, int detailPx,
                int blurSample, int workers, int clockMs) {
            this.label = label;
            this.memoryMiB = memoryMiB;
            this.diskMiB = diskMiB;
            this.thumbnailPx = thumbnailPx;
            this.detailPx = detailPx;
            this.blurSample = blurSample;
            this.workers = workers;
            this.clockMs = clockMs;
        }
    }

    static final class Capabilities {
        final int totalMiB, heapMiB, gles;
        final boolean lowRam;

        Capabilities(int totalMiB, int heapMiB, int gles, boolean lowRam) {
            this.totalMiB = totalMiB;
            this.heapMiB = heapMiB;
            this.gles = gles;
            this.lowRam = lowRam;
        }

        Profile ceiling() {
            if (heapMiB < 32) return Profile.ULTRA_LITE;
            if (heapMiB < 48 || (gles != 0 && gles < 0x20000)) return Profile.LITE;
            // 2 GB devices report usable RAM after system reservations.
            if (totalMiB >= 1800 && heapMiB >= 128 && !lowRam && gles >= 0x20000) return Profile.HIGH_QUALITY;
            return Profile.BALANCED;
        }
    }

    private static final long STEP_DOWN_MS = 20_000;
    private static final long RECOVERY_MS = 90_000;
    private final Capabilities capabilities;
    private final Profile ceiling;
    private Profile requested;
    private Profile effective;
    private String reason = "";
    private long pressureAt = -RECOVERY_MS;
    private long frameDownAt = -STEP_DOWN_MS;
    private int framePressure; // Consecutive bad samples with sufficient frame counts.

    PerformancePolicy(Capabilities capabilities, String preference, int availableMiB, int thresholdMiB,
                      boolean lowMemory, long now) {
        this.capabilities = capabilities;
        ceiling = capabilities.ceiling();
        requested = parse(preference);
        effective = target();
        if (lowMemory || (availableMiB > 0 && availableMiB <= thresholdMiB)) {
            memoryPressure(true, now);
        } else if (availableMiB > 0 && availableMiB < Math.max(96, thresholdMiB * 2)) {
            memoryPressure(false, now);
        } else if (effective == Profile.HIGH_QUALITY && (availableMiB == 0 || availableMiB < 384)) {
            effective = Profile.BALANCED;
            reason = "Available memory";
            pressureAt = now;
        }
    }

    private static Profile parse(String preference) {
        for (Profile profile : Profile.values()) {
            if (profile.name().equals(preference)) return profile;
        }
        return null;
    }

    private Profile target() {
        return requested != null && requested.ordinal() < ceiling.ordinal() ? requested : ceiling;
    }

    Profile effective() { return effective; }

    String summary() {
        String detail = reason;
        if (detail.isEmpty() && requested != null && requested != effective) detail = "Device capability";
        if (detail.isEmpty() && requested == null) detail = "Automatic selection";
        return detail.isEmpty() ? effective.label : effective.label + " · " + detail;
    }

    void choose(String preference, long now) {
        requested = parse(preference);
        Profile target = target();
        if (target.ordinal() <= effective.ordinal() || now - pressureAt >= RECOVERY_MS) {
            effective = target;
            reason = "";
        }
    }

    void memoryPressure(boolean severe, long now) {
        boolean recent = now - pressureAt < STEP_DOWN_MS;
        pressureAt = now;
        if (severe) {
            effective = Profile.ULTRA_LITE;
            reason = "System memory pressure";
        } else if (!recent) {
            Profile limit = effective == Profile.HIGH_QUALITY ? Profile.BALANCED : Profile.LITE;
            if (effective.ordinal() > limit.ordinal()) effective = limit;
            reason = "Available memory";
        }
        framePressure = 0;
    }

    boolean recoveryDue(long now) {
        return effective.ordinal() < target().ordinal() && now - pressureAt >= RECOVERY_MS;
    }

    void frames(int sampled, int slow, int availableMiB, int thresholdMiB, boolean lowMemory, long now) {
        if (sampled < 12) return;
        if (slow * 3 >= sampled) {
            if (++framePressure >= 2 && now - frameDownAt >= STEP_DOWN_MS && effective.ordinal() > 0) {
                effective = Profile.values()[effective.ordinal() - 1];
                reason = "Repeated slow frames";
                pressureAt = frameDownAt = now;
                framePressure = 0;
            }
        } else {
            framePressure = 0;
            if (slow * 10 <= sampled) recover(availableMiB, thresholdMiB, lowMemory, now);
        }
    }

    void recover(int availableMiB, int thresholdMiB, boolean lowMemory, long now) {
        if (!recoveryDue(now) || lowMemory) return;
        Profile higher = Profile.values()[effective.ordinal() + 1];
        int minimum = higher == Profile.HIGH_QUALITY ? 384 : capabilities.lowRam ? 128 : 96;
        if (availableMiB < Math.max(minimum, thresholdMiB * 2)) return;
        effective = higher;
        pressureAt = now;
        reason = effective == target() ? "" : "Recovering gradually";
    }

    int cacheMiB() {
        int limit = capabilities.totalMiB > 0 && capabilities.totalMiB < 768 ? 2 : effective.memoryMiB;
        return Math.min(limit, Math.min(effective.memoryMiB, Math.max(1, capabilities.heapMiB / 16)));
    }
}
