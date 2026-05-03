package org.kkkzbh.cph

import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

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
