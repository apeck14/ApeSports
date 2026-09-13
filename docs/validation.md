# Gridiron TV preview validation

Validated September 13, 2026.

- `assembleDebug`: successful.
- `testDebugUnitTest`: 4 tests, 0 failures, 0 errors. Covers NFL/team filtering, relative M3U URL resolution, rejecting HLS segment playlists as channel catalogs, and invalid/non-HTTP URLs.
- `lintDebug`: successful, 0 errors, 8 warnings. Warnings concern newer dependency versions, fixed TV orientation, backup-rule metadata, banner density folder, and localization.
- Final APK signature verification: successful, APK v1 and v2 signatures present. The tool also reports v1 metadata-entry warnings; the APK carries a verified v2 signature.
- APK installed and launched successfully on Android TV API 30 x86 emulator at 1280×720.
- Four HLS test players simultaneously reached `Playing`; screenshot saved as `GridironTV-preview.png`. Content is Big Buck Bunny test video, not NFL broadcasts.
- D-pad navigation and long-press OK opened game options. Selecting the second game changed the visible audio selection to that game.
- Full-screen view displayed the selected game; Back restored multiview. Two-view layout displayed two feeds. Returning to four views restored the other assignments and playback.
- Feed assignments and audio selection survived APK update/relaunch during testing.
- After pressing Home, logcat showed four separate ExoPlayer `Release` entries. No AndroidRuntime crash was reported in that check.

The emulator ran without audible output. Audio selection was checked through UI state and code behavior, not listening. Hardware performance, live NFL access, DRM/authentication, and actual audio output on the Hisense 65UGGR remain untested. DASH support is included as a dependency but was not exercised with a DASH stream. M3U parser tests do not constitute an end-to-end test against a real provider catalog.

The screenshot shows independently buffered test players; synchronization between feeds is not implemented.
