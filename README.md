# Pubspec Pilot for Flutter

[![Platform](https://img.shields.io/badge/Platform-Android%20Studio%20%7C%20IntelliJ%20IDEA-042B59.svg)](https://plugins.jetbrains.com/)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.1.20-7F52FF.svg)](https://kotlinlang.org/)
[![Gradle](https://img.shields.io/badge/Gradle-9.9.0-02303A.svg)](https://gradle.org/)
[![License](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)

Lightweight, high-performance, and non-intrusive plugin for **Android Studio** and **IntelliJ IDEA** dedicated exclusively to enhancing `pubspec.yaml` in Flutter & Dart projects — **with zero interference to Dart code IntelliSense**.

---

## 🌐 Documentation / Documentación

* 🇪🇸 **[Documentación en Español](README_ES.md)** — Guía completa de características, arquitectura, configuración e instalación.
* 🇺🇸 **[English Documentation](README_EN.md)** — Complete guide to features, architecture, configuration, and installation.

---

## ⚡ Executive Summary / Resumen Ejecutivo

### 🇪🇸 Resumen en Español
* **Detección de paquetes desactualizados:** Inspección en segundo plano (`ExternalAnnotator`) con advertencias visuales, Quick Fix (`Alt + Enter`) individual o masivo, e **Inlay Hints** interactivos al final de la línea.
* **Autocompletado con pub.dev:** Búsqueda en tiempo real de paquetes y versiones estables sin ralentizar la interfaz (etapa instantánea en 0 ms + etapa remota).
* **flutter pub get automático:** Guarda y sincroniza paquetes automáticamente al insertar o actualizar dependencias, resolviendo rutas de SDK (FVM, local.properties, PATH).
* **Acciones de menú con BGT:** Integración en menú contextual y Tools con `ActionUpdateThread.BGT`.

### 🇺🇸 English Summary
* **Outdated Package Detection:** Background analysis (`ExternalAnnotator`) with warning highlights, single and batch Quick Fixes (`Alt + Enter`), and interactive end-of-line **Inlay Hints**.
* **Real-time pub.dev Auto-Completion:** Two-stage lookup (0 ms instant local matches + remote search) for packages and version constraints.
* **Automatic flutter pub get:** Seamless background execution upon inserting or updating dependencies with multi-strategy SDK discovery (FVM, local.properties, PATH).
* **Modern BGT Threading:** Context and Tools menu actions powered by `ActionUpdateThread.BGT`.

---

## 🏗️ Quick Build / Compilación Rápida

```bash
# Ejecutar pruebas unitarias / Run unit tests
./gradlew test

# Compilar plugin distribuible / Build plugin distribution
./gradlew clean buildPlugin
```

Output: `build/distributions/pubspec-pilot-for-flutter-1.0.0.zip`
