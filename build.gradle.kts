import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.1.20"
    id("org.jetbrains.intellij.platform") version "2.20.0"
}

group = providers.gradleProperty("pluginGroup").get()
version = providers.gradleProperty("pluginVersion").get()

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        androidStudio("2024.1.1.13")
        bundledPlugin("com.intellij.java")
        bundledPlugin("org.jetbrains.plugins.yaml")

        pluginVerifier()
        zipSigner()
        testFramework(TestFrameworkType.Platform)
    }
    testImplementation(kotlin("test"))
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    jvmToolchain(17)
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

intellijPlatform {
    pluginConfiguration {
        id = "com.nyk.pubspecpilot"
        name = "Pubspec Pilot for Flutter"
        version = providers.gradleProperty("pluginVersion")
        description = """
            Lightweight, fast, and non-intrusive assistant for Flutter &amp; Dart <code>pubspec.yaml</code> files by <b>NyK</b>.<br/><br/>
            <b>Features:</b>
            <ul>
                <li><b>Outdated Package Detection:</b> Highlights outdated dependencies in <code>pubspec.yaml</code> with inline version hints and one-click Quick Fix updates.</li>
                <li><b>Hybrid Package Auto-Completion (Online &amp; Offline):</b> Search and autocomplete packages directly from <code>pub.dev</code> with strict prefix matching, and automatically fallback to system local <code>Pub Cache</code> when offline.</li>
                <li><b>Automatic <code>flutter pub get</code>:</b> Automatically syncs packages in the background after inserting or updating a dependency, with graceful automatic fallback to <code>--offline</code> if internet is unreachable.</li>
                <li><b>Zero Dart IntelliSense Interference:</b> Operates strictly on <code>pubspec.yaml</code> so it never slows down or blocks Dart code completion.</li>
            </ul>
        """.trimIndent()

        vendor {
            name = "NyK"
        }

        ideaVersion {
            sinceBuild.set("241")
            untilBuild.set(provider { null })
        }
    }

    signing {
        certificateChain = providers.environmentVariable("CERTIFICATE_CHAIN")
        privateKey = providers.environmentVariable("PRIVATE_KEY")
        password = providers.environmentVariable("PRIVATE_KEY_PASSWORD")
    }

    publishing {
        token = providers.environmentVariable("PUBLISH_TOKEN")
    }
}

tasks {
    named("buildSearchableOptions") {
        enabled = false
    }
}
