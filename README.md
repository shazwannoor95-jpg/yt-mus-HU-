# YTM Head Unit

An Android port of the anonymous song search and audio playback paths in
[ytm-rs](https://github.com/grapedjuice/ytm-rs) at
`99d404b9d78d0edb72361eb8c5c465ae6af57979`. The Android app uses platform
Views, MediaPlayer and MediaSession. Search follows the upstream WEB_REMIX
request and result structure. Playback requests the upstream visionOS player
and tries audio-only AAC HLS before progressive AAC.

The debug APK targets the TEST9278B head unit's reported API 27. It contains
DEX only, so it can install on ARM 32-bit without a native library. It runs
as a separate launcher app under package `my.ytmhu`. iDash3 can see its
active media session if iDash3 has notification access and selects that
session. No launcher changes are required to try the APK.

## Build

Run `./build-apk.sh` with Java, Android SDK Platform 35 and Android Build
Tools 35.0.0. On a standard SDK installation, set `ANDROID_JAR` to the
platform's `android.jar` and `ANDROID_BUILD_TOOLS` to the
`build-tools/35.0.0` directory. The script supports a JDK's `javac` or
Eclipse ECJ via `ECJ_JAR`. The cloud workspace has default paths for its
local SDK and ECJ. The output is `build/ytm-head-unit-debug.apk` and uses
a locally generated debug key.

Copy the APK to the head unit via USB or use
`adb install -r build/ytm-head-unit-debug.apk` with an authorized USB
debugging connection. Open **YTM Head Unit** from the app list.

## Scope

This build provides anonymous search, a queue from search results, playback
and standard Android media controls. Login, account library, downloads, lyrics,
video and the upstream desktop UI are outside this build. YouTube may refuse
anonymous streams depending on the track, region or network.

GPL-3.0-only. See [LICENSE](LICENSE).
