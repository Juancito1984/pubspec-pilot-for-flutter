package com.nyk.pubspecpilot.annotator

import com.intellij.codeInsight.daemon.impl.InlayHintsPassFactoryInternal
import com.intellij.codeInsight.intention.impl.BaseIntentionAction
import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.ExternalAnnotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiFile
import com.intellij.psi.SmartPointerManager
import com.intellij.psi.SmartPsiElementPointer
import com.nyk.pubspecpilot.api.PubDevApiService
import com.nyk.pubspecpilot.runner.FlutterPubGetRunner
import com.nyk.pubspecpilot.util.PubspecYamlUtils
import org.jetbrains.yaml.YAMLElementGenerator
import org.jetbrains.yaml.psi.YAMLFile
import org.jetbrains.yaml.psi.YAMLKeyValue

data class RawDependencyItem(
    val packageName: String,
    val rawConstraint: String,
    val cleanCurrentVersion: String,
    val textRange: TextRange,
    val pointer: SmartPsiElementPointer<YAMLKeyValue>
)

data class PubspecCollectedInfo(
    val file: PsiFile,
    val items: List<RawDependencyItem>
)

data class OutdatedDependencyResult(
    val item: RawDependencyItem,
    val latestVersion: String,
    val recommendedConstraint: String
)

class PubspecOutdatedAnnotator :
    ExternalAnnotator<PubspecCollectedInfo, List<OutdatedDependencyResult>>() {

    override fun collectInformation(file: PsiFile, editor: Editor, hasErrors: Boolean): PubspecCollectedInfo? {
        if (!PubspecYamlUtils.isPubspecFile(file)) return null
        val yamlFile = file as? YAMLFile ?: return null

        val pointerManager = SmartPointerManager.getInstance(file.project)
        val dependencies = PubspecYamlUtils.collectDependencies(yamlFile)
        if (dependencies.isEmpty()) return null

        val items = dependencies.map { dep ->
            val range = dep.keyValueElement.value?.textRange ?: dep.keyValueElement.textRange
            RawDependencyItem(
                packageName = dep.packageName,
                rawConstraint = dep.rawConstraint,
                cleanCurrentVersion = dep.cleanCurrentVersion,
                textRange = range,
                pointer = pointerManager.createSmartPsiElementPointer(dep.keyValueElement)
            )
        }
        return PubspecCollectedInfo(file, items)
    }

    override fun doAnnotate(collectedInfo: PubspecCollectedInfo?): List<OutdatedDependencyResult> {
        if (collectedInfo == null || collectedInfo.items.isEmpty()) return emptyList()
        val apiService = PubDevApiService.getInstance()

        // Fetch all package versions concurrently in parallel instead of sequentially
        val packageNames = collectedInfo.items.map { it.packageName }
        val infoMap = apiService.getPackagesInfoParallel(
            packageNames = packageNames,
            perRequestTimeoutMs = 2500,
            overallDeadlineMs = 3500L
        )

        val results = ArrayList<OutdatedDependencyResult>(collectedInfo.items.size)

        for (item in collectedInfo.items) {
            ProgressManager.checkCanceled()
            val info = infoMap[item.packageName.lowercase()] ?: continue
            val targetLatest = if ('-' in item.cleanCurrentVersion) {
                info.latestVersion
            } else {
                info.latestStableVersion
            }

            if (PubspecYamlUtils.isNewerVersion(item.cleanCurrentVersion, targetLatest)) {
                val recommended = PubspecYamlUtils.buildRecommendedConstraint(item.rawConstraint, targetLatest)
                results.add(
                    OutdatedDependencyResult(
                        item = item,
                        latestVersion = targetLatest,
                        recommendedConstraint = recommended
                    )
                )
            }
        }
        return results
    }

    override fun apply(
        file: PsiFile,
        annotationResult: List<OutdatedDependencyResult>?,
        holder: AnnotationHolder
    ) {
        if (annotationResult.isNullOrEmpty()) return

        for (outdated in annotationResult) {
            val element = outdated.item.pointer.element ?: continue
            val range = element.value?.textRange ?: element.textRange

            val message =
                "Paquete '${outdated.item.packageName}' desactualizado (${outdated.item.cleanCurrentVersion} → ${outdated.recommendedConstraint} disponible en pub.dev)"

            holder.newAnnotation(HighlightSeverity.WARNING, message)
                .range(range)
                .withFix(
                    UpdateSinglePackageQuickFix(
                        packageName = outdated.item.packageName,
                        newConstraint = outdated.recommendedConstraint,
                        pointer = outdated.item.pointer
                    )
                )
                .withFix(
                    UpdateAllPackagesQuickFix(annotationResult)
                )
                .create()
        }

        // Refresh Inlay Hints so end-of-line version hints appear immediately once versions are cached
        try {
            InlayHintsPassFactoryInternal.forceHintsUpdateOnNextPass()
        } catch (_: Throwable) {
        }
    }
}

class UpdateSinglePackageQuickFix(
    private val packageName: String,
    private val newConstraint: String,
    private val pointer: SmartPsiElementPointer<YAMLKeyValue>
) : BaseIntentionAction() {

    override fun getText(): String =
        "Pubspec Pilot: Actualizar '$packageName' a $newConstraint (y ejecutar flutter pub get)"

    override fun getFamilyName(): String = "Pubspec Pilot: Actualizar paquete"

    override fun isAvailable(project: Project, editor: Editor?, file: PsiFile?): Boolean {
        return pointer.element != null
    }

    override fun invoke(project: Project, editor: Editor?, file: PsiFile?) {
        val kv = pointer.element ?: return
        val virtualFile = file?.virtualFile ?: kv.containingFile?.virtualFile

        WriteCommandAction.runWriteCommandAction(project, "Actualizar $packageName a $newConstraint", null, {
            val generator = YAMLElementGenerator.getInstance(project)
            val dummyKv = generator.createYamlKeyValue(packageName, newConstraint)
            val newValue = dummyKv.value
            if (newValue != null) {
                kv.setValue(newValue)
            }
        })

        FlutterPubGetRunner.runPubGet(project, virtualFile, "$packageName $newConstraint")
    }
}

class UpdateAllPackagesQuickFix(
    private val allOutdated: List<OutdatedDependencyResult>
) : BaseIntentionAction() {

    override fun getText(): String =
        "Pubspec Pilot: Actualizar todos los paquetes desactualizados (${allOutdated.size}) y ejecutar flutter pub get"

    override fun getFamilyName(): String = "Pubspec Pilot: Actualizar todos los paquetes"

    override fun isAvailable(project: Project, editor: Editor?, file: PsiFile?): Boolean {
        return allOutdated.size > 1
    }

    override fun invoke(project: Project, editor: Editor?, file: PsiFile?) {
        val virtualFile = file?.virtualFile
        WriteCommandAction.runWriteCommandAction(project, "Actualizar todos los paquetes en pubspec.yaml", null, {
            val generator = YAMLElementGenerator.getInstance(project)
            for (outdated in allOutdated) {
                val kv = outdated.item.pointer.element ?: continue
                val dummyKv = generator.createYamlKeyValue(outdated.item.packageName, outdated.recommendedConstraint)
                val newValue = dummyKv.value
                if (newValue != null) {
                    kv.setValue(newValue)
                }
            }
        })

        FlutterPubGetRunner.runPubGet(project, virtualFile, "${allOutdated.size} paquetes actualizados")
    }
}
