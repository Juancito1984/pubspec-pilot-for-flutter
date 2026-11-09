package com.nyk.pubspecpilot.cache

import com.nyk.pubspecpilot.util.PubspecYamlUtils
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LocalPubCacheServiceTest {

    @Test
    fun testResolvePubCacheDirectory() {
        val dir = LocalPubCacheService.resolvePubCacheDirectory()
        // If run on machine with Flutter/Dart installed, directory is present
        if (dir != null) {
            assertTrue(dir.exists())
            assertTrue(dir.isDirectory)
        }
    }

    @Test
    fun testPackageDirectoryParsingSemVer() {
        // Test SemVer sorting descending
        val versions = listOf("1.0.0", "1.2.1", "1.1.0", "2.0.0-beta.1", "1.2.0")
        val sortedDesc = versions.sortedWith { v1, v2 ->
            PubspecYamlUtils.compareSemVer(v2, v1)
        }
        assertEquals("2.0.0-beta.1", sortedDesc.first())
        assertEquals("1.2.1", sortedDesc[1])
    }
}
