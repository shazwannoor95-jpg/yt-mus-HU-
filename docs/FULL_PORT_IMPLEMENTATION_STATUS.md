# Full port implementation status

Baseline Android commit `d45de60c6b26712bc31cec17c14ee4c6bcc61413` (0.4.10), upstream `99d404b9d78d0edb72361eb8c5c465ae6af57979`. Baseline checkout had two untracked reports, which remain local. Experimental branch is `experiment/automatic-timed-lyrics`.

| Phase | Checkpoint and next work |
| --- | --- |
| 1. Baseline and mapping | Imported all 91 existing audit rows with stable IDs. Auth baseline compared. Upstream provider, display and pairing logic traced. |
| 2. Lyrics and playback | Implemented automatic Unison/LRCLIB discovery, direct LRCLIB lookup, bounded normalized matching and timed cache. Bundled IPADIC, default-on lyrics alternates, locale selection and TTML karaoke shipped in the checkpoint. Other provider formats, alternate vocal lanes and word-aligned alternates remain open. |
| 3. Song/Video | Source investigation began. Upstream `backend.rs::music_video` tries a YouTube Premium paired relationship and falls back to ranked anonymous matches in `ytm.rs::searched_video/sure_video`. `video.rs` handles the native desktop decoder pipeline and frame presentation. AndroidX Media3 and Android MediaPlayer are candidate backends for a verified permitted stream. The TEST9278B has no evidenced supported parking signal. A permit and a verified video source are required before driver-display playback. |
| 4. Discovery and context actions | Android already has a Go to artist action in `MainActivity.showSongMenu` when a browse ID exists. Multiple artist identities and richer browse navigation remain open. See matrix for album, playlist, history and recommendation gaps. |
| 5. Playback and advanced features | Existing foreground playback remains. Queue restore, advanced transitions and desktop listening integrations remain open. |
| 6. UI, themes and settings | Prior responsive layouts and theme assets retained. Missing rendering and system-window equivalents tracked individually. |
| 7. Remaining parity | Unchanged rows remain in the matrix for follow-up, including desktop-only equivalents and provider constraints. |
| 8. Final release | This is an installable intermediate checkpoint. Full parity, device memory/performance, installed-storage and live-provider tests are still pending. |

## 0.4.11 lyrics mapping and evidence

The supplied 0.4.10 diagnostic for the affected song says **YouTube Music, plain, 55 groups, 0 valid timestamps**. That establishes a provider/matching issue. It does not establish that a valid timed result exists for the recording. The previous default disabled Unison's lyrics endpoint and LRCLIB only called search with raw metadata. The default now tries Unison by video ID and LRCLIB by exact title/artist/duration, raw search and a typography-normalized search when different. Explicit disabled providers and priority overrides still apply. Recording qualifiers are retained. There is no automatic merging of unrelated providers or synthetic timeline.

Upstream `src/lyrics.rs` ranks genuine line/word results over plain sources. It includes Unison, LRCLIB, YouTube Music and additional formats/providers such as Musixmatch, BiniLyrics, Portato and Legato. Android uses the existing Unison/LRCLIB integration and validates independent TTML span ranges for karaoke. Original line and valid end times and source-provided alternates remain intact. `KaraokeLineView` paints the active span from authoritative playback position, with proportional fill for straightforward LTR layout on Balanced and higher and full-span emphasis as the low-cost fallback. No character or translated-word timestamps are generated. Word trim and existing line trim adjust display timing separately. More complex bilingual and overlapping-vocal streams are pending.

`JapaneseDictionary` loads original IPADIC 0.9.0 binary resources directly from the signed APK only when needed. The resources were extracted from the upstream JAR with SHA-256 `24909fd751c0b439f7af5131b080eb65bc85062f0c4977ca4a71c76abe74e0b6`. Existing Kuromoji Apache-2.0 and IPADIC/NAIST attribution remains packaged. No app dependency is added by the dictionary or karaoke port. Romanization and translation are on by default with saved overrides retained. The Cloud translation fallback remains conditional on explicit user configuration. Prior local-dictionary JVM estimates are documented in the 0.4.9 notes. New head-unit memory and installed footprint have not been measured.

Resume from the verified 0.4.11 branch tip. Follow the Phase 3 evidence boundary for driver-display video and use the [parity matrix](FULL_PORT_PARITY_MATRIX.md) for the next source-mapped feature. The pinned desktop source and Android playback continue to use independent implementations. [Upstream lyrics](https://github.com/grapedjuice/ytm-rs/blob/99d404b9d78d0edb72361eb8c5c465ae6af57979/src/lyrics.rs)

## Verified checkpoint

Gradle passed 46 tests, including all prior and unmodified authentication tests. The new API 27 tests cover bundled dictionary use without downloading, locale overrides, real TTML span offsets and fill progress, and recording-qualifier matching. The signed APK is 15,506,722 bytes. Its eight IPADIC assets occupy 14,248,259 compressed bytes inside the APK and 33,426,790 bytes uncompressed. Android reads them without materializing a second dictionary file. Installed package storage and runtime memory were not measured on device. The 30 MB cap is satisfied. APK artifact inspection used REA and Craft Anything.

Protected auth code and dependency declarations match the baseline. Only version fields changed in build configuration/manifest. Application ID `my.ytmhu`, signed certificate, and API 27 minimum were verified. No account storage operations were introduced. Live account session persistence and deployment were unverified. Both lyric endpoints failed through the workspace proxy with HTTP 403 on CONNECT. No emulator or hardware device was attached to validate live timing, progressive rendering or playback.

Artifact: `downloads/ytm-head-unit-0.4.11-debug.apk`.
