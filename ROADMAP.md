# Anchor Launcher — Roadmap & Known Issues

## Planned Features

### Core

- **Custom persistent row** — hotseat replacement that participates in grid transposition as a regular row. Stored in the workspace container (not `CONTAINER_HOTSEAT`) so the transpose logic handles it uniformly. Should visually match Lawnchair's hotseat (pill background etc) and transpose to a side column in landscape.

### Home Screen

- **Rotation crossfade: "fade to black" option** — alternative to the current "fade to wallpaper dominant colour" which can produce a jarring bright flash on some wallpapers. User-selectable in Home Screen settings.
- **Stack folder style** — 2–3 icons with slight rotation/offset (like a physical stack of cards) as an alternative to the default 2×2 grid preview. Toggle in Home Screen settings. New `StackFolderPreviewManager` class in `anchor/folder/`.
- **Infinite scroll per row** — Lawnchair's infinite scroll wraps using `getChildCount()` across the entire workspace; in multi-row mode it jumps rows instead of wrapping within the current row. Currently hidden when `rowCount > 1`. Fix: intercept the wrap condition in `PagedView.java` to use the active row's first/last page instead of 0 / `getChildCount()-1`.

### Drag & Drop

- **Drag overlay Phase 2: spring physics** — replace the fixed-duration `AccelerateInterpolator` row-switch animation in `TwoRowNavigationManager.animateRowTransition` with `SpringAnimation` (androidx.dynamicanimation) for a physical snap-and-settle feel.
- **Drag overlay Phase 3: bitmap thumbnails** — capture `Bitmap` from each row's `CellLayout` via `View.drawToBitmap()` at drag-start and display as `ImageView`s in the shelf strip instead of dot indicators.

### Polish

- **Notification badge dots** — regression in Lawnchair 16; being fixed upstream. Track the upstream fix and pull it in.

---

## Known Issues

### Active (not yet fixed)

| Issue | Notes |
|-------|-------|
| Infinite scroll broken in multi-row mode | Wraps across all rows instead of within the current row. Hidden in settings when `rowCount > 1` as a stopgap. |
| Notification badge dots missing | Lawnchair 16 regression; upstream fix pending. |

### Fixed

| Issue | Fix |
|-------|-----|
| App drawer icons overlap A–Z letter strip | 32dp right padding added to RecyclerView in `SearchContainerView.setupLetterIndex()` |
| Symbol-starting apps listed under `·` instead of `#` | `AlphabeticIndexCompat.computeSectionName()` now returns `#` for all non-alphabetic, non-digit starters |
| Settings UI shows "Lawnchair" instead of "Anchor" | User-visible strings updated in `lawnchair/res/values/strings.xml` |
| Widget drag auto-navigates to wrong page | `Workspace.onDragStart()` widget-search loop clamped to `mAllowedPageEnd` |
| Back press does nothing when not on home screen | `LawnchairLauncher.onStateBack()` routes to `twoRowNavigationManager.navigateToHome()` |
| Large widget deleted after screen rotation | `GridTransposeHelper` now uses span-aware `remapWidgetPosition` / `reverseWidgetPositionToPortrait` instead of the 1×1-only `remapCoordinates` |
| Icons clipped on dense phone grids | `withDimensionsOverride` in `InvariantDeviceProfile.newDPBuilder()` now uses a 4dp safety margin so icon+label never fills the cell to the boundary |
| Workspace snaps to wrong page after drag | Stale auto-scroll to EXTRA_EMPTY_SCREEN cancelled before scroll bounds are reapplied; pre-drag page restored via screen-ID sticky redirect |
