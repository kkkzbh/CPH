package org.kkkzbh.cph.server

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class CphCppFileRunConfigurationFactoryTest {
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
}
