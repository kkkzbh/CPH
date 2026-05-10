package org.kkkzbh.cph

import java.nio.charset.StandardCharsets
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

internal data class CphProcessExecutionResult(
    val exitCode: Int?,
    val stdout: String,
    val stderr: String,
    val durationMillis: Long,
    val timedOut: Boolean,
)

internal class CphProcessRunner(
    private val ioExecutor: ExecutorService? = null,
) {
    fun execute(
        startProcess: () -> Process,
        stdin: String = "",
        timeoutMillis: Long,
        timeoutGraceMillis: Long = 0,
    ): CphProcessExecutionResult {
        val executor = ioExecutor ?: newIoExecutor("CPH process IO", 2)
        val shutdownExecutor = ioExecutor == null
        val startedAt = System.nanoTime()
        var process: Process? = null
        var stdoutFuture: CompletableFuture<String>? = null
        var stderrFuture: CompletableFuture<String>? = null
        var completedNormally = false

        try {
            process = startProcess()
            stdoutFuture = readTextAsync(process.inputStream, executor)
            stderrFuture = readTextAsync(process.errorStream, executor)

            process.outputStream.use { stream ->
                stream.write(stdin.toByteArray(StandardCharsets.UTF_8))
                stream.flush()
            }

            val timeout = timeoutMillis.coerceAtLeast(1) + timeoutGraceMillis.coerceAtLeast(0)
            val finished = process.waitFor(timeout, TimeUnit.MILLISECONDS)
            if (!finished) {
                destroyProcess(process)
                waitForDestroyedProcess(process)
                return CphProcessExecutionResult(
                    exitCode = null,
                    stdout = stdoutFuture.getNow(""),
                    stderr = stderrFuture.getNow(""),
                    durationMillis = elapsedMillis(startedAt),
                    timedOut = true,
                )
            }

            val stdout = awaitOutput(stdoutFuture)
            val stderr = awaitOutput(stderrFuture)
            completedNormally = true
            return CphProcessExecutionResult(
                exitCode = process.exitValue(),
                stdout = stdout,
                stderr = stderr,
                durationMillis = elapsedMillis(startedAt),
                timedOut = false,
            )
        } catch (e: InterruptedException) {
            destroyProcess(process)
            cancelReader(stdoutFuture)
            cancelReader(stderrFuture)
            Thread.currentThread().interrupt()
            throw e
        } finally {
            if (!completedNormally) {
                destroyProcess(process)
                cancelReader(stdoutFuture)
                cancelReader(stderrFuture)
            }
            if (shutdownExecutor) {
                executor.shutdownNow()
                awaitExecutorTermination(executor)
            }
        }
    }

    private fun readTextAsync(
        stream: java.io.InputStream,
        executor: ExecutorService,
    ): CompletableFuture<String> {
        return CompletableFuture.supplyAsync({
            stream.bufferedReader().readText()
        }, executor)
    }

    private fun awaitOutput(future: Future<String>): String {
        return try {
            future.get(1, TimeUnit.SECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw e
        }
    }

    private fun destroyProcess(process: Process?) {
        if (process?.isAlive == true) {
            process.destroyForcibly()
        }
    }

    private fun waitForDestroyedProcess(process: Process?) {
        if (process?.isAlive != true) return
        try {
            process.waitFor(1, TimeUnit.SECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw e
        }
    }

    private fun cancelReader(future: Future<String>?) {
        future?.cancel(true)
    }

    private fun awaitExecutorTermination(executor: ExecutorService) {
        try {
            executor.awaitTermination(1, TimeUnit.SECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private fun elapsedMillis(startedAt: Long): Long {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)
    }

    companion object {
        private val threadCounter = AtomicLong()

        fun newIoExecutor(threadName: String, threadCount: Int): ExecutorService {
            return Executors.newFixedThreadPool(threadCount.coerceAtLeast(1)) { task ->
                Thread(task, "$threadName-${threadCounter.incrementAndGet()}").apply {
                    isDaemon = true
                }
            }
        }
    }
}

internal class CphCaseProcessExecutor(
    private val processRunner: CphProcessRunner,
) {
    fun run(
        startProcess: () -> Process,
        input: String,
        expectedOutput: String,
        timeoutMillis: Long,
        ignoreTrailingWhitespace: Boolean,
        compareExpectedOutput: Boolean,
        runnerLabel: String,
    ): CphCaseResult {
        val result = processRunner.execute(
            startProcess = startProcess,
            stdin = input,
            timeoutMillis = timeoutMillis,
            timeoutGraceMillis = 100,
        )
        if (result.timedOut) {
            return CphCaseResult(
                verdict = CphVerdict.TLE,
                actualOutput = result.stdout,
                stderr = result.stderr,
                durationMillis = result.durationMillis,
                message = "Time limit exceeded after ${timeoutMillis}ms.",
            )
        }

        val exitCode = result.exitCode ?: -1
        if (exitCode != 0) {
            return CphCaseResult(
                verdict = CphVerdict.RE,
                actualOutput = result.stdout,
                stderr = result.stderr,
                exitCode = exitCode,
                durationMillis = result.durationMillis,
                message = "Process exited with code $exitCode.",
            )
        }

        if (!compareExpectedOutput) {
            return CphCaseResult(
                verdict = CphVerdict.OK,
                actualOutput = result.stdout,
                stderr = result.stderr,
                exitCode = exitCode,
                durationMillis = result.durationMillis,
                message = "Process completed successfully ($runnerLabel)",
            )
        }

        val (accepted, message) = CphComparator.compare(
            actual = result.stdout,
            expected = expectedOutput,
            ignoreTrailingWhitespace = ignoreTrailingWhitespace,
        )
        return CphCaseResult(
            verdict = if (accepted) CphVerdict.AC else CphVerdict.WA,
            actualOutput = result.stdout,
            stderr = result.stderr,
            exitCode = exitCode,
            durationMillis = result.durationMillis,
            message = "$message ($runnerLabel)",
        )
    }
}
