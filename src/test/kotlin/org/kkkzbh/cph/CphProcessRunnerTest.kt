package org.kkkzbh.cph

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.TimeUnit

class CphProcessRunnerTest {
    @Test
    fun processRunnerReadsOutputThroughInjectedExecutor() {
        val executor = RecordingExecutorService()
        val process = TestProcess(stdout = "out", stderr = "err")
        val runner = CphProcessRunner(executor)

        val result = runner.execute(
            startProcess = { process },
            stdin = "input",
            timeoutMillis = 1_000,
        )

        assertEquals(0, result.exitCode)
        assertEquals("out", result.stdout)
        assertEquals("err", result.stderr)
        assertEquals("input", process.stdinText())
        assertFalse(result.timedOut)
        assertEquals(2, executor.executions)
    }

    @Test
    fun processRunnerDestroysTimedOutProcess() {
        val process = TestProcess(waitResult = false)
        val runner = CphProcessRunner(RecordingExecutorService())

        val result = runner.execute(
            startProcess = { process },
            timeoutMillis = 1,
        )

        assertTrue(result.timedOut)
        assertTrue(process.destroyedForcibly)
    }

    @Test
    fun processRunnerDestroysProcessAndRestoresInterruptFlagWhenInterrupted() {
        val process = TestProcess(waitInterrupted = true)
        val runner = CphProcessRunner(RecordingExecutorService())

        try {
            runner.execute(
                startProcess = { process },
                timeoutMillis = 1_000,
            )
            fail("Expected InterruptedException")
        } catch (_: InterruptedException) {
            assertTrue(process.destroyedForcibly)
            assertTrue(Thread.currentThread().isInterrupted)
        } finally {
            Thread.interrupted()
        }
    }

    private class RecordingExecutorService : AbstractExecutorService() {
        var executions = 0
            private set
        private var shutdown = false

        override fun execute(command: Runnable) {
            executions += 1
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
        private val waitInterrupted: Boolean = false,
    ) : Process() {
        private val stdin = ByteArrayOutputStream()
        private val stdoutStream = ByteArrayInputStream(stdout.toByteArray())
        private val stderrStream = ByteArrayInputStream(stderr.toByteArray())
        var destroyedForcibly = false
            private set

        fun stdinText(): String = stdin.toString()

        override fun getOutputStream(): OutputStream = stdin

        override fun getInputStream(): InputStream = stdoutStream

        override fun getErrorStream(): InputStream = stderrStream

        override fun waitFor(): Int {
            if (waitInterrupted) throw InterruptedException("interrupted")
            return exitCode
        }

        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean {
            if (waitInterrupted) throw InterruptedException("interrupted")
            return waitResult
        }

        override fun exitValue(): Int = exitCode

        override fun destroy() {
            destroyedForcibly = true
        }

        override fun destroyForcibly(): Process {
            destroyedForcibly = true
            return this
        }

        override fun isAlive(): Boolean = !waitResult && !destroyedForcibly || waitInterrupted && !destroyedForcibly
    }
}
