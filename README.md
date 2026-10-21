# Anchor Launcher

**Tired of your icons jumping around when you rotate your phone? Anchor keeps every icon exactly where you put it.**

Anchor is an Android home screen app built on [Lawnchair](https://github.com/LawnchairLauncher/lawnchair). When you turn your phone, the system turns everything upright as usual, but each icon stays at the same physical spot on the glass. An icon under your thumb in portrait is still under your thumb in landscape.

## Features

- **Icons that stay put on rotation.** The grid turns with the phone (4×9 in portrait becomes 9×4 in landscape), so nothing reflows or jumps.
- **Home screens in rows as well as pages.** Swipe left and right between pages, and down and up between rows. Each row remembers which page you were on. What goes in each row is up to you.
- **A steady background.** The wallpaper stays fixed when you rotate and moves with you as you scroll between pages and rows.
- **Built-in wallpapers**, including vector art drawn for Anchor that stays sharp at any screen size.
- **An app drawer with A–Z sections** and a letter scroller for jumping straight to a letter.
- **Everything else from Lawnchair:** icon packs, fonts, theming that follows your wallpaper, and search.

## Download

Anchor is not publicly released yet. A Google Play release is on the way.

## Building

```bash
git clone --recurse-submodules https://github.com/willov/anchor-launcher
cd anchor-launcher
./gradlew assembleLawnWithQuickstepPlayDebug
```

The debug build installs as **Anchor Launcher (Debug)** (`se.willovapps.anchor.debug`), next to any release install.

Run the unit tests with:

```bash
./gradlew testLawnWithQuickstepPlayDebugUnitTest
```

See [CHANGELOG.md](CHANGELOG.md) for what changed in each version and [ROADMAP.md](ROADMAP.md) for what's planned.

## Donating

Donations to Anchor Launcher have not been set up yet. In the meantime, please consider supporting the projects Anchor is built on:

- **[Lawnchair](https://opencollective.com/lawnchair):** the open-source launcher Anchor is built on.
- **[GNOME](https://www.gnome.org/donate/):** the GNOME community made many of Anchor's built-in wallpapers.

Anchor Launcher is an independent project. It is not affiliated with or endorsed by Lawnchair, the GNOME Foundation, Google or the Android Open Source Project. Donations made through these links go directly to those projects, not to Anchor Launcher.

## Credits and license

Anchor is a fork of [Lawnchair](https://github.com/LawnchairLauncher/lawnchair), which is based on Launcher3 from the [Android Open Source Project](https://source.android.com/). Thank you to everyone who has worked on both.

Anchor is licensed under the [Apache License 2.0](LICENSE.txt), the same as Lawnchair and Launcher3. Their original copyright notices are kept in their files.
