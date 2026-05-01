package org.kkkzbh.cph

import com.intellij.execution.ExecutionException
import com.intellij.execution.ExecutionTarget
import com.intellij.execution.ExecutionTargetManager
import com.intellij.execution.Executor
import com.intellij.execution.BeforeRunTaskProvider
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.runners.ExecutionEnvironmentBuilder
import com.intellij.execution.runners.ProgramRunner
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

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

        return when (identity.kind) {
            CphTargetKind.CMAKE_APP -> runExecutableFallback(settings, testCase, timeoutMillis, ignoreTrailingWhitespace, null)
            CphTargetKind.CPP_FILE -> runCppFileConfiguration(settings, testCase, timeoutMillis, ignoreTrailingWhitespace)
            CphTargetKind.UNSUPPORTED -> CphCaseResult(CphVerdict.ERROR, message = identity.message)
        }
    }

    private fun runCppFileConfiguration(
        settings: com.intellij.execution.RunnerAndConfigurationSettings,
        testCase: CphTestCase,
        timeoutMillis: Long,
        ignoreTrailingWhitespace: Boolean,
    ): CphCaseResult {
        val setupStartedAt = System.nanoTime()
        return try {
            val context = buildDefaultRunContext(settings)
            val buildResult = buildCppFileTarget(settings, context.environment, setupStartedAt)
            if (buildResult != null) return buildResult

            val commandLine = resolveCppFileCommandLine(settings, context.executor, context.environment)
            runCommandLine(
                commandLine = commandLine,
                testCase = testCase,
                timeoutMillis = timeoutMillis,
                ignoreTrailingWhitespace = ignoreTrailingWhitespace,
                runnerLabel = "CLion C/C++ File runner",
            )
        } catch (e: Exception) {
            CphCaseResult(
                verdict = CphVerdict.ERROR,
                durationMillis = elapsedMillis(setupStartedAt),
                message = e.message ?: e.javaClass.simpleName,
            )
        }
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

            runLocalExecutable(
                executable = executable,
                workingDir = File(project.basePath ?: executable.parentFile.absolutePath),
                testCase = testCase,
                timeoutMillis = timeoutMillis,
                ignoreTrailingWhitespace = ignoreTrailingWhitespace,
                runnerLabel = "fallback executable runner",
            )
        } catch (e: Exception) {
            CphCaseResult(
                verdict = CphVerdict.ERROR,
                durationMillis = elapsedMillis(setupStartedAt),
                message = e.message ?: e.javaClass.simpleName,
            )
        }
    }

    private fun runLocalExecutable(
        executable: File,
        workingDir: File,
        testCase: CphTestCase,
        timeoutMillis: Long,
        ignoreTrailingWhitespace: Boolean,
        runnerLabel: String,
    ): CphCaseResult {
        return runProcess(
            startProcess = {
                ProcessBuilder(executable.absolutePath)
                    .directory(workingDir)
                    .start()
            },
            testCase = testCase,
            timeoutMillis = timeoutMillis,
            ignoreTrailingWhitespace = ignoreTrailingWhitespace,
            runnerLabel = runnerLabel,
        )
    }

    private fun runCommandLine(
        commandLine: GeneralCommandLine,
        testCase: CphTestCase,
        timeoutMillis: Long,
        ignoreTrailingWhitespace: Boolean,
        runnerLabel: String,
    ): CphCaseResult {
        return runProcess(
            startProcess = { commandLine.createProcess() },
            testCase = testCase,
            timeoutMillis = timeoutMillis,
            ignoreTrailingWhitespace = ignoreTrailingWhitespace,
            runnerLabel = runnerLabel,
        )
    }

    private fun runProcess(
        startProcess: () -> Process,
        testCase: CphTestCase,
        timeoutMillis: Long,
        ignoreTrailingWhitespace: Boolean,
        runnerLabel: String,
    ): CphCaseResult {
        val startedAt = System.nanoTime()
        val process = startProcess()

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
            return CphCaseResult(
                verdict = CphVerdict.TLE,
                actualOutput = stdoutFuture.getNow(""),
                stderr = stderrFuture.getNow(""),
                durationMillis = elapsedMillis(startedAt),
                message = "Time limit exceeded after ${timeoutMillis}ms.",
            )
        }

        val actualOutput = stdoutFuture.get(1, TimeUnit.SECONDS)
        val stderr = stderrFuture.get(1, TimeUnit.SECONDS)
        val exitCode = process.exitValue()
        if (exitCode != 0) {
            return CphCaseResult(
                verdict = CphVerdict.RE,
                actualOutput = actualOutput,
                stderr = stderr,
                exitCode = exitCode,
                durationMillis = elapsedMillis(startedAt),
                message = "Process exited with code $exitCode.",
            )
        }

        val (accepted, message) = CphComparator.compare(
            actual = actualOutput,
            expected = testCase.expectedOutput,
            ignoreTrailingWhitespace = ignoreTrailingWhitespace,
        )
        return CphCaseResult(
            verdict = if (accepted) CphVerdict.AC else CphVerdict.WA,
            actualOutput = actualOutput,
            stderr = stderr,
            exitCode = exitCode,
            durationMillis = elapsedMillis(startedAt),
            message = "$message ($runnerLabel)",
        )
    }

    private data class CMakeLaunchPlan(
        val executable: File,
        val buildWorkingDir: File?,
        val buildTargetName: String?,
    )

    private data class DefaultRunContext(
        val executor: Executor,
        val environment: ExecutionEnvironment,
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

    private fun buildCppFileTarget(
        settings: com.intellij.execution.RunnerAndConfigurationSettings,
        environment: ExecutionEnvironment,
        startedAt: Long,
    ): CphCaseResult? {
        return try {
            val configuration = settings.configuration
            val provider = cppFileBuildBeforeRunTaskProvider()
                ?: throw ExecutionException("Cannot find CLion C/C++ File build task provider.")
            val task = provider.createTask(configuration)
                ?: throw ExecutionException("Cannot create CLion C/C++ File build task for '${settings.name}'.")

            val executeTask = provider.javaClass.methods.firstOrNull {
                it.name == "executeTask" &&
                    it.parameterCount == 4 &&
                    it.parameterTypes[0].name == "com.intellij.openapi.actionSystem.DataContext" &&
                    it.parameterTypes[1].isAssignableFrom(configuration.javaClass) &&
                    it.parameterTypes[2].isAssignableFrom(environment.javaClass) &&
                    it.parameterTypes[3].isAssignableFrom(task.javaClass)
            } ?: throw ExecutionException("Cannot find CLion C/C++ File build executor.")

            val ok = executeTask.invoke(provider, DataContext.EMPTY_CONTEXT, configuration, environment, task) as? Boolean
                ?: false
            if (ok) {
                null
            } else {
                CphCaseResult(
                    verdict = CphVerdict.ERROR,
                    durationMillis = elapsedMillis(startedAt),
                    message = "Build failed for C/C++ File configuration '${settings.name}'.",
                )
            }
        } catch (e: Throwable) {
            val cause = invocationCause(e)
            CphCaseResult(
                verdict = CphVerdict.ERROR,
                durationMillis = elapsedMillis(startedAt),
                message = "Build failed for C/C++ File configuration '${settings.name}': ${cause.message ?: cause.javaClass.simpleName}",
            )
        }
    }

    private fun cppFileBuildBeforeRunTaskProvider(): BeforeRunTaskProvider<*>? {
        return BeforeRunTaskProvider.EP_NAME.getExtensions(project).firstOrNull {
            it.javaClass.name == "com.jetbrains.cidr.cpp.runfile.CppFileBuildBeforeRunTaskProvider"
        }
    }

    private fun resolveCppFileCommandLine(
        settings: com.intellij.execution.RunnerAndConfigurationSettings,
        executor: Executor,
        environment: ExecutionEnvironment,
    ): GeneralCommandLine {
        val configuration = settings.configuration
        val state = configuration.getState(executor, environment)
            ?: throw ExecutionException("Cannot create CLion run state for C/C++ File configuration '${settings.name}'.")
        val launcher = cppFileLauncher(state)
            ?: throw ExecutionException("Cannot access CLion C/C++ File launcher for '${settings.name}'.")

        val runFileAndEnvironment = launcher.javaClass.methods.firstOrNull {
            it.name == "getRunFileAndEnvironment" && it.parameterCount == 0
        }?.let { invokeReflective(it, launcher) }
            ?: throw ExecutionException("Cannot resolve CLion C/C++ File executable for '${settings.name}'.")
        val runFile = pairComponent(runFileAndEnvironment, "component1") as? File
            ?: throw ExecutionException("CLion C/C++ File launcher did not return an executable for '${settings.name}'.")
        if (!runFile.isFile) {
            throw ExecutionException("Executable is not ready: ${runFile.absolutePath}")
        }
        val cppEnvironment = pairComponent(runFileAndEnvironment, "component2")
            ?: throw ExecutionException("CLion C/C++ File launcher did not return a toolchain environment for '${settings.name}'.")

        val createCommandLine = findMethodInHierarchy(launcher.javaClass, "createCommandLine", 5)
            ?: throw ExecutionException("Cannot create CLion command line for C/C++ File configuration '${settings.name}'.")
        createCommandLine.isAccessible = true
        return invokeReflective(createCommandLine, launcher, state, runFile, cppEnvironment, false, false) as? GeneralCommandLine
            ?: throw ExecutionException("CLion C/C++ File launcher returned an invalid command line for '${settings.name}'.")
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

    private fun buildDefaultRunContext(
        settings: com.intellij.execution.RunnerAndConfigurationSettings,
    ): DefaultRunContext {
        val configuration = settings.configuration
        val executor = DefaultRunExecutor.getRunExecutorInstance()
        val runner = ProgramRunner.getRunner(executor.id, configuration)
            ?: throw ExecutionException("No runner is available for ${settings.name}.")
        return DefaultRunContext(
            executor = executor,
            environment = buildEnvironment(settings, runner, executor),
        )
    }

    private fun buildEnvironment(
        settings: com.intellij.execution.RunnerAndConfigurationSettings,
        runner: ProgramRunner<*>,
        executor: Executor = DefaultRunExecutor.getRunExecutorInstance(),
    ) = ExecutionEnvironmentBuilder(project, executor)
        .runnerAndSettings(runner, settings)
        .target(resolveExecutionTarget(settings))
        .build()

    private fun resolveExecutionTarget(
        settings: com.intellij.execution.RunnerAndConfigurationSettings,
    ): ExecutionTarget {
        val configuration = settings.configuration
        val activeTarget = ExecutionTargetManager.getActiveTarget(project)
        if (ExecutionTargetManager.canRun(configuration, activeTarget)) {
            log.info("CPH using active execution target '${activeTarget.displayName}' for '${settings.name}'")
            return activeTarget
        }

        val manager = ExecutionTargetManager.getInstance(project)
        val foundTarget = manager.findTarget(configuration)
        if (foundTarget != null && ExecutionTargetManager.canRun(configuration, foundTarget)) {
            log.info("CPH using discovered execution target '${foundTarget.displayName}' for '${settings.name}'")
            return foundTarget
        }

        val candidate = ExecutionTargetManager.getTargetsToChooseFor(project, configuration)
            .firstOrNull { ExecutionTargetManager.canRun(configuration, it) }
        if (candidate != null) {
            log.info("CPH using candidate execution target '${candidate.displayName}' for '${settings.name}'")
            return candidate
        }

        throw ExecutionException(
            "No execution target can run '${settings.name}'. Active target is '${activeTarget.displayName}'.",
        )
    }

    private fun elapsedMillis(startedAt: Long): Long {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)
    }

    private fun invocationCause(error: Throwable): Throwable {
        return if (error is InvocationTargetException && error.targetException != null) {
            error.targetException
        } else {
            error
        }
    }

    private fun pairComponent(pair: Any, componentName: String): Any? {
        val component = pair.javaClass.methods.firstOrNull {
            it.name == componentName && it.parameterCount == 0
        } ?: throw ExecutionException("Cannot read CLion launcher result $componentName.")
        return invokeReflective(component, pair)
    }

    private fun invokeReflective(method: Method, target: Any, vararg args: Any?): Any? {
        return try {
            method.invoke(target, *args)
        } catch (e: InvocationTargetException) {
            val cause = invocationCause(e)
            if (cause is Exception) {
                throw cause
            }
            throw ExecutionException(cause.message ?: cause.javaClass.simpleName)
        }
    }

    companion object {
        internal fun cppFileLauncher(state: Any): Any? {
            if (isCppFileLauncher(state)) return state

            val launcher = state.javaClass.methods.firstOrNull {
                it.name == "getLauncher" && it.parameterCount == 0
            }?.let { method ->
                runCatching { method.invoke(state) }.getOrNull()
            } ?: return null

            return launcher.takeIf(::isCppFileLauncher)
        }

        private fun isCppFileLauncher(candidate: Any): Boolean {
            val canResolveRunFile = candidate.javaClass.methods.any {
                it.name == "getRunFileAndEnvironment" && it.parameterCount == 0
            }
            return canResolveRunFile && findMethodInHierarchy(candidate.javaClass, "createCommandLine", 5) != null
        }

        private fun findMethodInHierarchy(type: Class<*>, name: String, parameterCount: Int): Method? {
            var current: Class<*>? = type
            while (current != null) {
                current.declaredMethods.firstOrNull {
                    it.name == name && it.parameterCount == parameterCount
                }?.let { return it }
                current = current.superclass
            }
            return null
        }
    }
}
