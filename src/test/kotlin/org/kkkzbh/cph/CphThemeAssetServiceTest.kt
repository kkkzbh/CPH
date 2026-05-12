package org.kkkzbh.cph

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.readText

class CphThemeAssetServiceTest {
    @Rule
    @JvmField
    val temp = TemporaryFolder()

    @Test
    fun themeManifestRoundTrips() {
        val manifest = CphThemePackageManifest(
            themeId = CphThemeId.AVE_MUJICA,
            version = "1.2.3",
            minPluginVersion = "1.1.0",
            packageUrl = "https://example.com/cph-theme-avemujica-1.2.3.zip",
            sha256 = "abcdef",
            sizeBytes = 123,
        )

        assertEquals(manifest, CphThemePackageJson.parse(CphThemePackageJson.render(manifest)))
    }

    @Test
    fun versionComparatorHandlesNumericVersions() {
        assertTrue(CphVersionComparator.compare("1.10.0", "1.2.9") > 0)
        assertEquals(0, CphVersionComparator.compare("1.1.0", "1.1.0"))
        assertTrue(CphVersionComparator.compare("1.0.9", "1.1.0") < 0)
    }

    @Test
    fun aveMujicaManifestUrlsUseStandaloneThemeReleaseOnly() {
        assertEquals(
            listOf(
                "https://github.com/kkkzbh/CPH/releases/download/theme-avemujica/cph-theme-avemujica.json",
            ),
            CphThemeAssetService.aveMujicaManifestUrls(),
        )
    }

    @Test
    fun themePackageInstallsAndResolvesAssets() {
        val state = CphPluginSettingsState()
        val store = CphThemeAssetStore(temp.newFolder("themes").toPath(), state)
        val zip = createThemeZip(
            mapOf(
                "icons/" to null,
                "icons/avemujica/" to null,
                "icons/avemujica/status/" to null,
                "icons/avemujica/status/512/" to null,
                "fonts/" to null,
                "theme-package.json" to """{"themeId":"avemujica","version":"1.1.3"}""",
                "icons/avemujica/status/512/ac.png" to "png-bytes",
                "fonts/AnglicanText.ttf" to "font-bytes",
            ),
        )
        val manifest = manifestFor(zip)

        store.installPackage(manifest, zip)

        assertEquals("1.1.3", state.installedThemeVersions[CphThemeId.AVE_MUJICA])
        val resolved = store.resolve(CphThemeId.AVE_MUJICA, "icons/avemujica/status/512/ac.png")
        assertNotNull(resolved)
        assertEquals("png-bytes", Path.of(resolved!!.toURI()).readText())
    }

    @Test(expected = java.io.IOException::class)
    fun damagedThemePackageHashIsRejected() {
        val state = CphPluginSettingsState()
        val store = CphThemeAssetStore(temp.newFolder("themes").toPath(), state)
        val zip = createThemeZip(mapOf("theme-package.json" to "{}"))
        val manifest = manifestFor(zip).copy(sha256 = "0000")

        store.installPackage(manifest, zip)
    }

    @Test(expected = java.io.IOException::class)
    fun unsafeThemePackageEntryIsRejected() {
        val state = CphPluginSettingsState()
        val store = CphThemeAssetStore(temp.newFolder("themes").toPath(), state)
        val zip = createThemeZip(
            mapOf(
                "theme-package.json" to "{}",
                "../escape.txt" to "bad",
            ),
        )
        val manifest = manifestFor(zip)

        store.installPackage(manifest, zip)
    }

    private fun createThemeZip(entries: Map<String, String?>): Path {
        val zip = temp.newFile("theme-${System.nanoTime()}.zip").toPath()
        ZipOutputStream(Files.newOutputStream(zip)).use { out ->
            entries.forEach { (name, content) ->
                out.putNextEntry(ZipEntry(name))
                if (content != null) {
                    out.write(content.toByteArray())
                }
                out.closeEntry()
            }
        }
        return zip
    }

    private fun manifestFor(zip: Path): CphThemePackageManifest =
        CphThemePackageManifest(
            themeId = CphThemeId.AVE_MUJICA,
            version = "1.1.3",
            minPluginVersion = "1.1.0",
            packageUrl = "https://example.com/cph-theme-avemujica-1.1.3.zip",
            sha256 = CphThemeAssetStore.sha256(zip),
            sizeBytes = Files.size(zip),
        )
}
