package com.nyk.pubspecpilot.runner

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.Properties
import java.util.concurrent.ConcurrentHashMap

object FlutterPubGetRunner {

    // Cache resolved Flutter executable per project directory to avoid repeated disk I/O
    private val flutterExeCache = ConcurrentHashMap<String, String>()

    // Track active process per working directory so rapid package updates supersede older runs instead of fighting over Flutter lock
    private val activeProcesses = ConcurrentHashMap<String, CapturingProcessHandler>()

    /**
     * Saves pubspec.yaml to disk and runs `flutter pub get` asynchronously in the background.
     * Supersedes any in-flight `flutter pub get` on the same project to prevent Flutter lock contention.
     */
    fun runPubGet(project: Project, pubspecVirtualFile: VirtualFile?, reasonLabel: String) {
        val workDirPath = pubspecVirtualFile?.parent?.path ?: project.basePath ?: return

        // Save only modified documents on EDT first so pubspec.yaml changes are written to disk
        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed) return@invokeLater
            val fileDocManager = FileDocumentManager.getInstance()
            if (pubspecVirtualFile != null) {
                fileDocManager.getDocument(pubspecVirtualFile)?.let { fileDocManager.saveDocument(it) }
            } else {
                fileDocManager.saveAllDocuments()
            }

            // Cancel any previous in-flight `flutter pub get` for this directory to avoid lock contention
            activeProcesses.remove(workDirPath)?.let { previousHandler ->
                if (!previousHandler.isProcessTerminated && !previousHandler.isProcessTerminating) {
                    previousHandler.destroyProcess()
                }
            }

            ProgressManager.getInstance().run(
                object : Task.Backgroundable(
                    project,
                    "Pubspec Pilot: Ejecutando flutter pub get ($reasonLabel)...",
                    true
                ) {
                    override fun run(indicator: ProgressIndicator) {
                        indicator.isIndeterminate = true
                        indicator.text = "Descargando paquetes ($reasonLabel)..."

                        val flutterExec = resolveFlutterExecutable(workDirPath)
                        val commandLine = GeneralCommandLine()
                            .withExePath(flutterExec)
                            .withParameters("pub", "get")
                            .withWorkDirectory(workDirPath)
                            .withCharset(StandardCharsets.UTF_8)
                            .withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.CONSOLE)

                        var handler: CapturingProcessHandler? = null
                        try {
                            handler = CapturingProcessHandler(commandLine)
                            activeProcesses[workDirPath] = handler

                            val output = handler.runProcessWithProgressIndicator(indicator, 120_000)

                            // If cancelled or superseded by a newer run, exit silently
                            if (indicator.isCanceled || activeProcesses[workDirPath] != handler) {
                                return
                            }

                            // Refresh only the files touched by `flutter pub get` instead of scanning the entire project tree
                            refreshPubFilesOnly(workDirPath, pubspecVirtualFile)

                            if (output.exitCode == 0) {
                                notify(
                                    project,
                                    "Pubspec Pilot",
                                    "Paquete sincronizado ($reasonLabel) — <code>flutter pub get</code> completado.",
                                    NotificationType.INFORMATION
                                )
                            } else if (!output.isCancelled) {
                                val errSnippet = (output.stderr.ifBlank { output.stdout })
                                    .lines()
                                    .takeLast(5)
                                    .joinToString("<br/>")
                                notify(
                                    project,
                                    "Pubspec Pilot: Error en flutter pub get",
                                    "No se pudo completar <code>flutter pub get</code> para <b>$reasonLabel</b>:<br/>$errSnippet",
                                    NotificationType.WARNING
                                )
                            }
                        } catch (e: Exception) {
                            if (!indicator.isCanceled) {
                                notify(
                                    project,
                                    "Pubspec Pilot: No se encontró Flutter SDK",
                                    "Actualizado <b>$reasonLabel</b> en <code>pubspec.yaml</code>, pero no se pudo ejecutar <code>flutter pub get</code> automáticamente (${e.message}).",
                                    NotificationType.WARNING
                                )
                            }
                        } finally {
                            if (handler != null) {
                                activeProcesses.remove(workDirPath, handler)
                            }
                        }
                    }
                }
            )
        }
    }

    /**
     * Refreshes only `pubspec.yaml`, `pubspec.lock`, and `.dart_tool/package_config.json`
     * instead of recursively walking `build/`, `ios/Pods/`, `android/`, etc.
     */
    private fun refreshPubFilesOnly(workDirPath: String, pubspecVirtualFile: VirtualFile?) {
        val lfs = LocalFileSystem.getInstance()
        val filesToRefresh = mutableListOf<VirtualFile>()
        pubspecVirtualFile?.let { filesToRefresh.add(it) }

        val targetedPaths = listOf(
            File(workDirPath, "pubspec.lock"),
            File(workDirPath, ".dart_tool/package_config.json"),
            File(workDirPath, ".flutter-plugins"),
            File(workDirPath, ".flutter-plugins-dependencies")
        )

        for (ioFile in targetedPaths) {
            if (ioFile.exists()) {
                lfs.refreshAndFindFileByIoFile(ioFile)?.let { filesToRefresh.add(it) }
            }
        }

        if (filesToRefresh.isNotEmpty()) {
            VfsUtil.markDirtyAndRefresh(true, false, false, *filesToRefresh.toTypedArray())
        }
    }

    private fun resolveFlutterExecutable(projectDirPath: String): String {
        flutterExeCache[projectDirPath]?.let { cached ->
            if (cached == "flutter" || cached == "flutter.bat" || File(cached).isFile) {
                return cached
            }
        }

        val resolved = findFlutterExecutableOnDisk(projectDirPath)
        flutterExeCache[projectDirPath] = resolved
        return resolved
    }

    private fun findFlutterExecutableOnDisk(projectDirPath: String): String {
        val exeName = if (SystemInfo.isWindows) "flutter.bat" else "flutter"
        val projectDir = File(projectDirPath)

        // 1. Check android/local.properties for flutter.sdk=... (standard in Android Studio Flutter projects)
        val localPropsCandidates = listOf(
            File(projectDir, "android/local.properties"),
            File(projectDir, "local.properties")
        )
        for (propFile in localPropsCandidates) {
            if (propFile.isFile) {
                try {
                    val props = Properties()
                    propFile.inputStream().use { props.load(it) }
                    val sdkPath = props.getProperty("flutter.sdk")
                    if (!sdkPath.isNullOrBlank()) {
                        val candidate = File(sdkPath, "bin/$exeName")
                        if (candidate.isFile) return candidate.absolutePath
                    }
                } catch (_: Exception) {
                }
            }
        }

        // 2. Check FVM (.fvm/flutter_sdk) if project uses Flutter Version Management
        val fvmCandidate = File(projectDir, ".fvm/flutter_sdk/bin/$exeName")
        if (fvmCandidate.isFile) return fvmCandidate.absolutePath

        // 3. Check FLUTTER_ROOT environment variable
        val flutterRoot = System.getenv("FLUTTER_ROOT")
        if (!flutterRoot.isNullOrBlank()) {
            val candidate = File(flutterRoot, "bin/$exeName")
            if (candidate.isFile) return candidate.absolutePath
        }

        // 4. Check common installation locations on Windows / macOS / Linux
        val userHome = System.getProperty("user.home") ?: ""
        val commonLocations = listOf(
            File("C:/src/flutter/bin/$exeName"),
            File("C:/flutter/bin/$exeName"),
            File(userHome, "fvm/default/bin/$exeName"),
            File(userHome, "flutter/bin/$exeName"),
            File(userHome, "development/flutter/bin/$exeName"),
            File(userHome, "dev/flutter/bin/$exeName"),
            File(userHome, "AppData/Local/flutter/bin/$exeName"),
            File("/opt/homebrew/bin/$exeName"),
            File("/usr/local/bin/$exeName"),
            File("/snap/bin/$exeName")
        )
        for (candidate in commonLocations) {
            if (candidate.isFile) return candidate.absolutePath
        }

        // 5. Fallback to PATH
        return exeName
    }

    private fun notify(project: Project, title: String, content: String, type: NotificationType) {
        if (project.isDisposed) return
        NotificationGroupManager.getInstance()
            .getNotificationGroup("PubspecPilot.Notifications")
            .createNotification(title, content, type)
            .notify(project)
    }
}
