package com.nyk.pubspecpilot.util

import com.intellij.psi.PsiFile
import org.jetbrains.yaml.psi.YAMLDocument
import org.jetbrains.yaml.psi.YAMLFile
import org.jetbrains.yaml.psi.YAMLKeyValue
import org.jetbrains.yaml.psi.YAMLMapping
import org.jetbrains.yaml.psi.YAMLScalar

data class DependencyEntry(
    val packageName: String,
    val rawConstraint: String,
    val cleanCurrentVersion: String,
    val keyValueElement: YAMLKeyValue
)

object PubspecYamlUtils {

    val DEPENDENCY_SECTIONS = setOf(
        "dependencies",
        "dev_dependencies",
        "dependency_overrides"
    )

    val IGNORED_SDK_PACKAGES = setOf(
        "flutter",
        "flutter_test",
        "flutter_localizations",
        "flutter_web_plugins",
        "flutter_driver",
        "sky_engine"
    )

    // Pre-compiled regexes to avoid allocation on hot PSI / Inlay / Annotator paths
    val PACKAGE_NAME_REGEX = Regex("^[a-z0-9_]+$")
    private val SEMVER_REGEX = Regex("""(\d+\.\d+\.\d+(?:-[0-9A-Za-z.-]+)?(?:\+[0-9A-Za-z.-]+)?)""")

    fun isPubspecFile(file: PsiFile?): Boolean {
        return file is YAMLFile && file.name.equals("pubspec.yaml", ignoreCase = true)
    }

    /**
     * Checks if the given YAMLKeyValue is a direct child of dependencies, dev_dependencies, or dependency_overrides.
     */
    fun isDirectDependencyEntry(keyValue: YAMLKeyValue): Boolean {
        val parentMapping = keyValue.parent as? YAMLMapping ?: return false
        val sectionKeyValue = parentMapping.parent as? YAMLKeyValue ?: return false
        val topMapping = sectionKeyValue.parent as? YAMLMapping ?: return false
        if (topMapping.parent !is YAMLDocument) return false

        return sectionKeyValue.keyText in DEPENDENCY_SECTIONS
    }

    /**
     * Collects all simple versioned dependencies from a pubspec.yaml YAMLFile.
     * Skips sdk, git, path, and hosted sub-mappings.
     */
    fun collectDependencies(yamlFile: YAMLFile): List<DependencyEntry> {
        val documents = yamlFile.documents
        if (documents.isEmpty()) return emptyList()

        val topMapping = documents.first().topLevelValue as? YAMLMapping ?: return emptyList()
        val result = ArrayList<DependencyEntry>(16)

        for (sectionName in DEPENDENCY_SECTIONS) {
            val sectionKV = topMapping.getKeyValueByKey(sectionName) ?: continue
            val sectionMapping = sectionKV.value as? YAMLMapping ?: continue

            for (depKV in sectionMapping.keyValues) {
                val pkgName = depKV.keyText.trim()
                if (pkgName.isEmpty() || pkgName in IGNORED_SDK_PACKAGES) continue

                // Only inspect scalar version constraints (e.g. ^1.2.3, 1.2.3, >=1.0.0 <2.0.0)
                val scalarValue = depKV.value as? YAMLScalar ?: continue
                val rawConstraint = scalarValue.textValue.trim()
                if (rawConstraint.isEmpty() || rawConstraint.equals("any", ignoreCase = true)) continue

                val cleanVersion = extractBaseSemVer(rawConstraint) ?: continue
                result.add(
                    DependencyEntry(
                        packageName = pkgName,
                        rawConstraint = rawConstraint,
                        cleanCurrentVersion = cleanVersion,
                        keyValueElement = depKV
                    )
                )
            }
        }
        return result
    }

    /**
     * Extracts the base X.Y.Z(-pre) version from a constraint like "^1.2.3" or ">=1.2.3 <2.0.0".
     */
    fun extractBaseSemVer(constraint: String): String? {
        return SEMVER_REGEX.find(constraint)?.groupValues?.get(1)
    }

    /**
     * Builds the recommended version constraint preserving the user's style (caret vs exact pin).
     */
    fun buildRecommendedConstraint(rawConstraint: String, latestVersion: String): String {
        val trimmed = rawConstraint.trim()
        return if (trimmed.isNotEmpty() && trimmed[0].isDigit()) {
            // If user wrote an exact version pin (e.g. "1.2.3"), still suggest "^latest" or keep caret standard
            "^$latestVersion"
        } else {
            "^$latestVersion"
        }
    }

    /**
     * Returns true if latestVersion is strictly newer than currentVersion according to SemVer.
     */
    fun isNewerVersion(currentVersion: String, latestVersion: String): Boolean {
        if (currentVersion == latestVersion) return false
        return compareSemVer(latestVersion, currentVersion) > 0
    }

    /**
     * Fast, low-allocation comparison of two semantic version strings (e.g. "1.10.0" vs "1.2.5", "2.0.0-beta.10" vs "2.0.0-beta.2").
     */
    fun compareSemVer(v1: String, v2: String): Int {
        if (v1 == v2) return 0

        val clean1 = v1.substringBefore('+')
        val clean2 = v2.substringBefore('+')

        val main1 = clean1.substringBefore('-')
        val main2 = clean2.substringBefore('-')

        val parts1 = main1.split('.')
        val parts2 = main2.split('.')

        val maxLen = maxOf(parts1.size, parts2.size)
        for (i in 0 until maxLen) {
            val p1 = parts1.getOrNull(i)?.toIntOrNull() ?: 0
            val p2 = parts2.getOrNull(i)?.toIntOrNull() ?: 0
            if (p1 != p2) {
                return p1.compareTo(p2)
            }
        }

        // Pre-release check: 1.0.0 > 1.0.0-beta.1
        val hasPre1 = '-' in clean1
        val hasPre2 = '-' in clean2
        if (!hasPre1 && !hasPre2) return 0
        if (!hasPre1 && hasPre2) return 1
        if (hasPre1 && !hasPre2) return -1

        val preParts1 = clean1.substringAfter('-').split('.')
        val preParts2 = clean2.substringAfter('-').split('.')
        val preMaxLen = maxOf(preParts1.size, preParts2.size)
        for (i in 0 until preMaxLen) {
            val s1 = preParts1.getOrNull(i) ?: return -1
            val s2 = preParts2.getOrNull(i) ?: return 1
            if (s1 == s2) continue

            val n1 = s1.toIntOrNull()
            val n2 = s2.toIntOrNull()
            val cmp = when {
                n1 != null && n2 != null -> n1.compareTo(n2)
                n1 != null -> -1 // Numeric identifiers have lower precedence than non-numeric per SemVer 2.0
                n2 != null -> 1
                else -> s1.compareTo(s2)
            }
            if (cmp != 0) return cmp
        }
        return 0
    }
}
