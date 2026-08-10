# Flip Launcher (KaiOS-style Android launcher)

`com.flipos.launcher` — minSdk 21, targetSdk 34, compileSdk 36, Kotlin, no Compose.

Guidance for AI agents and human contributors working in this repo. Kept
machine-agnostic on purpose: it should hold for any fork/checkout, not one
person's laptop.

## Build & toolchain

- Build the debug APK from the repo root:
  ```
  ./gradlew :app:assembleDebug
  ```
  Output: `app/build/outputs/apk/debug/app-debug.apk`.
- **JDK is auto-provisioned.** The build declares a Java toolchain
  (`kotlin { jvmToolchain(17) }` in `app/build.gradle.kts`) and applies the
  Foojay resolver in `settings.gradle.kts`, so Gradle downloads/selects JDK 17
  for compilation regardless of the machine's default JDK. There is no need to
  set `JAVA_HOME` or prefix Gradle commands. Gradle itself (9.6.x) runs on any
  JDK from 17 to 26.
- **Android SDK is required** and is the one thing not auto-provisioned. Point
  the build at an SDK either via `local.properties` (`sdk.dir=/path/to/sdk`,
  gitignored) or the `ANDROID_HOME` environment variable. You need
  `platforms;android-36` and `build-tools;36.0.0` (AGP 9.3's defaults). See
  `CONTRIBUTING.md` for a from-scratch setup.
- Versions are centralized in `gradle/libs.versions.toml` (AGP + AndroidX /
  Material dependencies). AGP 9 ships built-in Kotlin, so the
  `org.jetbrains.kotlin.android` plugin is intentionally not applied.

## Devices

- `adb` is expected on `PATH` (e.g. Homebrew `android-platform-tools`). Don't
  hardcode absolute `adb`/SDK paths — they differ per machine.
- Prefer an emulator for exploratory or repro-driven UI testing. This is a
  portrait, D-pad/soft-key launcher, so any small AVD works; boot it and poll
  `adb shell getprop sys.boot_completed` until it returns `1`.
- A real physical device may be connected and **actively in use by the owner**.
  Don't drive it with synthetic `input keyevent`/`input tap` unless explicitly
  asked to test on hardware — it can collide with real input and navigate the
  user away from what they were doing.
- Some phones ship key-remapping apps (e.g. "Button Mapper") that intercept
  hardware keys, making synthetic `adb shell input keyevent` behave
  inconsistently (dropped/double-fired events). If hardware-key repro looks
  contradictory or flaky, switch to the emulator for clean signal.
- `uiautomator dump` can capture a transitional frame for fast-changing focus
  state (stale/non-monotonic). Prefer `exec-out screencap -p` with ~0.5-0.8s
  settle time after the triggering input, or cross-check
  `dumpsys window | grep mCurrentFocus` for the foregrounded Activity.

## Project structure

`app/src/main/java/com/flipos/launcher/` is split into sibling packages:

- `activities/` — every screen Activity plus `BaseListActivity`.
- `ui/` — RecyclerView adapters and custom views.
- `data/` — prefs, repositories, and the built-in icon/wallpaper registries.
- `service/` — the notification listener.
- `util/` — small extensions.

Key pieces:

- `BaseListActivity` is shared scaffolding (title bar + RecyclerView +
  SoftKeyBar) for every vertical list screen (Options, Launcher Settings,
  Hide Apps, Shortcuts, App/Activity Picker, Notices). It also owns the
  accent-color theme-overlay-on-`onCreate` + recreate-on-resume-if-changed
  pattern — new list screens should extend it rather than reinventing this.
- `ListRowAdapter` + `Row` is the generic one-line-or-icon-row adapter reused
  across those list screens; `AppGridAdapter` is the App Drawer's icon grid;
  `NoticeRowAdapter` is the richer 3-line notice row.
- `LauncherPrefs` is the single SharedPreferences wrapper — all settings
  (icon size/shape/pack, accent color, drawer view mode, badges, shortcuts,
  hidden apps, per-app icon overrides) live there.
- `IconShapeRenderer` masks app icons into the user's chosen shape (adaptive
  icons composite their own fg/bg layers then get clipped; legacy icons get
  an optional synthesized tinted background disc).

## Bundled icons & wallpapers

Built-in icons and wallpapers live directly in
`app/src/main/res/drawable-nodpi/` — that folder is the single source of truth
(there are no separate staging folders). To add one:

1. Drop the PNG (icon) or JPG (wallpaper) into
   `app/src/main/res/drawable-nodpi/`.
2. Add the filename (no extension) to the matching registry:
   `BuiltInIcons.NAMES` in `data/BuiltInIcons.kt` or `BuiltInWallpapers.NAMES`
   in `data/BuiltInWallpapers.kt`.

Resources aren't auto-discovered, so step 2 is required for the asset to show
up in the picker.
