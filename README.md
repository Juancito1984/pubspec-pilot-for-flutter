# Pubspec Pilot for Flutter

[![Platform](https://img.shields.io/badge/Platform-Android%20Studio%20%7C%20IntelliJ%20IDEA-042B59.svg)](https://plugins.jetbrains.com/)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.1.20-7F52FF.svg)](https://kotlinlang.org/)
[![Gradle](https://img.shields.io/badge/Gradle-9.9.0-02303A.svg)](https://gradle.org/)
[![Author](https://img.shields.io/badge/Author-Ing.%20Juan%20Pablo%20Gosalvez%20(NyK)-blue.svg)](mailto:ingejpablo@gmail.com)
[![License](https://img.shields.io/badge/License-MIT-green.svg)](LICENSE)

Lightweight, high-performance, and non-intrusive plugin for **Android Studio** and **IntelliJ IDEA** created by **Ing. Juan Pablo Gosalvez (NyK)** ([ingejpablo@gmail.com](mailto:ingejpablo@gmail.com)), dedicated exclusively to enhancing `pubspec.yaml` in Flutter & Dart projects — **with zero interference to Dart code IntelliSense**.

---

## 📖 Documentación Completa / Full Documentation

Accede a la documentación detallada con guías de arquitectura, flujos online/offline y opciones de configuración:

* 🇪🇸 **[Documentación Completa en Español](docs/DOCUMENTATION_ES.md)**
* 🇺🇸 **[Full Documentation in English](docs/DOCUMENTATION_EN.md)**

---

## ⚡ Resumen de Funcionalidades / Feature Summary

### 🇪🇸 Español
* **Autocompletado Híbrido (Online & Offline):** Búsqueda en tiempo real en `pub.dev` (`package:<prefijo>`) con soporte offline automático desde la caché local de Flutter (`Pub Cache`) del sistema cuando no hay conexión a internet.
* **Detección de paquetes desactualizados:** Análisis no bloqueante en segundo plano con advertencias visuales, Quick Fix (`Alt + Enter`) individual o masivo, e **Inlay Hints** interactivos al final de la línea.
* **flutter pub get automático:** Guarda y sincroniza dependencias automáticamente al insertar o actualizar paquetes, resolviendo rutas de SDK (local.properties, FVM, PATH).
* **Cero interferencia:** Diseñado exclusivamente para `pubspec.yaml`, manteniendo el código Dart al 100% de su rendimiento nativo.

### 🇺🇸 English
* **Hybrid Auto-Completion (Online & Offline):** Real-time `pub.dev` lookup (`package:<prefix>`) with automatic offline fallback to the system's local Flutter `Pub Cache` when internet connection is lost.
* **Outdated Package Detection:** Asynchronous background analysis with warning highlights, single and batch Quick Fixes (`Alt + Enter`), and interactive end-of-line **Inlay Hints**.
* **Automatic flutter pub get:** Saves and synchronizes dependencies automatically upon inserting or updating packages, with smart SDK discovery (local.properties, FVM, PATH).
* **Zero Interference:** Exclusively active on `pubspec.yaml`, leaving Dart source code editing at 100% native responsiveness.

---

## 🏗️ Compilación Rápida / Quick Build

```bash
# Ejecutar pruebas unitarias / Run unit tests
./gradlew test

# Compilar plugin distribuible / Build plugin distribution
./gradlew clean buildPlugin
```

Artefacto resultante / Output: `build/distributions/pubspec-pilot-for-flutter-1.0.0.zip`
