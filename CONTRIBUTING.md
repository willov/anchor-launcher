# Contributing to Anchor Launcher

Pull requests are welcome, whether it's a bug fix, a new feature or a typo. For a larger change, consider opening an issue first so we can agree on the approach before you put in the work.

## Reporting bugs and requesting features

- **Bugs:** [open an issue](https://github.com/willov/anchor-launcher/issues/new). Include the Anchor version (Settings → About), your device and Android version, and the steps to reproduce it. A screen recording helps a lot with anything to do with rotation.
- **Features:** open an issue describing what you'd like and why.
- **Security issues:** don't open a public issue. Follow the [security policy](SECURITY.md) instead.

Please follow the [Code of Conduct](CODE_OF_CONDUCT.md).

## Getting started

1. Clone the repository with its submodules:
   ```bash
   git clone --recurse-submodules https://github.com/willov/anchor-launcher
   ```
   If you already cloned it without them, run `git submodule update --init --recursive`.
2. Open the project in Android Studio and select the `lawnWithQuickstepPlayDebug` build variant.
3. Branch from `dev`, and open your pull request against `dev`.

Before opening a pull request, run the unit tests (CI runs them too):

```bash
./gradlew testLawnWithQuickstepPlayDebugUnitTest
```

Changes to the home screen grid, rotation or navigation also need testing on a real device in both portrait and landscape. Please say in the pull request which devices you tested on.

## Keeping close to Lawnchair

Anchor is a fork of [Lawnchair](https://github.com/LawnchairLauncher/lawnchair) and regularly merges in its updates. Every line changed in Lawnchair's or Launcher3's files is a possible merge conflict later, so the aim is to change as little of their code as possible.

- **Put new code in the `anchor` package** (`lawnchair/src/app/anchor/`), not in Lawnchair's `app.lawnchair` package or in Launcher3's `src/`.
- **Keep edits to upstream files small.** When Lawnchair or Launcher3 code has to change, prefer a short call into Anchor code over a large edit in place. Mark each change with an `// Anchor:` comment explaining why it's needed, so it's easy to spot and keep during a merge.
- **Don't reformat, rename or reorder upstream code** you aren't otherwise changing, even if it would be tidier.
- **Don't rename internal Lawnchair names** (classes, packages, preference keys, log tags). Only text users can see should say Anchor.
- **Add new strings to `res/values/strings.xml`** with an `anchor_` prefix. Don't hand-edit Lawnchair's translations (`values-*/strings.xml`). They are rebranded by `scripts/rebrand-translations.py` after each merge.
- **Keep the copyright headers** in existing Lawnchair and Launcher3 files. For a new file, use:
  ```kotlin
  /*
   * Copyright (C) 2025 Anchor Launcher Contributors
   *
   * Licensed under the Apache License, Version 2.0.
   * See LICENSE for details.
   */
  ```
  If you substantially change an upstream file, add a `Modifications Copyright (C) <year> Anchor Launcher Contributors` line below its existing notice. Never replace the notice.

If a fix isn't specific to Anchor, consider sending it to Lawnchair as well. Once they've merged it, Anchor gets it with no extra code to carry.

## Writing the change

- Match the style of the code around you. For Kotlin, follow the [Kotlin coding conventions](https://kotlinlang.org/docs/coding-conventions.html).
- Write commit messages as a short, plain summary of what changed and why, for example "Keep the drawer centred in landscape on phones with a camera cutout".
- For changes users will notice, add an entry under `[Unreleased]` in [CHANGELOG.md](CHANGELOG.md), describing what changed from the user's point of view.

## License

Anchor is licensed under the [Apache License 2.0](LICENSE.txt). By contributing, you agree that your contributions are licensed under it too.
