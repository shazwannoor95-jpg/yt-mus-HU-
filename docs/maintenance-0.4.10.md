# YTM Head Unit 0.4.10 — Runtime synced lyrics

The supplied Android emulator recording shows three lyric layers staying on the same groups while the player time advances and seeks. The recording does not expose the provider response or timestamp count. The code confirms two paths the old fixtures missed. The provider loop returned the first nonempty result, including plain text, while active-line lookup only received timed lines. Seeking also waited for the next polling sample because PlaybackService did not publish a position change. The 0.4.9 fixtures inserted timed lines and called the renderer directly. Enrichment itself did not remove timestamps, but it did force scrolling back to the active group and could interrupt manual reading.

## Changes

- The provider selector checks enabled timed sources before choosing an untimed fallback. The existing priority setting orders results of the same kind. LRCLIB timed results require an exact title and artist, a known duration within four seconds of the recording, and a timeline within the playback bounds (one-second end tolerance). Unison uses the video ID and rejects a conflicting returned ID. Lyrics loading waits for audio duration during startup buffering. Provider results are kept whole. The app shows **Synced lyrics unavailable — showing plain lyrics.** when a timed result cannot be matched.
- The playback service publishes the requested position immediately during a seek and resumes sampling MediaPlayer after its callback. Rapid seeks keep the latest target. The same timestamp controls highlighting for original, romanized and translated text. Activity sampling stops for plain lyrics or while the lyric panel is closed. Automatic following uses the same playback position in Ultra Lite and High Quality, with animation and sampling cadence governed by the existing profile policy.
- Enrichment updates the existing groups and keeps the visible group anchored after layout. A manual swipe suspends following for eight seconds, or until **Return to current lyric** is pressed. Tap a timed group to seek to it. Copy lyrics diagnostics by holding **Lyrics** or using the button at the bottom.

The Android provider behavior was compared with the pinned Windows implementation, which ranks synchronized LRCLIB hits ahead of plain hits and filters by recording duration. LRCLIB requests from this workspace were blocked by the proxy. Timed provider availability on the specific recording in the video and playback on an installed 0.4.10 device have not been verified. [Pinned upstream provider logic](https://github.com/grapedjuice/ytm-rs/blob/99d404b9d78d0edb72361eb8c5c465ae6af57979/src/lyrics.rs)

## Diagnostics example

API 27 Robolectric fixture after seeking, with a 400 ms lyric offset applied:

```text
YTM 0.4.10 lyrics
Provider: LRCLIB fixture
Timing: timed
Groups: 24
Valid timestamps: 24
Playback position: 16400 ms
Applied offset: 400 ms
Active index (zero-based): 15
Active timestamp: 16000 ms
Romanization: pending or unavailable
Translation: source-provided
Automatic follow: on
Effective profile: ULTRA_LITE
```

## Authentication protection

Baseline commit `a605eaa08f8423e10e1c5d7fc751a89804950afb`. The pre-existing audit Markdown remains local. **Protected code changes: NONE.** GoogleSignIn, WebSession, MusicApi, account actions and settings, credentials, authentication dependencies and existing tests match the baseline. Shared-file changes are limited to lyrics and playback position, plus build version metadata. Application ID `my.ytmhu` remains unchanged. Live Google account session persistence on a device remains unverified.

## Build and tests

Gradle passed all 43 regression tests, including all 40 pre-existing tests and three new API 27 tests. The playback fixture uses a simulated MediaPlayer with real production position, seek and UI paths. It covers buffering, pause/resume, coalesced forward and backward seeks, three-layer highlighting, enrichment, automatic follow, manual recovery, track changes and Ultra Lite.

The APK is 1,251,966 bytes and retains min API 27, application ID `my.ytmhu`, and the 0.4.9 signing certificate. It contains no native libraries. REA and Craft Anything compared the APKs and found no asset additions or removals. [Signed debug APK](../downloads/ytm-head-unit-0.4.10-debug.apk)
