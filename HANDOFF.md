# Handoff — TV m3u8

## Project snapshot

- Project: Android IPTV player `TV m3u8`.
- Source root: `C:\Users\sky86\Documents\Codex\2026-10-30\vi\outputs\M3UPlayer`.
- Package/application ID: `vn.codex.m3uplayer`.
- Current branch: `master`; HEAD `0759087` (`Merge remote-tracking branch 'origin/master'`). At review time, working tree was clean and `master` matched `origin/master`.
- Android app is mostly implemented programmatically in one file: `app/src/main/java/vn/codex/m3uplayer/MainActivity.kt` (about 1,550 lines). There are no source test files in the project tree.

## Build setup

- Android Gradle Plugin: 8.7.3; Kotlin: 2.0.21; JDK toolchain: 17; compile SDK 35; min SDK 23; target SDK 35.
- Gradle wrapper is 9.6.0. Use the wrapper (`gradlew.bat`) instead of a machine-installed Gradle version.
- Common Windows command from project root: `./gradlew.bat :app:assembleDebug`.
- Debug APK output: `app/build/outputs/apk/debug/app-debug.apk`.
- In this environment, Gradle cache is under `C:\Users\sky86\.gradle`. Earlier builds needed that cache path to be writable. Do not put Gradle caches/build output in Git; `.gitignore` already excludes `.gradle*` and `build/`.
- Recent Kotlin compile completed successfully with `:app:compileDebugKotlin`. A full release build/signing setup was not confirmed in this review.

## Main behavior and code map

Everything below is in `MainActivity.kt` unless a path is specified.

- `onCreate` / `buildUi` (roughly lines 167–299): immersive, responsive home/player UI; Android TV D-pad/launcher support; header with settings/reload, clock, date and weather; centered no-playlist prompt.
- Playlist persistence and initial cache (roughly 300, 712, 974–1033): one saved URL source, cached parsed channels, refresh timestamp, configurable refresh cadence (1/3/7/30 days). Failed refresh keeps old channels. New URL replaces the previous URL and is loaded.
- Weather (roughly 313–610): local province/commune data from `app/src/main/assets/locations.json`; selected coordinates/name persist in SharedPreferences; current conditions from Open-Meteo, refreshed every 30 minutes. Click weather block to change location. Date/lunar calendar calculations are local (roughly 398–710).
- Settings (roughly 718–970): URL entry, startup action/channel, refresh interval, themed dialogs and software/license dialog. Startup modes are play channel N, focus channel N, or do nothing (default).
- M3U parsing/fetch/cache (roughly 983–1153): playlist network load on executor, parse `#EXTINF`, logo attributes and relative stream URLs; rejects HTML/empty playlist; cancellation/generation handling prevents stale requests replacing a newer list.
- Channel grid/list/logo loading (roughly 1154–1290): batch-render 24 channels per UI pass; logo cache and separate logo executor pool.
- Playback and remote input (roughly 1291–1495): Media3/ExoPlayer fullscreen playback; right-side channel list; Back returns home and stops playback; CH+/CH− and numeric channel input supported. Numeric input timeout is longer for first/second digit and third digit tunes immediately.
- Lifecycle cleanup (roughly 1497–1520): cancel scheduled work, interrupt playlist jobs, release player and shut down executors.
- App icon/banner: `app/src/main/res/mipmap-nodpi/m3utv_icon.webp` and `app/src/main/res/drawable-nodpi/tv_m3u8_banner.webp`.

## Known maintenance notes

1. `README.md` is partly stale. It says the app never auto-plays, but startup settings now support auto-play/focus. It mentions Gradle 8.9 although the checked-in wrapper is 9.6.0. It references the old in-app license menu name and has a dated note about local playlist paths even though the UI is URL-only. Update README when touching product behavior.
2. Playback intentionally accepts untrusted TLS certificates through a custom trust manager in `MainActivity.kt`; this was inherited/adapted for problematic IPTV hosts. Playlist fetching itself has its own `HttpURLConnection` path. Treat this as a deliberate security-sensitive playback compatibility choice; do not spread certificate bypass to unrelated requests.
3. App UI, networking, persistence, date logic and player are tightly coupled in `MainActivity.kt`. Prefer small focused edits and compile after each change. A larger modularization should preserve TV remote/focus behavior and be scoped separately.
4. `app/src/main/assets/licenses/GPL-3.0.txt`, root `LICENSE`, and `THIRD_PARTY_NOTICES.md` document the GPL-3.0-derived player code. Keep them aligned if playback code or notices change.
5. Past push failure was caused by accidentally tracking a Gradle cache JAR over GitHub's 100 MB limit. `.gitignore` now excludes build/cache directories. Before committing, inspect `git status` and `git diff --cached --stat`; never add `.gradle-userhome`, `.gradle`, build directories or local SDK configuration.
6. There are adjacent history commits that both mention GPL text reflow; HEAD includes a remote merge. Use current files/HEAD as source of truth rather than assuming prior conversational commit IDs describe the current remote state.

## Documentation follow-up

Recommended small cleanup: update `README.md` to accurately document startup behavior, Gradle 9.6 wrapper command, the current “THÔNG TIN PHẦN MỀM” menu name, and URL-only playlist behavior. This review recorded the discrepancy but did not modify README.
