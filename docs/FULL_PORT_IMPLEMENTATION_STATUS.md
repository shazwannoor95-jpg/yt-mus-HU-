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
