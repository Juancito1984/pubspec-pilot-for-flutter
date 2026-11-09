# Documentación Completa — Pubspec Pilot for Flutter

**Autor:** Ing. Juan Pablo Gosalvez (NyK)  
**Contacto:** [ingejpablo@gmail.com](mailto:ingejpablo@gmail.com)  
**Licencia:** MIT  

Plugin ligero, de ultra alto rendimiento y no intrusivo para **Android Studio** e **IntelliJ IDEA**, diseñado exclusivamente para potenciar el flujo de trabajo en el archivo `pubspec.yaml` de proyectos Flutter y Dart, **sin interferir jamás con el análisis ni el IntelliSense del código fuente Dart**.

---

## 📑 Tabla de Contenidos
1. [Visión General](#visión-general)
2. [Modo Online vs Modo Offline (Pub Cache Local)](#modo-online-vs-modo-offline-pub-cache-local)
3. [Detección Multiplataforma de la Caché de Flutter](#detección-multiplataforma-de-la-caché-de-flutter)
4. [Características Principales](#características-principales)
   - [Autocompletado Inteligente en Dos Etapas](#1-autocompletado-inteligente-en-dos-etapas)
   - [Detección de Paquetes Desactualizados](#2-detección-de-paquetes-desactualizados)
   - [Ejecución Automática de flutter pub get](#3-ejecución-automática-de-flutter-pub-get)
   - [Menú Contextual y Atajos](#4-menú-contextual-y-atajos)
5. [Arquitectura y Rendimiento](#arquitectura-y-rendimiento)
6. [Compilación e Instalación](#compilación-e-instalación)
7. [Licencia](#licencia)

---

## 🎯 Visión General

Al trabajar en aplicaciones Flutter, los desarrolladores modifican frecuentemente `pubspec.yaml` para añadir librerías, actualizar versiones o probar componentes. `Pubspec Pilot` convierte este proceso manual en una experiencia fluida, rápida y libre de fricción:

- **Cero bloqueos de interfaz:** Todas las consultas de red, escaneos de disco y procesos de terminal ocurren de forma asíncrona en hilos de fondo (`AppExecutorUtil` y `ActionUpdateThread.BGT`).
- **Respeto absoluto al código Dart:** No registra analizadores ni hooks en archivos `.dart`, preservando al 100% la velocidad nativa del plugin de Flutter/Dart.

---

## 📴 Modo Online vs Modo Offline (Pub Cache Local)

Una de las capacidades más destacadas de Pubspec Pilot es su soporte nativo para **trabajo offline y sin conexión**:

### 📶 Con Conexión a Internet (Modo Online)
1. Al comenzar a escribir un nombre de paquete, el plugin emite de inmediato (en **0 ms**) las coincidencias de los paquetes populares precheados y los paquetes instalados en el equipo.
2. En paralelo, consulta la API de `pub.dev` (`https://pub.dev/api/search?q=package:<prefijo>` y búsqueda de texto completo) para descubrir paquetes nuevos o librerías recién publicadas en la nube.
3. Si el usuario escribe el nombre exacto de un paquete (como `flutter_circle_of_fifths` o `flutter_native_ui_nyk`), el plugin realiza una resolución directa (`/api/packages/<exacto>`) y lo sitúa en la primera posición con su versión resuelta al instante.

### 🔌 Sin Conexión a Internet (Modo Offline)
1. Si el equipo se queda sin conexión a internet (en viajes, aviones o caídas de red) o `pub.dev` se encuentra inalcanzable, el plugin detecta el fallo de red sin arrojar alertas invasivas ni congelar el editor.
2. El motor consulta de inmediato el **Pub Cache local del sistema** (`LocalPubCacheService`), identificando todos los paquetes que ya han sido descargados previamente en tu computadora por cualquier otro proyecto de Flutter.
3. **Autocompletado de Nombre:** Sugiere los paquetes disponibles en disco con el distintivo `(local)` y `(disponible en pub cache local offline)`.
4. **Autocompletado de Versiones:** Al escribir después de los dos puntos (`paquete: <Ctrl+Espacio>`), lista las versiones físicas instaladas en el disco local (`Local` / `pub cache`).
5. Al seleccionar el paquete, inserta la versión local (`^X.Y.Z`).
6. Si el comando estándar de descarga falla por falta de internet (`Got socket error...`), el plugin reintenta automáticamente con `flutter pub get --offline`, resolviendo las dependencias desde el Pub Cache local sin mostrar errores falsos.
7. En cuanto la conexión a internet se restablece, el plugin vuelve automáticamente a priorizar el catálogo completo en la nube.

---

## 💻 Detección Multiplataforma de la Caché de Flutter

El servicio [`LocalPubCacheService`](../src/main/kotlin/com/nyk/pubspecpilot/cache/LocalPubCacheService.kt) detecta automáticamente la ubicación de la caché de Flutter/Dart respetando las convenciones del sistema operativo y las variables del entorno:

| Sistema Operativo | Ruta por Defecto |
| :--- | :--- |
| **Windows** | `%LOCALAPPDATA%\Pub\Cache\hosted\pub.dev\` *(ej: `C:\Users\<Usuario>\AppData\Local\Pub\Cache`)* |
| **macOS** | `$HOME/.pub-cache/hosted/pub.dev/` |
| **Linux** | `$HOME/.pub-cache/hosted/pub.dev/` |
| **Personalizada** | Variable de entorno `PUB_CACHE` (respetada en todos los sistemas operativos) |

El plugin indexa los nombres y versiones mediante análisis SemVer ligero, manteniendo un caché en memoria con refresco automático cada 5 minutos.

---

## 🚀 Características Principales

### 1. Autocompletado Inteligente en Dos Etapas
* **Etapa 1 (Instantánea / 0 ms):** Despliega al instante paquetes populares precalculados + paquetes del Pub Cache local + búsquedas previas en memoria.
* **Etapa 2 (Remota / Concurrente):** Consulta `pub.dev` con consultas estrictas por prefijo `package:<query>`, eliminando falsos positivos.
* **Inserción con formato estándar:** Inserta automáticamente la indentación adecuada, el nombre del paquete y su versión recomendada con caret: `nombre: ^X.Y.Z`.

### 2. Detección de Paquetes Desactualizados
* Analiza de manera no bloqueante las secciones `dependencies`, `dev_dependencies` y `dependency_overrides`.
* **Quick Fix (`Alt + Enter`):**
  * *Actualizar paquete a ^X.Y.Z*
  * *Actualizar todos los paquetes desactualizados en bloque*
* **Inlay Hints interactivos:** Muestra un hint gris al final de la línea `⬆ ^X.Y.Z disponible`. Al hacer clic sobre el hint, actualiza la versión y dispara la sincronización.

### 3. Ejecución Automática de `flutter pub get`
* Al insertar un nuevo paquete o actualizar una versión existente, el plugin guarda `pubspec.yaml` y lanza `flutter pub get` en segundo plano.
* **Resolución inteligente del SDK de Flutter:**
  1. Archivo `android/local.properties` (`flutter.sdk`).
  2. Flutter Version Management (**FVM**): `.fvm/flutter_sdk`.
  3. Variable de entorno `FLUTTER_ROOT`.
  4. Ejecutable en el `PATH` del sistema.
* **Control de contención:** Cancela automáticamente procesos previos para evitar bloqueos por archivos de lock (`.packages` / `pubspec.lock`).

### 4. Menú Contextual y Atajos
* Acceso directo en el menú contextual del editor (`clic derecho`) y en `Tools` -> **Pubspec Pilot: Ejecutar flutter pub get**.
* Totalmente integrado con el hilo de actualización en segundo plano (`ActionUpdateThread.BGT`).

---

## 🛠️ Arquitectura y Rendimiento

* **Lenguaje:** Kotlin 2.1.20
* **Gradle Wrapper:** 9.9.0
* **IntelliJ Platform Plugin:** 2.20.0
* **Compatibilidad de Plataforma:**
  * `sinceBuild = "241"` (Android Studio 2024.1 Koala en adelante).
  * `untilBuild = null` (Soporte hacia adelante para Android Studio Build 262+ y versiones futuras).
* **Compatibilidad de Bytecode:** Java 17 bytecode target (`sourceCompatibility = 17`, `targetCompatibility = 17`, `jvmTarget = JVM_17`), 100% interoperable con entornos de ejecución Java 17, Java 21 y Java 25 (JBR).

---

## 📦 Compilación e Instalación

### Compilación desde Terminal

```bash
# Ejecutar la suite de pruebas unitarias
./gradlew test

# Compilar de forma limpia y generar el archivo .zip empaquetado
./gradlew clean buildPlugin
```

El artefacto final se genera en:
```text
build/distributions/pubspec-pilot-for-flutter-1.0.0.zip
```

### Instalación Manual en Android Studio / IntelliJ IDEA

1. Ve a **Settings** (o **Preferences** en macOS) -> **Plugins**.
2. Haz clic en el icono del engranaje ⚙️ y selecciona **Install Plugin from Disk...**.
3. Selecciona el archivo `.zip` generado en `build/distributions/`.
4. Aplica los cambios y reinicia el IDE.

---

## 📄 Licencia

Este proyecto está distribuido bajo la licencia [MIT](../LICENSE).
