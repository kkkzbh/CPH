package org.kkkzbh.cph

import com.intellij.execution.ExecutionException
import com.intellij.execution.ExecutionTarget
import com.intellij.execution.ExecutionTargetManager
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.process.ProcessAdapter
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessOutputTypes
import com.intellij.execution.runners.ExecutionEnvironmentBuilder
import com.intellij.execution.runners.ProgramRunner
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

class CphRunner(private val project: Project) {
    private val log = Logger.getInstance(CphRunner::class.java)
    private val preparedBuilds = linkedSetOf<String>()

    fun runCase(
        identity: CphTargetIdentity,
        testCase: CphTestCase,
        timeoutMillis: Long,
        ignoreTrailingWhitespace: Boolean,
    ): CphCaseResult {
        val settings = identity.settings
            ?: return CphCaseResult(CphVerdict.ERROR, message = identity.message)
        if (!identity.runnable) {
            return CphCaseResult(CphVerdict.ERROR, message = identity.message)
        }

        return runExecutableFallback(settings, testCase, timeoutMillis, ignoreTrailingWhitespace, null)
    }

    private fun startProcess(
        settings: com.intellij.execution.RunnerAndConfigurationSettings,
        resultFuture: java.util.concurrent.CompletableFuture<CphCaseResult>,
        stdout: StringBuilder,
        stderr: StringBuilder,
        startedAt: Long,
    ): ProcessHandler {
        val executor = DefaultRunExecutor.getRunExecutorInstance()
        val configuration = settings.configuration
        val runner = ProgramRunner.getRunner(executor.id, configuration)
            ?: throw ExecutionException("No runner is available for ${settings.name}.")

        val executionResult: com.intellij.execution.ExecutionResult = if (ApplicationManager.getApplication().isDispatchThread) {
            val environment = buildEnvironment(settings, runner)
            settings.checkSettings(executor)
            val state = configuration.getState(executor, environment)
                ?: throw ExecutionException("Run configuration did not provide a run state.")
            state.execute(executor, runner) ?: throw ExecutionException("Run configuration did not start.")
        } else {
            val holder = arrayOfNulls<com.intellij.execution.ExecutionResult>(1)
            val error = arrayOfNulls<Throwable>(1)
            ApplicationManager.getApplication().invokeAndWait {
                try {
                    val environment = buildEnvironment(settings, runner)
                    settings.checkSettings(executor)
                    val state = configuration.getState(executor, environment)
                        ?: throw ExecutionException("Run configuration did not provide a run state.")
                    holder[0] = state.execute(executor, runner)
                } catch (e: Throwable) {
                    error[0] = e
                }
            }
            error[0]?.let { throw it }
            holder[0] ?: throw ExecutionException("Run configuration did not start.")
        }

        val processHandler = executionResult.processHandler
            ?: throw ExecutionException("Run configuration did not provide a process handler.")

        processHandler.addProcessListener(object : ProcessAdapter() {
            override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
                when (outputType) {
                    ProcessOutputTypes.STDOUT -> stdout.append(event.text)
                    ProcessOutputTypes.STDERR -> stderr.append(event.text)
                }
            }

            override fun processTerminated(event: ProcessEvent) {
                val exitCode = event.exitCode
                resultFuture.complete(
                    CphCaseResult(
                        verdict = if (exitCode == 0) CphVerdict.NOT_RUN else CphVerdict.RE,
                        actualOutput = stdout.toString(),
                        stderr = stderr.toString(),
                        exitCode = exitCode,
                        durationMillis = elapsedMillis(startedAt),
                        message = if (exitCode == 0) "Process finished." else "Process exited with code $exitCode.",
                    ),
                )
            }
        })

        return processHandler
    }

    private fun runExecutableFallback(
        settings: com.intellij.execution.RunnerAndConfigurationSettings,
        testCase: CphTestCase,
        timeoutMillis: Long,
        ignoreTrailingWhitespace: Boolean,
        originalError: Throwable?,
    ): CphCaseResult {
        val setupStartedAt = System.nanoTime()
        return try {
            val launchPlan = resolveCMakeLaunchPlan(settings)
                ?: throw ExecutionException("Cannot resolve executable for '${settings.name}': ${originalError?.message.orEmpty()}")
            val buildResult = prepareBuildTarget(settings, launchPlan)
            if (buildResult != null) return buildResult

            val executable = launchPlan.executable
            if (!executable.isFile || !executable.canExecute()) {
                throw ExecutionException("Executable is not ready: ${executable.absolutePath}")
            }

            val startedAt = System.nanoTime()
            val process = ProcessBuilder(executable.absolutePath)
                .directory(File(project.basePath ?: executable.parentFile.absolutePath))
                .start()

            val stdoutFuture = java.util.concurrent.CompletableFuture.supplyAsync {
                process.inputStream.bufferedReader().readText()
            }
            val stderrFuture = java.util.concurrent.CompletableFuture.supplyAsync {
                process.errorStream.bufferedReader().readText()
            }

            process.outputStream.use { stream ->
                stream.write(testCase.input.toByteArray(StandardCharsets.UTF_8))
                stream.flush()
            }

            val finished = process.waitFor(timeoutMillis.coerceAtLeast(1) + 100, TimeUnit.MILLISECONDS)
            if (!finished) {
                process.destroyForcibly()
                CphCaseResult(
                    verdict = CphVerdict.TLE,
                    actualOutput = stdoutFuture.getNow(""),
                    stderr = stderrFuture.getNow(""),
                    durationMillis = elapsedMillis(startedAt),
                    message = "Time limit exceeded after ${timeoutMillis}ms.",
                )
            } else {
                val actualOutput = stdoutFuture.get(1, TimeUnit.SECONDS)
                val stderr = stderrFuture.get(1, TimeUnit.SECONDS)
                val exitCode = process.exitValue()
                if (exitCode != 0) {
                    CphCaseResult(
                        verdict = CphVerdict.RE,
                        actualOutput = actualOutput,
                        stderr = stderr,
                        exitCode = exitCode,
                        durationMillis = elapsedMillis(startedAt),
                        message = "Process exited with code $exitCode.",
                    )
                } else {
                    val (accepted, message) = CphComparator.compare(
                        actual = actualOutput,
                        expected = testCase.expectedOutput,
                        ignoreTrailingWhitespace = ignoreTrailingWhitespace,
                    )
                    CphCaseResult(
                        verdict = if (accepted) CphVerdict.AC else CphVerdict.WA,
                        actualOutput = actualOutput,
                        stderr = stderr,
                        exitCode = exitCode,
                        durationMillis = elapsedMillis(startedAt),
                        message = "$message (fallback executable runner)",
                    )
                }
            }
        } catch (e: Exception) {
            CphCaseResult(
                verdict = CphVerdict.ERROR,
                durationMillis = elapsedMillis(setupStartedAt),
                message = e.message ?: e.javaClass.simpleName,
            )
        }
    }

    private data class CMakeLaunchPlan(
        val executable: File,
        val buildWorkingDir: File?,
        val buildTargetName: String?,
    )

    private data class CommandResult(
        val exitCode: Int,
        val stdout: String,
        val stderr: String,
    )

    private enum class BuildFreshness {
        UP_TO_DATE,
        STALE,
        UNKNOWN,
    }

    private fun prepareBuildTarget(
        settings: com.intellij.execution.RunnerAndConfigurationSettings,
        launchPlan: CMakeLaunchPlan,
    ): CphCaseResult? {
        val buildDir = launchPlan.buildWorkingDir ?: return null
        val key = listOf(buildDir.absolutePath, launchPlan.buildTargetName.orEmpty()).joinToString("|")
        if (!preparedBuilds.add(key)) return null
        return buildTarget(settings, launchPlan)
    }

    private fun buildTarget(
        settings: com.intellij.execution.RunnerAndConfigurationSettings,
        launchPlan: CMakeLaunchPlan,
    ): CphCaseResult? {
        val buildDir = launchPlan.buildWorkingDir ?: return null
        if (!buildDir.isDirectory) return null
        val targetName = launchPlan.buildTargetName

        when (buildFreshness(buildDir, targetName)) {
            BuildFreshness.UP_TO_DATE -> {
                log.info("CPH build is up to date for '${settings.name}' in ${buildDir.absolutePath}")
                return null
            }
            BuildFreshness.STALE -> log.info("CPH build is stale for '${settings.name}' in ${buildDir.absolutePath}")
            BuildFreshness.UNKNOWN -> log.info("CPH build freshness is unknown for '${settings.name}', building conservatively")
        }

        val command = mutableListOf("cmake", "--build", buildDir.absolutePath)
        targetName?.let { command.addAll(listOf("--target", it)) }
        log.info("CPH building '${settings.name}' with: ${command.joinToString(" ")}")
        val startedAt = System.nanoTime()
        return try {
            val result = runCommand(command, File(project.basePath ?: buildDir.absolutePath), 120)
            if (result == null) {
                CphCaseResult(
                    verdict = CphVerdict.ERROR,
                    durationMillis = elapsedMillis(startedAt),
                    message = "Build timed out after 120s.",
                )
            } else if (result.exitCode != 0) {
                CphCaseResult(
                    verdict = CphVerdict.ERROR,
                    actualOutput = result.stdout,
                    stderr = result.stderr,
                    exitCode = result.exitCode,
                    durationMillis = elapsedMillis(startedAt),
                    message = if (targetName == null) {
                        "Build failed."
                    } else {
                        "Build failed for target '$targetName'."
                    },
                )
            } else {
                null
            }
        } catch (e: Exception) {
            CphCaseResult(
                verdict = CphVerdict.ERROR,
                durationMillis = elapsedMillis(startedAt),
                message = "Build failed: ${e.message ?: e.javaClass.simpleName}",
            )
        }
    }

    private fun buildFreshness(buildDir: File, targetName: String?): BuildFreshness {
        if (File(buildDir, "build.ninja").isFile) {
            val command = mutableListOf(readCMakeCacheValue(buildDir, "CMAKE_MAKE_PROGRAM") ?: "ninja", "-C", buildDir.absolutePath, "-n")
            targetName?.let(command::add)
            val result = runCommand(command, File(project.basePath ?: buildDir.absolutePath), 30)
                ?: return BuildFreshness.UNKNOWN
            if (result.exitCode != 0) return BuildFreshness.UNKNOWN
            val output = "${result.stdout}\n${result.stderr}"
            return if (output.contains("no work to do", ignoreCase = true)) {
                BuildFreshness.UP_TO_DATE
            } else {
                BuildFreshness.STALE
            }
        }

        if (File(buildDir, "Makefile").isFile && File(buildDir, "CMakeCache.txt").isFile) {
            val command = mutableListOf(readCMakeCacheValue(buildDir, "CMAKE_MAKE_PROGRAM") ?: "make", "-C", buildDir.absolutePath, "-q")
            targetName?.let(command::add)
            val result = runCommand(command, File(project.basePath ?: buildDir.absolutePath), 30)
                ?: return BuildFreshness.UNKNOWN
            return when (result.exitCode) {
                0 -> BuildFreshness.UP_TO_DATE
                1 -> BuildFreshness.STALE
                else -> BuildFreshness.UNKNOWN
            }
        }

        return BuildFreshness.UNKNOWN
    }

    private fun resolveCMakeLaunchPlan(
        settings: com.intellij.execution.RunnerAndConfigurationSettings,
    ): CMakeLaunchPlan? {
        val configuration = settings.configuration
        val target = runCatching { resolveExecutionTarget(settings) }.getOrNull()

        if (target != null) {
            val buildAndRun = runCatching {
                configuration.javaClass.methods.firstOrNull {
                    it.name == "getBuildAndRunConfigurations" &&
                        it.parameterCount == 1 &&
                        it.parameterTypes[0].isAssignableFrom(target.javaClass)
                }?.invoke(configuration, target)
            }.getOrNull()
            val file = invokeFile(buildAndRun, "getRunFile", project)
                ?: invokeFile(buildAndRun, "getRunFile")
            if (file != null) {
                val buildWorkingDir = invokeFileField(buildAndRun, "buildConfiguration", "getBuildWorkingDir")
                    ?: findCMakeBuildDir(file)
                return CMakeLaunchPlan(
                    executable = file,
                    buildWorkingDir = buildWorkingDir,
                    buildTargetName = readBuildTargetName(configuration, buildAndRun),
                )
            }
        }

        val executableData = runCatching {
            configuration.javaClass.methods.firstOrNull {
                it.name == "getExecutableData" && it.parameterCount == 0
            }?.invoke(configuration)
        }.getOrNull()
        val path = executableData?.javaClass?.fields?.firstOrNull { it.name == "path" }
            ?.get(executableData)
            ?.toString()
            ?.takeIf { it.isNotBlank() }
        return path?.let {
            val executable = File(it)
            CMakeLaunchPlan(
                executable = executable,
                buildWorkingDir = findCMakeBuildDir(executable),
                buildTargetName = readBuildTargetName(configuration, null),
            )
        }
    }

    private fun findCMakeBuildDir(executable: File): File? {
        var dir = executable.parentFile
        repeat(12) {
            if (dir == null) return null
            if (File(dir, "CMakeCache.txt").isFile || File(dir, "build.ninja").isFile) {
                return dir
            }
            dir = dir.parentFile
        }
        return null
    }

    private fun readCMakeCacheValue(buildDir: File, key: String): String? {
        val cache = File(buildDir, "CMakeCache.txt")
        if (!cache.isFile) return null
        return runCatching {
            cache.useLines { lines ->
                lines.firstNotNullOfOrNull { line ->
                    val separator = line.indexOf('=')
                    if (separator <= 0) return@firstNotNullOfOrNull null
                    val rawKey = line.substring(0, separator).substringBefore(':')
                    if (rawKey == key) line.substring(separator + 1).takeIf { it.isNotBlank() } else null
                }
            }
        }.getOrNull()
    }

    private fun runCommand(command: List<String>, workingDir: File, timeoutSeconds: Long): CommandResult? {
        val process = ProcessBuilder(command)
            .directory(workingDir)
            .start()
        val stdoutFuture = java.util.concurrent.CompletableFuture.supplyAsync {
            process.inputStream.bufferedReader().readText()
        }
        val stderrFuture = java.util.concurrent.CompletableFuture.supplyAsync {
            process.errorStream.bufferedReader().readText()
        }
        val finished = process.waitFor(timeoutSeconds.coerceAtLeast(1), TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            return null
        }
        return CommandResult(
            exitCode = process.exitValue(),
            stdout = stdoutFuture.get(1, TimeUnit.SECONDS),
            stderr = stderrFuture.get(1, TimeUnit.SECONDS),
        )
    }

    private fun readBuildTargetName(configuration: Any, buildAndRun: Any?): String? {
        val explicit = buildAndRun?.javaClass?.fields?.firstOrNull { it.name == "explicitBuildTargetName" }
            ?.get(buildAndRun)
            ?.toString()
            ?.takeIf { it.isNotBlank() }
        if (explicit != null) return explicit

        return runCatching {
            val target = configuration.javaClass.methods.firstOrNull {
                it.name == "getCMakeTarget" && it.parameterCount == 0
            }?.invoke(configuration)
            target?.javaClass?.methods?.firstOrNull {
                it.name == "getName" && it.parameterCount == 0
            }?.invoke(target)?.toString()
        }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    private fun invokeFileField(value: Any?, fieldName: String, methodName: String): File? {
        if (value == null) return null
        val nested = value.javaClass.fields.firstOrNull { it.name == fieldName }?.get(value) ?: return null
        return runCatching {
            nested.javaClass.methods.firstOrNull {
                it.name == methodName && it.parameterCount == 0
            }?.invoke(nested) as? File
        }.getOrNull()
    }

    private fun invokeFile(value: Any?, methodName: String, vararg args: Any): File? {
        if (value == null) return null
        val method = value.javaClass.methods.firstOrNull {
            it.name == methodName && it.parameterCount == args.size
        } ?: return null
        return runCatching { method.invoke(value, *args) as? File }.getOrNull()
    }

    private fun buildEnvironment(
        settings: com.intellij.execution.RunnerAndConfigurationSettings,
        runner: ProgramRunner<*>,
    ) = ExecutionEnvironmentBuilder(project, DefaultRunExecutor.getRunExecutorInstance())
        .runnerAndSettings(runner, settings)
        .target(resolveExecutionTarget(settings))
        .build()

    private fun resolveExecutionTarget(
        settings: com.intellij.execution.RunnerAndConfigurationSettings,
    ): ExecutionTarget {
        val activeTarget = ExecutionTargetManager.getActiveTarget(project)
        if (ExecutionTargetManager.canRun(settings, activeTarget)) {
            log.info("CPH using active execution target '${activeTarget.displayName}' for '${settings.name}'")
            return activeTarget
        }

        val manager = ExecutionTargetManager.getInstance(project)
        val foundTarget = manager.findTarget(settings.configuration)
        if (foundTarget != null && ExecutionTargetManager.canRun(settings, foundTarget)) {
            log.info("CPH using discovered execution target '${foundTarget.displayName}' for '${settings.name}'")
            return foundTarget
        }

        val candidate = ExecutionTargetManager.getTargetsToChooseFor(project, settings.configuration)
            .firstOrNull { ExecutionTargetManager.canRun(settings.configuration, it) }
        if (candidate != null) {
            log.info("CPH using candidate execution target '${candidate.displayName}' for '${settings.name}'")
            return candidate
        }

        throw ExecutionException(
            "No execution target can run '${settings.name}'. Active target is '${activeTarget.displayName}'.",
        )
    }

    private fun writeInput(processHandler: ProcessHandler, input: String) {
        val stream = processHandler.processInput ?: return
        stream.write(input.toByteArray(StandardCharsets.UTF_8))
        stream.flush()
        stream.close()
    }

    private fun elapsedMillis(startedAt: Long): Long {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)
    }
}
