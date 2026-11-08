package com.nyk.pubspecpilot.inlay

import com.intellij.codeInsight.hints.*
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.SmartPointerManager
import com.nyk.pubspecpilot.api.PubDevApiService
import com.nyk.pubspecpilot.runner.FlutterPubGetRunner
import com.nyk.pubspecpilot.util.PubspecYamlUtils
import org.jetbrains.yaml.YAMLElementGenerator
import org.jetbrains.yaml.psi.YAMLKeyValue
import org.jetbrains.yaml.psi.YAMLScalar
import javax.swing.JComponent
import javax.swing.JPanel

@Suppress("UnstableApiUsage")
class PubspecVersionInlayHintsProvider : InlayHintsProvider<NoSettings> {

    override val key: SettingsKey<NoSettings> = SettingsKey("com.nyk.pubspecpilot.inlay.hints")

    override val name: String = "Pubspec Pilot: Indicador de nueva versión disponible"

    override val previewText: String = """
        dependencies:
          http: ^0.13.5
          provider: ^6.0.0
    """.trimIndent()

    override fun createSettings(): NoSettings = NoSettings()

    override fun createConfigurable(settings: NoSettings): ImmediateConfigurable {
        return object : ImmediateConfigurable {
            override fun createComponent(listener: ChangeListener): JComponent = JPanel()
        }
    }

    override fun getCollectorFor(
        file: PsiFile,
        editor: Editor,
        settings: NoSettings,
        sink: InlayHintsSink
    ): InlayHintsCollector? {
        if (!PubspecYamlUtils.isPubspecFile(file)) return null

        return object : FactoryInlayHintsCollector(editor) {
            private val apiService = PubDevApiService.getInstance()

            override fun collect(element: PsiElement, editor: Editor, sink: InlayHintsSink): Boolean {
                val kv = element as? YAMLKeyValue ?: return true
                if (!PubspecYamlUtils.isDirectDependencyEntry(kv)) return true

                val pkgName = kv.keyText.trim()
                if (pkgName.isEmpty() || pkgName in PubspecYamlUtils.IGNORED_SDK_PACKAGES) return true

                val scalar = kv.value as? YAMLScalar ?: return true
                val rawConstraint = scalar.textValue.trim()
                val currentVersion = PubspecYamlUtils.extractBaseSemVer(rawConstraint) ?: return true

                // Read strictly from memory cache so InlayHints never block the editor read action
                val cachedInfo = apiService.getCachedPackageInfo(pkgName) ?: return true
                val latestVersion = if (currentVersion.contains("-")) {
                    cachedInfo.latestVersion
                } else {
                    cachedInfo.latestStableVersion
                }

                if (!PubspecYamlUtils.isNewerVersion(currentVersion, latestVersion)) {
                    return true
                }

                val recommendedConstraint = "^$latestVersion"
                val lineEndOffset = scalar.textRange.endOffset
                val pointer = SmartPointerManager.getInstance(file.project).createSmartPsiElementPointer(kv)

                val textPresentation = factory.smallText("  ⬆ $recommendedConstraint disponible (clic para actualizar)")
                val withClick = factory.referenceOnHover(textPresentation) { _, _ ->
                    val targetKv = pointer.element ?: return@referenceOnHover
                    val project = targetKv.project
                    val virtualFile = file.virtualFile

                    WriteCommandAction.runWriteCommandAction(
                        project,
                        "Actualizar $pkgName a $recommendedConstraint",
                        null,
                        {
                            val generator = YAMLElementGenerator.getInstance(project)
                            val dummyKv = generator.createYamlKeyValue(pkgName, recommendedConstraint)
                            dummyKv.value?.let { targetKv.setValue(it) }
                        }
                    )

                    FlutterPubGetRunner.runPubGet(project, virtualFile, "$pkgName $recommendedConstraint")
                }

                sink.addInlineElement(
                    offset = lineEndOffset,
                    relatesToPrecedingText = true,
                    presentation = withClick,
                    placeAtTheEndOfLine = true
                )

                return true
            }
        }
    }
}
