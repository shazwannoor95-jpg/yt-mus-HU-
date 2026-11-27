# YTM Head Unit

An Android port of parts of [ytm-rs](https://github.com/grapedjuice/ytm-rs)
at `99d404b9d78d0edb72361eb8c5c465ae6af57979`. The landscape UI uses
Android Views, MediaPlayer and MediaSession. The original app icon comes
from the same upstream revision.

## Android preview

Settings includes Account, Playback, Lyrics, Appearance, Connections, General,
Keyboard shortcuts and About. Controls save on this device. See the
[settings parity audit](docs/settings-parity.md) for the Android behavior and
the remaining desktop controls.

- Home and Explore show YouTube Music shelves and artwork. Home mood chips
  reload the feed, and search supports songs, albums, artists and playlists.
- Settings › Account opens Google's sign-in page in Android System WebView.
  On return, the app verifies the YouTube Music account before displaying the
  account name. Its YouTube session stays in the app's WebView cookie store.
  Signed-in requests use that session for the home feed and saved playlists.
- Now Playing displays album art, lyrics with line-by-line sync from LRCLIB
  and a plain-text YouTube Music fallback. The app sends song title and
  artist to LRCLIB only when lyrics are opened.
- Up Next includes queue editing, play next, ratings, saved playlists,
  radio and links. The player also includes media volume and an Android
  route selector for outputs exposed by the firmware.
- Download exports a complete direct AAC stream to an Android document
  chosen by the user. HLS-only tracks, tags and an in-app offline library
  are pending.
- The signed-in player can use directly available AAC sources. The upstream
  signature solver and additional playback fallbacks have yet to be ported.
  YouTube refusals are displayed in the player.
- Google sign-in and device audio routes still need testing on the target
  head unit. Sign-in supports the dedicated Google and YouTube account
  pages in WebView. If Google rejects the flow, **Open separately** opens
  YouTube Music outside the APK and leaves its browser session separate.
- Word-by-word lyric providers, Listen together and the remainder of
  desktop settings are pending.

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
