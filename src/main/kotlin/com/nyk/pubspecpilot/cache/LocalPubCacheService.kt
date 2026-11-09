package com.nyk.pubspecpilot.cache

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.util.SystemInfo
import com.nyk.pubspecpilot.util.PubspecYamlUtils
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

data class LocalCachedPackage(
    val name: String,
    val latestVersion: String,
    val installedVersions: List<String>,
)

/**
 * Service that detects, scans, and indexes locally downloaded Flutter/Dart packages
 * from the system Pub Cache across Windows, macOS, and Linux.
 *
 * Supports zero-latency completions when offline and instant local backups when online.
 */
@Service(Service.Level.APP)
class LocalPubCacheService {

    private val log = Logger.getInstance(LocalPubCacheService::class.java)

    // Map: packageName -> LocalCachedPackage
    private val localPackagesCache = ConcurrentHashMap<String, LocalCachedPackage>()
    private val lastScanTime = AtomicLong(0L)

    companion object {
        private val SCAN_TTL_MS = TimeUnit.MINUTES.toMillis(5)

        fun getInstance(): LocalPubCacheService =
            ApplicationManager.getApplication().getService(LocalPubCacheService::class.java)

        /**
         * Resolves the system Pub Cache directory across OS environments:
         * 1. Environment variable PUB_CACHE (if defined by user).
         * 2. Windows: %LOCALAPPDATA%\Pub\Cache
         * 3. macOS / Linux: $HOME/.pub-cache
         */
        fun resolvePubCacheDirectory(): File? {
            val envPubCache = System.getenv("PUB_CACHE")
            if (!envPubCache.isNullOrBlank()) {
                val dir = File(envPubCache)
                if (dir.isDirectory) return dir
            }

            val home = System.getProperty("user.home") ?: ""

            val defaultDir = when {
                SystemInfo.isWindows -> {
                    val localAppData = System.getenv("LOCALAPPDATA")
                    if (!localAppData.isNullOrBlank()) {
                        File(localAppData, "Pub/Cache")
                    } else if (home.isNotBlank()) {
                        File(home, "AppData/Local/Pub/Cache")
                    } else {
                        null
                    }
                }
                else -> {
                    if (home.isNotBlank()) File(home, ".pub-cache") else null
                }
            }

            return defaultDir?.takeIf { it.isDirectory }
        }
    }

    /**
     * Returns indexed local packages matching prefix.
     * Ensures scan is fresh within SCAN_TTL_MS.
     */
    fun getLocalPackagesMatching(prefix: String): List<LocalCachedPackage> {
        ensureIndexLoaded()
        val cleanPrefix = prefix.trim().lowercase()
        if (cleanPrefix.isEmpty()) {
            return localPackagesCache.values.toList()
        }
        return localPackagesCache.values.filter { (name) -> name.startsWith(cleanPrefix) }
    }

    /**
     * Gets local package details for an exact package name if downloaded in pub cache.
     */
    fun getLocalPackage(packageName: String): LocalCachedPackage? {
        ensureIndexLoaded()
        return localPackagesCache[packageName.trim().lowercase()]
    }

    /**
     * Returns true if any versions of this package are available offline in the local pub cache.
     */
    @Suppress("unused")
    fun hasPackage(packageName: String): Boolean {
        ensureIndexLoaded()
        return localPackagesCache.containsKey(packageName.trim().lowercase())
    }

    /**
     * Scans hosted pub.dev folder in pub-cache.
     */
    @Synchronized
    fun ensureIndexLoaded(forceRefresh: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!forceRefresh && ((now - lastScanTime.get()) < SCAN_TTL_MS) && localPackagesCache.isNotEmpty()) {
            return
        }

        val cacheBase = resolvePubCacheDirectory()
        if (cacheBase == null) {
            log.debug("Pub cache directory could not be located on this machine.")
            lastScanTime.set(now)
            return
        }

        val hostedDir = File(cacheBase, "hosted")
        if (!hostedDir.isDirectory) {
            lastScanTime.set(now)
            return
        }

        // Find hosted repository directory (pub.dev or pub.dartlang.org)
        val targetHostedDirs = hostedDir.listFiles { f ->
            f.isDirectory && (f.name == "pub.dev" || f.name.startsWith("pub.dev-") || f.name == "pub.dartlang.org")
        } ?: emptyArray()

        val tempMap = HashMap<String, MutableList<String>>()

        for (hostFolder in targetHostedDirs) {
            val packageDirs = hostFolder.listFiles { f -> f.isDirectory && !f.name.startsWith(".") } ?: continue

            for (pkgDir in packageDirs) {
                val folderName = pkgDir.name // e.g. "flutter_bloc-8.1.6" or "http-1.2.0"
                val hyphenIdx = folderName.lastIndexOf('-')
                if ((hyphenIdx <= 0) || (hyphenIdx >= folderName.length - 1)) continue

                val pkgName = folderName.substring(0, hyphenIdx).lowercase()
                val versionStr = folderName.substring(hyphenIdx + 1)

                if (PubspecYamlUtils.PACKAGE_NAME_REGEX.matches(pkgName)) {
                    tempMap.computeIfAbsent(pkgName) { ArrayList() }.add(versionStr)
                }
            }
        }

        localPackagesCache.clear()
        for ((name, versions) in tempMap) {
            // Sort versions using SemVer comparator
            val sortedVersions = versions.asSequence().distinct().sortedWith { v1, v2 ->
                PubspecYamlUtils.compareSemVer(v2, v1) // descending order: newest first
            }.toList()
            val newest = sortedVersions.firstOrNull() ?: "0.0.0"
            localPackagesCache[name] = LocalCachedPackage(
                name = name,
                latestVersion = newest,
                installedVersions = sortedVersions,
            )
        }

        lastScanTime.set(now)
        log.info("LocalPubCacheService indexed ${localPackagesCache.size} offline packages from ${cacheBase.absolutePath}")
    }
}
