# Settings parity audit

Comparison baseline is the observed Windows ytm-rs 0.4.0 layout and upstream
[99d404b](https://github.com/grapedjuice/ytm-rs/tree/99d404b9d78d0edb72361eb8c5c465ae6af57979).
The pinned source has since reorganized the same settings into more independent
categories. The Android screen groups them as in the recordings.

Recordings 1 and 5 cover Playback, recordings 2 and 4 cover Lyrics, recording
7 covers General, and recording 8 covers About. The Appearance and Connections
recordings exceeded the workspace transfer limit, so those sections were
checked against upstream source and English labels. This inventory covers
settings and their underlying features. Desktop-only labels do not stand in
for implemented Android behavior.

| Desktop area | Android v0.4.0 | Remaining differences |
| --- | --- | --- |
| Account | Google sign-in, current account and sign-out. | Channel picker, credential storage parity and end-to-end Google verification on the head unit. |
| Playback | Autoplay, explicit badge filtering, Restricted Mode, volume mapping, pause fade, attenuation from loudness metadata, balance, Android output, EQ controls and speed/pitch. | Smart shuffle inserts recommendations among existing entries, gapless, crossfade and length/skip settings, trailing silence detection, podcasts, comments. Upstream only: rating-icon placement, dim/skip dislikes, confirmation or delayed removal and hold duration. |
| Playback / Sound | EQ presents ten target frequencies and maps device bands to the nearest controls. Presets include Flat, Bass boost, Vocal and Treble boost. | Firmware may expose fewer EQ bands or no equalizer. Upstream normalization can also boost quiet songs. Premium 256 kbps, headphone crossfeed, mono and clipping prevention need the corresponding playback paths. |
| Playback / Music videos | APK plays audio from supported items. | Official song/video pairing, video mode/quality, video ambient light, animated covers, optional video results and SponsorBlock still require Android counterparts. |
| Lyrics | Timing offset and line-synced trim, text size and weight, classic alignment, quick line changes, enable and prioritize LRCLIB and YouTube Music. | Romanization, translation and target language, translation scaling, full theme lyric styles, screen refresh-rate rendering, word-synced trim, and the Better Lyrics, Unison, BiniLyrics, Musixmatch, Portato and Legato sources. |
| Appearance | Upstream theme palette metadata and credits, accent overrides, accented controls, gradient/solid background, album colour tint, brightness, font, text zoom, seek knob. | Original theme shader and typography rules, custom accent/flat background picker, speed/pulse/animated background, frosted glass, advanced cover dimming, alternate app icons, window materials/title bars, visualizer and mini player layouts. |
| Connections and Import | ListenBrainz validates and stores a token in Android Keystore, and can send playing-now and completed listens. Settings link to Android media notifications and audio routes. | Last.fm, Discord presence, Stream Deck and Spotify sign-in/transfer. ListenBrainz offline retries and upstream queue persistence are pending. Actual submissions require a user opt-in and an available network. |
| General / Language and Window | YouTube song-info language/country, startup page and collapsed sidebar. | In-app UI translation, tray behavior, desktop title bars, keep-on-top and separate song announcements. Ongoing Android playback notification remains required by the foreground player. |
| General / Downloads | Existing direct AAC export lets the user pick a destination through Android. | Persistent/default download folder, album/playlist folders, automatically download liked songs, embed plain or timed lyrics, metadata and offline library. |
| General / Network and Storage | YouTube Music host connection check and decoded-artwork cache clear. Android manages its system proxy and output routes. | Backup stream toggle, AirPlay/local network scan, custom proxy and credentials, storage size and broader cache management. |
| Keyboard shortcuts | Space, Ctrl+Left/Right, Ctrl+F and Escape. | Upstream editable global bindings, media-specific shortcuts, command palette, find, seek, fullscreen, video fill, navigation reload, volume and zoom shortcuts. |
| About | APK version, upstream/source links, credits, What’s new and a manual GitHub update check. Android opens its normal APK installation flow after the user downloads. | Stable/nightly tracks, background download/automatic updates, full release notes and upstream download counts. |
