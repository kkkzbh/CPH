package org.kkkzbh.cph

import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.application.PathManager
import com.intellij.util.io.HttpRequests
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URL
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Comparator
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.inputStream
import kotlin.io.path.isDirectory
import kotlin.io.path.name
import kotlin.io.path.outputStream

internal data class CphThemePackageManifest(
    val themeId: String,
    val version: String,
    val minPluginVersion: String,
    val packageUrl: String,
    val sha256: String,
    val sizeBytes: Long,
)

internal enum class CphThemePackageStatus {
    NOT_INSTALLED,
    INSTALLED,
    UPDATE_AVAILABLE,
    INCOMPATIBLE,
    DOWNLOADING,
    FAILED,
}

internal data class CphThemePackageState(
    val status: CphThemePackageStatus,
    val installedVersion: String? = null,
    val remoteVersion: String? = null,
    val message: String? = null,
) {
    val installed: Boolean
        get() = installedVersion != null
}

internal object CphThemePackageJson {
    fun parse(text: String): CphThemePackageManifest {
        return CphThemePackageManifest(
            themeId = stringField(text, "themeId"),
            version = stringField(text, "version"),
            minPluginVersion = stringField(text, "minPluginVersion"),
            packageUrl = stringField(text, "packageUrl"),
            sha256 = stringField(text, "sha256").lowercase(Locale.ROOT),
            sizeBytes = longField(text, "sizeBytes"),
        )
    }

    fun render(manifest: CphThemePackageManifest): String {
        return """
            {
              "themeId": "${escape(manifest.themeId)}",
              "version": "${escape(manifest.version)}",
              "minPluginVersion": "${escape(manifest.minPluginVersion)}",
              "packageUrl": "${escape(manifest.packageUrl)}",
              "sha256": "${escape(manifest.sha256.lowercase(Locale.ROOT))}",
              "sizeBytes": ${manifest.sizeBytes}
            }
        """.trimIndent() + "\n"
    }

    private fun stringField(text: String, name: String): String {
        val pattern = Regex(""""${Regex.escape(name)}"\s*:\s*"((?:\\.|[^"\\])*)"""")
        val raw = pattern.find(text)?.groupValues?.get(1)
            ?: throw IllegalArgumentException("Theme manifest is missing string field '$name'.")
        return unescape(raw)
    }

    private fun longField(text: String, name: String): Long {
        val pattern = Regex(""""${Regex.escape(name)}"\s*:\s*(\d+)""")
        return pattern.find(text)?.groupValues?.get(1)?.toLong()
            ?: throw IllegalArgumentException("Theme manifest is missing numeric field '$name'.")
    }

    private fun escape(value: String): String =
        buildString {
            value.forEach { ch ->
                when (ch) {
                    '\\' -> append("\\\\")
                    '"' -> append("\\\"")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> append(ch)
                }
            }
        }

    private fun unescape(value: String): String =
        buildString {
            var i = 0
            while (i < value.length) {
                val ch = value[i]
                if (ch != '\\' || i == value.lastIndex) {
                    append(ch)
                    i++
                    continue
                }
                when (val escaped = value[i + 1]) {
                    '\\' -> append('\\')
                    '"' -> append('"')
                    'n' -> append('\n')
                    'r' -> append('\r')
                    't' -> append('\t')
                    else -> append(escaped)
                }
                i += 2
            }
        }
}

internal object CphVersionComparator {
    fun compare(left: String, right: String): Int {
        val leftParts = left.split('.', '-', '_')
        val rightParts = right.split('.', '-', '_')
        val size = maxOf(leftParts.size, rightParts.size)
        for (index in 0 until size) {
            val l = leftParts.getOrNull(index).orEmpty()
            val r = rightParts.getOrNull(index).orEmpty()
            val numeric = l.toIntOrNull() to r.toIntOrNull()
            val diff = if (numeric.first != null && numeric.second != null) {
                numeric.first!!.compareTo(numeric.second!!)
            } else {
                l.compareTo(r)
            }
            if (diff != 0) return diff
        }
        return 0
    }
}

internal class CphThemeAssetStore(
    private val themesRoot: Path,
    private val settingsState: CphPluginSettingsState,
) {
    fun installedVersion(themeId: String): String? =
        settingsState.installedThemeVersions[themeId]?.takeIf {
            themeRoot(themeId, it).isDirectory()
        }

    fun isInstalled(themeId: String): Boolean =
        installedVersion(themeId) != null

    fun resolve(themeId: String, relativePath: String): URL? {
        val version = installedVersion(themeId) ?: return null
        val path = safeResolve(themeRoot(themeId, version), relativePath) ?: return null
        if (!path.exists()) return null
        return path.toUri().toURL()
    }

    fun installPackage(manifest: CphThemePackageManifest, packageFile: Path) {
        validateManifest(manifest)
        val actualSha = sha256(packageFile)
        if (!actualSha.equals(manifest.sha256, ignoreCase = true)) {
            throw IOException("Theme package sha256 mismatch: expected ${manifest.sha256}, got $actualSha.")
        }
        val actualSize = Files.size(packageFile)
        if (actualSize != manifest.sizeBytes) {
            throw IOException("Theme package size mismatch: expected ${manifest.sizeBytes}, got $actualSize.")
        }
        val tempDir = themesRoot.resolve(".tmp-${manifest.themeId}-${System.nanoTime()}")
        val targetDir = themeRoot(manifest.themeId, manifest.version)
        themesRoot.createDirectories()
        deleteRecursively(tempDir)
        tempDir.createDirectories()
        try {
            unzipThemePackage(packageFile, tempDir)
            val packageJson = tempDir.resolve(THEME_PACKAGE_JSON)
            if (!packageJson.exists()) {
                throw IOException("Theme package is missing $THEME_PACKAGE_JSON.")
            }
            deleteRecursively(targetDir)
            targetDir.parent.createDirectories()
            runCatching {
                Files.move(tempDir, targetDir, StandardCopyOption.ATOMIC_MOVE)
            }.getOrElse {
                Files.move(tempDir, targetDir, StandardCopyOption.REPLACE_EXISTING)
            }
            settingsState.installedThemeVersions[manifest.themeId] = manifest.version
        } catch (t: Throwable) {
            deleteRecursively(tempDir)
            throw t
        }
    }

    private fun validateManifest(manifest: CphThemePackageManifest) {
        if (manifest.themeId != CphThemeId.AVE_MUJICA) {
            throw IOException("Unsupported theme package '${manifest.themeId}'.")
        }
        if (manifest.version.isBlank()) {
            throw IOException("Theme package version is blank.")
        }
    }

    private fun unzipThemePackage(packageFile: Path, destination: Path) {
        ZipInputStream(packageFile.inputStream()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                unzipEntry(zip, entry, destination)
                zip.closeEntry()
            }
        }
    }

    private fun unzipEntry(zip: ZipInputStream, entry: ZipEntry, destination: Path) {
        val name = entry.name.replace('\\', '/')
        if (!allowedEntryName(name)) {
            throw IOException("Theme package contains unsupported entry: $name")
        }
        val target = safeResolve(destination, name)
            ?: throw IOException("Theme package contains unsafe entry: $name")
        if (entry.isDirectory) {
            target.createDirectories()
            return
        }
        target.parent.createDirectories()
        target.outputStream().use { out ->
            zip.copyTo(out)
        }
    }

    private fun themeRoot(themeId: String, version: String): Path =
        themesRoot.resolve(themeId).resolve(version)

    private fun safeResolve(root: Path, relativePath: String): Path? {
        val normalizedRoot = root.toAbsolutePath().normalize()
        val normalizedPath = normalizedRoot.resolve(relativePath.removePrefix("/")).normalize()
        return normalizedPath.takeIf { it.startsWith(normalizedRoot) }
    }

    companion object {
        const val THEME_PACKAGE_JSON = "theme-package.json"

        fun sha256(path: Path): String {
            val digest = MessageDigest.getInstance("SHA-256")
            path.inputStream().use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }

        fun deleteRecursively(path: Path) {
            if (!path.exists()) return
            Files.walk(path).use { stream ->
                stream.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
            }
        }

        fun allowedEntryName(name: String): Boolean {
            if (name.isBlank() || name.startsWith("/") || name.contains("../")) return false
            return name == THEME_PACKAGE_JSON ||
                name == "icons/" ||
                name == "fonts/" ||
                name.startsWith("icons/avemujica/") ||
                name == "fonts/AnglicanText.ttf"
        }
    }
}

internal class CphThemeAssetService {
    private val lock = Any()
    private val remoteManifests = mutableMapOf<String, CphThemePackageManifest>()
    private val failures = mutableMapOf<String, String>()
    private val downloading = mutableSetOf<String>()

    fun resolve(themeId: String, relativePath: String): URL? =
        store().resolve(themeId, relativePath)

    fun openStream(themeId: String, relativePath: String) =
        resolve(themeId, relativePath)?.openStream()

    fun isThemeInstalled(themeId: String): Boolean =
        store().isInstalled(themeId)

    fun state(themeId: String): CphThemePackageState {
        synchronized(lock) {
            if (themeId in downloading) {
                return CphThemePackageState(
                    status = CphThemePackageStatus.DOWNLOADING,
                    installedVersion = store().installedVersion(themeId),
                    remoteVersion = remoteManifests[themeId]?.version,
                )
            }
            remoteManifests[themeId]?.let { manifest ->
                if (!isCompatible(manifest)) {
                    return CphThemePackageState(
                        status = CphThemePackageStatus.INCOMPATIBLE,
                        installedVersion = store().installedVersion(themeId),
                        remoteVersion = manifest.version,
                        message = "Requires CPH ${manifest.minPluginVersion} or newer.",
                    )
                }
                val installed = store().installedVersion(themeId)
                if (installed == null) {
                    return CphThemePackageState(
                        status = CphThemePackageStatus.NOT_INSTALLED,
                        remoteVersion = manifest.version,
                    )
                }
                if (CphVersionComparator.compare(manifest.version, installed) > 0) {
                    return CphThemePackageState(
                        status = CphThemePackageStatus.UPDATE_AVAILABLE,
                        installedVersion = installed,
                        remoteVersion = manifest.version,
                    )
                }
            }
            failures[themeId]?.let { message ->
                if (!store().isInstalled(themeId)) {
                    return CphThemePackageState(
                        status = CphThemePackageStatus.FAILED,
                        message = message,
                    )
                }
            }
        }
        val installed = store().installedVersion(themeId)
        return if (installed == null) {
            CphThemePackageState(CphThemePackageStatus.NOT_INSTALLED)
        } else {
            CphThemePackageState(CphThemePackageStatus.INSTALLED, installedVersion = installed)
        }
    }

    fun checkForUpdatesAsync(onDone: () -> Unit = {}) {
        ApplicationManager.getApplication().executeOnPooledThread {
            runCatching {
                val manifest = fetchAveMujicaManifest()
                synchronized(lock) {
                    remoteManifests[manifest.themeId] = manifest
                    failures.remove(manifest.themeId)
                }
                CphPluginSettings.getInstance().state.themeUpdateCheckedAtMillis = System.currentTimeMillis()
            }.onFailure { error ->
                synchronized(lock) {
                    failures[CphThemeId.AVE_MUJICA] = error.message ?: error.javaClass.simpleName
                }
            }
            ApplicationManager.getApplication().invokeLater(onDone)
        }
    }

    fun installOrUpdateAveMujica(project: Project, onDone: (Boolean) -> Unit) {
        synchronized(lock) {
            if (!downloading.add(CphThemeId.AVE_MUJICA)) return
            failures.remove(CphThemeId.AVE_MUJICA)
        }
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, CphText.current().installingAveMujicaTheme, false) {
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                runCatching {
                    val manifest = synchronized(lock) { remoteManifests[CphThemeId.AVE_MUJICA] }
                        ?: fetchAveMujicaManifest().also {
                            synchronized(lock) { remoteManifests[it.themeId] = it }
                        }
                    if (!isCompatible(manifest)) {
                        throw IOException("Theme package requires CPH ${manifest.minPluginVersion} or newer.")
                    }
                    val packageFile = downloadPackage(manifest, indicator)
                    store().installPackage(manifest, packageFile)
                }.onSuccess {
                    synchronized(lock) {
                        failures.remove(CphThemeId.AVE_MUJICA)
                        downloading.remove(CphThemeId.AVE_MUJICA)
                    }
                    notifyTheme(CphText.current().aveMujicaThemeInstalled, NotificationType.INFORMATION)
                    ApplicationManager.getApplication().invokeLater { onDone(true) }
                }.onFailure { error ->
                    synchronized(lock) {
                        failures[CphThemeId.AVE_MUJICA] = error.message ?: error.javaClass.simpleName
                        downloading.remove(CphThemeId.AVE_MUJICA)
                    }
                    notifyTheme(CphText.current().aveMujicaThemeInstallFailed(error.message ?: error.javaClass.simpleName), NotificationType.ERROR)
                    ApplicationManager.getApplication().invokeLater { onDone(false) }
                }
            }

            override fun onFinished() {
                synchronized(lock) {
                    downloading.remove(CphThemeId.AVE_MUJICA)
                }
            }
        })
    }

    private fun store(): CphThemeAssetStore =
        CphThemeAssetStore(defaultThemesRoot(), CphPluginSettings.getInstance().state)

    private fun fetchManifest(url: String): CphThemePackageManifest {
        val text = readUrl(url)
        return CphThemePackageJson.parse(text)
    }

    private fun fetchAveMujicaManifest(): CphThemePackageManifest {
        val errors = mutableListOf<String>()
        for (url in aveMujicaManifestUrls()) {
            val manifest = runCatching { fetchManifest(url) }
                .onFailure { errors += "${url}: ${it.message ?: it.javaClass.simpleName}" }
                .getOrNull()
            if (manifest != null) return manifest
        }
        throw IOException("Ave Mujica theme manifest unavailable. ${errors.joinToString("; ")}")
    }

    private fun downloadPackage(manifest: CphThemePackageManifest, indicator: ProgressIndicator): Path {
        val tempDir = defaultThemesRoot().resolve(".downloads")
        tempDir.createDirectories()
        val target = tempDir.resolve("${manifest.themeId}-${manifest.version}-${System.nanoTime()}.zip")
        httpRequest(manifest.packageUrl).connect { request ->
            request.inputStream.use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var copied = 0L
                    while (true) {
                        indicator.checkCanceled()
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        copied += read
                        if (manifest.sizeBytes > 0) {
                            indicator.fraction = copied.toDouble() / manifest.sizeBytes
                        }
                    }
                }
            }
        }
        return target
    }

    private fun readUrl(url: String): String {
        return httpRequest(url).connect { request ->
            val out = ByteArrayOutputStream()
            request.inputStream.use { input -> input.copyTo(out) }
            out.toString(StandardCharsets.UTF_8)
        }
    }

    private fun httpRequest(url: String) =
        HttpRequests.request(url)
            .connectTimeout(HTTP_TIMEOUT_MILLIS)
            .readTimeout(HTTP_TIMEOUT_MILLIS)
            .followRedirects(true)
            .useProxy(true)
            .productNameAsUserAgent()
            .throwStatusCodeException(true)

    private fun isCompatible(manifest: CphThemePackageManifest): Boolean =
        CphVersionComparator.compare(currentPluginVersion(), manifest.minPluginVersion) >= 0

    private fun currentPluginVersion(): String =
        PluginManagerCore.getPlugin(PluginId.getId(CPH_PLUGIN_ID))?.version ?: "0.0.0"

    private fun defaultThemesRoot(): Path =
        PathManager.getConfigDir().resolve("cph").resolve("themes")

    private fun notifyTheme(message: String, type: NotificationType) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup(CPH_NOTIFICATION_GROUP_ID)
            .createNotification(message, type)
            .notify(null)
    }

    companion object {
        private const val AVE_MUJICA_MANIFEST_URL =
            "https://github.com/kkkzbh/CPH/releases/download/theme-avemujica/cph-theme-avemujica.json"
        private const val HTTP_TIMEOUT_MILLIS = 15_000
        private const val CPH_PLUGIN_ID = "org.kkkzbh.cph"
        private const val CPH_NOTIFICATION_GROUP_ID = "CPH Target Runner"

        fun aveMujicaManifestUrls(): List<String> =
            listOf(AVE_MUJICA_MANIFEST_URL)

        fun getInstance(): CphThemeAssetService =
            ApplicationManager.getApplication().getService(CphThemeAssetService::class.java)
    }
}
