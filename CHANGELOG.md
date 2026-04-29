# Changelog

All notable changes to Anchor Launcher will be documented here.

## [0.2] - 2026-04-29

### Added
- **Alphabetical section dividers toggle** — new option in App Drawer → Advanced to disable the inline A–Z section headers. On by default.

### Fixed
- Icons and labels were clipped inside their cells. The square-cell sizing now re-fits icon and padding to the computed cell size after Lawnchair's layout pass, so content never overflows regardless of grid size or screen density.
- Swiping down from the status bar area while already on the top row was navigating to the row below instead of playing the bounce animation. Now consistent with the workspace swipe gesture.

## [0.1] - 2026-04-28

Initial release of Anchor Launcher, forked from Lawnchair 16-dev (based on AOSP Launcher3).

### Added
- **Spatial grid transpose** — icons stay at the same physical screen position when the device rotates. Grid remaps M×N → N×M on rotation; system rotation makes everything upright as usual.
- **Square cell layout** — cells are locked to the largest square that fits both orientations, with symmetric padding. Guarantees icons land at the same physical pixel after rotation.
- **Rotation crossfade** — suppresses the system rotation animation and replaces it with a fade to the dominant wallpaper colour (or plain crossfade as fallback) to hide the brief layout reflow. Configurable in Home Screen settings: Traditional / Instant / Crossfade.
- **2D navigation matrix** — home screens are arranged in rows. Swipe left/right between pages within a row; swipe down/up to move between rows. Each row remembers its own scroll position independently. Up to 5 rows configurable in settings.
- **Top-row bounce** — swiping down at the top row plays a spring-back nudge instead of navigating to a non-existent row.
- **Alphabetical section headers in app drawer** — inline letter headers between alphabetical groups.
- **A–Z letter index strip** — vertical letter strip on the right edge of the app drawer. A pill highlights all sections currently visible on screen. Tap or drag to jump to a section. Toggle in App Drawer → Advanced.
- **Pull-down notifications suppressed in multi-row mode** — status bar swipe-down routes to row navigation to match the workspace gesture. Configurable in settings.
- **Smartspace disabled by default** — the top bar occupied grid row 0 and broke spatial consistency; disabled on first launch.
- **Hotseat removed** — Launcher3's hotseat has hardcoded assumptions incompatible with spatial transposition. Replaced by a regular grid row.
- **About screen credits** — Lawnchair and AOSP Launcher3 credited in the About screen with links to their source repositories.

### Build / publishing
- Application ID: `se.willovapps.anchor`
- Triple-T (gradle-play-publisher) wired up for Play Store publishing via the `play` product flavor
