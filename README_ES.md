# Pubspec Pilot for Flutter (por NyK)

Plugin ligero, rápido y no intrusivo para **Android Studio** e **IntelliJ IDEA** enfocado exclusivamente en optimizar el archivo `pubspec.yaml` de proyectos Flutter y Dart, **sin interferir jamás con el IntelliSense de código Dart**.

---

## 🚀 Características Principales

1. **Detección de paquetes desactualizados en `pubspec.yaml`:**
   * Analiza `dependencies`, `dev_dependencies` y `dependency_overrides` en segundo plano mediante `ExternalAnnotator`.
   * **Resaltado de advertencia + Quick Fix (`Alt + Enter`):** Permite actualizar un paquete individual o todos los paquetes desactualizados en bloque con un solo clic.
   * **Inlay Hint interactivo al final de la línea:** Muestra en texto gris `⬆ ^X.Y.Z disponible (clic para actualizar)` justo a la derecha de la versión antigua sin bloquear el hilo de edición.

2. **Autocompletado inteligente conectado a `pub.dev`:**
   * Al escribir el nombre de un paquete debajo de `dependencies:` o `dev_dependencies:`, busca en tiempo real en `pub.dev` y muestra la última versión estable disponible en la lista desplegable.
   * Al seleccionarlo con `Enter` o `Tab`, inserta automáticamente `nombre_paquete: ^X.Y.Z`.
   * Ofrece autocompletado de versiones si escribes después de los dos puntos (`paquete: <Ctrl+Espacio>`).

3. **Descarga automática (`flutter pub get`):**
   * Cada vez que insertas un paquete nuevo o actualizas una versión existente, el plugin guarda `pubspec.yaml` y ejecuta automáticamente `flutter pub get` en segundo plano.
   * Detecta automáticamente el SDK de Flutter (`local.properties`, FVM, `FLUTTER_ROOT` y `PATH`).
   * Cancela ejecuciones en vuelo previas para evitar bloqueos por contención de lock en Flutter.

4. **Acción Manual de Menú:**
   * Menú contextual (`clic derecho`) y menú `Tools`: **Pubspec Pilot: Ejecutar flutter pub get**.
   * Ejecutado con `ActionUpdateThread.BGT` garantizando máxima fluidez en la interfaz.

---

## 🛠️ Arquitectura y Tecnologías

* **Kotlin:** `2.1.20`
* **IntelliJ Platform Gradle Plugin:** `2.20.0`
* **Gradle Wrapper:** `9.9.0`
* **Compatibilidad de Plataforma:**
  * `sinceBuild = "241"` (Android Studio 2024.1 Koala en adelante).
  * Sin límite superior (`untilBuild = null`) para total compatibilidad con Android Studio Build 262+ y versiones futuras.
* **Compatibilidad JVM:** Java 17 (`sourceCompatibility`, `targetCompatibility`, Kotlin `jvmTarget = JVM_17`), optimizado para ejecución sobre JBR 17/21/25.

---

## 📦 Compilación e Instalación

### Compilación desde Terminal

```bash
# Ejecutar pruebas unitarias
./gradlew test

# Compilación limpia y generación del plugin instalable
./gradlew clean buildPlugin
```

El artefacto distribuible se generará en:
```
build/distributions/pubspec-pilot-for-flutter-1.0.0.zip
```

### Instalación en Android Studio / IntelliJ IDEA

1. Abre **Settings** (o **Preferences** en macOS) -> **Plugins**.
2. Haz clic en el icono de engranaje ⚙️ y selecciona **Install Plugin from Disk...**.
3. Selecciona el archivo `build/distributions/pubspec-pilot-for-flutter-1.0.0.zip`.
4. Reinicia el IDE o aplica los cambios.
