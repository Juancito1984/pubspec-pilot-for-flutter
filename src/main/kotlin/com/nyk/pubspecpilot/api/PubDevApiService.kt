package com.nyk.pubspecpilot.api

import com.google.gson.JsonParser
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProgressManager
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.io.HttpRequests
import com.nyk.pubspecpilot.util.PubspecYamlUtils
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

data class PubPackageInfo(
    val name: String,
    val latestStableVersion: String,
    val latestVersion: String,
    val description: String,
    val recentVersions: List<String>,
    val fetchedAtMillis: Long = System.currentTimeMillis()
)

@Service(Service.Level.APP)
class PubDevApiService {
    private val log = Logger.getInstance(PubDevApiService::class.java)

    // Bounded thread pool for parallel pub.dev HTTP requests without starving IDE threads
    private val httpExecutor = AppExecutorUtil.createBoundedApplicationPoolExecutor("PubspecPilotHttpPool", 8)

    private val packageCache = ConcurrentHashMap<String, PubPackageInfo>()
    private val searchCache = ConcurrentHashMap<String, Pair<Long, List<String>>>()
    private val negativeCache = ConcurrentHashMap<String, Long>()

    // Deduplicate concurrent in-flight HTTP requests for the same package or search query
    private val inFlightPackageRequests = ConcurrentHashMap<String, CompletableFuture<PubPackageInfo?>>()
    private val inFlightSearchRequests = ConcurrentHashMap<String, CompletableFuture<List<String>>>()

    companion object {
        private val CACHE_TTL_MS = TimeUnit.MINUTES.toMillis(30)
        private val SEARCH_TTL_MS = TimeUnit.MINUTES.toMillis(15)
        private val NEGATIVE_TTL_MS = TimeUnit.MINUTES.toMillis(2)
        private const val PUB_API_BASE = "https://pub.dev/api"

        // Curated popular Flutter/Dart packages for instant zero-latency suggestions
        val POPULAR_PACKAGES = listOf(
            "http", "dio", "provider", "flutter_bloc", "bloc", "riverpod", "flutter_riverpod",
            "hooks_riverpod", "get", "get_it", "go_router", "shared_preferences", "sqflite",
            "hive", "hive_flutter", "isar", "drift", "freezed", "freezed_annotation",
            "json_annotation", "json_serializable", "build_runner", "equatable", "intl",
            "url_launcher", "path_provider", "path", "cached_network_image", "flutter_svg",
            "google_fonts", "image_picker", "permission_handler", "connectivity_plus",
            "device_info_plus", "package_info_plus", "share_plus", "firebase_core",
            "firebase_auth", "cloud_firestore", "firebase_messaging", "firebase_storage",
            "firebase_analytics", "firebase_crashlytics", "supabase_flutter", "flutter_dotenv",
            "flutter_secure_storage", "local_auth", "geolocator", "google_maps_flutter",
            "webview_flutter", "video_player", "just_audio", "audioplayers", "lottie",
            "shimmer", "animations", "flutter_animate", "flutter_hooks", "dartz", "fpdart",
            "rxdart", "uuid", "crypto", "logger", "talker", "mocktail", "mockito",
            "bloc_test", "flutter_lints", "very_good_analysis", "auto_route", "retrofit"
        )

        fun getInstance(): PubDevApiService =
            ApplicationManager.getApplication().getService(PubDevApiService::class.java)
    }

    /**
     * Returns cached package info immediately if available (0 ms latency).
     * Uses stale-while-revalidate: if expired, returns the cached value immediately and refreshes in background.
     */
    fun getCachedPackageInfo(packageName: String): PubPackageInfo? {
        val normalized = packageName.trim().lowercase()
        val cached = packageCache[normalized] ?: return null
        if (System.currentTimeMillis() - cached.fetchedAtMillis > CACHE_TTL_MS) {
            prefetchPackagesAsync(listOf(normalized))
        }
        return cached
    }

    /**
     * Returns local popular + cached search matches immediately without any network I/O.
     */
    fun getInstantLocalMatches(query: String): List<String> {
        val cleanQuery = query.trim().lowercase()
        if (cleanQuery.isEmpty()) {
            return POPULAR_PACKAGES.take(20)
        }
        val startsWithMatches = POPULAR_PACKAGES.filter { it.startsWith(cleanQuery) }
        val containsMatches = POPULAR_PACKAGES.filter { !it.startsWith(cleanQuery) && it.contains(cleanQuery) }
        val cachedSearch = searchCache[cleanQuery]?.second ?: emptyList()
        return (startsWithMatches + containsMatches + cachedSearch).distinct()
    }

    /**
     * Fetches multiple packages in parallel with cancellation responsiveness and an overall deadline.
     */
    fun getPackagesInfoParallel(
        packageNames: Collection<String>,
        perRequestTimeoutMs: Int = 2500,
        overallDeadlineMs: Long = 3000L
    ): Map<String, PubPackageInfo> {
        val uniqueNames = packageNames.map { it.trim().lowercase() }
            .filter { it.isNotEmpty() && PubspecYamlUtils.PACKAGE_NAME_REGEX.matches(it) }
            .distinct()

        if (uniqueNames.isEmpty()) return emptyMap()

        val result = ConcurrentHashMap<String, PubPackageInfo>()
        val toFetch = ArrayList<String>()

        for (name in uniqueNames) {
            val cached = getCachedPackageInfo(name)
            if (cached != null) {
                result[name] = cached
            } else if (!isRecentlyFailed(name)) {
                toFetch.add(name)
            }
        }

        if (toFetch.isEmpty()) return result

        val futures = toFetch.map { name ->
            name to startOrJoinPackageFetch(name, perRequestTimeoutMs)
        }

        val deadlineNano = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(overallDeadlineMs)
        for ((name, future) in futures) {
            ProgressManager.checkCanceled()
            while (!future.isDone) {
                ProgressManager.checkCanceled()
                val remainingMs = TimeUnit.NANOSECONDS.toMillis(deadlineNano - System.nanoTime())
                if (remainingMs <= 0) break
                try {
                    val info = future.get(minOf(remainingMs, 40L), TimeUnit.MILLISECONDS)
                    if (info != null) {
                        result[name] = info
                    }
                    break
                } catch (_: java.util.concurrent.TimeoutException) {
                    // Loop again to check ProgressManager.checkCanceled()
                } catch (_: Exception) {
                    break
                }
            }
            if (future.isDone && !future.isCompletedExceptionally) {
                future.getNow(null)?.let { result[name] = it }
            }
        }

        return result
    }

    /**
     * Non-blocking background prefetch to warm up the cache.
     */
    fun prefetchPackagesAsync(packageNames: Collection<String>, timeoutMs: Int = 2500) {
        for (rawName in packageNames) {
            val normalized = rawName.trim().lowercase()
            if (normalized.isEmpty() || !PubspecYamlUtils.PACKAGE_NAME_REGEX.matches(normalized)) continue
            val existing = packageCache[normalized]
            if (existing != null && System.currentTimeMillis() - existing.fetchedAtMillis <= CACHE_TTL_MS) continue
            if (isRecentlyFailed(normalized)) continue

            startOrJoinPackageFetch(normalized, timeoutMs)
        }
    }

    /**
     * Fetches package info from pub.dev (or returns cached copy).
     * Deduplicates concurrent requests and stays responsive to IDE cancellation.
     */
    fun getPackageInfo(packageName: String, timeoutMs: Int = 2500): PubPackageInfo? {
        val normalized = packageName.trim().lowercase()
        if (normalized.isEmpty() || !PubspecYamlUtils.PACKAGE_NAME_REGEX.matches(normalized)) {
            return null
        }

        getCachedPackageInfo(normalized)?.let { return it }
        if (isRecentlyFailed(normalized)) return null

        val future = startOrJoinPackageFetch(normalized, timeoutMs)
        val deadlineNano = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs.toLong() + 200L)

        while (!future.isDone) {
            ProgressManager.checkCanceled()
            val remainingMs = TimeUnit.NANOSECONDS.toMillis(deadlineNano - System.nanoTime())
            if (remainingMs <= 0) return null
            try {
                return future.get(minOf(remainingMs, 40L), TimeUnit.MILLISECONDS)
            } catch (_: java.util.concurrent.TimeoutException) {
                // Continue polling to keep ProgressManager.checkCanceled() responsive
            } catch (_: Exception) {
                return null
            }
        }
        return future.getNow(null)
    }

    private fun startOrJoinPackageFetch(normalized: String, timeoutMs: Int): CompletableFuture<PubPackageInfo?> {
        return inFlightPackageRequests.computeIfAbsent(normalized) {
            CompletableFuture.supplyAsync({
                try {
                    fetchPackageInfoOverHttp(normalized, timeoutMs)
                } finally {
                    inFlightPackageRequests.remove(normalized)
                }
            }, httpExecutor)
        }
    }

    private fun fetchPackageInfoOverHttp(normalized: String, timeoutMs: Int): PubPackageInfo? {
        return try {
            val url = "$PUB_API_BASE/packages/$normalized"
            val responseJson = HttpRequests.request(url)
                .accept("application/vnd.pub.v2+json")
                .gzip(true)
                .connectTimeout(timeoutMs)
                .readTimeout(timeoutMs)
                .readString()

            val root = JsonParser.parseString(responseJson).asJsonObject
            val latestObj = root.getAsJsonObject("latest")
            val latestVersion = latestObj?.get("version")?.asString ?: return null
            val pubspecObj = latestObj.getAsJsonObject("pubspec")
            val description = pubspecObj?.get("description")?.asString ?: ""

            val allVersions = ArrayList<String>(32)
            var latestStableFromList: String? = null

            val versionsArray = root.getAsJsonArray("versions")
            if (versionsArray != null) {
                for (element in versionsArray) {
                    val vObj = element.asJsonObject
                    val isRetracted = vObj.get("retracted")?.asBoolean ?: false
                    if (isRetracted) continue
                    val vStr = vObj.get("version")?.asString ?: continue
                    allVersions.add(vStr)
                    if ('-' !in vStr) {
                        latestStableFromList = vStr
                    }
                }
            }

            val latestStable = if ('-' !in latestVersion) {
                latestVersion
            } else {
                latestStableFromList ?: latestVersion
            }

            val recent = allVersions.takeLast(8).reversed()
            val info = PubPackageInfo(
                name = normalized,
                latestStableVersion = latestStable,
                latestVersion = latestVersion,
                description = description,
                recentVersions = recent.ifEmpty { listOf(latestStable) }
            )
            packageCache[normalized] = info
            negativeCache.remove(normalized)
            info
        } catch (e: Exception) {
            negativeCache[normalized] = System.currentTimeMillis()
            log.debug("Could not fetch pub.dev info for '$normalized': ${e.message}")
            null
        }
    }

    private fun isRecentlyFailed(normalized: String): Boolean {
        val failedAt = negativeCache[normalized] ?: return false
        return System.currentTimeMillis() - failedAt < NEGATIVE_TTL_MS
    }

    /**
     * Searches pub.dev for packages matching the query prefix with request deduplication and background prefetching.
     */
    fun searchPackages(query: String, timeoutMs: Int = 1800): List<String> {
        val cleanQuery = query.trim().lowercase()
        if (cleanQuery.isEmpty()) {
            prefetchPackagesAsync(POPULAR_PACKAGES.take(8))
            return POPULAR_PACKAGES.take(20)
        }

        val localMatches = getInstantLocalMatches(cleanQuery)

        val cached = searchCache[cleanQuery]
        if (cached != null && System.currentTimeMillis() - cached.first < SEARCH_TTL_MS) {
            val combined = (localMatches + cached.second).distinct()
            prefetchPackagesAsync(combined.take(6))
            return combined
        }

        val future = inFlightSearchRequests.computeIfAbsent(cleanQuery) {
            CompletableFuture.supplyAsync({
                try {
                    val encoded = URLEncoder.encode(cleanQuery, StandardCharsets.UTF_8)
                    val url = "$PUB_API_BASE/search?q=$encoded"
                    val responseJson = HttpRequests.request(url)
                        .accept("application/vnd.pub.v2+json")
                        .gzip(true)
                        .connectTimeout(timeoutMs)
                        .readTimeout(timeoutMs)
                        .readString()

                    val root = JsonParser.parseString(responseJson).asJsonObject
                    val packagesArray = root.getAsJsonArray("packages")
                    val results = ArrayList<String>(16)
                    if (packagesArray != null) {
                        for (element in packagesArray) {
                            val pkgName = element.asJsonObject.get("package")?.asString
                            if (!pkgName.isNullOrBlank()) {
                                results.add(pkgName)
                            }
                        }
                    }
                    searchCache[cleanQuery] = System.currentTimeMillis() to results
                    results
                } catch (e: Exception) {
                    log.debug("Search on pub.dev failed for '$cleanQuery': ${e.message}")
                    emptyList()
                } finally {
                    inFlightSearchRequests.remove(cleanQuery)
                }
            }, httpExecutor)
        }

        val deadlineNano = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs.toLong() + 100L)
        var remoteResults: List<String> = emptyList()
        while (!future.isDone) {
            ProgressManager.checkCanceled()
            val remainingMs = TimeUnit.NANOSECONDS.toMillis(deadlineNano - System.nanoTime())
            if (remainingMs <= 0) break
            try {
                remoteResults = future.get(minOf(remainingMs, 40L), TimeUnit.MILLISECONDS)
                break
            } catch (_: java.util.concurrent.TimeoutException) {
                // Keep polling for cancellation responsiveness
            } catch (_: Exception) {
                break
            }
        }
        if (future.isDone && !future.isCompletedExceptionally) {
            remoteResults = future.getNow(emptyList())
        }

        val combined = (localMatches + remoteResults).distinct()
        prefetchPackagesAsync(combined.take(8))
        return combined
    }
}
