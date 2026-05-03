package org.kkkzbh.cph.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class CphCppFileRunConfigurationFactoryTest {
    @Test
    fun readsSourcePathFromConfigurationFilePathGetter() {
        assertEquals(
            "/project/main.cpp",
            CphCppFileRunConfigurationFactory.readSourceFile(ConfigurationWithFilePath("/project/main.cpp")),
        )
    }

    @Test
    fun readsSourcePathFromOptionsSourcePathGetter() {
        assertEquals(
            "/project/main.cpp",
            CphCppFileRunConfigurationFactory.readSourceFile(ConfigurationWithOptions(OptionsWithSourcePath("/project/main.cpp"))),
        )
    }

    @Test
    fun readsWorkingDirectoryFromConfigurationGetter() {
        assertEquals(
            "/project/.cph",
            CphCppFileRunConfigurationFactory.readWorkingDirectory(ConfigurationWithWorkingDirectory("/project/.cph")),
        )
    }

    @Test
    fun appliesWorkingDirectoryThroughConfigurationSetter() {
        val configuration = ConfigurationWithWorkingDirectory("")

        assertTrue(CphCppFileRunConfigurationFactory.applyWorkingDirectory(configuration, "/project/.cph"))
        assertEquals("/project/.cph", configuration.getWorkingDirectory())
        assertFalse(CphCppFileRunConfigurationFactory.applyWorkingDirectory(configuration, "/project/.cph"))
    }

    @Test
    fun appliesWorkingDirectoryThroughOptionsSetter() {
        val options = OptionsWithWorkingDirectory("")

        assertTrue(CphCppFileRunConfigurationFactory.applyWorkingDirectory(ConfigurationWithOptions(options), "/project/.cph"))
        assertEquals("/project/.cph", options.getWorkingDirectory())
    }

    @Test
    fun ensureWorkingDirectoryExistsCreatesMissingDirectory() {
        val root = Files.createTempDirectory("cph-working-dir-test").toFile()
        try {
            val nested = root.resolve(".cph")

            CphCppFileRunConfigurationFactory.ensureWorkingDirectoryExists(nested.path)

            assertTrue(nested.isDirectory)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun sourcePathsEqualNormalizesFileSchemeAndSlashes() {
        assertTrue(
            CphCppFileRunConfigurationFactory.sourcePathsEqual(
                "file:///project/main.cpp?refresh=1",
                "/project/main.cpp",
            ),
        )
        assertTrue(
            CphCppFileRunConfigurationFactory.sourcePathsEqual(
                "C:\\project\\main.cpp",
                "C:/project/main.cpp",
            ),
        )
        assertFalse(
            CphCppFileRunConfigurationFactory.sourcePathsEqual(
                "/project/a.cpp",
                "/project/b.cpp",
            ),
        )
    }

    private class ConfigurationWithFilePath(private val filePath: String) {
        @Suppress("unused")
        fun getFilePath(): String = filePath
    }

    private class ConfigurationWithOptions(private val options: Any) {
        @Suppress("unused")
        fun getOptions(): Any = options
    }

    private class OptionsWithSourcePath(private val sourcePath: String) {
        @Suppress("unused")
        fun getSourcePath(): String = sourcePath
    }

    private class ConfigurationWithWorkingDirectory(private var workingDirectory: String) {
        @Suppress("unused")
        fun getWorkingDirectory(): String = workingDirectory

        @Suppress("unused")
        fun setWorkingDirectory(value: String) {
            workingDirectory = value
        }
    }

    private class OptionsWithWorkingDirectory(private var workingDirectory: String) {
        @Suppress("unused")
        fun getWorkingDirectory(): String = workingDirectory

        @Suppress("unused")
        fun setWorkingDirectory(value: String) {
            workingDirectory = value
        }
    }
}
