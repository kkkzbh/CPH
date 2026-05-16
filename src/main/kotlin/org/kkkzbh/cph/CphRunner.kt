package org.kkkzbh.cph

import com.intellij.execution.ExecutionException
import com.intellij.execution.ExecutionTarget
import com.intellij.execution.ExecutionTargetManager
import com.intellij.execution.Executor
import com.intellij.execution.BeforeRunTaskProvider
import com.intellij.execution.InputRedirectAware
import com.intellij.execution.ProgramRunnerUtil
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.runners.ExecutionEnvironmentBuilder
import com.intellij.execution.runners.ProgramRunner
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.components.service
import com.jetbrains.cidr.cpp.runfile.CppFileBuildBeforeRunTaskProvider
import com.jetbrains.cidr.cpp.runfile.CppFileBuildTargetsService
import com.jetbrains.cidr.cpp.runfile.CppFileRunConfiguration
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.concurrent.TimeUnit

internal data class CphCppFileBuildKey(
    val settingsName: String,
    val sourcePath: String,
    val compilerOptions: String,
    val toolchainName: String,
    val compilerFile: String,
    val workingDirectory: String,
    val sourceModified: Long,
    val sourceSize: Long,
)

@Service(Service.Level.PROJECT)
internal class CphCppFileBuildCache {
    private data class Entry(
        val key: CphCppFileBuildKey,
        val executablePath: String,
        val executableModified: Long,
    )

    private val entries = linkedMapOf<String, Entry>()

    fun matches(settingsName: String, key: CphCppFileBuildKey, executable: File): Boolean {
        val entry = entries[settingsName] ?: return false
        return entry.key == key &&
            entry.executablePath == executable.absolutePath &&
            entry.executableModified == executable.lastModified()
    }

    fun remember(settingsName: String, key: CphCppFileBuildKey, executable: File) {
        entries[settingsName] = Entry(
            key = key,
            executablePath = executable.absolutePath,
            executableModified = executable.lastModified(),
        )
    }
}

internal sealed class CphPreparedRunTarget {
    data class CMakeExecutable(
        val executable: File,
        val workingDir: File,
    ) : CphPreparedRunTarget()

    data class CppFile(
        val commandLine: GeneralCommandLine,
    ) : CphPreparedRunTarget()
}

internal data class CphRunPrepareDiagnostics(
    val buildMillis: Long = 0L,
    val buildSkippedByCphCache: Boolean = false,
    val commandLineResolveMillis: Long = 0L,
    val totalPrepareMillis: Long = 0L,
)

internal fun copyCphCommandLine(source: GeneralCommandLine): GeneralCommandLine = CphCommandLineCopy(source)

private class CphCommandLineCopy(source: GeneralCommandLine) : GeneralCommandLine(source)

internal sealed class CphRunPreparation {
    data class Ready(
        val target: CphPreparedRunTarget,
        val diagnostics: CphRunPrepareDiagnostics = CphRunPrepareDiagnostics(),
    ) : CphRunPreparation()
    data class Failed(val result: CphCaseResult) : CphRunPreparation()
}

internal class CphRunner(
    private val project: Project,
    private val processRunner: CphProcessRunner = CphProcessRunner(),
) {
    private val log = Logger.getInstance(CphRunner::class.java)
    private val cppFileBuildCache = project.service<CphCppFileBuildCache>()
    private val preparedBuilds = linkedMapOf<String, CphCaseResult?>()
    private val cppFileBuildLock = Any()
    private val caseProcessExecutor = CphCaseProcessExecutor(processRunner)

    fun runCase(
        identity: CphTargetIdentity,
        testCase: CphTestCase,
        timeoutMillis: Long,
        ignoreTrailingWhitespace: Boolean,
        compareExpectedOutput: Boolean = true,
    ): CphCaseResult {
        return when (val preparation = prepareForRun(identity)) {
            is CphRunPreparation.Failed -> preparation.result
            is CphRunPreparation.Ready -> runPreparedCase(
                preparation.target,
                testCase,
                timeoutMillis,
                ignoreTrailingWhitespace,
                compareExpectedOutput,
            )
        }
    }

    internal fun prepareForRun(identity: CphTargetIdentity): CphRunPreparation {
        val settings = identity.settings
            ?: return CphRunPreparation.Failed(CphCaseResult(CphVerdict.ERROR, message = identity.message))
        if (!identity.runnable) {
            return CphRunPreparation.Failed(CphCaseResult(CphVerdict.ERROR, message = identity.message))
        }

        return when (identity.kind) {
            CphTargetKind.CMAKE_APP -> prepareCMakeRunTarget(settings)
            CphTargetKind.CPP_FILE -> prepareCppFileRunTarget(settings)
            CphTargetKind.UNSUPPORTED -> CphRunPreparation.Failed(CphCaseResult(CphVerdict.ERROR, message = identity.message))
        }
    }

    internal fun runPreparedCase(
        target: CphPreparedRunTarget,
        testCase: CphTestCase,
        timeoutMillis: Long,
        ignoreTrailingWhitespace: Boolean,
        compareExpectedOutput: Boolean = true,
    ): CphCaseResult {
        val startedAt = System.nanoTime()
        return try {
            when (target) {
                is CphPreparedRunTarget.CMakeExecutable -> runLocalExecutable(
                    executable = target.executable,
                    workingDir = target.workingDir,
                    testCase = testCase,
                    timeoutMillis = timeoutMillis,
                    ignoreTrailingWhitespace = ignoreTrailingWhitespace,
                    compareExpectedOutput = compareExpectedOutput,
                    runnerLabel = "fallback executable runner",
                )
                is CphPreparedRunTarget.CppFile -> {
                    runCommandLine(
                        commandLine = copyCphCommandLine(target.commandLine),
                        testCase = testCase,
                        timeoutMillis = timeoutMillis,
                        ignoreTrailingWhitespace = ignoreTrailingWhitespace,
                        compareExpectedOutput = compareExpectedOutput,
                        runnerLabel = "CLion C/C++ File runner",
                    )
                }
            }
        } catch (e: Exception) {
            CphCaseResult(
                verdict = CphVerdict.ERROR,
                durationMillis = elapsedMillis(startedAt),
                message = e.message ?: e.javaClass.simpleName,
            )
        }
    }

    fun debugCase(identity: CphTargetIdentity, testCase: CphTestCase) {
        val settings = identity.settings
            ?: throw ExecutionException(identity.message)
        if (!identity.runnable) {
            throw ExecutionException(identity.message)
        }
        if (identity.kind != CphTargetKind.CMAKE_APP && identity.kind != CphTargetKind.CPP_FILE) {
            throw ExecutionException("CPH Debug supports CLion CMake Application and C/C++ File configurations.")
        }

        val debugSettings = settings.createFactory().create()
        debugSettings.name = debugConfigurationName(identity.kind, settings.name, testCase.name)
        debugSettings.setTemporary(true)

        val inputFile = Files.createTempFile("cph-debug-", ".in").toFile()
        inputFile.writeText(testCase.input, StandardCharsets.UTF_8)
        inputFile.deleteOnExit()

        val inputOptions = InputRedirectAware.getInputRedirectOptions(debugSettings.configuration)
            ?: debugSettings.configuration as? InputRedirectAware.InputRedirectOptions
            ?: throw ExecutionException("Cannot configure input redirection for '${settings.name}'.")
        inputOptions.setRedirectInput(true)
        inputOptions.setRedirectInputPath(inputFile.absolutePath)

        val configuration = debugSettings.configuration
        val executor = DefaultDebugExecutor.getDebugExecutorInstance()
        val runner = ProgramRunner.getRunner(executor.id, configuration)
            ?: throw ExecutionException("No debugger runner is available for ${settings.name}.")
        val environment = buildEnvironment(debugSettings, runner, executor)
        ProgramRunnerUtil.executeConfiguration(environment, false, true)
    }

    private fun prepareCMakeRunTarget(
        settings: com.intellij.execution.RunnerAndConfigurationSettings,
    ): CphRunPreparation {
        val setupStartedAt = System.nanoTime()
        return try {
            val launchPlan = resolveCMakeLaunchPlan(settings)
                ?: throw ExecutionException("Cannot resolve executable for '${settings.name}'.")
            val buildResult = prepareBuildTarget(settings, launchPlan)
            if (buildResult != null) return CphRunPreparation.Failed(buildResult)

            val executable = launchPlan.executable
            if (!executable.isFile || !executable.canExecute()) {
                throw ExecutionException("Executable is not ready: ${executable.absolutePath}")
            }

            CphRunPreparation.Ready(
                CphPreparedRunTarget.CMakeExecutable(
                    executable = executable,
                    workingDir = File(project.basePath ?: executable.parentFile.absolutePath),
                ),
                CphRunPrepareDiagnostics(totalPrepareMillis = elapsedMillis(setupStartedAt)),
            )
        } catch (e: Exception) {
            CphRunPreparation.Failed(
                CphCaseResult(
                    verdict = CphVerdict.ERROR,
                    durationMillis = elapsedMillis(setupStartedAt),
                    message = e.message ?: e.javaClass.simpleName,
                ),
            )
        }
    }

    private fun prepareCppFileRunTarget(
        settings: com.intellij.execution.RunnerAndConfigurationSettings,
    ): CphRunPreparation {
        val setupStartedAt = System.nanoTime()
        return try {
            val context = buildDefaultRunContext(settings)
            val buildResult = buildCppFileTarget(settings, context.environment, setupStartedAt)
            if (buildResult.error != null) return CphRunPreparation.Failed(buildResult.error)
            val commandLineStartedAt = System.nanoTime()
            val commandLine = when (val resolution = resolveCppFileCommandLineRecovering(
                settings = settings,
                executor = context.executor,
                environment = context.environment,
                startedAt = setupStartedAt,
            )) {
                is CppFileCommandLineResolution.Ready -> resolution.commandLine
                is CppFileCommandLineResolution.Failed -> return CphRunPreparation.Failed(resolution.result)
            }
            val commandLineResolveMillis = elapsedMillis(commandLineStartedAt)
            CphRunPreparation.Ready(
                CphPreparedRunTarget.CppFile(commandLine),
                CphRunPrepareDiagnostics(
                    buildMillis = buildResult.elapsedMillis,
                    buildSkippedByCphCache = buildResult.skippedByCphCache,
                    commandLineResolveMillis = commandLineResolveMillis,
                    totalPrepareMillis = elapsedMillis(setupStartedAt),
                ),
            )
        } catch (e: Exception) {
            CphRunPreparation.Failed(
                CphCaseResult(
                    verdict = CphVerdict.ERROR,
                    durationMillis = elapsedMillis(setupStartedAt),
                    message = e.message ?: e.javaClass.simpleName,
                ),
            )
        }
    }

    private fun runCppFileConfiguration(
        settings: com.intellij.execution.RunnerAndConfigurationSettings,
        testCase: CphTestCase,
        timeoutMillis: Long,
        ignoreTrailingWhitespace: Boolean,
        compareExpectedOutput: Boolean,
    ): CphCaseResult {
        val setupStartedAt = System.nanoTime()
        return try {
            val context = buildDefaultRunContext(settings)
            val buildResult = buildCppFileTarget(settings, context.environment, setupStartedAt)
            if (buildResult.error != null) return buildResult.error

            val commandLine = when (val resolution = resolveCppFileCommandLineRecovering(
                settings = settings,
                executor = context.executor,
                environment = context.environment,
                startedAt = setupStartedAt,
            )) {
                is CppFileCommandLineResolution.Ready -> resolution.commandLine
                is CppFileCommandLineResolution.Failed -> return resolution.result
            }
            runCommandLine(
                commandLine = commandLine,
                testCase = testCase,
                timeoutMillis = timeoutMillis,
                ignoreTrailingWhitespace = ignoreTrailingWhitespace,
                compareExpectedOutput = compareExpectedOutput,
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
        compareExpectedOutput: Boolean,
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
                compareExpectedOutput = compareExpectedOutput,
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
        compareExpectedOutput: Boolean,
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
            compareExpectedOutput = compareExpectedOutput,
            runnerLabel = runnerLabel,
        )
    }

    private fun runCommandLine(
        commandLine: GeneralCommandLine,
        testCase: CphTestCase,
        timeoutMillis: Long,
        ignoreTrailingWhitespace: Boolean,
        compareExpectedOutput: Boolean,
        runnerLabel: String,
    ): CphCaseResult {
        return runProcess(
            startProcess = { commandLine.createProcess() },
            testCase = testCase,
            timeoutMillis = timeoutMillis,
            ignoreTrailingWhitespace = ignoreTrailingWhitespace,
            compareExpectedOutput = compareExpectedOutput,
            runnerLabel = runnerLabel,
        )
    }

    private fun runProcess(
        startProcess: () -> Process,
        testCase: CphTestCase,
        timeoutMillis: Long,
        ignoreTrailingWhitespace: Boolean,
        compareExpectedOutput: Boolean,
        runnerLabel: String,
    ): CphCaseResult {
        return caseProcessExecutor.run(
            startProcess = startProcess,
            input = testCase.input,
            expectedOutput = testCase.expectedOutput,
            timeoutMillis = timeoutMillis,
            ignoreTrailingWhitespace = ignoreTrailingWhitespace,
            compareExpectedOutput = compareExpectedOutput,
            runnerLabel = runnerLabel,
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

    private sealed class CppFileCommandLineResolution {
        data class Ready(val commandLine: GeneralCommandLine) : CppFileCommandLineResolution()
        data class Failed(val result: CphCaseResult) : CppFileCommandLineResolution()
    }

    private class CppFileExecutableNotReadyException(val executable: File) :
        ExecutionException("Executable is not ready: ${executable.absolutePath}")

    private fun prepareBuildTarget(
        settings: com.intellij.execution.RunnerAndConfigurationSettings,
        launchPlan: CMakeLaunchPlan,
    ): CphCaseResult? {
        val buildDir = launchPlan.buildWorkingDir ?: return null
        val key = listOf(buildDir.absolutePath, launchPlan.buildTargetName.orEmpty()).joinToString("|")
        return synchronized(preparedBuilds) {
            if (preparedBuilds.containsKey(key)) return@synchronized preparedBuilds[key]
            val result = buildTarget(settings, launchPlan)
            preparedBuilds[key] = result
            result
        }
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
        force: Boolean = false,
    ): CphCppFileBuildResult {
        synchronized(cppFileBuildLock) {
            return buildCppFileTargetLocked(settings, environment, startedAt, force)
        }
    }

    private fun buildCppFileTargetLocked(
        settings: com.intellij.execution.RunnerAndConfigurationSettings,
        environment: ExecutionEnvironment,
        startedAt: Long,
        force: Boolean,
    ): CphCppFileBuildResult {
        val buildStartedAt = System.nanoTime()
        return try {
            val buildKey = cppFileBuildKey(settings)
            if (!force && isCppFileBuildFresh(settings, environment, buildKey)) {
                return CphCppFileBuildResult(
                    skippedByCphCache = true,
                    elapsedMillis = elapsedMillis(buildStartedAt),
                )
            }
            val ok = executeCppFileBuildTask(settings, environment)
            if (ok) {
                markCppFileBuildFresh(settings, environment, buildKey)
                CphCppFileBuildResult(elapsedMillis = elapsedMillis(buildStartedAt))
            } else {
                CphCppFileBuildResult(
                    error = CphCaseResult(
                        verdict = CphVerdict.ERROR,
                        durationMillis = elapsedMillis(startedAt),
                        message = "Build failed for C/C++ File configuration '${settings.name}'.",
                    ),
                    elapsedMillis = elapsedMillis(buildStartedAt),
                )
            }
        } catch (e: Throwable) {
            val cause = invocationCause(e)
            if (isCppFileTargetMiss(cause)) {
                val diagnostics = CphCompileSettingsSynchronizer(project).diagnoseCppFileWorkspace(settings)
                return CphCppFileBuildResult(
                    error = CphCaseResult(
                        verdict = CphVerdict.ERROR,
                        durationMillis = elapsedMillis(startedAt),
                        message = "Build failed for C/C++ File configuration '${settings.name}' because CLion has no matching single-file build target. $diagnostics",
                    ),
                    elapsedMillis = elapsedMillis(buildStartedAt),
                )
            }
            CphCppFileBuildResult(
                error = CphCaseResult(
                    verdict = CphVerdict.ERROR,
                    durationMillis = elapsedMillis(startedAt),
                    message = "Build failed for C/C++ File configuration '${settings.name}': ${cause.message ?: cause.javaClass.simpleName}",
                ),
                elapsedMillis = elapsedMillis(buildStartedAt),
            )
        }
    }

    private data class CphCppFileBuildResult(
        val error: CphCaseResult? = null,
        val skippedByCphCache: Boolean = false,
        val elapsedMillis: Long = 0L,
    )

    private fun executeCppFileBuildTask(
        settings: com.intellij.execution.RunnerAndConfigurationSettings,
        environment: ExecutionEnvironment,
    ): Boolean {
        val configuration = settings.configuration as? CppFileRunConfiguration
            ?: throw ExecutionException("'${settings.name}' is not a CLion C/C++ File configuration.")
        val provider = cppFileBuildBeforeRunTaskProvider()
            ?: throw ExecutionException("Cannot find CLion C/C++ File build task provider.")
        val task = provider.createTask(configuration)
            ?: throw ExecutionException("Cannot create CLion C/C++ File build task for '${settings.name}'.")

        return provider.executeTask(DataContext.EMPTY_CONTEXT, configuration, environment, task)
    }

    private fun isCppFileTargetMiss(error: Throwable): Boolean {
        return error is NoSuchElementException &&
            error.message?.contains("Collection contains no element matching the predicate") == true
    }

    private fun cppFileBuildBeforeRunTaskProvider(): CppFileBuildBeforeRunTaskProvider? {
        return BeforeRunTaskProvider.EP_NAME.getExtensions(project)
            .filterIsInstance<CppFileBuildBeforeRunTaskProvider>()
            .firstOrNull()
    }

    private fun isCppFileBuildFresh(
        settings: com.intellij.execution.RunnerAndConfigurationSettings,
        environment: ExecutionEnvironment,
        buildKey: CphCppFileBuildKey,
    ): Boolean {
        val configuration = settings.configuration as? CppFileRunConfiguration ?: return false
        val targetsService = project.getService(CppFileBuildTargetsService::class.java) ?: return false
        if (targetsService.getTargetOrNullFor(configuration) == null) return false
        val source = configuration.options.sourceFile?.let(::File)?.takeIf { it.isFile } ?: return false
        val executable = runCatching {
            resolveCppFileExecutable(settings, environment.executor, environment)
        }.getOrNull()?.takeIf { it.isFile } ?: return false
        if (!cppFileBuildCache.matches(settings.name, buildKey, executable)) return false
        if (source.lastModified() > executable.lastModified()) return false
        log.info("CPH C/C++ File build cache hit for '${settings.name}': ${executable.absolutePath}")
        return true
    }

    private fun markCppFileBuildFresh(
        settings: com.intellij.execution.RunnerAndConfigurationSettings,
        environment: ExecutionEnvironment,
        buildKey: CphCppFileBuildKey,
    ) {
        val executable = runCatching {
            resolveCppFileExecutable(settings, environment.executor, environment)
        }.getOrNull()?.takeIf { it.isFile } ?: return
        cppFileBuildCache.remember(settings.name, buildKey, executable)
    }

    private fun resolveCppFileCommandLineRecovering(
        settings: com.intellij.execution.RunnerAndConfigurationSettings,
        executor: Executor,
        environment: ExecutionEnvironment,
        startedAt: Long,
    ): CppFileCommandLineResolution {
        return try {
            CppFileCommandLineResolution.Ready(resolveCppFileCommandLine(settings, executor, environment))
        } catch (e: CppFileExecutableNotReadyException) {
            log.warn(
                "CPH C/C++ File executable is missing after build for '${settings.name}', retrying once: " +
                    e.executable.absolutePath,
            )
            cleanupCppFileExecutableArtifacts(e.executable)
            val refresh = CphCompileSettingsSynchronizer(project).refreshCppFileWorkspace(settings, waitForTarget = true)
            if (refresh.error != null) {
                log.warn("CPH C/C++ File target refresh failed before retry for '${settings.name}': ${refresh.error}")
            }
            val rebuildResult = buildCppFileTarget(settings, environment, startedAt, force = true)
            if (rebuildResult.error != null) return CppFileCommandLineResolution.Failed(rebuildResult.error)

            CppFileCommandLineResolution.Ready(resolveCppFileCommandLine(settings, executor, environment))
        }
    }

    private fun cleanupCppFileExecutableArtifacts(executable: File) {
        val candidates = linkedSetOf(executable)
        val parent = executable.parentFile
        val name = executable.name
        val baseName = name.removeSuffix(".exe")
        if (parent != null && baseName != name) {
            listOf(".ilk", ".pdb", ".obj", ".o").forEach { suffix ->
                candidates.add(File(parent, baseName + suffix))
            }
        }
        candidates.forEach { file ->
            runCatching {
                if (file.exists() && !file.delete()) {
                    log.warn("CPH could not delete stale C/C++ File artifact: ${file.absolutePath}")
                }
            }.onFailure {
                log.warn("CPH failed to delete stale C/C++ File artifact: ${file.absolutePath}", it)
            }
        }
    }

    private fun cppFileBuildKey(settings: com.intellij.execution.RunnerAndConfigurationSettings): CphCppFileBuildKey {
        val configuration = settings.configuration as? CppFileRunConfiguration
            ?: return CphCppFileBuildKey(settings.name, "", "", "", "", "", 0L, 0L)
        val source = configuration.options.sourceFile.orEmpty()
        val sourceFile = source.takeIf { it.isNotBlank() }?.let(::File)
        return CphCppFileBuildKey(
            settingsName = settings.name,
            sourcePath = sourceFile?.absolutePath ?: source,
            compilerOptions = configuration.options.compilerOptions.orEmpty(),
            toolchainName = configuration.options.toolchainName.orEmpty(),
            compilerFile = configuration.options.compilerFile.orEmpty(),
            workingDirectory = configuration.workingDirectory.orEmpty(),
            sourceModified = sourceFile?.takeIf { it.isFile }?.lastModified() ?: 0L,
            sourceSize = sourceFile?.takeIf { it.isFile }?.length() ?: 0L,
        )
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
        val runFile = runFileFromPair(settings, runFileAndEnvironment)
        if (!runFile.isFile) {
            throw CppFileExecutableNotReadyException(runFile)
        }
        val cppEnvironment = pairComponent(runFileAndEnvironment, "component2")
            ?: throw ExecutionException("CLion C/C++ File launcher did not return a toolchain environment for '${settings.name}'.")

        val createCommandLine = findMethodInHierarchy(launcher.javaClass, "createCommandLine", 5)
            ?: throw ExecutionException("Cannot create CLion command line for C/C++ File configuration '${settings.name}'.")
        createCommandLine.isAccessible = true
        return invokeReflective(createCommandLine, launcher, state, runFile, cppEnvironment, false, false) as? GeneralCommandLine
            ?: throw ExecutionException("CLion C/C++ File launcher returned an invalid command line for '${settings.name}'.")
    }

    private fun resolveCppFileExecutable(
        settings: com.intellij.execution.RunnerAndConfigurationSettings,
        executor: Executor,
        environment: ExecutionEnvironment,
    ): File {
        val state = settings.configuration.getState(executor, environment)
            ?: throw ExecutionException("Cannot create CLion run state for C/C++ File configuration '${settings.name}'.")
        val launcher = cppFileLauncher(state)
            ?: throw ExecutionException("Cannot access CLion C/C++ File launcher for '${settings.name}'.")
        val runFileAndEnvironment = launcher.javaClass.methods.firstOrNull {
            it.name == "getRunFileAndEnvironment" && it.parameterCount == 0
        }?.let { invokeReflective(it, launcher) }
            ?: throw ExecutionException("Cannot resolve CLion C/C++ File executable for '${settings.name}'.")
        return runFileFromPair(settings, runFileAndEnvironment)
    }

    private fun runFileFromPair(settings: com.intellij.execution.RunnerAndConfigurationSettings, pair: Any): File {
        return pairComponent(pair, "component1") as? File
            ?: throw ExecutionException("CLion C/C++ File launcher did not return an executable for '${settings.name}'.")
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
        val result = processRunner.execute(
            startProcess = {
                ProcessBuilder(command)
                    .directory(workingDir)
                    .start()
            },
            timeoutMillis = TimeUnit.SECONDS.toMillis(timeoutSeconds.coerceAtLeast(1)),
        )
        if (result.timedOut) return null
        return CommandResult(
            exitCode = result.exitCode ?: -1,
            stdout = result.stdout,
            stderr = result.stderr,
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

        internal fun debugConfigurationName(kind: CphTargetKind, settingsName: String, caseName: String): String {
            return if (kind == CphTargetKind.CPP_FILE) {
                settingsName
            } else {
                "CPH Debug: $settingsName / $caseName"
            }
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
