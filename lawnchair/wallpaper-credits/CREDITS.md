# Bundled wallpaper credits

The `anchor_wp_*.webp` images in `lawnchair/res/raw/` are backgrounds from the
[GNOME project](https://gitlab.gnome.org/GNOME/gnome-backgrounds), bundled with Anchor Launcher. Each
background ships in a **light** (`_l`) and **dark** (`_d`) variant.

They are licensed under **Creative Commons Attribution-ShareAlike 3.0** (CC-BY-SA 3.0) — a per-asset
carve-out from the repository's Apache-2.0 license: the code is Apache-2.0, these image assets are
CC-BY-SA 3.0.

The images are shipped **unmodified** — the original GNOME artwork, rasterized from the upstream
SVG/JXL to 3072×3072 and stored as WebP (lossless for the flat-shape artwork, Blobs and Drool; lossy
for the photographic ones). Converting between file formats is a format shift, which CC-BY-SA 3.0
expressly permits: the licensed rights "may be exercised in all media and formats" and include "the
right to make such modifications as are technically necessary to exercise the rights in other media
and formats". The artwork itself is unaltered — same composition, same colours, no crop or recolour.
Anchor downscales and crops it at runtime on the device for display; no modified derivative is
distributed.

All by **Jakub Steiner** (GNOME project), **CC-BY-SA 3.0**. Source:
<https://gitlab.gnome.org/GNOME/gnome-backgrounds/-/tree/main/backgrounds>

| Background | Files | Upstream |
|------------|-------|----------|
| Adwaita | `anchor_wp_adwaita_{l,d}.webp` | `adwaita-{l,d}.jxl` |
| Amber | `anchor_wp_amber_{l,d}.webp` | `amber-{l,d}.jxl` |
| Blobs | `anchor_wp_blobs_{l,d}.webp` | `blobs-{l,d}.svg` |
| Drool | `anchor_wp_drool_{l,d}.webp` | `drool-{l,d}.svg` |
| Fold | `anchor_wp_fold_{l,d}.webp` | `fold-{l,d}.jxl` |

Anchor Launcher is not affiliated with or endorsed by the GNOME Foundation.

See the in-app wallpaper credits screen (Settings → Home Screen → Wallpaper → Anchor wallpaper →
Wallpaper credits) for the user-facing attribution and a GNOME donation link.
