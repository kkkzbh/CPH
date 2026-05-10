package org.kkkzbh.cph

import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.Project
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

internal data class CphCaseRunOptions(
    val timeoutMillis: Long,
    val ignoreTrailingWhitespace: Boolean,
    val compareExpectedOutput: Boolean,
    val noExpectedMode: Boolean,
)

internal data class CphCaseRunCompletion(
    val testCase: CphTestCase,
    val result: CphCaseResult,
    val completedCases: Int,
    val totalCases: Int,
)

internal data class CphSampleRunSummary(
    val completedCases: Int,
    val completedAllCases: Boolean,
)

internal class CphSampleRunSession(
    project: Project,
    parallelism: Int,
) : AutoCloseable {
    private val effectiveParallelism = parallelism.coerceAtLeast(1)
    private val caseExecutor = newExecutor("CPH case runner", effectiveParallelism)
    private val processIoExecutor = CphProcessRunner.newIoExecutor(
        "CPH process IO",
        (effectiveParallelism * 2).coerceAtLeast(2),
    )
    private val runner = CphRunner(project, CphProcessRunner(processIoExecutor))

    fun run(
        preparedTarget: CphPreparedRunTarget,
        cases: List<CphTestCase>,
        options: CphCaseRunOptions,
        indicator: ProgressIndicator,
        onCaseCompleted: (CphCaseRunCompletion) -> Unit,
    ): CphSampleRunSummary {
        if (cases.isEmpty()) return CphSampleRunSummary(0, completedAllCases = true)
        val completionService = ExecutorCompletionService<Pair<CphTestCase, CphCaseResult>>(caseExecutor)
        cases.forEach { testCase ->
            completionService.submit {
                val result = runCatching {
                    runner.runPreparedCase(
                        preparedTarget,
                        testCase,
                        options.timeoutMillis,
                        options.ignoreTrailingWhitespace,
                        compareExpectedOutput = options.compareExpectedOutput,
                    ).let {
                        if (options.noExpectedMode) CphStatusMapper.normalizeNoExpectedResult(it) else it
                    }
                }.getOrElse { error ->
                    CphCaseResult(
                        verdict = CphVerdict.ERROR,
                        message = error.message ?: error.javaClass.simpleName,
                    )
                }
                testCase to result
            }
        }

        var completedCases = 0
        var interrupted = false
        try {
            while (completedCases < cases.size && !indicator.isCanceled) {
                val (testCase, result) = completionService.take().get()
                completedCases += 1
                onCaseCompleted(
                    CphCaseRunCompletion(
                        testCase = testCase,
                        result = result,
                        completedCases = completedCases,
                        totalCases = cases.size,
                    ),
                )
            }
        } catch (e: InterruptedException) {
            interrupted = true
            Thread.currentThread().interrupt()
        } finally {
            close()
        }

        return CphSampleRunSummary(
            completedCases = completedCases,
            completedAllCases = completedCases == cases.size && !indicator.isCanceled && !interrupted,
        )
    }

    override fun close() {
        shutdown(caseExecutor)
        shutdown(processIoExecutor)
    }

    private fun shutdown(executor: ExecutorService) {
        executor.shutdownNow()
        try {
            executor.awaitTermination(1, TimeUnit.SECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    companion object {
        private val threadCounter = AtomicLong()

        private fun newExecutor(threadName: String, threadCount: Int): ExecutorService {
            return Executors.newFixedThreadPool(threadCount.coerceAtLeast(1)) { task ->
                Thread(task, "$threadName-${threadCounter.incrementAndGet()}").apply {
                    isDaemon = true
                }
            }
        }
    }
}
