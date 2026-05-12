import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.gradle.api.tasks.bundling.Zip
import java.security.MessageDigest

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
val marketplaceChannels = providers.gradleProperty("marketplaceChannels")
    .map { value -> value.split(",").map(String::trim).filter(String::isNotEmpty) }
    .orElse(emptyList())
val browserExtensionVersion = providers.gradleProperty("browserExtensionVersion").orElse("1.0.0")
val browserExtensionDistributionName = "cph-target-runner-browser-${browserExtensionVersion.get()}"
val aveMujicaThemeVersion = providers.gradleProperty("aveMujicaThemeVersion")
val aveMujicaThemeMinPluginVersion = providers.gradleProperty("aveMujicaThemeMinPluginVersion")
val aveMujicaThemeReleaseTag = "theme-avemujica"
val aveMujicaThemeZipName = "cph-theme-avemujica-${aveMujicaThemeVersion.get()}.zip"

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
        bundledModule("intellij.clion.runFile")
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
        channels.set(marketplaceChannels)
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

tasks.processResources {
    exclude("icons/avemujica/**")
    exclude("fonts/AnglicanText.ttf")
}

val buildBrowserExtension by tasks.registering(Sync::class) {
    from(layout.projectDirectory.dir("browser-extension/cph-target-runner")) {
        exclude("*.test.js")
    }
    into(layout.buildDirectory.dir("distributions/$browserExtensionDistributionName"))
}

val packageBrowserExtension by tasks.registering(Zip::class) {
    dependsOn(buildBrowserExtension)
    archiveFileName.set("$browserExtensionDistributionName.zip")
    destinationDirectory.set(layout.buildDirectory.dir("distributions"))
    from(layout.buildDirectory.dir("distributions/$browserExtensionDistributionName")) {
        into(browserExtensionDistributionName)
    }
}

val prepareAveMujicaThemePackage by tasks.registering(Sync::class) {
    inputs.property("aveMujicaThemeVersion", aveMujicaThemeVersion)
    inputs.property("aveMujicaThemeMinPluginVersion", aveMujicaThemeMinPluginVersion)
    from(layout.projectDirectory.dir("src/main/resources")) {
        include("icons/avemujica/**")
        include("fonts/AnglicanText.ttf")
    }
    into(layout.buildDirectory.dir("aveMujicaTheme/package"))
    doLast {
        val packageJson = destinationDir.resolve("theme-package.json")
        packageJson.writeText(
            """
            {
              "themeId": "avemujica",
              "version": "${aveMujicaThemeVersion.get()}",
              "minPluginVersion": "${aveMujicaThemeMinPluginVersion.get()}"
            }
            """.trimIndent() + "\n",
        )
    }
}

val zipAveMujicaTheme by tasks.registering(Zip::class) {
    dependsOn(prepareAveMujicaThemePackage)
    archiveFileName.set(aveMujicaThemeZipName)
    destinationDirectory.set(layout.buildDirectory.dir("distributions"))
    from(layout.buildDirectory.dir("aveMujicaTheme/package"))
}

val generateAveMujicaThemeManifest by tasks.registering {
    dependsOn(zipAveMujicaTheme)
    inputs.property("aveMujicaThemeVersion", aveMujicaThemeVersion)
    inputs.property("aveMujicaThemeMinPluginVersion", aveMujicaThemeMinPluginVersion)
    inputs.file(zipAveMujicaTheme.flatMap { it.archiveFile })
    val manifestFile = layout.buildDirectory.file("distributions/cph-theme-avemujica.json")
    outputs.file(manifestFile)
    doLast {
        val zipFile = zipAveMujicaTheme.get().archiveFile.get().asFile
        val digest = MessageDigest.getInstance("SHA-256")
        zipFile.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        val sha256 = digest.digest().joinToString("") { "%02x".format(it) }
        manifestFile.get().asFile.writeText(
            """
            {
              "themeId": "avemujica",
              "version": "${aveMujicaThemeVersion.get()}",
              "minPluginVersion": "${aveMujicaThemeMinPluginVersion.get()}",
              "packageUrl": "https://github.com/kkkzbh/CPH/releases/download/${aveMujicaThemeReleaseTag}/${aveMujicaThemeZipName}",
              "sha256": "$sha256",
              "sizeBytes": ${zipFile.length()}
            }
            """.trimIndent() + "\n",
        )
    }
}

tasks.register("packageAveMujicaTheme") {
    dependsOn(zipAveMujicaTheme, generateAveMujicaThemeManifest)
}

tasks.named("buildPlugin") {
    dependsOn(buildBrowserExtension)
}

tasks.named("build") {
    dependsOn(buildBrowserExtension)
}
