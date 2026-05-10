package org.kkkzbh.cph

import com.intellij.execution.configurations.GeneralCommandLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.TimeUnit

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

    @Test
    fun caseProcessExecutorMapsAcceptedOutput() {
        val result = runMappedCase(stdout = "42\n", expectedOutput = "42\n")

        assertEquals(CphVerdict.AC, result.verdict)
        assertEquals("42\n", result.actualOutput)
        assertEquals(0, result.exitCode)
    }

    @Test
    fun caseProcessExecutorMapsWrongAnswer() {
        val result = runMappedCase(stdout = "41\n", expectedOutput = "42\n")

        assertEquals(CphVerdict.WA, result.verdict)
    }

    @Test
    fun caseProcessExecutorMapsRuntimeError() {
        val result = runMappedCase(stdout = "partial", stderr = "boom", exitCode = 7, expectedOutput = "partial")

        assertEquals(CphVerdict.RE, result.verdict)
        assertEquals(7, result.exitCode)
        assertEquals("boom", result.stderr)
    }

    @Test
    fun caseProcessExecutorMapsNoExpectedSuccess() {
        val result = runMappedCase(stdout = "anything", expectedOutput = "", compareExpectedOutput = false)

        assertEquals(CphVerdict.OK, result.verdict)
    }

    @Test
    fun caseProcessExecutorMapsTimeout() {
        val process = TestProcess(waitResult = false)
        val executor = CphCaseProcessExecutor(CphProcessRunner(DirectExecutorService()))

        val result = executor.run(
            startProcess = { process },
            input = "",
            expectedOutput = "",
            timeoutMillis = 1,
            ignoreTrailingWhitespace = true,
            compareExpectedOutput = true,
            runnerLabel = "test runner",
        )

        assertEquals(CphVerdict.TLE, result.verdict)
        assertSame(true, process.destroyedForcibly)
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

    private fun runMappedCase(
        stdout: String,
        expectedOutput: String,
        stderr: String = "",
        exitCode: Int = 0,
        compareExpectedOutput: Boolean = true,
    ): CphCaseResult {
        val executor = CphCaseProcessExecutor(CphProcessRunner(DirectExecutorService()))
        return executor.run(
            startProcess = { TestProcess(stdout = stdout, stderr = stderr, exitCode = exitCode) },
            input = "",
            expectedOutput = expectedOutput,
            timeoutMillis = 1_000,
            ignoreTrailingWhitespace = true,
            compareExpectedOutput = compareExpectedOutput,
            runnerLabel = "test runner",
        )
    }

    private class DirectExecutorService : AbstractExecutorService() {
        private var shutdown = false

        override fun execute(command: Runnable) {
            command.run()
        }

        override fun shutdown() {
            shutdown = true
        }

        override fun shutdownNow(): MutableList<Runnable> {
            shutdown = true
            return mutableListOf()
        }

        override fun isShutdown(): Boolean = shutdown

        override fun isTerminated(): Boolean = shutdown

        override fun awaitTermination(timeout: Long, unit: TimeUnit): Boolean = true
    }

    private class TestProcess(
        stdout: String = "",
        stderr: String = "",
        private val exitCode: Int = 0,
        private val waitResult: Boolean = true,
    ) : Process() {
        private val stdin = ByteArrayOutputStream()
        private val stdoutStream = ByteArrayInputStream(stdout.toByteArray())
        private val stderrStream = ByteArrayInputStream(stderr.toByteArray())
        var destroyedForcibly = false
            private set

        override fun getOutputStream(): OutputStream = stdin

        override fun getInputStream(): InputStream = stdoutStream

        override fun getErrorStream(): InputStream = stderrStream

        override fun waitFor(): Int = exitCode

        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean = waitResult

        override fun exitValue(): Int = exitCode

        override fun destroy() {
            destroyedForcibly = true
        }

        override fun destroyForcibly(): Process {
            destroyedForcibly = true
            return this
        }

        override fun isAlive(): Boolean = !waitResult && !destroyedForcibly
    }
}
