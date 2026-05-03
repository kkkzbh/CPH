package org.kkkzbh.cph.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

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
}
