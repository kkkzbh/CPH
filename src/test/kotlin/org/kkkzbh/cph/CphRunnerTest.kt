package org.kkkzbh.cph

import com.intellij.execution.configurations.GeneralCommandLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import java.io.File
import java.nio.charset.StandardCharsets

class CphRunnerTest {
    @Test
    fun cppFileLauncherAcceptsDirectLauncherState() {
        val state = DirectLauncherState()

        assertSame(state, CphRunner.cppFileLauncher(state))
    }

    @Test
    fun cppFileLauncherAcceptsWrappedLauncherState() {
        val launcher = DirectLauncherState()
        val state = WrappedLauncherState(launcher)

        assertSame(launcher, CphRunner.cppFileLauncher(state))
    }

    @Test
    fun cppFileLauncherRejectsInvalidState() {
        assertNull(CphRunner.cppFileLauncher(InvalidState()))
    }

    @Test
    fun cppFileDebugConfigurationKeepsOriginalName() {
        assertSameName(
            "main.cpp",
            CphRunner.debugConfigurationName(CphTargetKind.CPP_FILE, "main.cpp", "Case 1"),
        )
    }

    @Test
    fun cmakeDebugConfigurationUsesCphDebugName() {
        assertSameName(
            "CPH Debug: app / Case 1",
            CphRunner.debugConfigurationName(CphTargetKind.CMAKE_APP, "app", "Case 1"),
        )
    }

    @Test
    fun commandLineCopyPreservesExecutionSettings() {
        val workingDirectory = File(System.getProperty("java.io.tmpdir")).absoluteFile
        val original = GeneralCommandLine("/bin/echo", "hello")
            .withWorkDirectory(workingDirectory)
            .withEnvironment("CPH_TEST_ENV", "1")
            .withCharset(StandardCharsets.UTF_16)
            .withRedirectErrorStream(true)
            .withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.NONE)

        val copy = copyCphCommandLine(original)
        copy.addParameter("copy-only")

        assertNotSame(original, copy)
        assertEquals(original.exePath, copy.exePath)
        assertEquals(original.workDirectory, copy.workDirectory)
        assertEquals(listOf("hello"), original.parametersList.parameters)
        assertEquals(listOf("hello", "copy-only"), copy.parametersList.parameters)
        assertEquals(original.environment["CPH_TEST_ENV"], copy.environment["CPH_TEST_ENV"])
        assertEquals(original.charset, copy.charset)
        assertEquals(original.isRedirectErrorStream, copy.isRedirectErrorStream)
        assertEquals(original.parentEnvironmentType, copy.parentEnvironmentType)
    }

    private open class LauncherBase {
        @Suppress("unused")
        protected fun createCommandLine(
            state: Any?,
            runFile: Any?,
            environment: Any?,
            usePty: Boolean,
            parseColors: Boolean,
        ): Any = listOf(state, runFile, environment, usePty, parseColors)
    }

    private class DirectLauncherState : LauncherBase() {
        @Suppress("unused")
        fun getRunFileAndEnvironment(): Any = Any()
    }

    private class WrappedLauncherState(private val launcher: DirectLauncherState) {
        @Suppress("unused")
        fun getLauncher(): DirectLauncherState = launcher
    }

    private class InvalidState

    private fun assertSameName(expected: String, actual: String) {
        org.junit.Assert.assertEquals(expected, actual)
    }
}
