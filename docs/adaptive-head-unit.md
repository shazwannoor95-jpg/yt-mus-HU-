# YTM Head Unit 0.4.6 adaptive layout and performance

The upgrade starts from 0.4.5 (`a6c006e`) on `experiment/adaptive-head-unit`. The application uses Android Views, the existing YouTube Music WebView session, and the existing playback service, media session and notification. The 0.4.5 authentication regression tests run as part of the release suite.

## Layout

- Auto density selects Compact for the reference 1024 × 600 landscape and Standard for roomier windows. Window metrics use system-bar and cutout insets on API 30 and later. API 27–29 use the resource configuration. The app uses Android font scale with the separate Text size preference.
- Under Appearance › Display & Layout, Auto, Ultra Compact (70%), Compact (85%), Standard (100%) and Large (115%) change spacing and artwork dimensions. Typography uses readable presets and follows system text scale. Primary controls retain 48 dp hit areas. Reset restores automatic layout and the default app text adjustment.
- The reference window starts with a 68 dp icon rail. Text navigation expands to 168–180 dp where space permits, and opens as a drawer in narrow windows. Home, Explore, Library, Liked Music, Account, Settings and the existing pinned playlists remain reachable.
- Browse pages bind visible shelves and rows in a native list. Shelves bind only the current horizontal page. Album shelves use square artwork and two-line titles. Collection and song results use song rows, and the type of each live data section is preserved. Recent page data is retained for two minutes with a two-page or four-page cap. A previously loaded page can remain visible if a refresh fails. Account changes clear the browse cache.
- Settings uses a 176–200 dp category panel when there is room and a category chooser in narrow windows. Theme previews render at most two rows at once. The persistent player is 64–72 dp on regular windows. Narrow windows get separate seek or metadata rows, and the Playback options menu keeps secondary actions reachable.
- Layout changes recreate the screen with its route, query, settings tab, vertical position and player panel. Profile changes update budgets and effects in place. The audio stream, output quality, queue and media controls run through the playback service independently of the UI profile.

## Profile policy

| Profile | Intended use | Decoded artwork cache cap | Compressed artwork disk cap | Thumbnail / detail ceiling | Background |
| --- | --- | ---: | ---: | ---: | --- |
| Auto | All devices by default | Effective profile | Effective profile | Effective profile | Effective profile |
| Ultra Lite | Critical memory pressure or a very small app heap | 1 MiB | 0 | 128 / 256 px | Precomputed album tint |
| Lite | Compatibility fallback | 2 MiB | 8 MiB | 192 / 384 px | Precomputed album tint |
| Balanced | 512 MB–2 GB target, including the 1 GB reference | 4 MiB | 16 MiB | 256 / 576 px | Cached 12 × 12 album sample |
| High Quality | 2–4 GB target and capable devices above 4 GB | 8 MiB | 32 MiB | 320 / 768 px | Cached 18 × 18 album sample |

Cache ceilings are working budgets. The decoded LRU is also capped at approximately one sixteenth of the reported app heap, with a 2 MiB limit below 768 MiB total RAM. Attached views and active decodes use additional memory. The disk cache is disabled below 128 MiB free storage, expires entries after seven days and is capped at 384 files. Artwork requests use display-size buckets, one or two bounded worker slots, same-size request deduplication, and cancellation when views detach. The backgrounds use CPU-precomputed samples and ordinary Android drawing supported on the GLES 2 reference.

Auto considers usable total memory, Android's available memory and pressure threshold, heap class, low-RAM classification and advertised GLES capability. A capable 2 GB device can use High Quality when usable RAM is at least 1,800 MiB, the app heap is at least 128 MiB and memory headroom is at least 384 MiB. A capable 512 MB or 1 GB device can start in Balanced. An unknown graphics capability caps selection at Balanced. Manual selections retain the same safety ceilings. Low-memory callbacks, sustained slow-frame samples and available-memory checks can lower the effective level. Recovery advances one level after at least 90 seconds with sufficient headroom. Performance settings show the saved choice, effective profile and fallback reason.

## Verification

Run `gradle :app:testDebugUnitTest :app:assembleDebug` with JDK 17, Gradle 8.11.1 and Android SDK 35. The Android module retains `minSdk 27`, `targetSdk 32`, application ID `my.ytmhu` and uses the existing debug key in `build/debug.keystore` when present. APK contents are managed code with no ABI-specific libraries.

The release build passed all 27 tests. The debug APK is `downloads/ytm-head-unit-0.4.6-debug.apk`, version code 11. Its verified certificate matches 0.4.5. The APK grew from 995,013 bytes to 1,015,453 bytes (+2.1%). All 47 packaged resource and asset files matched the previous APK.

The layout suite exercises 800 × 480, 1024 × 600, 1280 × 720, 1280 × 800, 1920 × 720, 1920 × 1080, 2000 × 1200 and 2560 × 1440 fixture windows, plus short and high-density variants. It checks both sidebar preferences, primary control bounds, seek width and a large text configuration. Profile and queue checks run with Android API 27, 31 and 35 fixtures. Separate policy tests cover RAM tiers, the 2 GB overlap, memory pressure, repeated slow frames, recovery and manual choice.

A 1,000-song layout fixture in Robolectric created 1,000 rows with the eager container pattern. The virtualized list bound 10 rows initially and 20 total after jumping to song 500. These are measured view creation counts in the same host workload. They do not estimate device PSS or frame time. Before and after Home and Playback layout captures use the same UI code and fixture data under API 31 native graphics at 1024 × 600. The covers are blank because the fixture avoids live account and artwork traffic.

The project compared the supplied references with the pinned Windows `ytm-rs` source (`99d404b`), primarily the sidebar, shelf and mini-player layout code. REA and Craft Anything checked the Windows installer and compared the Android packages. The Windows installer is an Inno Setup wrapper. The Android design adapts the layout patterns to touch and uses static or cached color effects.

There was no connected physical head unit or bootable Android emulator in this workspace. The recorded 1 GB RAM, 315.2 MB available memory and API 27 are hardware-reference observations. The historical API 31 sign-in error can be from another device. Cold/warm start timing, PSS, CPU, GPU frames, sustained streaming, real account sign-in, audio focus under load, Bluetooth and steering-wheel controls remain physical-device checks. Older or rooted firmware can limit WebView support. Account verification still depends on Google and YouTube Music accepting the app's existing WebView session.

## Parked head-unit checklist

1. Install over the existing APK signed by the same key. Confirm runtime API, actual dp window and the effective profile in Settings › Performance.
2. Compare both builds at the same density, font setting, device and playlist. Record cold/warm launch, Home load, scrolling, PSS and missed frames with `adb shell dumpsys meminfo my.ytmhu` and `adb shell dumpsys gfxinfo my.ytmhu`.
3. Switch Auto, Balanced and Lite while playing. Confirm the track, position and queue remain. Repeat under memory pressure and check the reported fallback.
4. Test playback in the background, audio focus, a network interruption, track switching and available steering-wheel controls.
5. Confirm both sidebar modes, larger text, playlist access, volume, seek, lyrics and mini player. Try Google sign-in and library refresh with the account holder present.
