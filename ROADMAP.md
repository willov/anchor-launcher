# Anchor Launcher — Roadmap & Known Issues

## Next Up

### Grid setup wizard — ✅ COMPLETE & VERIFIED ON DEVICE (2026-07-12)

First-launch (and on-demand via Settings → Home Screen → Run setup wizard) 3-page flow
(`GridWizardScreen.kt`: Wallpaper → Cell → Grid) that recommends a grid sized to the device.

**The sizing model (the hard-won invariant):** the user picks an ICON SIZE first; that size is
**LOCKED** and is what limits how many columns/rows fit. Density (Spacious / Balanced / Dense)
changes only the column/row COUNT and the gap — never the icon size. The pure math lives in
`GridSizeCaps.recommendPure` / `computePure` (Context-free, unit-tested — `GridSizeCapsTest`):

- **Column count** = most columns whose cell still holds the full desired icon at ≥ a minimum gap
  (`MIN_DENSE_GAP_DP`), minus the density offset. So even Dense never squishes the icon, and a
  larger icon simply caps columns lower. Distinct by construction (spacious < balanced ≤ dense).
- **Even gap** = leftover short-side space split into (cols+1) parts, capped at ~½ cell so it never
  dwarfs the icons. (A gap large enough to shrink the cell below the icon *would* shrink the icon —
  the cap prevents that.)
- **Row count** = aspect-matched (`round(cols × screenAspect)`), stepped down until the grid fits
  the raw long side with `gridH ≤ longRaw − 2 × maxInset` (the top-cutout clearance — the override's
  Phase-4 compensation subtracts the cutout from the TOP padding, so that is the true no-overflow
  bound). The manual slider caps (`computePure`) are DERIVED from the Dense recommendation, so they
  always allow what the wizard suggests and can never overflow (unit-tested).
- **P (edge padding) MUST equal the launcher override's P** (= landscape short-side inset ≈ bottom
  nav + 4dp). Using a smaller P here was the root "icon not locked" bug: recommend assumed bigger
  cells than the launcher produced, so the override's `icon = min(cellFit, desired)` silently shrank
  the icon. Fixed — icon is now genuinely constant across densities (verified by measuring on device
  and by `GridSizeCapsTest.iconSizeIsPreservedAcrossDensities`).

**Preview:** the Grid step uses a **faithful custom Compose render** (`GridPreview` in
`GridWizardScreen.kt`), NOT the full-launcher `LauncherPreviewView`. The launcher preview scaled by
grid height, which made dense icons look *bigger* than spacious (backwards). The Compose render
draws the real cols×rows at the fixed icon fraction of the card (`GridSizeCaps.previewMetrics`) using
the user's REAL installed app icons (`LauncherApps.getActivityList`) — so it matches reality and the
icon stays constant across densities.

**Also fixed along the way:** ExampleCell `getDeviceProfile` NPE crash; "Use this grid" no-op
(`popBackStack` → `activity.finish()` fallback); duplicate wallpaper popup (grid gate sets
`wallpaperOnboardingShown`); and — in the SETTINGS grid preview (`GridOverridesPreview` +
`AnchorPreviewPopulator`, which still uses the real launcher render) — "Populate grid" overlaying
real icons (occupancy now read from the child views' `CellLayoutLayoutParams`, because
`CellLayout.isOccupied()` is NOT populated in the preview renderer) and solid-colour placeholder
icons (pool now filters to non-low-res bitmaps, upgrading via `IconCache` if needed).

**Open polish (not blockers):**

1. **Link rows/columns: grey out the driven axis.** When "Link rows and columns" is ON, the slider
   computed from the other should be visually disabled/greyed. `HomeScreenGridPreferences.kt`.
2. **App-drawer (all-apps) icon size ≠ home-screen icon size.** Home icons use Anchor's square-cell
   override (`InvariantDeviceProfile.withDimensionsOverride`); all-apps icons use `dp.allAppsIconSizePx`
   (`allAppsIconSize × drawerIconSizeFactor` in `DeviceProfileOverrides.applyUi`) — a separate path
   the override doesn't touch, so they diverge. If they should match, set `allAppsIconSizePx` from the
   same desired-icon basis in the override (which already aligns the drawer COLUMN count there).

## Next Up

### 0. Grid-resize migration — spatial preservation ✅ DONE & VERIFIED ON DEVICE 2026-06-09

**Goal:** changing the grid (rows/cols) must keep icons in place instead of compacting them all onto
the first page (the upstream Launcher3 default). Resizing switches DB files `launcher_{r}_{c}_…db` and
runs `GridSizeMigrationLogic`, whose default policy compacts from the top-left — that's the "all icons
stacked on page 1" breakage.

**Where the code is:** `src/com/android/launcher3/model/GridSizeMigrationLogic.kt`.
`migrateWorkspace` → `placeWorkspaceItems` calls `preserveAndDropEmptyEdges(...)` BEFORE the upstream
solver; it resolves EVERY item (placed or dropped) and empties the to-add list, so the compacting
solver normally never runs.

**Final policy (per screen, per axis, independent):**
1. **Drop empty lines** — if an axis overflows by `need`, drop `need` empty columns/rows, chosen by
   ALTERNATING from the bottom/right end then the top/left end of the empty band (bottom/right first).
   This keeps content above the removed band top-anchored and content below bottom-anchored, so a
   bottom-right icon stays bottom-right instead of reflowing up to the top-left.
2. **Clamp** — an item still past the edge (a genuinely full axis with no empty line left to drop) is
   clamped to the last valid index (`coerceAtMost(target-1)`).
3. **Drop on collision** — if the clamped cell is already occupied, the item is DROPPED entirely
   (removed from the workspace), NOT relocated by the compacting solver. (`dropEmptyEdgesMap` helper +
   the per-screen loop in `preserveAndDropEmptyEdges`.)

Items that fit keep their exact (x,y); interior gaps are preserved (not collapsed). Grow = identity.

**Verified on device (Pixel 10a, 2026-06-09):** 5×11 → 4×9 with folder(0,0), Signal(0,1), three
cluster rows at y=3/5/8, Gmail bottom-right (4,10). Result: cluster rows compact to y=2/4/7, Gmail →
(3,8) bottom-right corner (left 1 from the dropped column, up 2 from pruned rows). Not cropped, not
jumped top-left. Confirmed by pulling the DB and diffing against the predicted placement.

**Tests:** `tests/multivalentTests/.../GridSizeMigrationTest.kt` has Anchor tests (grow,
drop-rightmost-col, single-col-drop, no-overflow, drop-bottom-row, bottom-right-anchored, full-col
drop-on-collision, both-axes bottom-right). Upstream compaction tests on the refactor path are
`@Ignore`'d. **STILL OUTSTANDING: `tests/multivalentTests/` is NOT wired into this fork's gradle build**
(root `build.gradle` sourceSets include only `tests/shared`). So these tests don't actually run yet —
wiring a runnable test source set (Robolectric/host) is the real foundation for regression protection
and remains a TODO. Also flagged: extract the wallpaper crop math from
`WallpaperStabilizationDrawable.draw()` into a pure `computeSrcRect(...)` for unit-testing.

**Side note (user idea → now its own Home Screen roadmap item):** linking column/row counts and
orientation/screen/icon-aware slider caps — see "Smarter grid-size sliders" under Home Screen below.

### 1. Wallpaper stabilization — settled architecture (custom-image based) ✅ implemented 2026-05-30

**The platform constraint (researched & confirmed 2026-05-30):** A third-party Play-Store launcher **cannot READ the real system wallpaper bitmap on Android 13+.** `getDrawable()`/`getFastDrawable()`/`getWallpaperFile()` all need `MANAGE_EXTERNAL_STORAGE` (Play-hostile) or `READ_WALLPAPER_INTERNAL` (privileged-only); `READ_EXTERNAL_STORAGE` can't be requested at targetSdk 33+; from Android 14 the read throws `SecurityException` / returns only the default. Google: "Won't Fix" (issuetracker 237124750, 236690156). No API holds the system wallpaper upright during rotation either. **BUT a launcher CAN _set_ the wallpaper:** `WallpaperManager.setBitmap()` needs only `SET_WALLPAPER` — a *normal* permission, auto-granted, Play-safe. OEM built-in/preset wallpapers are never handed to third-party apps by any picker, so they can't be imported.

**The design (shipped):** stabilization works only with bitmaps Anchor controls.

- **System wallpaper (DEFAULT)** — `windowShowWallpaper=true` stays, no custom drawable → real wallpaper shows, rotates normally, never black, zero permission. Stabilization OFF.
- **Custom image** — user picks via the permission-free photo picker (`ACTION_PICK_IMAGES`); we copy it to `filesDir`, render it with the counter-rotation (pixel-stable + parallax), AND **set it as the system wallpaper via `setBitmap`** so the same image appears on home, lock, recents and the all-apps blur — all in sync. We can SET even though we can't READ. This is the primary mechanism.
- **System stabilized (power-user, github/nightly only)** — `WALLPAPER_SOURCE_SYSTEM_STABILIZED` reads the real wallpaper via `WallpaperManager` (needs `MANAGE_EXTERNAL_STORAGE`, only in github/nightly manifests). Documented on `readWallpaperBitmap()`; not a Play path.

**Implemented:** `AnchorPreferences.wallpaperSource` (system/custom/system_stabilized) + `customWallpaperPath` + `wallpaperStabilizationActive`. `WallpaperStabilizationManager.setup()` only clears `FLAG_SHOW_WALLPAPER`/installs the drawable when active; `reapplyIfChanged()` (called from `onResume`) applies a source/image change live without restart. `WallpaperStabilizationManager.importCustomWallpaper(ctx, uri, alsoSetSystemWallpaper=true)`. Settings → Home Screen Anchor section: Wallpaper source ListPreference + "Choose image" (photo picker). `AnchorWallpaperPicker.launch()` drives the picker from the home long-press menu. Long-press "Wallpaper & style" relabels to Anchor's picker when source==custom (`LauncherOptionsPopup`). `SET_WALLPAPER` added to `lawnchair/AndroidManifest.xml`.

**Debug:** `AnchorPreferences.useTestWallpaper` (Settings → "Debug: test wallpaper", default OFF) renders a gradient+grid test pattern.

**Added 2026-06-05/06 (verified working on device):**
- **Perf:** custom images are downscaled on load (`decodeDownscaled`, companion) to cover the screen
  in both axes with headroom (`COVER_FACTOR=1.1`, `HARD_CAP_FACTOR=2`), using the FULL display size
  (`realScreenSize` via `maximumWindowMetrics`, NOT `displayMetrics` which excludes system bars). A
  4096² GNOME wallpaper was laggy at full size; downscaling fixed swipe smoothness. GNOME backgrounds
  render "amazingly nice" per the user.
- **Re-pick reliability:** `configSignature` now includes the custom file's `lastModified()` — the
  image always saves to the same path (`custom_wallpaper.jpg`), so without mtime, re-picking a
  different image was a silent no-op (signature unchanged → `reapplyIfChanged` skipped). This was why
  the longpress picker and Settings re-picks "didn't apply."
- **Longpress picker fixed:** `BlankActivity` only forwarded result `extras`, dropping the photo
  picker's `Intent.data` URI (→ `uri=null`). Now forwards the full result Intent
  (`KEY_RESULT_INTENT`). `AnchorWallpaperPicker` works from the home long-press menu.
- **Lock-screen match attempts:** set the system wallpaper to a `homeRestCropRect` (worldX=0,
  worldY=1−ROW_MARGIN) via `setBitmap(src, cropHint, allowBackup, FLAG_SYSTEM|FLAG_LOCK)` +
  `suggestDesiredDimensions`. Reduced but did NOT eliminate the lock/home mismatch — see Known Issues;
  treated as an inherent system-lock-render limitation and accepted.
- **First-launch onboarding:** one-time prompt (`maybeShowWallpaperOnboarding` in `LawnchairLauncher`,
  gated by `AnchorPreferences.wallpaperOnboardingShown`) offering "Choose image" → `AnchorWallpaperPicker`,
  shown only on the default System source in NORMAL state. Won't show for users already on custom.

**Open / next:** bundled GNOME wallpapers + the About/tribute screen (coupled — build together; see
"Bundled anchor-optimised wallpapers" + GNOME tribute spec below). Three GNOME images
(`morphogenesis-d`, `blobs-d`, `map-d`) were rendered 4096² and pushed to the device's `/sdcard/Pictures`
for manual evaluation; not bundled in-app yet.

---

## Planned Features

### Core

- **Custom persistent row** — hotseat replacement that participates in grid transposition as a regular row. Stored in the workspace container (not `CONTAINER_HOTSEAT`) so the transpose logic handles it uniformly. Should visually match Lawnchair's hotseat (pill background etc) and transpose to a side column in landscape.

### Wallpaper

- **Bundled anchor-optimised wallpapers** — include a small set of stock wallpapers sized to support parallax in both portrait and landscape. Standard wallpapers are `2× screen-width × 1× screen-height`; they give portrait left/right parallax but no landscape parallax (landscape requires extra bitmap height since bitmap-Y maps to screen-X after counter-rotation). Anchor wallpapers should be `2× screen-width × 2× screen-height` (e.g. 2160×4680 for a 1080×2340 base) — same convention as standard wallpapers, extended to both axes. This gives symmetrical full-range parallax in portrait and landscape. Good candidates: abstract gradients, geometric patterns, or nature shots that look good regardless of which sub-region is visible (~1–3 MB each as JPEG). Ship in `lawnchair/res/raw/` or as drawable assets; expose as a third `WALLPAPER_SOURCE_ANCHOR` source (alongside System/Custom) — selecting one copies it like a custom image (and sets it as the system wallpaper) so it gets full stabilization.

  - **Sourcing — GNOME backgrounds (checked 2026-06-03):** ⚠️ **ALL current gnome-backgrounds (master) are CC-BY-SA 3.0 — there are NO CC0/public-domain ones.** Every image in `backgrounds/` (adwaita, balls, blobs, amber, dithered-sun, map, morphogenesis, pills, tarka, vnc, …) is CC-BY-SA 3.0 per the repo `AUTHORS` (mostly Jakub Steiner; some by David Lapshin, Tobias Bernard, Dominik Baran). Formats are **JXL (.jxl) and SVG** — need conversion/rasterising to PNG/JPG for Android. CC-BY-SA's share-alike makes resizing/cropping (our 2×W×2×H) a derivative that must also be CC-BY-SA, awkward for a proprietary app. **DECISION (2026-06-03): IF we ship GNOME backgrounds, ship them UNMODIFIED** — as clearly-marked CC-BY-SA 3.0 separable assets with full per-image attribution (author from `AUTHORS`) + the tribute screen + required non-affiliation disclaimer. (Whether to ship at all is still pending — user is first evaluating the look of one background manually via the existing Custom-image picker.)

    Implications of "unmodified" we must respect when building:
    - **Don't crop/resize/recolour the artwork.** Rasterising JXL→PNG/JPG and SVG→PNG at the image's native aspect/size is fine (format conversion, not a derivative edit); changing the *content* (crop to 2×W×2×H, recolour) is not. So bundled GNOME images keep their **native dimensions** — they may NOT satisfy the 2×W×2×H parallax-headroom convention, so some will give limited/no parallax in one or both axes. Accept that, or only ship ones whose native size already affords parallax.
    - **License + attribution must be displayed** (CC-BY-SA requires it): per-image author + "CC-BY-SA 3.0" + link to the license, reachable from the wallpaper picker and the About/tribute screen.
    - Keep the original files as the shipped asset (convert format only); store per-image metadata (author, license=CC-BY-SA-3.0, source URL) for the credits list.
    - Alternatives if unmodified-CC-BY-SA feels too constraining remain open: older CC0 gnome tags, or CC0 wallpapers from elsewhere.
  - **Attribution as a genuine tribute (not just legal compliance), even for public-domain ones:** the framing matters — appreciation first, license second. Ship a warm, *visible* credits section (not buried fine print): lead with something like *"These backgrounds come from the GNOME Project, whose beautiful work we're proud to feature — please consider supporting them."*, then a per-wallpaper list crediting the **individual artists by name** (from GNOME's `AUTHORS`) with their license, and a prominent link to GNOME's project/donation page. Store per-file metadata (author, license, source URL) alongside each bundled image so the list stays accurate.
    - **Non-affiliation disclaimer (REQUIRED):** make it *abundantly clear* that Anchor Launcher is **not affiliated with, sponsored by, or endorsed by the GNOME Project or the GNOME Foundation.** This disclaimer must appear prominently anywhere the GNOME name is used — in the wallpaper credits section, the About screen, and the Play Store listing if GNOME wallpapers are mentioned — not as buried fine print. Exact wording TBD but unambiguous, e.g. *"Anchor Launcher is an independent project and is not affiliated with, sponsored, or endorsed by the GNOME Project."*
    - **Trademark caution:** "GNOME" and its logo are trademarks. Use descriptive attribution ("backgrounds from the GNOME Project") rather than branding the app *with* the GNOME mark/logo; check GNOME's trademark policy before using the logo.
    - **Give back (optional, strongest tribute):** if Anchor creates/commissions wallpapers, consider dual-licensing them CC0 and offering them upstream to gnome-backgrounds.

### Home Screen

- **Smarter grid-size sliders (cols↔rows coupling + adaptive caps)** — Anchor's square-cell, label-below
  layout has sweet-spot aspect ratios (labels OFF, the user likes **4×9** and **5×11**; rows ≈ ~2.2×cols).
  Three improvements:
  1. **Couple columns and rows** — when the user adds/removes a column, auto-expand/contract rows to keep a
     sensible aspect (smart default with an opt-out, or snap suggestions) instead of letting cells go too
     dense/wrong-shaped. (Supersedes the deferred "link column/row counts / prompt 'append more rows?'" note
     under Next Up #0.)
  2. **Orientation-aware caps** — the columns slider currently runs to 20, which is absurd for a square-cell
     transposing grid. Only the longer physical side should allow many cells; cap the shorter side much lower.
  3. **Screen- + icon-size-aware caps** — compute the max cells that fit before icons/labels are *guaranteed*
     to overflow (S = (shortRaw − 2P − (n−1)g)/n vs the minimum icon size at the current icon-size pref), and
     clamp the slider's max just below that. Range adapts per device and per icon-size setting.

  Prefs live in `app.lawnchair.preferences*` (`pref_workspaceRows` / columns); slider UI in the Compose
  preference screens; cell-fit math mirrors `InvariantDeviceProfile.newDPBuilder()` `withDimensionsOverride`.

  4. **Resize placement strategy setting** — let the user choose WHERE rows/columns are added (grow)
     and removed (shrink): append to end, prepend to start, interleave, or center. Add and remove can
     likely be a SINGLE setting, MIRRORED — e.g. "end" → grow appends at right/bottom and shrink drops
     from right/bottom; "start" → both at left/top; "center" → both in the middle. (Could allow
     separate add/remove later if needed.) The migration's per-axis remap is centralised in
     `GridSizeMigrationLogic.dropEmptyEdgesMap` (see Next Up #0), so a strategy enum can swap the
     empty-line selection + shift direction there.

- **Independent vs connected rows option** — add a setting to choose whether the navigation rows scroll independently (each row remembers its own horizontal page position — current behaviour) or are *connected* like a normal grid (all rows share one horizontal scroll position, so swiping right on one row moves every row together / they behave as columns of a single 2D grid). Default to the current independent behaviour.
- **Stack folder style** — 2–3 icons with slight rotation/offset (like a physical stack of cards) as an alternative to the default 2×2 grid preview. Toggle in Home Screen settings. New `StackFolderPreviewManager` class in `anchor/folder/`.
- **Infinite scroll per row** — Lawnchair's infinite scroll wraps using `getChildCount()` across the entire workspace; in multi-row mode it jumps rows instead of wrapping within the current row. Currently hidden when `rowCount > 1`. Fix: intercept the wrap condition in `PagedView.java` to use the active row's first/last page instead of 0 / `getChildCount()-1`.

### Drag & Drop

- **Drag overlay Phase 2: spring physics** — replace the fixed-duration `AccelerateInterpolator` row-switch animation in `TwoRowNavigationManager.animateRowTransition` with `SpringAnimation` (androidx.dynamicanimation) for a physical snap-and-settle feel.
- **Drag overlay Phase 3: bitmap thumbnails** — capture `Bitmap` from each row's `CellLayout` via `View.drawToBitmap()` at drag-start and display as `ImageView`s in the shelf strip instead of dot indicators.

### Polish

- **Notification badge dots** — regression in Lawnchair 16; being fixed upstream. Track the upstream fix and pull it in.
- **About page cleanup** — remove Lawnchair developer credits and Lawnchair-specific information; replace with Anchor Launcher identity. Keep attribution to Lawnchair and AOSP Launcher3 as required by Apache 2.0.
- **Remaining Lawnchair string references** — audit all user-visible strings, notification text, and dialog copy for leftover "Lawnchair" mentions; replace with "Anchor" where appropriate. (Preserve "Lawnchair" only in the About screen attribution.)

---

## Known Issues

### Active (not yet fixed)

| Issue | Notes |
|-------|-------|
| ~~Wallpaper parallax anisotropic (h vs v)~~ FIXED 2026-06-09 | Equal-drift model: each step (page swipe / row switch) drifts the wallpaper a target `D = 8% × screen short side` glass-px, capped PER AXIS independently at its own pan room (so a portrait-height image still pans horizontally — neither axis zeroes the other). Row spacing is now D-based from the bottom rest (lock match preserved). Pure fns in `WallpaperCropMath` (`worldTravelForGlassDrift`/`horizontalSweepWorld`/`rowWorldOffset`), unit-tested (14 tests). Device-verified. Tune `DRIFT_FRACTION` for strength. |
| ~~Wallpaper moves in wrong direction on rotation~~ FIXED 2026-06-09 | Was actually the LANDSCAPE vertical parallax inverted (rotation itself was fine). The counter-rotation negates bitmap-X on glass-Y for ROT_90, but row transitions drove worldX with a + sign. Fixed via per-rotation (axis,sign) tables in the new pure `WallpaperCropMath` (`horizontalGlassAxis`/`verticalGlassAxis`); manager routes scroll/row/rest-init through them. Unit-tested (`WallpaperCropMathTest`, 9 tests) + device-verified. |
| Backup/restore scrambles multi-row layout | **Found 2026-06-13.** Lawnchair's Backup & Restore saves the workspace DB but NOT Anchor's per-row screen assignments (`AnchorPreferences.row_screens_*`). Worse, those assignments reference specific screen IDs, which **change on restore** — so even if backed up verbatim they'd be stale. After restore, `TwoRowNavigationManager` partitions rows against the wrong IDs → pages land in the wrong row (observed: backup P1R0→P0R1, P0R1→P1R0, P1R1→P2R0) and manual drags also place wrong ("launcher thinks the upper row is at a given index"). Fix needs row membership to survive ID remapping or be reconstructed from something ID-stable (e.g. page order/position), AND the row prefs included in the backup set. Also seen: restore sometimes only "takes" on the second attempt (one-time post-restore reload timing). |
| Custom wallpaper pick doesn't set source (standalone path) | **Found 2026-06-13.** The first-launch wallpaper prompt / standalone picker sometimes leaves `wallpaperSource=System` after picking an image, so stabilization never turns on. `AnchorWallpaperPicker.launch` sets the source on success, so the likely cause is the launcher Activity not being resumed when the result returns (so `reapplyIfChanged` doesn't fire) or a null result URI. **Worked around** by adding the wallpaper choice as a step in the grid wizard (the reliable path); the standalone path still needs a proper fix. |
| Infinite scroll broken in multi-row mode | Wraps across all rows instead of within the current row. Hidden in settings when `rowCount > 1` as a stopgap. |
| Notification badge dots missing | Lawnchair 16 regression; upstream fix pending. |
| Wallpaper stabilization: no landscape parallax | In landscape, left/right parallax requires extra bitmap height (bitmap-Y maps to screen-X after counter-rotation). Standard wallpapers have bmp.height == rawH (no vertical room), so landscape wallpaper is static. Wallpapers with extra height get natural srcTop parallax. A zoom-based workaround was tried but broke spatial stability. |
| ~~App drawer icons overlap the A–Z letter index~~ FIXED 2026-06-11 | Right padding was set once in `SearchContainerView` but `ActivityAllAppsContainerView.applyAdapterSideAndBottomPaddings()` re-assigns `mPadding.right` on every insets/search-state change and clobbered it. The reservation (single source of truth `AlphabetIndexView.reservedWidthPx` = 36 dp) is now applied centrally in that core method, on the MAIN list only, when the letter scroller is enabled — so it survives every re-pad. Device-verified. |
| ~~No visual separator between letter groups in app drawer~~ FIXED 2026-06-11 | New `app.anchor.applist.SectionHeaderView` (replaces the inline plain TextView in `BaseAllAppsAdapter`) draws a hairline rule across the top of each section header (~30% of secondary text colour, 1.5 dp); the first header skips the line. Device-verified. |
| Custom wallpaper: lock screen doesn't exactly match home | The home wallpaper is Anchor's custom rotation-stable render; the lock screen is rendered by the system from the same image set via `setBitmap`. On Android 14+ the system applies its own crop/zoom + unlock zoom-animation that `visibleCropHint`/`suggestDesiredDimensions` don't reliably override, so lock looks slightly zoomed/shifted (and shifts on unlock) vs home. Inherent to rendering home ourselves (which rotation-stability requires) while the system owns lock — no app-level fix found. Cosmetic, lock-screen only. |
