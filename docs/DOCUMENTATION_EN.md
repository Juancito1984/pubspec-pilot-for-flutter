# Full Documentation — Pubspec Pilot for Flutter (by NyK)

Lightweight, ultra-fast, and non-intrusive plugin for **Android Studio** and **IntelliJ IDEA**, exclusively tailored to optimize workflow inside the `pubspec.yaml` file of Flutter and Dart projects, **with zero interference to Dart source code analysis or IntelliSense**.

---

## 📑 Table of Contents
1. [Overview](#overview)
2. [Online Mode vs Offline Mode (Local Pub Cache)](#online-mode-vs-offline-mode-local-pub-cache)
3. [Cross-Platform Pub Cache Detection](#cross-platform-pub-cache-detection)
4. [Key Features](#key-features)
   - [Two-Stage Smart Auto-Completion](#1-two-stage-smart-auto-completion)
   - [Outdated Package Detection](#2-outdated-package-detection)
   - [Automatic flutter pub get Execution](#3-automatic-flutter-pub-get-execution)
   - [Context Menus and Shortcuts](#4-context-menus-and-shortcuts)
5. [Architecture and Performance](#architecture-and-performance)
6. [Build and Installation](#build-and-installation)
7. [License](#license)

---

## 🎯 Overview

When developing Flutter applications, developers frequently interact with `pubspec.yaml` to discover libraries, upgrade version constraints, and sync dependencies. `Pubspec Pilot` streamlines this entire process:

- **Zero UI Freezes:** All network lookups, filesystem scans, and CLI runners execute asynchronously in background thread pools (`AppExecutorUtil` and `ActionUpdateThread.BGT`).
- **Complete Respect for Dart Files:** Does not register AST annotators or completion contributors on `.dart` files, preserving 100% of the native speed and responsiveness of the official Flutter/Dart plugins.

---

## 📴 Online Mode vs Offline Mode (Local Pub Cache)

One of the standout capabilities of Pubspec Pilot is its native, transparent **offline workflow support**:

### 📶 Online Mode (Internet Connected)
1. As soon as you begin typing a package name under `dependencies:`, the plugin immediately renders instant matches in **0 ms** using pre-cached popular packages and local packages.
2. In parallel, it queries `pub.dev` (`https://pub.dev/api/search?q=package:<prefix>` along with full-text search) to discover newly published libraries and cloud updates.
3. If you type the exact name of an existing package (such as `flutter_circle_of_fifths` or `flutter_native_ui_nyk`), the plugin performs a direct API lookup (`/api/packages/<exact>`) and promotes it to the top position with its version resolved in real time.

### 🔌 Offline Mode (No Internet Connection)
1. If your machine loses its internet connection (traveling, flights, or network outages) or `pub.dev` is unreachable, the plugin gracefully catches network failures without raising intrusive error banners or lagging the editor.
2. The engine immediately queries the **system's local Pub Cache** via [`LocalPubCacheService`](../src/main/kotlin/com/nyk/pubspecpilot/cache/LocalPubCacheService.kt), finding all packages previously downloaded on your machine by any Flutter project.
3. **Package Name Auto-Completion:** Suggests locally available packages with clear labels: `(local)` and `(disponible en pub cache local offline)`.
4. **Version Auto-Completion:** Typing after the colon (`package: <Ctrl+Space>`) lists the physical versions found on disk (`Local` / `pub cache`).
5. Selecting a package inserts the locally available version constraint (`^X.Y.Z`).
6. If the default command fails due to no network connection (`Got socket error...`), the plugin automatically retries with `flutter pub get --offline`, linking the packages directly from the local Pub Cache with zero false alarms.
7. Once the internet connection is restored, the plugin automatically resumes prioritizing live `pub.dev` queries.

---

## 💻 Cross-Platform Pub Cache Detection

The [`LocalPubCacheService`](../src/main/kotlin/com/nyk/pubspecpilot/cache/LocalPubCacheService.kt) dynamically identifies the system's Pub Cache path adhering to operating system conventions:

| Operating System | Default Hosted Cache Directory |
| :--- | :--- |
| **Windows** | `%LOCALAPPDATA%\Pub\Cache\hosted\pub.dev\` *(e.g. `C:\Users\<User>\AppData\Local\Pub\Cache`)* |
| **macOS** | `$HOME/.pub-cache/hosted/pub.dev/` |
| **Linux** | `$HOME/.pub-cache/hosted/pub.dev/` |
| **Custom Path** | Environment variable `PUB_CACHE` (honored across all platforms) |

The plugin indexes package names and versions using lightweight SemVer evaluation, caching results in memory and refreshing every 5 minutes.

---

## 🚀 Key Features

### 1. Two-Stage Smart Auto-Completion
* **Stage 1 (Instant / 0 ms):** Instantly displays pre-calculated popular packages + local Pub Cache entries + previously cached in-memory searches.
* **Stage 2 (Remote / Parallel):** Searches `pub.dev` with strict `package:<query>` prefix filtering to eliminate false positives and irrelevant description matches.
* **Standard Format Insertion:** Inserts matching indentation, package name, and the recommended caret constraint: `package_name: ^X.Y.Z`.

### 2. Outdated Package Detection
* Non-blocking background scan across `dependencies`, `dev_dependencies`, and `dependency_overrides`.
* **Quick Fix (`Alt + Enter`):**
  * *Upgrade package to ^X.Y.Z*
  * *Upgrade all outdated packages at once*
* **Interactive Inlay Hints:** Renders a clean gray hint at the end of the line: `⬆ ^X.Y.Z available`. Clicking the hint updates the version constraint and triggers sync.

### 3. Automatic `flutter pub get` Execution
* Upon inserting a new dependency or upgrading a version, the plugin saves `pubspec.yaml` and executes `flutter pub get` in the background.
* **Multi-Strategy Flutter SDK Discovery:**
  1. `android/local.properties` (`flutter.sdk` property).
  2. Flutter Version Management (**FVM**): `.fvm/flutter_sdk`.
  3. Environment variable `FLUTTER_ROOT`.
  4. Executable discovered via system `PATH`.
* **Lock Contention Management:** Automatically cancels stale in-flight pub get runs to avoid `.packages` and `pubspec.lock` lock conflicts.

### 4. Context Menus and Shortcuts
* Right-click editor context menu and `Tools` -> **Pubspec Pilot: Run flutter pub get**.
* Fully integrated with IntelliJ's background action thread (`ActionUpdateThread.BGT`).

---

## 🛠️ Architecture and Performance

* **Language:** Kotlin 2.1.20
* **Gradle Wrapper:** 9.9.0
* **IntelliJ Platform Plugin:** 2.20.0
* **Platform Compatibility:**
  * `sinceBuild = "241"` (Android Studio 2024.1 Koala and newer).
  * `untilBuild = null` (Forward compatibility for Android Studio Build 262+ and future versions).
* **Bytecode Target:** Java 17 bytecode (`sourceCompatibility = 17`, `targetCompatibility = 17`, `jvmTarget = JVM_17`), 100% compatible with Java 17, Java 21, and Java 25 (JBR) runtimes.

---

## 📦 Build and Installation

### Building from Terminal

```bash
# Run unit test suite
./gradlew test

# Clean build and create distribution zip archive
./gradlew clean buildPlugin
```

The distribution zip is created at:
```text
build/distributions/pubspec-pilot-for-flutter-1.0.0.zip
```

### Manual Installation in Android Studio / IntelliJ IDEA

1. Navigate to **Settings** (or **Preferences** on macOS) -> **Plugins**.
2. Click the gear icon ⚙️ and select **Install Plugin from Disk...**.
3. Choose the generated `.zip` in `build/distributions/`.
4. Apply changes and restart the IDE.

---

## 📄 License

This project is licensed under the [MIT License](../LICENSE).
