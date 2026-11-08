package com.nyk.pubspecpilot.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PubspecYamlUtilsTest {

    @Test
    fun testCompareSemVerEquality() {
        assertEquals(0, PubspecYamlUtils.compareSemVer("1.2.3", "1.2.3"))
        assertEquals(0, PubspecYamlUtils.compareSemVer("1.2.3+1", "1.2.3+2")) // build metadata ignored
    }

    @Test
    fun testCompareSemVerMajorMinorPatch() {
        assertTrue(PubspecYamlUtils.compareSemVer("2.0.0", "1.9.9") > 0)
        assertTrue(PubspecYamlUtils.compareSemVer("1.10.0", "1.9.0") > 0)
        assertTrue(PubspecYamlUtils.compareSemVer("1.0.2", "1.0.1") > 0)
        assertTrue(PubspecYamlUtils.compareSemVer("1.0.0", "1.0.1") < 0)
    }

    @Test
    fun testCompareSemVerPreRelease() {
        // Normal version has higher precedence than pre-release
        assertTrue(PubspecYamlUtils.compareSemVer("1.0.0", "1.0.0-beta.1") > 0)
        assertTrue(PubspecYamlUtils.compareSemVer("1.0.0-beta.1", "1.0.0") < 0)

        // Pre-release comparisons
        assertTrue(PubspecYamlUtils.compareSemVer("1.0.0-beta.2", "1.0.0-beta.1") > 0)
        assertTrue(PubspecYamlUtils.compareSemVer("1.0.0-rc.1", "1.0.0-beta.9") > 0)
    }

    @Test
    fun testIsNewerVersion() {
        assertTrue(PubspecYamlUtils.isNewerVersion("1.0.0", "1.0.1"))
        assertTrue(PubspecYamlUtils.isNewerVersion("0.13.5", "1.0.0"))
        assertFalse(PubspecYamlUtils.isNewerVersion("1.0.1", "1.0.0"))
        assertFalse(PubspecYamlUtils.isNewerVersion("1.0.0", "1.0.0"))
    }

    @Test
    fun testExtractBaseSemVer() {
        assertEquals("1.2.3", PubspecYamlUtils.extractBaseSemVer("^1.2.3"))
        assertEquals("0.14.0", PubspecYamlUtils.extractBaseSemVer(">=0.14.0 <1.0.0"))
        assertEquals("2.0.0-beta.1", PubspecYamlUtils.extractBaseSemVer("^2.0.0-beta.1"))
        assertEquals("3.0.0", PubspecYamlUtils.extractBaseSemVer("3.0.0"))
        assertEquals(null, PubspecYamlUtils.extractBaseSemVer("any"))
        assertEquals(null, PubspecYamlUtils.extractBaseSemVer(""))
    }

    @Test
    fun testBuildRecommendedConstraint() {
        assertEquals("^2.0.0", PubspecYamlUtils.buildRecommendedConstraint("^1.0.0", "2.0.0"))
        assertEquals("^2.0.0", PubspecYamlUtils.buildRecommendedConstraint("1.0.0", "2.0.0"))
    }

    @Test
    fun testPackageNameRegex() {
        assertTrue(PubspecYamlUtils.PACKAGE_NAME_REGEX.matches("http"))
        assertTrue(PubspecYamlUtils.PACKAGE_NAME_REGEX.matches("flutter_bloc"))
        assertTrue(PubspecYamlUtils.PACKAGE_NAME_REGEX.matches("provider2"))
        assertFalse(PubspecYamlUtils.PACKAGE_NAME_REGEX.matches("Invalid-Name"))
        assertFalse(PubspecYamlUtils.PACKAGE_NAME_REGEX.matches("has space"))
        assertFalse(PubspecYamlUtils.PACKAGE_NAME_REGEX.matches(""))
    }

    @Test
    fun testIgnoredPackagesAndSections() {
        assertTrue("flutter" in PubspecYamlUtils.IGNORED_SDK_PACKAGES)
        assertTrue("flutter_test" in PubspecYamlUtils.IGNORED_SDK_PACKAGES)
        assertTrue("dependencies" in PubspecYamlUtils.DEPENDENCY_SECTIONS)
        assertTrue("dev_dependencies" in PubspecYamlUtils.DEPENDENCY_SECTIONS)
        assertTrue("dependency_overrides" in PubspecYamlUtils.DEPENDENCY_SECTIONS)
    }
}
