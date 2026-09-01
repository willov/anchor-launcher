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

## Prerequisite: per-row default page

The model rests on "the home page of the current row", so home must be settable per row. Today
Lawnchair has a single global `default_home_page` pref plus a "Set as default page" long-press action.

- Extend to `defaultPageForRow(r)`; migrate the existing global value to row 0.
- Default: leftmost on every row, which is today's behaviour — no migration surprise.
- Reuses existing UX (the long-press action), so no new concept for the user.

**Open question:** Home-press currently returns to the global default page. Should it return to the
CURRENT ROW's home instead? That is arguably correct independently of the wallpaper, but it changes
navigation, not just rendering — decide deliberately.

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
