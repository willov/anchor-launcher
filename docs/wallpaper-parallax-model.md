# Wallpaper parallax — design model

Status: **agreed, not implemented.** Written 2026-08-31 after the wallpaper rendering work
(`fix/background-artifacts`, merged to `dev`). This captures the target model and the reasoning, so it
does not have to be re-derived.

## What the wallpaper is for

Anchor's home screen is a 2D matrix: rows are scrolled independently, and the user decides what each
row is for (apps / widgets, private / work, whatever). The wallpaper should make that matrix feel like
a single continuous space rather than a set of unrelated screens.

## The model

**The image centre maps to the HOME PAGE of the current row. The camera rests at the image centre.**

*(Implemented: `WallpaperStabilizationManager.anchorPageCameraToHome()`. `onScrollOffset` remains a
pure delta accumulator — that is what gives the "continuous world you pan through" feel within a row
— and is pinned to rest at the row's home page on row switch, on model rebind, and when home is
redefined. It is deliberately NOT re-anchored after a rotation rebind: a rotation must be
pixel-perfect and the camera is already correct by construction, since rotation never touches it.)*

That single rule settles most of the design:

- **"Which crop is canonical?"** — the one you see on your home page. No arbitrary choice.
- **Lock screen / preview / home rest all agree**, because all three render the rest camera.
- **Centred subjects work.** Many photos put the subject dead centre deliberately (a pet, a face);
  resting anywhere else crops them off-centre.
- **Pages left of home work naturally** — negative excursion is as valid as positive.

Rest is ALWAYS the image centre, whatever the home page's index. Only the reachable RANGE is
asymmetric:

| home page position   | parallax range      |
|----------------------|---------------------|
| leftmost (default)   | right only          |
| middle               | left and right      |
| rightmost            | left only           |

This is the important difference from "anchor at page 0": you are not sitting at an EDGE at rest, you
are at CENTRE, and the layout only decides how far you can wander each way.

## Asymmetric reach is accepted, not compensated

If home is a corner — "super home" at row 0, page 0 — you can only pan UP and RIGHT from centre. Half
of each axis's excursion is unreachable.

**Decision: accept it.** The camera still rests at the image centre; the layout simply determines which
directions are reachable, and the unreachable half stays unused.

| home position   | reachable directions      |
|-----------------|---------------------------|
| bottom-left     | up + right (two half-axes)|
| middle-left     | up/down + right           |
| middle          | all four                  |

Two alternatives were considered and REJECTED:

- **Re-allocate the budget to only the reachable directions** (so a corner home gets the full budget
  spread over the moves it can actually make). Removes the dead range, but makes total drift constant
  and per-swipe drift vary with page count — reversing the "every swipe moves the same" property, and
  coupling feel to layout.
- **Rest at the MATRIX centre rather than home.** Gives symmetric reach, but home rest is then no
  longer the image centre, which breaks lock-screen centring — the thing this model exists to fix.

Accepting the asymmetry keeps: image centre at rest, constant per-swipe drift, equal H/V, and no
per-direction budget arithmetic. The cost is that a corner home never scrolls into the down-left half
of the excursion — it is only ever seen at rest.

## Requirements this has to satisfy

- Multiple rows, independently scrolled; more than two rows must work (`MAX_ROWS = 5`).
- Parallax must not feel like it "stops" at one row's extreme and resumes on another.
- Works in portrait AND landscape. (Note the camera is stored by ROLE — `pageWorld`/`rowWorld` — and
  mapped to bitmap axes only at push time. Rotation swaps which bitmap axis each role drives.)
- Every navigation gives SOME movement, if the image has room for it.
- Up/down and left/right move the same distance. (Done: the row step is scaled to the page step.)
- Not too much zoom. Parallax headroom is paid for in zoom, because the engine crops 1:1 — every pixel
  of pan room is a pixel of image you no longer see.
- Must not be complex enough to cost frame time. This should stay arithmetic on the existing camera,
  not new machinery.

## The tension, and how it resolves

Two requirements pull against each other:

- "parallax shouldn't stop at a row's extreme, it should continue" → wants a LARGE world
- "images are centred intentionally, don't shift too much" → wants a SMALL excursion

These cannot both be maximised. The resolution is the concession that the world does not need to be
FILLED, it needs to RESPOND: every gesture moves the wallpaper a little, and the total excursion stays
small enough that the composition still reads.

Concretely that means the total excursion is a fixed budget (from the parallax slider) subdivided
across the whole matrix, rather than each row spending a full sweep:

    total excursion = slider budget
    step            = budget / (total navigable positions)

Consequences:
- More screens = smaller step each, same total excursion → **zoom stays constant as the user adds
  screens**, which is not true today.
- You are always somewhere inside one shared budget, so nothing "stops" at a row boundary.
- Every step is small but non-zero.

## Drift-back to the "natural" view — considered and dropped, twice

An earlier idea: if the camera had wandered far from the view that "naturally" belongs to the current
screen, slowly drift it back. Scrapped then, and re-examined after the centring decision — still no.

The new model removes the problem it solved. Drift-back was a corrective for a camera that was a pure
accumulator with no canonical position per screen. Now home IS the image centre and every screen has a
defined position relative to it, bounded by the excursion budget, so there is nothing to correct.

It also actively conflicts with two things we want:

- **The world metaphor.** The appeal of the current design is that the wallpaper is a continuous space
  you move through. Drift-back rearranges that space while you are not looking, and breaks spatial
  stability: return to the same page twice, see different pixels.
- **Single-writer camera.** A background timer mutating the camera would be a fourth writer on a path
  where three already caused the unlock snap, inverted rows, and the landscape stall. The current fix
  works precisely because the camera only changes on explicit navigation.

Revisit only if, after per-row home lands, the camera is observed drifting far from centre in normal
use — but the excursion is small (600px of 2896px at 10% on a 1200x1920 tablet), so "far" is not
really reachable.

## Prerequisite: per-row default page — IMPLEMENTED

The model rests on "the home page of the current row", so home must be settable per row. Lawnchair
had a single global `default_home_page` pref plus a "Set as default page" long-press action; both are
now per-row.

- `AnchorPreferences.getRowHomeScreenId(r)` / `setRowHomeScreenId(r, id)`. Stored as a **screen ID**,
  not a page index — Launcher3 renumbers indices whenever screens are added, removed or reordered
  (and Anchor reorders them itself to keep rows contiguous), so an index would silently come to mean
  a different page. This mirrors how row membership is already stored.
- `TwoRowNavigationManager.homePageInRow(r)` / `homeWorkspacePage(r)` / `setHomeWorkspacePage(page)`.
- `Workspace.getDefaultPage()` reports the ACTIVE row's home; `setDefaultPage()` routes to the row
  that owns the page. So the existing long-press action and the popup's "hide when already default"
  filter became per-row with no change at their call sites.
- Default: unset → the row's leftmost page, which is exactly Lawnchair's original behaviour.
- The legacy global value is imported once (`globalDefaultPageMigrated`) and attributed to the row
  that owns that page — row 0 for any pre-existing install.

**Open question — still open, deliberately not decided here.** Home-press currently returns to ROW
0's home (via `navigateToHome`, which is also what the back gesture uses). Should it instead return
to the CURRENT ROW's home? That is arguably correct independently of the wallpaper, but it changes
navigation, not just rendering. The per-row data now exists to implement either, so this is purely a
UX decision.

## Other open questions

- Should the excursion budget scale with screen count, or stay fixed and subdivide? (Subdividing keeps
  zoom constant, which is the more valuable property.)
- What happens with 1 row / 1 page — no navigation at all? Presumably the wallpaper simply sits
  centred with no parallax, which is correct but should be confirmed.

## Known bug, tracked separately

The set-wallpaper PREVIEW and the resulting LOCK SCREEN do not show the same crop. Both should render
the rest camera. Not yet diagnosed — the preview engine may not receive the same rest, or the system
may re-crop previews itself.

## Background: what was already fixed

See memory `project-wallpaper-render-bug`. Relevant invariants that must not be broken:

- Camera is stored by ROLE (`pageWorld` / `rowWorld`), each with its own rest and full range. Storing
  by bitmap axis caused landscape parallax to stall.
- Roles are canonical in storage; the axis swap AND direction mirror are applied together in
  `WallpaperCropMath.roleToBitmapCamera()` at push time. Applying the mirror anywhere else made stored
  values change meaning per rotation.
- The engine accepts an offset only if it matches `lastPushedCamera`. Three writers push to the same
  token (Anchor, Launcher3's `WallpaperOffsetInterpolator`, the system) and the other two send from
  their own threads, so no suppression flag can win that race.
- Never call `setWallpaperOffsetSteps()` to "tag" our offsets — it declares the offset GRID and the
  framework quantises the value to the nearest multiple.
