package org.kkkzbh.cph

import com.intellij.execution.BeforeRunTask
import com.intellij.execution.BeforeRunTaskProvider
import com.intellij.execution.RunManager
import com.intellij.execution.RunManagerListener
import com.intellij.execution.RunnerAndConfigurationSettings
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.components.service
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.jetbrains.cidr.cpp.runfile.CppFileRunConfiguration
import java.util.concurrent.CancellationException

internal class CphCppFilePreparationTask : BeforeRunTask<CphCppFilePreparationTask>(ID) {
    init {
        isEnabled = true
    }

    companion object {
        val ID: Key<CphCppFilePreparationTask> = Key.create("CphCppFilePreparation")

        fun orderedTasks(tasks: List<BeforeRunTask<*>>): List<BeforeRunTask<*>> =
            listOf(CphCppFilePreparationTask()) + tasks.filter { it.providerId != ID }
    }
}

internal class CphCppFilePreparationProvider : BeforeRunTaskProvider<CphCppFilePreparationTask>(), DumbAware {
    override fun getId(): Key<CphCppFilePreparationTask> = CphCppFilePreparationTask.ID
    override fun getName(): String = "CPH: Prepare C++ compilation"
    override fun isConfigurable(): Boolean = false
    override fun createTask(configuration: RunConfiguration): CphCppFilePreparationTask? =
        if (configuration is CppFileRunConfiguration) CphCppFilePreparationTask() else null

    override fun executeTask(
        context: DataContext,
        configuration: RunConfiguration,
        environment: ExecutionEnvironment,
        task: CphCppFilePreparationTask,
    ): Boolean {
        val project = configuration.project
        if (!CphProjectActivationService.getInstance(project).isEnabled()) return true
        if (configuration !is CppFileRunConfiguration) return true
        return try {
            CphCompileSettingsSynchronizer.getInstance(project).prepareCppFile(configuration)
            true
        } catch (e: ProcessCanceledException) {
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            NotificationGroupManager.getInstance().getNotificationGroup("CPH Target Runner")
                .createNotification("CPH C++ preparation failed", e.message ?: e.javaClass.simpleName, NotificationType.ERROR)
                .notify(project)
            false
        }
    }
}

internal class CphCppFilePreparationService(private val project: Project) {
    private var started = false

    fun start() {
        if (started) return
        started = true
        val manager = RunManager.getInstance(project)
        project.messageBus.connect(project).subscribe(RunManagerListener.TOPIC, object : RunManagerListener {
            override fun runConfigurationAdded(settings: RunnerAndConfigurationSettings) = install(settings)
            override fun runConfigurationChanged(settings: RunnerAndConfigurationSettings) = install(settings)
            override fun stateLoaded(runManager: RunManager, isFirstLoadState: Boolean) {
                runManager.allSettings.forEach(::install)
            }
        })
        manager.allSettings.forEach(::install)
    }

    private fun install(settings: RunnerAndConfigurationSettings) {
        val configuration = settings.configuration as? CppFileRunConfiguration ?: return
        configuration.beforeRunTasks = CphCppFilePreparationTask.orderedTasks(configuration.beforeRunTasks)
    }

    companion object {
        fun getInstance(project: Project): CphCppFilePreparationService = project.service()
    }
}
