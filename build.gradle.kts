import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("java")
    kotlin("jvm") version "2.3.0"
    id("org.jetbrains.intellij.platform") version "2.13.1"
}

group = providers.gradleProperty("pluginGroup").get()
version = providers.gradleProperty("pluginVersion").get()

val platformVersion = providers.gradleProperty("platformVersion")
val useLocalClion = providers.gradleProperty("useLocalClion")
    .map(String::toBooleanStrictOrNull)
    .orElse(false)
val clionLocalPath = providers.gradleProperty("clionLocalPath")
    .orElse(providers.environmentVariable("CLION_HOME"))
val marketplaceToken = providers.environmentVariable("JB_MARKETPLACE_TOKEN")

kotlin {
    jvmToolchain(21)
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
    }
}

repositories {
    mavenCentral()

    intellijPlatform {
        defaultRepositories()
        localPlatformArtifacts()
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")

    intellijPlatform {
        if (useLocalClion.get()) {
            local(clionLocalPath.get())
        } else {
            clion(platformVersion.get())
        }
        bundledPlugin("com.intellij.clion")
        testFramework(TestFrameworkType.Platform)
    }
}

intellijPlatform {
    pluginConfiguration {
        name = providers.gradleProperty("pluginName")
        version = providers.gradleProperty("pluginVersion")
        description = """
            A CLion sidebar helper for competitive-programming samples.
            Manage sample cases per CMake target, run them quickly, compare outputs, and inspect mismatched lines.
        """.trimIndent()

        ideaVersion {
            sinceBuild = providers.gradleProperty("pluginSinceBuild")
        }
    }

    publishing {
        token = marketplaceToken
    }

    pluginVerification {
        ides {
            recommended()
        }
    }
}

tasks.named("buildSearchableOptions").configure {
    enabled = false
}
