# Pubspec Pilot for Flutter (by NyK)

Lightweight, fast, and non-intrusive plugin for **Android Studio** and **IntelliJ IDEA** exclusively focused on enhancing the `pubspec.yaml` file of Flutter and Dart projects, **without ever interfering with Dart code IntelliSense**.

---

## 🚀 Key Features

1. **Outdated Package Detection in `pubspec.yaml`:**
   * Scans `dependencies`, `dev_dependencies`, and `dependency_overrides` asynchronously via `ExternalAnnotator`.
   * **Warning Highlights + Quick Fix (`Alt + Enter`):** Update an individual package or all outdated packages at once with a single click.
   * **Interactive Inlay Hint at End-of-Line:** Displays gray inline text `⬆ ^X.Y.Z available (click to update)` directly next to the outdated version constraint.

2. **Smart Auto-Completion Powered by `pub.dev`:**
   * Start typing any package name under `dependencies:` or `dev_dependencies:` to search `pub.dev` in real-time, showing the latest stable version in the lookup list.
   * Pressing `Enter` or `Tab` automatically inserts `package_name: ^X.Y.Z`.
   * Also provides version auto-completion when typing after the colon (`package: <Ctrl+Space>`).

3. **Automatic Dependency Fetching (`flutter pub get`):**
   * After inserting a package or updating a version, the plugin saves `pubspec.yaml` and runs `flutter pub get` in the background with balloon notifications upon completion.
   * Automatic Flutter SDK discovery (`local.properties`, FVM, `FLUTTER_ROOT`, and `PATH`).
   * Cancels previous in-flight runs to prevent Flutter lock contention.

4. **Dedicated Context & Tools Menu Action:**
   * Context menu (`right-click`) and `Tools` menu: **Pubspec Pilot: Run flutter pub get**.
   * Powered by `ActionUpdateThread.BGT` for zero UI freezes.

---

## 🛠️ Architecture and Stack

* **Kotlin:** `2.1.20`
* **IntelliJ Platform Gradle Plugin:** `2.20.0`
* **Gradle Wrapper:** `9.9.0`
* **Platform Compatibility:**
  * `sinceBuild = "241"` (Android Studio 2024.1 Koala and newer).
  * No upper bound (`untilBuild = null`) for complete forward compatibility with Android Studio Build 262+ and future releases.
* **JVM Compatibility:** Java 17 (`sourceCompatibility`, `targetCompatibility`, Kotlin `jvmTarget = JVM_17`), fully compatible with runtime execution on JBR 17/21/25.

---

## 📦 Build and Installation

### Building from Terminal

```bash
# Run unit tests
./gradlew test

# Clean build and create the plugin distribution zip
./gradlew clean buildPlugin
```

The distributable artifact will be generated at:
```
build/distributions/pubspec-pilot-for-flutter-1.0.0.zip
```

### Installing in Android Studio / IntelliJ IDEA

1. Open **Settings** (or **Preferences** on macOS) -> **Plugins**.
2. Click the gear icon ⚙️ and select **Install Plugin from Disk...**.
3. Choose `build/distributions/pubspec-pilot-for-flutter-1.0.0.zip`.
4. Restart the IDE or apply changes.
