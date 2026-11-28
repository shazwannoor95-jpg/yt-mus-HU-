# Full port implementation status

Baseline Android commit `d45de60c6b26712bc31cec17c14ee4c6bcc61413` (0.4.10), upstream `99d404b9d78d0edb72361eb8c5c465ae6af57979`. Baseline checkout had two untracked reports, which remain local. Lyrics checkpoint `b66afd3fefab89a90213b359c61dff28b88916ed` is published on `experiment/automatic-timed-lyrics`. Discovery checkpoint branch is `experiment/music-discovery`.

| Phase | Checkpoint and next work |
| --- | --- |
| 1. Baseline and mapping | Imported all 91 existing audit rows with stable IDs. Auth baseline compared. Upstream provider, display and pairing logic traced. |
| 2. Lyrics and playback | Implemented automatic Unison/LRCLIB discovery, direct LRCLIB lookup, bounded normalized matching and timed cache. Bundled IPADIC, default-on lyrics alternates, locale selection and TTML karaoke shipped in the checkpoint. Other provider formats, alternate vocal lanes and word-aligned alternates remain open. |
| 3. Song/Video | Source investigation began. Upstream `backend.rs::music_video` tries a YouTube Premium paired relationship and falls back to ranked anonymous matches in `ytm.rs::searched_video/sure_video`. `video.rs` handles the native desktop decoder pipeline and frame presentation. AndroidX Media3 and Android MediaPlayer are candidate backends for a verified permitted stream. The TEST9278B has no evidenced supported parking signal. A permit and a verified video source are required before driver-display playback. |
| 4. Discovery and context actions | 0.4.12 adds validated multi-artist navigation, local recent searches, and a mixed search scope with provider-ranked top result. Artist, album and library page gaps remain open. See matrix for album, playlist, history and recommendation gaps. |
| 5. Playback and advanced features | Existing foreground playback remains. Queue restore, advanced transitions and desktop listening integrations remain open. |
| 6. UI, themes and settings | Prior responsive layouts and theme assets retained. Missing rendering and system-window equivalents tracked individually. |
| 7. Remaining parity | Unchanged rows remain in the matrix for follow-up, including desktop-only equivalents and provider constraints. |
| 8. Final release | This is an installable intermediate checkpoint. Full parity, device memory/performance, installed-storage and live-provider tests are still pending. |

## 0.4.11 lyrics mapping and evidence

The supplied 0.4.10 diagnostic for the affected song says **YouTube Music, plain, 55 groups, 0 valid timestamps**. That establishes a provider/matching issue. It does not establish that a valid timed result exists for the recording. The previous default disabled Unison's lyrics endpoint and LRCLIB only called search with raw metadata. The default now tries Unison by video ID and LRCLIB by exact title/artist/duration, raw search and a typography-normalized search when different. Explicit disabled providers and priority overrides still apply. Recording qualifiers are retained. There is no automatic merging of unrelated providers or synthetic timeline.

Upstream `src/lyrics.rs` ranks genuine line/word results over plain sources. It includes Unison, LRCLIB, YouTube Music and additional formats/providers such as Musixmatch, BiniLyrics, Portato and Legato. Android uses the existing Unison/LRCLIB integration and validates independent TTML span ranges for karaoke. Original line and valid end times and source-provided alternates remain intact. `KaraokeLineView` paints the active span from authoritative playback position, with proportional fill for straightforward LTR layout on Balanced and higher and full-span emphasis as the low-cost fallback. No character or translated-word timestamps are generated. Word trim and existing line trim adjust display timing separately. More complex bilingual and overlapping-vocal streams are pending.

`JapaneseDictionary` loads original IPADIC 0.9.0 binary resources directly from the signed APK only when needed. The resources were extracted from the upstream JAR with SHA-256 `24909fd751c0b439f7af5131b080eb65bc85062f0c4977ca4a71c76abe74e0b6`. Existing Kuromoji Apache-2.0 and IPADIC/NAIST attribution remains packaged. No app dependency is added by the dictionary or karaoke port. Romanization and translation are on by default with saved overrides retained. The Cloud translation fallback remains conditional on explicit user configuration. Prior local-dictionary JVM estimates are documented in the 0.4.9 notes. New head-unit memory and installed footprint have not been measured.

Resume from the verified 0.4.12 discovery branch tip. Follow the Phase 3 evidence boundary for driver-display video and use the [parity matrix](FULL_PORT_PARITY_MATRIX.md) for the next source-mapped feature. The pinned desktop source and Android playback continue to use independent implementations. [Upstream lyrics](https://github.com/grapedjuice/ytm-rs/blob/99d404b9d78d0edb72361eb8c5c465ae6af57979/src/lyrics.rs)

## Verified checkpoint

Gradle passed 46 tests, including all prior and unmodified authentication tests. The new API 27 tests cover bundled dictionary use without downloading, locale overrides, real TTML span offsets and fill progress, and recording-qualifier matching. The signed APK is 15,506,722 bytes. Its eight IPADIC assets occupy 14,248,259 compressed bytes inside the APK and 33,426,790 bytes uncompressed. Android reads them without materializing a second dictionary file. Installed package storage and runtime memory were not measured on device. The 30 MB cap is satisfied. APK artifact inspection used REA and Craft Anything.

Protected auth code and dependency declarations match the baseline. Only version fields changed in build configuration/manifest. Application ID `my.ytmhu`, signed certificate, and API 27 minimum were verified. No account storage operations were introduced. Live account session persistence and deployment were unverified. Both lyric endpoints failed through the workspace proxy with HTTP 403 on CONNECT. No emulator or hardware device was attached to validate live timing, progressive rendering or playback.

Artifact: `downloads/ytm-head-unit-0.4.11-debug.apk`.


## 0.4.12 discovery mapping

Desktop ytm-rs carries ordered artist credits and navigates by their browse endpoints. The secondary InnerTune reference uses a dedicated artist-ID route and Metrolist separates song menus from artist screens. Android now retains verified YouTube Music artist/user-channel IDs across track parsing, browse cards, radio and queue insertion. **Go to artist** opens an identified artist directly or asks the listener to choose among credited artists. It does not guess by display name. The original primary artist display remains intact for existing lyric and stream behavior.

Mixed search now sends an unfiltered query on the existing provider route, uses the provider’s ranked top result where present, and retains the explicit Songs, Albums, Artists and Playlists scopes. Holding **Search** or submitting an empty query opens up to eight recent local queries. These are independently clearable. No remote suggestion endpoint or new credentials were added. The remaining discovery work starts with dedicated artist/album navigation and the library/history gaps in the matrix.

Song/Video source mapping remains in Phase 3 above. No video view or start action was enabled on hardware lacking a reliable parked-use signal. A validated video backend and parking source, followed by audio recovery and decoder tests, remain prerequisites for enabling that mode.

The 0.4.12 suite passed 49 tests with zero failures or skips. Three new API 27 tests cover stable artist identity through queue insertion, persisted bounded search history and ranked mixed-search rendering. The initial full run hit the production dictionary's heap-headroom fallback in an existing fixture. The final run gave the host test worker a 1 GiB heap and still ran the simulated low-memory cases. The app's memory settings stayed unchanged.

The signed APK is 15,509,354 bytes. Application ID, API 27 minimum and signing certificate match 0.4.11. No native libraries or new dependencies were introduced. Android uses Kuromoji Core 0.9.0 under Apache-2.0, IPADIC/NAIST data, Korean Romanizer 2.0.1 under MIT, and Unicode Unihan data with bundled notices. AGP, JUnit and Robolectric remain build/test tools. REA and Craft Anything verified APK differences. Artifact: `downloads/ytm-head-unit-0.4.12-debug.apk`.

Protected auth code and dependencies remain unchanged. The `MusicApi` diff is limited to track metadata, search request parameters and radio byline parsing. Changes in `MainActivity` and `PlaybackService` are outside protected account paths. The existing auth tests passed unchanged, including AccountVerification (7), GoogleSignIn (2), Android31 (9) and the legacy WebView advice test. No account data operations were added. Session persistence and discovery on a live device are unverified.

## 0.4.13 Now Playing and karaoke checkpoint

Branch `experiment/now-playing-lyrics-0413`, based on Android 0.4.12 commit `1863cbf9703c8646339f229a1e6e816754ffde00`. The packaged reference recordings show synchronized Japanese, romanization and English for one NMB48 recording on Android 0.4.12. The supplied clip does not establish word-level timings.

- Upstream `src/ui/karaoke.rs` and `src/preset.rs` inform the 0.37 default active anchor, themed overrides, eased follow, separate spacing and typography, and dimmed neighbors. Android uses a rounded wrapping romaji capsule, independently scaled translation, native scroll fades, and immediate follow on Lite/Ultra Lite or with quick changes enabled. Per-theme desktop blur and glow remain open.
- Upstream `src/ui/nowplaying.rs` informs the compact square artwork and adjacent metadata. The Android panel sizes to available width and height and preserves the current profile-dependent cover loading and sampled backdrop. Desktop backdrop motion and physical-device image sharpness remain unverified.
- Upstream `src/lyrics.rs::parse_lrc` informs enhanced LRC parsing. Android renders original-word or syllable sweep from TTML and explicit ordered LRC intervals, including wrapped spans. Text at an unbounded last marker, reversed intervals, repeated line stamps and crossing the next line fall back to line timing. Translated and romanized layers remain line-aligned. Lite/Ultra Lite use the existing playback clock. Timed sources, lookup ordering, cache bounds and existing playback remain in place.
- The clipboard diagnostics report provider outcomes, cache hit, actual timed span counts, the current span/fill, alternate alignment, follow mode, offsets and the active rendering mode. Live Unison/LRCLIB, broader provider and format support, alternate vocal lanes, same-recording karaoke comparison and native head-unit performance remain pending.
- The existing seek-knob implementation passed the API 27 preference test with a playable duration. No seek-knob code change was required.

Gradle passed 51 tests, zero failures and zero skips. API 27 fixtures exercise enhanced LRC timestamps, malformed inputs, rewind, follow and alternate-text updates. An API 31 native graphics fixture verified progressive highlight pixels over wrapped rows. These results do not represent an Android emulator, external provider or physical head unit. This environment has no installed Android emulator. No live provider or Google account session was exercised.

Signed APK `downloads/ytm-head-unit-0.4.13-debug.apk`, 15,514,178 bytes, SHA-256 `ff143b4f8ce3ba58d58bf9b7dc99f003ed191cbfb86f731132547ab47a0fd2f6`. API 27 minimum, application ID `my.ytmhu`, and APK certificate match the prior version. Protected authentication files, dependency declarations, account storage paths, and authentication tests match 0.4.12. All protected tests passed. REA verified the APK digest and member inventory. Craft Anything verified 2 changed members (DEX and versioned manifest), 66 identical, no additions or removals.

Resume the next experiment from this checkpoint. The independent authorized-media video path may proceed without a driver-parking integration. Production Song/Video controls require verified pairing and reliable supported parked gating on the driver display.

## 0.4.13 native video experiment

Branch `experiment/native-video-fixture-0413`, based on `43185424cde31de843a745f4552410f8320d349d`. Upstream `backend.rs::music_video` uses a provider-declared pair or a strict ranked match via `ytm.rs::sure_video`. A fallback match does not establish a timestamp mapping. Upstream `video.rs` uses separate decoder and audio clocks. The Android experiment uses the platform MediaPlayer and off-screen ImageReader surface, with no added decoder dependency or integration on the driver display.

A separate API 27+ instrumentation APK prepares a synthetic 320 × 180 baseline H.264/AAC clip while the source audio continues. Its assertions cover audio-only candidate rejection, off-screen decoded frames, seek, pause, resume, one playing source at handoff and restoration after video release. FFprobe confirmed the two generated fixtures. Gradle built the test APK successfully. REA/Craft Anything and the test manifest verify that the clips and instrumentation are contained in the separate test APK. Its size is 91,108 bytes and its certificate matches the app. There is no Android emulator or USB device available in this workspace. The native decoder, timing of the handoff and audio recovery still require a runtime run.

On an authorized emulator or permitted non-driver-display Android device with the ordinary 0.4.13 app installed using the same key, install the instrumentation APK and run:

```sh
adb install -r downloads/ytm-native-video-fixture-0.4.13-test.apk
adb shell am instrument -w my.ytmhu.test/my.ytmhu.VideoFixtureInstrumentation
```

Next verify the harness on API 27 and a newer API. Then plan a verified media source and paired-version mapping before plumbing video into the single PlaybackService and MediaSession. Driver-display use still requires an OEM-supported parked-state gate. Production pairing, quality/decoder fallback, screen lifecycle, Song/Video selector and fullscreen are open. The main APK from the Now Playing branch is the installable app update.

## 0.4.14 lyric animation checkpoint

Branch `experiment/lyric-motion-0414`, based on `3d58f25fd954457c9c7e12d4001f7001d2a3b422`. The Now Playing and instrumentation checkpoints remain in its history. The two recordings named `20261128-0443-04.4718818.mp4` and `20261128-0443-31.6066679.mp4` were referenced in the brief. Neither was available in the workspace during implementation. The prior Mega Master Pack clips remain baseline visual references. No frame-by-frame comparison of the newer clips was possible.

| Pinned upstream evidence | Android adaptation |
| --- | --- |
| `ui/karaoke.rs::Trans`, `transform_origin`, `Cfg::new` and LyricStyle in `preset.rs` | The full lyric group scales around its leading edge and vertical center, or horizontal center in Classic. Default scale is 0.95 to 1.0 over 166 ms, reversing on exit. Default ytm-rs opacity transition is 195 ms. Theme-defined entry/exit curves, opacity ladders and per-state durations are read from the existing presets. Themes get a readability floor where desktop blur has no cheap Android equivalent. |
| `ui/karaoke.rs::scroll_duration/curve_duration` and `SCROLL_EASE` | ScrollView moves directly to the 0.37 or themed anchor. Nearby rows are translated so their starting screen positions remain continuous. A shared bounded frame callback settles rows independently. Default base is 750 ms with directional distance terms, using Bézier (0.86, 0, 0.2, 1). Theme overrides apply. |
| `ui/karaoke.rs::word_look/wobble` and `ui/nowplaying.rs::synced` | Genuine TTML and enhanced LRC ranges still follow PlaybackService. Balanced adds a soft 10% sweep edge. High Quality adds subtle glyph shadow and the source's 0.05 em side shift / 1.025 horizontal scale on the active single-row word. Sung fill fades on exit over 500 ms by default. Individual glyph waves, desktop blur and complex lyric effects remain open. |
| `ui/lyric_prefs.rs` and scrolling logic in `ui/karaoke.rs` | Visual motion adds no audio lead or lyric trim. Explicit seek revisions and discontinuities snap to the correct line. Manual scroll cancels automatic offsets. Async alternate enrichment preserves the viewport and may retarget the visible scroll. |
| `src/backdrop.rs`, `ui/nowplaying.rs::backdrop` | Existing bounded Android backdrop remains in use. This checkpoint does not extend desktop shader/ambient effects. |

Balanced and High Quality target roughly 30 and 60 motion updates per second on a 60 Hz panel. Lite uses simplified immediate follow and slower scale and opacity. Ultra Lite and system-disabled animations use static emphasis. Device FPS, installed footprint, CPU and peak memory were not measured.

Gradle passed 52 regression tests, zero failures or skips. API 27 fixtures verify natural line entry/exit, group movement, stagger, opacity and seek recovery. API 31 native graphics tests verify softened karaoke pixels, wrapped text and exit fading. No Android emulator or head unit was attached. Live provider timing, visual parity on the head unit, and Google session persistence remain unverified.

The signed APK is `downloads/ytm-head-unit-0.4.14-debug.apk`, 15,521,786 bytes. SHA-256 `0823108ae25e155b64bd2e6ab202e031db42ed535ffc5a198d36b7300f0e79da`. REA verified the APK digest and membership. Craft Anything found two changed entries (the manifest version and app DEX) and 66 identical entries. API minimum is 27, application ID is `my.ytmhu`, and the certificate matches 0.4.13. Protected authentication code, dependencies and tests match `3d58f25`. Protected tests passed. Account storage and session behavior were not altered by this change.

Next, run the native video fixture on a permitted device and verify a source with an authorized, explicit recording relationship. Driver-display activation still needs a supported OEM parked-state signal. Independent scoped video/backend work can proceed while those prerequisites are open.

## 0.4.15 off-screen video service checkpoint

Branch `experiment/service-video-0415`, based on `87521a77536023e692b43e5452c099c48206026c`. InnerTune's MusicService and Metrolist's MusicService keep one controller-facing MediaSession. AndroidX Media3's MediaCodecUtil separates decoder eligibility and hardware characteristics. This experiment keeps Android's platform MediaPlayer and reuses the app's existing service, queue, tuning, focus and MediaSession. It adds no decoder dependency. Upstream's authorized recording relationship and verified position mapping remain prerequisites for video discovery.

The service accepts an explicitly verified recording ID and an authorized file descriptor for off-screen output. A candidate prepares while the song continues. Dimension/duration checks, a bounded timeout and playback-position alignment precede handoff. The current player pointer switches after pausing the song. On return or candidate error, the service releases video, seeks the original song to the last known position and restores its playback state. Platform decoder selection, adaptive rendition selection, production discovery and visible UI remain open. The current interface accepts ImageReader output only.

Gradle passed 54 unit tests with zero skips/failures, including tests for wrong recording and duration rejection, single playing source, position recovery after a simulated decoder error and one MediaSession. The separate instrumentation APK now drives the real service using the original synthetic local H.264/AAC fixtures. Both APKs compile. No emulator or head unit was available for native decoder/surface, real audio continuity or production parked-state validation.

- App APK `downloads/ytm-head-unit-0.4.15-debug.apk`, 15,524,498 bytes, SHA-256 `25ed0ff0a407245d41d0ece5f68c2fe86f36afa35b3fc984812d356039c6cb4a`.
- Test APK `downloads/ytm-native-video-service-0.4.15-test.apk`, 101,370 bytes, SHA-256 `61e26339ea03c587b7004b435c5e8eb80b865d96f87fa6a66017f19673c24fb0`. Use the 0.4.15 app as its target. The instrumentation command from the preceding fixture still applies.
- REA package evidence `ev_7ae34d62069f275b5d6099a61412744665938bcbb0c9bd5d63068b77d6493b20`. Craft Anything found the app manifest version and app DEX changed, 66 members identical and no new app assets.
- Protected authentication production code, dependencies and tests match baseline. Application ID and signing certificate are unchanged. Existing accounts are not reset or migrated. Live account persistence remains unverified.

Next run the updated fixture on a permitted API 27 device and verify a legitimate paired service source and delivery mechanism. The reference head unit still needs an OEM-supported parked-state signal before visible driver-display activation.

## 04:43 reference recordings

The actual content identifies `20261128-0443-04.4718818.mp4` as Windows ytm-rs (desktop title/transport chrome) and `20261128-0443-31.6066679.mp4` as the Android app (Android navigation, Lyrics/Up next buttons, Android controls). Both are constant-rate 30 FPS captures. Both show NMB48's `太宰治を読んだか?`, with original Japanese, romaji and translation. The two captures start at different song positions. Frame indices below are zero-based and relative to the respective clips. A text-mask scan of decoded frames tracks each group's vertical position and relative contrast. The pixel values refer to per-capture crops and do not establish physical display timing.

| Event for `君と出会った日正直に言えば` | Windows | Android |
| --- | --- | --- |
| Before emphasis changes | At frame 80 (2.667 s) the rows are stable. The three observed Japanese text peaks are at y=255, 483 and 706 pixels in the crop. | At frame 183 (6.100 s) the three rows are stable. Their text peaks are y=146, 240 and 320. |
| Scroll starts and settles | From roughly frame 82 (2.733 s) the viewport starts moving. At frame 88 the rows have traveled about 94, 63 and 48 px respectively. The lower row is still settling after the newly active row's position stabilizes. | The highlight changes by frame 184 (6.133 s) with no row movement yet. At frame 192 all three tracked peaks have shifted 80 px. They move together and largely settle by frame 196 (6.533 s). |
| Entry and exit | At frames 93–98 (3.100–3.267 s) the incoming original line grows and brightens; the previous line keeps fading afterward. This matches the separate source transitions. | One brightness step is visible at frame 184. No comparable scale ramp or trailing release is resolved in this recording. |
| Karaoke and alternates | Romaji and translation move with the original group. The chosen transition shows whole-line emphasis; it does not establish source word timings. | All three layers remain aligned. The clip does not prove missing word timing or a regression in progressive karaoke. |

This comparison measures the two supplied reference builds, not post-build device behavior for 0.4.14 or later. Compression and repeated frames limit sub-frame measurements. Small percentage changes in scale were confirmed visually and mapped to the pinned source, not inferred from an absolute physical-size comparison.

## 0.4.16 visual scroll anticipation

Branch `experiment/lyric-scroll-lead-0416`, based on `848054c6924418bea67e209f1140c750fc06f0ab`. In the supplied transition, Windows started moving about 11 capture frames (0.37 s) before the new line brightened. The incoming glyph band's approximate width grew from 586 to 617 px in that capture. Android held its measured width at 233 px through the same line change. Existing foreground/background treatment and video encoding limit pixel accuracy. The Windows data corroborates the original small popout and separate visual scroll clock in `ui/karaoke.rs`.

The Android motion renderer now uses the preset's `scroll_offset` (default 500 ms) as a look-ahead for the next viewport target while playing. It anticipates only one genuinely timed upcoming line, preserves the stagger in progress as that line becomes active, and keeps timestamp evaluation, song offset and word trim on the existing audio clock. Manual reading, seeks and quick/Lite/Ultra Lite follow retain their prior behavior. No source timing is synthesized.

All 54 Gradle regression tests passed. The API 27 transition fixture verifies that the next group begins to move while the current lyric remains selected, and verifies the scale transition, interpolation and explicit seek afterward. No current device capture was available. The two supplied clips were the baseline inputs for comparison. Decoder compatibility, real performance and account session persistence still require device verification. The 0.4.15 off-screen code and the signed instrumentation APK remain available for that next video test.

Signed APK `downloads/ytm-head-unit-0.4.16-debug.apk`, 15,524,754 bytes, SHA-256 `1df40f17ee9474606c56f42fd34cb8bc9cd869b564baaad24b4493d3b4287db7`. REA and Craft Anything verified the digest and package changes. API 27 minimum, application ID and signing certificate match the 0.4.13 baseline. Protected code, test sources and dependencies match the baseline. No account state migration or reset was added. Authentication regression tests passed unchanged.

The next video checkpoint needs native decoder/surface verification with an authorized source and a permitted Android environment. Automatic discovery, adaptive renditions, visible Song/Video controls, fullscreen and the reference unit's OEM parked-state integration remain open. Remaining compatible discovery, library, queue, audio, appearance and settings items are tracked in the full parity matrix.

## 0.4.17 video capability checkpoint

Branch `experiment/video-output-policy-0417`, based on `4481802442cc019945d490fdd66be7d7224ce1d7`. 0.4.16 visual animation passed automated tests. User runtime verification is pending.

- Upstream `ui/nowplaying.rs::quality_rows` distinguishes the requested quality from the stream actually served. Android `VideoRenditions` orders known authorized candidates by playback profile, output size, frame rate, known bandwidth and reported decoder support. Codec preflight uses Android's regular codec list. API 29+ can report hardware capability. On API 27, unknown vendor implementation is treated conservatively. Platform MediaPlayer retains decoder ownership. The ordering is used for the local instrumentation preflight. Production multi-rendition discovery and decoder fallback require an authorized catalog and delivery mechanism.
- The service now reviews the off-screen Surface at a bounded cadence while video is active and restores audio when the Surface is invalid. It removes the callback on release. The candidate-preparation deadline and earlier seek, matching and playback constraints remain in place.
- Gradle passed 56 tests with no failures or skips. Two API 27 fixtures cover decoder ranking and policy bounds. The signed Android instrumentation APK compiles and probes platform codec capabilities before exercising the existing handoff. Native Surface-loss behavior, actual decoder selection, video throughput, real-device playback and parked restrictions remain unverified. No emulator or physical Android device was attached. No visible production video entry point was added.

The signed app APK `downloads/ytm-head-unit-0.4.17-debug.apk` is 15,527,718 bytes. The test APK `downloads/ytm-native-video-service-0.4.17-test.apk` is 101,737 bytes. REA verified app SHA-256 `db63ff99a47d757b215c1cb4c4df144417869af02221f0283dd728c55d89e5ab`. Craft Anything found two changes and 66 identical members. New dependencies and copied assets: none. API 27 minimum, application ID, signature, protected code, dependency declarations and authentication test sources match baseline. Authentication regression tests passed. Live session persistence remained unverified.

Independent continuation starts with collection/navigation. Production Song/Video still needs a legitimate provider pairing and media delivery path, native device validation and an OEM-supported parked signal for the driver display.
