# YTM Head Unit

An Android port of parts of [ytm-rs](https://github.com/grapedjuice/ytm-rs)
at `99d404b9d78d0edb72361eb8c5c465ae6af57979`. The landscape UI uses
Android Views, MediaPlayer and MediaSession. The original app icon comes
from the same upstream revision.

## Android preview

- Home and Explore show YouTube Music shelves and artwork. Home mood chips
  reload the feed, and search supports songs, albums, artists and playlists.
- Settings › Account opens Google's sign-in page in Android System WebView.
  On return, the app verifies the YouTube Music account before displaying the
  account name. Its YouTube session stays in the app's WebView cookie store.
  Signed-in requests use that session for the home feed and saved playlists.
- The player supports queue playback, shuffle, repeat, seek and background
  media controls. The signed-in player can use directly available AAC
  sources. The upstream desktop signature solver and further playback
  fallbacks have yet to be ported. YouTube can still deny playback, and its
  refusal is displayed in the player.
- Lyrics, Listen together, downloads and the remainder of the desktop
  settings are pending. Google sign-in and playback must still be verified
  on the target head unit. Google can reject login in an embedded WebView.

The debug APK targets the TEST9278B head unit's reported API 27. It contains
DEX only, so it can install on ARM 32-bit without a native library. It runs
as a separate launcher app under package `my.ytmhu`. iDash3 can see its
active media session if iDash3 has notification access and selects that
session.

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

GPL-3.0-only. See [LICENSE](LICENSE).
