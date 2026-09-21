# Contributing to Flip Launcher

Thanks for hacking on Flip Launcher. This guide gets you from a fresh clone to
a built APK, and covers the few project-specific conventions.

## Prerequisites

You need three things on your machine.

### 1. Android SDK

The build needs `platforms;android-36` and `build-tools;36.0.0` (AGP 9.3's
defaults). If you use Android Studio, install those via the SDK Manager and
you're done. For a headless / command-line setup on macOS:

```bash
brew install --cask android-commandlinetools
# Point sdkmanager at a sdk root and install the required components:
sdkmanager --sdk_root="$HOME/Library/Android/sdk" \
  "platform-tools" "platforms;android-36" "build-tools;36.0.0"
sdkmanager --sdk_root="$HOME/Library/Android/sdk" --licenses
```

Then tell the build where the SDK is, using **either**:

- a `local.properties` file in the repo root (gitignored):
  ```
  sdk.dir=/Users/you/Library/Android/sdk
  ```
- or an `ANDROID_HOME` environment variable pointing at the same path.

### 2. adb (for installing / debugging on a device)

`adb` should be on your `PATH`. It comes with the SDK's `platform-tools`, or on
macOS: `brew install --cask android-platform-tools`.

### 3. JDK 17 (for compilation)

The build pins Kotlin/Java compilation to JDK 17 (`kotlin { jvmToolchain(17) }`
in `app/build.gradle.kts`). Gradle auto-detects it from whatever's already on
your machine — `JAVA_HOME`, `~/.jdks`, Android Studio's own bundled JBR — so
there's usually nothing to install or configure. It will **not** download a
JDK over the network, so a proxy/VPN that breaks TLS won't break this build.
If auto-detection can't find a JDK 17, install one and either let it be
found automatically or point Gradle at it directly by adding
`org.gradle.java.installations.paths=/path/to/jdk-17` to a local,
gitignored `gradle.properties` override (not the committed one), or by
setting `JAVA_HOME`.

Gradle itself (9.6.x, used to run the build) works fine on any JDK from 17
through 26 — the pin above is specifically for what compiles the app's code.

## Building & installing

```bash
# Debug APK -> app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:assembleDebug

# Install onto a connected device / emulator
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Set Flip Launcher as the default Home app (Options -> Set as Default Launcher),
and grant Notification Access from Launcher Settings for the Notices screen and
Home notification badges.

## Project layout

Sources live under `app/src/main/java/com/flipos/launcher/`:

- `activities/` — every screen Activity plus the shared `BaseListActivity`.
- `ui/` — RecyclerView adapters and custom views.
- `data/` — prefs, repositories, and the built-in icon/wallpaper registries.
- `service/` — the notification listener.
- `util/` — small extensions.

Dependency and plugin versions are centralized in
[`gradle/libs.versions.toml`](gradle/libs.versions.toml).

## Adding a built-in icon or wallpaper

Bundled assets live in `app/src/main/res/drawable-nodpi/` (the single source of
truth — there are no staging folders).

1. Drop the PNG (icon) or JPG (wallpaper) into
   `app/src/main/res/drawable-nodpi/`.
2. Register the filename (without extension) in the matching list:
   - icons: `BuiltInIcons.NAMES` in `data/BuiltInIcons.kt`
   - wallpapers: `BuiltInWallpapers.NAMES` in `data/BuiltInWallpapers.kt`

Step 2 is required — resources are looked up by name at runtime and are not
auto-discovered.
