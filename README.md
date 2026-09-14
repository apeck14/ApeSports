# ApeSports

<img src="app/src/main/res/drawable-xxxhdpi/ape_logo.png" alt="ApeSports" width="120" />

Native Android / Google TV NFL multiview app. Targets the Hisense 65UGGR; physical-TV performance remains unverified.

**No working NFL stream provider is integrated.** Playback requires direct HTTP(S) HLS, DASH, or video URLs. Website embeds are not playable media URLs. The experimental ESPN endpoint supplies matchups and live status, can fail or return stale data, and does not supply video. Scores and game clocks are not displayed. Hardcoded kickoff times cannot establish whether a game is still live. Stale scoreboard data is labeled and does not block selecting games last reported live. Saved streams remain usable independently.

Team logos are bundled from [Acrisure Stadium’s NFL logo directory](https://acrisurestadium.com/wp-content/uploads/gbl/nfl-team-logos/); logo display requires no network connection.

## Install on Google TV

Use **ApeSports.apk**, not the test APK. Transfer it to the TV (for example, by USB), open it with a file manager, and allow installation from that app when prompted. Launch **ApeSports** from the TV's apps list. Requires Android 6.0 or newer.

Alternatively, connect the TV using [Android's ADB setup](https://developer.android.com/tools/adb), then run `adb -s YOUR_TV_SERIAL install -r ApeSports.apk`. Wireless pairing availability and ports depend on the TV's OS; use the values shown by the TV.

Preview APKs are debug-signed. Updates require the same signing key; a fresh CI runner may produce a different key. Keep the local signing key for compatible preview updates. Uninstalling clears saved feeds.

The home page checks GitHub Releases once daily and offers **Settings → Check for updates** for a manual check. Downloaded APKs are checked for size, SHA-256, package, newer version, and the installed signing key. Android requires installation approval and may first ask to allow updates from ApeSports. Leaving the update screen cancels an unfinished download; retry from Settings. No release has been published yet.

## Build

Use JDK 17 and Android SDK platform 35. Configure the SDK in Android Studio or local `local.properties`, then:

```sh
./gradlew assembleDebug assembleDebugAndroidTest testDebugUnitTest lintDebug
adb -s YOUR_TV_SERIAL install -r app/build/outputs/apk/debug/app-debug.apk
```

On Windows use `gradlew.bat`. CI publishes the `ApeSports-debug-apk` artifact containing `ApeSports.apk`. Keep package/namespace `tv.gridiron.app` for installed-data compatibility.

For installable updates, increment `versionCode` and `versionName`, set `APESPORTS_KEYSTORE` (absolute path), `APESPORTS_KEYSTORE_PASSWORD`, `APESPORTS_KEY_ALIAS`, and `APESPORTS_KEY_PASSWORD` using the installed app's signing key, then run `./gradlew prepareUpdate`. After owner approval, attach both files from `app/build/outputs/update/` to the public GitHub Release tagged `v<versionName>` and mark it latest. The task only prepares files; it does not publish. Preserve the keystore securely; a different key cannot update existing installs.

## Use

- Select up to four live games, choose **Sources** to link named alternatives, then **Watch**. Saved-source counts do not verify video availability.
- **Saved streams → Sources** adds URLs or imports an NFL-filtered M3U catalog.
- During playback: OK selects audio; hold OK opens source selection, fullscreen, retry, and diagnostics. Back returns from expansion to multiview, then reveals controls, then leaves playback. Menu reveals controls; Done hides them. Remote play/pause affects all players.
- Pause state and the expanded game survive app recreation. Backgrounding releases players and cancels retries/imports; returning restores the selected feeds. Pausing all games allows normal TV idle sleep.
- One game fills the display; two stack vertically; four form a 2×2 grid. Three selections leave one empty quadrant. Always preserve footage aspect ratio with black fill.

## Diagnose problems

After a problem, open **Settings → Diagnostics** and export or share the report. Include the approximate time, number of games, and what you saw. Logs stay on the TV, rotate at 512 KB total, and capture playback/load errors, retries, video formats, decoder names, lifecycle, and 30-second buffer/network/frame samples. Per-player cumulative summaries measure first-frame startup time, playing/waiting/rebuffer duration, stall count, format changes, and error/recovery outcomes. Pauses are excluded from active durations; recovery duration uses wall time. Use the latest summary for each process session/player, not the sum of periodic snapshots. Source IDs are hashed; raw URLs stay excluded. Export reports also show logger overflow/write-failure counts. Java crash evidence is recorded best-effort; Android 11+ also supplies recent process-exit reason codes (including ANR and low-memory exits). Sudden power loss may lose queued events. URLs, headers, and exception messages are excluded; no telemetry service or automatic upload is used. Export uses a compatible document picker or sharing app. If no picker is installed, Export saves `ApeSports-diagnostics.txt` in the app's external files directory and displays its path; retrieve it with `adb pull /sdcard/Android/data/tv.gridiron.app/files/ApeSports-diagnostics.txt`. Explicitly exported copies remain until removed. Reports include app/device versions.

## Development constraints

Java sources are under `app/src/main/java/tv/gridiron/app/`.

- `HomeActivity`, `NflScoreboard`, `GameSources`: game selection, scoreboard, and per-event sources. `MainActivity` owns playback; `PlaybackWall` owns persistent slot views. `Ui` provides shared styling.
- Preserve unaffected players and surfaces during retries, source changes, and layout changes. Release hidden players on expansion and all players on backgrounding.
- Only one audio decoder may run. Audio handoff disables the old owner before granting the new one; revalidate the playback-thread ordering when upgrading Media3.
- Keep coordinated bandwidth allocation, decoder admission, and bounded recovery. Count transfers once. Fixed-quality or unknown-bitrate sources can exceed adaptive allowances; quality settings do not transcode video.
- Preserve server retry delays and cancellation on pause/replacement/background. Avoid speculative buffer reductions, periodic restarts, or hidden source probing.

Custom headers, provider login, DRM, token refresh, automatic source failover, and cross-game synchronization are not implemented. Source discovery must verify actual manifests, segments, and decoded video before claiming NFL availability.

Playback rejects responses identified as HTML or JSON with a source-selection message and no automatic retries. Generic or missing content types remain accepted for media servers that use them.

## Validation

Run `./gradlew connectedDebugAndroidTest` on a dedicated device/emulator. Tests install builds and modify app preferences. They cover decoding, surfaces, layouts, exclusive audio, allocation, retries, and source switching using synthetic clips and a local HTTP server; they do not establish real NFL-source or Hisense performance.

Optional visual review: add `-Pandroid.testInstrumentationRunnerArguments.class=tv.gridiron.app.UiReviewTest -Pandroid.testInstrumentationRunnerArguments.renderReview=true`. Screenshots use synthetic game data. Fixture regeneration instructions are in [the test assets](app/src/androidTest/assets/README.md).

Keep documentation focused on current behavior, setup, and constraints; use code and tests for implementation detail. Do not add per-change reports or historical test counts. Do not commit or push until the owner explicitly finalizes review.
