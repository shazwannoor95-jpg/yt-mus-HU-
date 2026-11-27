# YTM 0.4.7 maintenance and lyrics parity

## Authentication

`MusicApi.verifyAccount` emits `ACCOUNT_MENU_SIGNED_OUT` after the native account-menu response is classified as signed out. In 0.4.6 this meant an explicit `mainAppWebResponseContext.loggedOut=true` or any service's `logged_in=0` tracking field. The screenshot diagnostic does not contain the response, so it cannot identify which condition fired or prove the Google sign-in itself completed. The WebView could hand off too early because the return URL and a signing cookie were sufficient.

The return page now gets a bounded readiness check. It can finish automatically after YouTube Music reports `LOGGED_IN` and the app WebView has a session. When older pages supply no state, **Continue** explicitly requests native verification. Explicit signed-out pages stay in the dialog with recovery options. A navigation or cancellation invalidates outstanding callbacks. The native check prioritizes the explicit response flag and uses only GFEEDBACK as tracking fallback, in line with Metrolist. The original app-owned cookie store, HTTPS restrictions and anonymous playback path remain in use. REA/Craft review is static and does not establish authentication success. [Pinned ytm-rs account logic](https://github.com/grapedjuice/ytm-rs/blob/99d404b9d78d0edb72361eb8c5c465ae6af57979/src/ytmapi.rs) · [Metrolist account logic](https://github.com/MetrolistGroup/Metrolist/blob/main/shared/src/commonMain/kotlin/com/metrolist/shared/api/YouTubeRepository.kt) · [InnerTune login](https://github.com/z-huang/InnerTune/blob/main/app/src/main/java/com/zionhuang/music/ui/screens/LoginScreen.kt)

Settings and the sign-in dialog explain that WebView 69 is old and point to a firmware-supported WebView update or **Open separately**. The age hint is advisory. A newer provider cannot guarantee that Google will allow embedded sign-in. **Open separately** uses Android's HTTPS handler for official YouTube Music. Its browser session remains separate. YouTube Data API OAuth grants do not authenticate the native YouTube Music web-session client. The runtime did not have a real authenticated Google session for verification.

## Lyrics parity

| Feature | Android 0.4.6 | Android 0.4.7 |
| --- | --- | --- |
| Romanization | Missing | Source-provided TTML first, then opt-in Unison. Android ICU offers a local fallback on API 29+ and suppresses ambiguous Japanese kanji readings where Japanese is known. |
| Translation and Translate into | Missing | Source-provided translations first. Opt-in Unison fills missing lines. English and Bahasa Melayu are selectable alongside Indonesian, Japanese, Korean, Simplified Chinese and Russian. Availability depends on the selected source and provider. |
| Text scales | Original lyric size only | Independently saved romanization and translation scale alongside original lyric size. |
| Synchronized display | Original LRCLIB lines | Alternate text follows the original line timestamps and offsets. Plain lyrics have no synthesized timestamps. |
| Lyrics at display refresh | 250 ms lyric check | Optional display-scheduled checks only while playing in the visible lyrics panel. Balanced runs near 30 FPS or lower and High Quality near 60 FPS or lower. Lite and Ultra Lite retain the coarse check and reduced animation. Only changed lines update the view. |
| Other desktop details | Missing | Word-level highlighting, word-level alternate timing, and alternate singer lanes remain unavailable. Existing Android sources, offsets, line weight and styles are retained. |

Unison is an optional lyrics source, with original LRCLIB and YouTube Music preferences kept as defaults. TTML reads original line starts and keyed translations or transliterations. Missing entries can be sent to the upstream Unison translation endpoint after enabling an alternate. The cache holds at most eight responses of 48 KiB each for up to one day, with only the active result used by the current lyrics panel. Repeated lines share one request entry, and track changes cancel old translation requests.

The pinned GPL-3.0 ytm-rs code documents the Unison request and response shape and an unofficial Google Translate fallback. The Android implementation uses the documented Unison integration pattern and platform ICU. It does not rely on undocumented Google endpoints, client identifiers used after rate limits, or a bundled Google Cloud credential. The workspace proxy blocked direct Unison testing, so shipped translations and provider responses were verified using fixtures. Online language coverage and availability on a legacy WebView device still need a live check. [Pinned desktop translation implementation](https://github.com/grapedjuice/ytm-rs/blob/99d404b9d78d0edb72361eb8c5c465ae6af57979/src/translate.rs) · [Pinned TTML source](https://github.com/grapedjuice/ytm-rs/blob/99d404b9d78d0edb72361eb8c5c465ae6af57979/src/lyrics.rs)

## Playback and package

The mini player and main transport draw centered, solid Play/Pause shapes from the playback service state, with their existing 48 dp and 52 dp targets. Buffering disables playback toggles while the existing queue and service continue to own media state. The API 27 fixture publishes buffering, playing, paused, resuming and stopped states to both controls. This is a service-state test, not a live stream or physical background playback test.
