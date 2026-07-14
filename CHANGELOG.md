# Changelog

All notable changes to Anchor Launcher will be documented here.

## [Unreleased]

### Added
- **Grid setup wizard** — a first-launch (and on-demand via Settings → Home Screen → Run setup wizard) guided flow: pick a wallpaper, choose your icon size and whether to show labels, then a density (Spacious / Balanced / Dense). Anchor computes a grid sized to your device and shows a live preview populated with your real app icons. The chosen icon size is **locked** — density only changes how many rows/columns fit and how much space is between them, never the icon size. A larger icon simply means fewer columns; the recommendation never overflows the screen or shrinks your icons to fit.
- **"Populate grid" preview toggle** in Settings → Home Screen → Grid — fills the grid preview's empty cells with sample app icons so you can judge a layout before applying it (never touches your real layout).
- **Wallpaper parallax** strength slider (Settings → Home Screen, shown with a custom wallpaper): sets how far the wallpaper drifts per page swipe / row switch, as a percent of the screen's short side (0–25 %, default 15 %; 0 turns parallax off).
- Rotation animation now has a **Fade duration** slider (50–600 ms) that controls how quickly icons fade back in after a rotation.
- Dialog when toggling labels off offers to add a column and increase cell spacing; toggling labels back on offers the reverse.
- Grid row/column slider max raised to 20 (30 with the extended range toggle) to support label-free high-density layouts.
- Folder labels can now be shown on the home screen via Settings → Home Screen → Show labels on folders.

### Changed
- **Grid row/column sliders are now fit-aware.** Instead of a flat 0–20/30 range, each slider caps at the most rows/columns that actually fit your current icon size without overflowing the screen or shrinking icons — and the caps always allow whatever the setup wizard recommends. The "increase max grid size" toggle still lifts the cap for power users who accept overflow. (Supersedes the earlier flat "raised to 20/30" behaviour.)
- App drawer **A–Z letter index** now glides to the tapped section with a smooth scroll instead of snapping; dragging down the strip scrolls continuously through the list.
- App drawer **search bar stays fully drawn while scrolling**. Previously (stock Lawnchair behaviour) its pill background faded out once you scrolled past a threshold, leaving a bare magnifier icon floating over the list. The bar is now pinned and always shown complete; the focus-driven hide when you tap into search is unchanged.
- Resizing the grid now **keeps icons where they are** instead of compacting them onto the first pages. When the grid shrinks, empty rows/columns are removed from the edges (and an off-edge icon slides just inside the new boundary); when it grows, every icon keeps its exact position and the new space is added at the edge.
- Wallpaper parallax is now **equal in both directions** — a page swipe and a row switch drift the wallpaper by the same amount, each axis capped independently to what the image allows (so a screen-height image still pans sideways).
- Rotation animation modes are now **Traditional / Instant / Fade**. "Fade" hides the icons instantly during the grid rebind (so the system's pre-transpose reflow never flashes through) and fades them back in over the pixel-stable wallpaper once settled. The fade-in speed is set by the new slider. The legacy "Crossfade" setting is migrated to "Fade".
- Wallpaper stabilization reworked to a single world-camera model. The wallpaper is now a fixed 2D world with one camera position `(worldX, worldY)`; page scroll moves the camera horizontally by a continuous gesture-driven delta, row transitions move it vertically, and rotation never touches the camera — so a pure rotation is pixel-perfect by construction. This replaces the previous four-offset model with its rotation-sync locks and drift animator, which were the source of the background snapping back to the page baseline after navigating rows and then scrolling.
- Rotation transition no longer fills the screen with the wallpaper's dominant colour. The workspace icons are hidden during the grid rebind and faded back in once it settles, over the already pixel-stable wallpaper — a cleaner, non-jarring transition.

### Fixed
- Dragging an app up to another row and then to the right edge no longer fails to create a new screen. In a single continuous drag, switching rows left the "new page" placeholder parked on the previous row, so the right edge of the switched row couldn't spawn a page (you had to drop and pick the icon up again). The placeholder is now re-parked into the row you switch to for the rest of the drag.
- **Icons could permanently disappear after a background rotation.** If another app rotated the screen while the launcher was backgrounded (e.g. a video going fullscreen, or sharing a photo from the camera) and you then returned home, the bottom half of a full page of icons — and empty pages — could be deleted for good; force-stopping did not bring them back. Three independent causes are fixed: (1) the home-screen transpose now only runs for rotations that happen while the launcher is actually visible, so another app's fullscreen rotation no longer rewrites your layout; (2) the rotation state and the coordinate rewrite are now committed together, so a process kill mid-rotation can no longer leave them disagreeing; and (3) an icon whose stored position ends up outside the current grid is now clamped/re-homed into a valid cell instead of being silently dropped — which also heals any layout already left in a bad state by the old behaviour.
- App drawer icons no longer overlap the A–Z letter index strip. The right-padding reservation was being clobbered every time the core re-applied its own padding (on insets/search-state changes); it is now applied centrally so it always holds.
- App drawer letter groups now have a visible separator — a hairline rule is drawn across the top of each alphabetical section header (the first header omits it).
- In landscape, navigating between rows moved the wallpaper in the wrong vertical direction. The counter-rotation inverts some bitmap axes on the glass; the row transition now accounts for that per rotation, so vertical parallax tracks correctly in every orientation.
- After rotating on the first page of an upper navigation row, that page visibly slid in from the side while the wallpaper stayed still. Cause: the row-contiguity reorder physically re-adds CellLayout views, and the workspace's `LayoutTransition` animated that move. The reorder is now wrapped to suppress the transition, so it is instant. A secondary one-frame page-flip during rebind is also prevented by setting the active row's parked screen as a pending-restore target before the rebind.
- Background no longer snaps back to the page baseline after navigating up/down a row and then swiping horizontally (removed the drift-to-baseline behaviour entirely).
- Removed the "background drift after rotation" setting (the world-camera model has no baseline to drift toward).
- Labels on the bottom row were clipped — font-metrics height is now used for the label budget so text always fits inside the cell.
- Icons were undersized when labels were off — the label budget no longer shrinks the icon when labels are disabled.
- Folder labels disabled by default — folder names were clipping on the bottom row; folders are identifiable by their icon previews.
- Folder icon circle now matches the size of regular workspace icons and stays vertically centred in its cell.
- Default grid changed to 4×9 on phones (was 5×7) to better use the screen space freed by removing the hotseat and smartspace.
- Cell spacing now defaults to 4 dp on phones and 12 dp on tablets.
- Cell spacing slider minimum lowered to 0 dp.
- Folder label text was not appearing even when the toggle was enabled — a double-padding bug in the label view pushed text below the cell boundary. Labels now render correctly.
- Bottom row of icons was shifted outside the visible workspace on first launch — the cell-size calculation used an empty profile list on the first `initGrid()` call, giving a padding of 4 dp instead of the correct ~44 dp and producing an oversized grid. Now reads insets from `DisplayController.Info.supportedBounds`, which is fully populated before any profile is built.
- Preference changes (e.g. toggling labels) no longer risk spuriously remapping grid coordinates — the reload path now bypasses the rotation-transpose hook that is only appropriate for display orientation changes.

## [0.3] - 2026-05-06

### Added
- App drawer column count now scales automatically with the available screen width. Icons in the drawer are sized to match the workspace square cell size; the launcher fills as many columns as will fit. This gives a density-consistent drawer in portrait and landscape on all device types, without any manual column setting.

### Fixed
- **Icons drifting between portrait and landscape rotations** — Icons now land at exact glass pixel locations after 90° rotation on all devices.
- Large widgets (spanning multiple cells) were deleted from the database after a screen rotation. The transpose math was only correct for 1×1 icons — for multi-cell widgets a different bounding-box corner becomes the top-left after rotation, causing `cellY + spanY > numRows` which Launcher3 treats as out-of-bounds and removes. The position mapping is now span-aware, correctly identifying the new top-left in all four orientations.
- Icons and labels were clipped on dense phone grids. The square-cell sizing now applies a 4 dp safety margin so the icon+label block is never pressed flush against the cell boundary, accounting for font-metric rounding and BubbleTextView internal padding.
- App drawer icons overlapped the A–Z letter index strip on the right edge. The RecyclerView now reserves 36 dp of right padding so all icons remain visible.
- Apps whose names start with a digit or symbol were grouped under `·` in the app drawer instead of `#`. All non-alphabetic starters now correctly appear under `#`.
- Several settings screens and system prompts still showed "Lawnchair" instead of "Anchor". All user-visible strings in the launcher UI have been updated.
- Dragging a widget from the picker could auto-navigate to a page outside the current row. The widget-search loop in the drag start path is now clamped to the active row's page range.
- Pressing Back on any non-home screen now navigates back to home (row 0, page 0) instead of doing nothing.

## [0.2] - 2026-04-29

### Added
- **Alphabetical section dividers toggle** — new option in App Drawer → Advanced to disable the inline A–Z section headers. On by default.

### Fixed
- Icons and labels were clipped inside their cells. The square-cell sizing now re-fits icon and padding to the computed cell size after Lawnchair's layout pass, so content never overflows regardless of grid size or screen density.
- Swiping down from the status bar area while already on the top row was navigating to the row below instead of playing the bounce animation. Now consistent with the workspace swipe gesture.
- Workspace no longer snaps one page to the right after a drag (delete, reposition, or page move) on rows above row 0. The stale auto-scroll toward the extra empty screen is now cancelled before the row's scroll bounds are reapplied, and the pre-drag page is restored via a screen-ID-based sticky redirect that survives Launcher3's deferred page changes.
- Dragging an icon down to a lower row and across to another page no longer teleports back to the source row's remembered page. The post-drag restore now only fires when the saved screen is still in the active row.

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
