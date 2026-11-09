package com.nyk.pubspecpilot.completion

import com.intellij.codeInsight.completion.*
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.util.TextRange
import com.intellij.patterns.PlatformPatterns
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.util.ProcessingContext
import com.nyk.pubspecpilot.api.PubDevApiService
import com.nyk.pubspecpilot.runner.FlutterPubGetRunner
import com.nyk.pubspecpilot.util.PubspecYamlUtils
import org.jetbrains.yaml.psi.YAMLDocument
import org.jetbrains.yaml.psi.YAMLKeyValue
import org.jetbrains.yaml.psi.YAMLMapping

class PubspecPackageCompletionContributor : CompletionContributor() {

    init {
        extend(
            CompletionType.BASIC,
            PlatformPatterns.psiElement(),
            PubspecCompletionProvider()
        )
    }
}

private class PubspecCompletionProvider : CompletionProvider<CompletionParameters>() {

    override fun addCompletions(
        parameters: CompletionParameters,
        context: ProcessingContext,
        result: CompletionResultSet
    ) {
        val originalFile = parameters.originalFile
        // Strictly activate only on pubspec.yaml — zero impact on any other file in the IDE
        if (!PubspecYamlUtils.isPubspecFile(originalFile)) return

        val position = parameters.position
        val document = parameters.editor.document
        val offset = parameters.offset
        val lineNum = document.getLineNumber(offset)
        val lineStart = document.getLineStartOffset(lineNum)
        val lineEnd = document.getLineEndOffset(lineNum)
        val textBeforeCaretOnLine = document.getText(TextRange(lineStart, offset))
        val fullLineText = document.getText(TextRange(lineStart, lineEnd))

        if (!isInsideDependencySection(document, lineNum, position, fullLineText)) {
            return
        }

        val colonIndexInBefore = textBeforeCaretOnLine.indexOf(':')
        if (colonIndexInBefore >= 0) {
            // Caret is AFTER the colon -> Completing version for an existing package name
            val pkgName = textBeforeCaretOnLine.substring(0, colonIndexInBefore).trim()
            if (pkgName.isNotEmpty() && pkgName !in PubspecYamlUtils.IGNORED_SDK_PACKAGES) {
                val versionPrefix = textBeforeCaretOnLine.substring(colonIndexInBefore + 1).trim()
                completePackageVersions(pkgName, versionPrefix, result)
            }
        } else {
            // Caret is BEFORE any colon -> Completing package name from pub.dev
            val rawPrefix = textBeforeCaretOnLine.trim()
            completePackageNames(rawPrefix, result)
        }
    }

    private fun completePackageNames(
        prefix: String,
        result: CompletionResultSet
    ) {
        val apiService = PubDevApiService.getInstance()
        val customResult = result.withPrefixMatcher(PlainPrefixMatcher(prefix))
        customResult.restartCompletionOnAnyPrefixChange()

        val emittedPackages = HashSet<String>(32)

        // STAGE 1: Emit instant local/cached matches in 0 ms so popup opens immediately without network wait
        val instantMatches = apiService.getInstantLocalMatches(prefix)
        apiService.prefetchPackagesAsync(instantMatches.take(6))

        for ((index, pkgName) in instantMatches.take(15).withIndex()) {
            ProgressManager.checkCanceled()
            emittedPackages.add(pkgName)
            val cachedInfo = apiService.getCachedPackageInfo(pkgName)
            customResult.addElement(
                buildPackageLookupElement(
                    pkgName = pkgName,
                    latestVersion = cachedInfo?.latestStableVersion,
                    isTopPriority = index < 5,
                    priority = (2000 - index).toDouble()
                )
            )
        }

        // STAGE 2: Query pub.dev search and enrich top uncached results in parallel
        ProgressManager.checkCanceled()
        val remotePackages = apiService.searchPackages(prefix, timeoutMs = 2000)
            .filter { it !in emittedPackages }
            .take(20)

        if (remotePackages.isEmpty()) return

        // Parallel fetch for top 5 new packages with a 900ms ceiling so UI stays snappy
        val topToFetch = remotePackages.take(5)
        val fetchedMap = apiService.getPackagesInfoParallel(
            packageNames = topToFetch,
            perRequestTimeoutMs = 1200,
            overallDeadlineMs = 900L
        )

        for ((index, pkgName) in remotePackages.withIndex()) {
            ProgressManager.checkCanceled()
            val info = fetchedMap[pkgName] ?: apiService.getCachedPackageInfo(pkgName)
            customResult.addElement(
                buildPackageLookupElement(
                    pkgName = pkgName,
                    latestVersion = info?.latestStableVersion,
                    isTopPriority = index < 3 && emittedPackages.isEmpty(),
                    priority = (1000 - index).toDouble()
                )
            )
        }
    }

    private fun buildPackageLookupElement(
        pkgName: String,
        latestVersion: String?,
        isTopPriority: Boolean,
        priority: Double
    ): com.intellij.codeInsight.lookup.LookupElement {
        val localPkg = com.nyk.pubspecpilot.cache.LocalPubCacheService.getInstance().getLocalPackage(pkgName)
        val isOfflineOnly = latestVersion == null && localPkg != null
        val effectiveVersion = latestVersion ?: localPkg?.latestVersion

        val typeText = when {
            effectiveVersion != null && isOfflineOnly -> "^$effectiveVersion (local)"
            effectiveVersion != null -> "^$effectiveVersion"
            else -> "pub.dev"
        }

        val tailText = when {
            effectiveVersion != null && isOfflineOnly -> ": ^$effectiveVersion  (disponible en pub cache local offline)"
            effectiveVersion != null -> ": ^$effectiveVersion  (descargar con flutter pub get)"
            else -> "  (obtener última versión de pub.dev)"
        }

        val element = LookupElementBuilder.create(pkgName)
            .withIcon(AllIcons.Nodes.PpLib)
            .withBoldness(isTopPriority)
            .withTailText(tailText, true)
            .withTypeText(typeText, true)
            .withInsertHandler { insertionContext, _ ->
                // Check cache again at insertion time in case background prefetch completed while menu was open
                val freshVersion = effectiveVersion
                    ?: PubDevApiService.getInstance().getCachedPackageInfo(pkgName)?.latestStableVersion
                    ?: com.nyk.pubspecpilot.cache.LocalPubCacheService.getInstance().getLocalPackage(pkgName)?.latestVersion
                handlePackageNameInsertion(
                    insertionContext = insertionContext,
                    pkgName = pkgName,
                    knownVersion = freshVersion
                )
            }

        return PrioritizedLookupElement.withPriority(element, priority)
    }

    private fun handlePackageNameInsertion(
        insertionContext: InsertionContext,
        pkgName: String,
        knownVersion: String?
    ) {
        val project = insertionContext.project
        val editor = insertionContext.editor
        val document = editor.document
        val virtualFile = insertionContext.file.virtualFile

        val lineNum = document.getLineNumber(insertionContext.startOffset)
        val lineStart = document.getLineStartOffset(lineNum)
        val lineEnd = document.getLineEndOffset(lineNum)
        val currentLine = document.getText(TextRange(lineStart, lineEnd))
        val indent = currentLine.takeWhile { it == ' ' || it == '\t' }.ifEmpty { "  " }
        val trailingComment = extractTrailingComment(currentLine)

        if (knownVersion != null) {
            val replacement = "$indent$pkgName: ^$knownVersion$trailingComment"
            document.replaceString(lineStart, lineEnd, replacement)
            editor.caretModel.moveToOffset(lineStart + replacement.length - trailingComment.length)
            PsiDocumentManager.getInstance(project).commitDocument(document)

            FlutterPubGetRunner.runPubGet(project, virtualFile, "$pkgName ^$knownVersion")
        } else {
            // Temporarily insert package name and resolve latest version asynchronously so UI never freezes
            val tempReplacement = "$indent$pkgName: $trailingComment"
            document.replaceString(lineStart, lineEnd, tempReplacement)
            editor.caretModel.moveToOffset(lineStart + indent.length + pkgName.length + 2)
            PsiDocumentManager.getInstance(project).commitDocument(document)

            ProgressManager.getInstance().run(
                object : Task.Backgroundable(
                    project,
                    "Pubspec Pilot: Obteniendo última versión de '$pkgName' en pub.dev...",
                    false
                ) {
                    override fun run(indicator: ProgressIndicator) {
                        val fetched = PubDevApiService.getInstance().getPackageInfo(pkgName, timeoutMs = 3000)
                        val versionToInsert = fetched?.latestStableVersion ?: "any"
                        val constraint = if (versionToInsert == "any") "any" else "^$versionToInsert"

                        ApplicationManager.getApplication().invokeLater {
                            WriteCommandAction.runWriteCommandAction(
                                project,
                                "Insertar versión $constraint para $pkgName",
                                null,
                                {
                                    val targetLine = findLineForPackage(document, pkgName)
                                    if (targetLine >= 0) {
                                        val lStart = document.getLineStartOffset(targetLine)
                                        val lEnd = document.getLineEndOffset(targetLine)
                                        val lineStr = document.getText(TextRange(lStart, lEnd))
                                        val lineIndent = lineStr.takeWhile { it == ' ' || it == '\t' }.ifEmpty { "  " }
                                        val comment = extractTrailingComment(lineStr)
                                        val finalLine = "$lineIndent$pkgName: $constraint$comment"
                                        document.replaceString(lStart, lEnd, finalLine)
                                        editor.caretModel.moveToOffset(lStart + finalLine.length - comment.length)
                                        PsiDocumentManager.getInstance(project).commitDocument(document)
                                    }
                                }
                            )
                            FlutterPubGetRunner.runPubGet(project, virtualFile, "$pkgName $constraint")
                        }
                    }
                }
            )
        }
    }

    private fun completePackageVersions(
        pkgName: String,
        versionPrefix: String,
        result: CompletionResultSet
    ) {
        ProgressManager.checkCanceled()
        val apiService = PubDevApiService.getInstance()
        val localPkg = com.nyk.pubspecpilot.cache.LocalPubCacheService.getInstance().getLocalPackage(pkgName)
        val info = apiService.getCachedPackageInfo(pkgName)
            ?: apiService.getPackageInfo(pkgName, timeoutMs = 1800)

        val latestVersion = info?.latestStableVersion ?: localPkg?.latestVersion ?: return
        val allRecentVersions = (info?.recentVersions ?: localPkg?.installedVersions ?: listOf(latestVersion)).distinct()

        val customResult = result.withPrefixMatcher(PlainPrefixMatcher(versionPrefix))
        val latestConstraint = "^$latestVersion"
        val isOfflineOnly = (info == null)

        // 1. Recommended caret version (^X.Y.Z)
        val tailDesc = if (isOfflineOnly) "  (instalado localmente en pub cache)" else "  (última versión estable en pub.dev)"
        val recommendedElement = LookupElementBuilder.create(latestConstraint)
            .withIcon(AllIcons.Nodes.PpLib)
            .withBoldness(true)
            .withTailText(tailDesc, true)
            .withTypeText(if (isOfflineOnly) "Local" else "Recomendado", true)
            .withInsertHandler { insertionContext, _ ->
                replaceVersionOnLineAndRunPubGet(insertionContext, pkgName, latestConstraint)
            }

        customResult.addElement(PrioritizedLookupElement.withPriority(recommendedElement, 2000.0))

        // 2. Other recent versions
        for ((idx, ver) in allRecentVersions.withIndex()) {
            val caretVer = "^$ver"
            if (caretVer == latestConstraint) continue

            val verElement = LookupElementBuilder.create(caretVer)
                .withIcon(AllIcons.Nodes.PpLib)
                .withTypeText(if (isOfflineOnly) "pub cache" else "pub.dev", true)
                .withInsertHandler { insertionContext, _ ->
                    replaceVersionOnLineAndRunPubGet(insertionContext, pkgName, caretVer)
                }

            customResult.addElement(
                PrioritizedLookupElement.withPriority(verElement, (1000 - idx).toDouble())
            )
        }
    }

    private fun replaceVersionOnLineAndRunPubGet(
        insertionContext: InsertionContext,
        pkgName: String,
        constraint: String
    ) {
        val project = insertionContext.project
        val editor = insertionContext.editor
        val document = editor.document
        val virtualFile = insertionContext.file.virtualFile

        val lineNum = document.getLineNumber(insertionContext.startOffset)
        val lineStart = document.getLineStartOffset(lineNum)
        val lineEnd = document.getLineEndOffset(lineNum)
        val currentLine = document.getText(TextRange(lineStart, lineEnd))
        val indent = currentLine.takeWhile { it == ' ' || it == '\t' }.ifEmpty { "  " }
        val comment = extractTrailingComment(currentLine)

        val replacement = "$indent$pkgName: $constraint$comment"
        document.replaceString(lineStart, lineEnd, replacement)
        editor.caretModel.moveToOffset(lineStart + replacement.length - comment.length)
        PsiDocumentManager.getInstance(project).commitDocument(document)

        FlutterPubGetRunner.runPubGet(project, virtualFile, "$pkgName $constraint")
    }

    private fun extractTrailingComment(line: String): String {
        val hashIndex = line.indexOf('#')
        if (hashIndex < 0) return ""
        return " " + line.substring(hashIndex).trim()
    }

    private fun findLineForPackage(document: Document, pkgName: String): Int {
        for (line in 0 until document.lineCount) {
            val start = document.getLineStartOffset(line)
            val end = document.getLineEndOffset(line)
            val text = document.getText(TextRange(start, end)).trimStart()
            if (text.startsWith(pkgName)) {
                val afterPkg = text.substring(pkgName.length).trimStart()
                if (afterPkg.startsWith(":")) {
                    return line
                }
            }
        }
        return -1
    }

    /**
     * Checks whether the current line is a direct child of dependencies:, dev_dependencies:, or dependency_overrides:
     * avoiding activation inside nested sub-mappings (e.g. sdk: flutter, git: url: ...).
     */
    private fun isInsideDependencySection(
        document: Document,
        currentLine: Int,
        position: PsiElement,
        fullLineText: String
    ): Boolean {
        val currentIndent = fullLineText.takeWhile { it == ' ' || it == '\t' }.length
        // Direct dependency entries typically have indent 2..4 spaces; skip deeper sub-mappings
        if (currentIndent > 4) return false

        // Check PSI hierarchy for direct child of dependency section
        var currentPsi: PsiElement? = position
        while (currentPsi != null) {
            if (currentPsi is YAMLKeyValue) {
                val parentMapping = currentPsi.parent as? YAMLMapping
                val parentKV = parentMapping?.parent as? YAMLKeyValue
                if (parentKV != null && parentKV.keyText in PubspecYamlUtils.DEPENDENCY_SECTIONS) {
                    val topMapping = parentKV.parent as? YAMLMapping
                    if (topMapping?.parent is YAMLDocument) {
                        return true
                    }
                }
            }
            currentPsi = currentPsi.parent
        }

        // Fallback when typing a new incomplete line: scan upwards for the nearest top-level YAML key (indent == 0)
        for (line in currentLine downTo 0) {
            val start = document.getLineStartOffset(line)
            val end = document.getLineEndOffset(line)
            val lineText = document.getText(TextRange(start, end))
            val trimmed = lineText.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue

            val indent = lineText.takeWhile { it == ' ' || it == '\t' }.length
            if (indent == 0 && trimmed.endsWith(":")) {
                val sectionName = trimmed.removeSuffix(":").trim()
                return sectionName in PubspecYamlUtils.DEPENDENCY_SECTIONS
            }
        }
        return false
    }
}
