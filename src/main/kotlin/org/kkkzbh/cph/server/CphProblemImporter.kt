package org.kkkzbh.cph.server

import com.intellij.execution.RunManager
import com.intellij.notification.NotificationType
import com.intellij.notification.NotificationGroupManager
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import org.kkkzbh.cph.CPH_DEFAULT_TIMEOUT_MILLIS
import org.kkkzbh.cph.CPH_MAX_TIMEOUT_MILLIS
import org.kkkzbh.cph.CPH_MIN_TIMEOUT_MILLIS
import org.kkkzbh.cph.CphCasesChangedListener
import org.kkkzbh.cph.CphCompileSettingsSynchronizer
import org.kkkzbh.cph.CphStateService
import org.kkkzbh.cph.CphTargetCases
import org.kkkzbh.cph.CphTargetResolver
import org.kkkzbh.cph.CphTestCase
import java.io.File
import java.util.concurrent.atomic.AtomicReference

internal data class CphImportOutcome(
    val success: Boolean,
    val message: String,
    val sourcePath: String? = null,
    val targetName: String? = null,
    val caseCount: Int = 0,
)

@Service(Service.Level.PROJECT)
internal class CphProblemImporter(private val project: Project) {
    private val logger = Logger.getInstance(CphProblemImporter::class.java)

    fun import(payload: CompetitiveCompanionPayload): CphImportOutcome {
        return try {
            doImport(payload)
        } catch (e: Throwable) {
            logger.warn("CPH import failed for ${payload.name}", e)
            notify(
                "CPH import failed: ${e.message ?: e.javaClass.simpleName}",
                NotificationType.ERROR,
            )
            CphImportOutcome(success = false, message = e.message ?: e.javaClass.simpleName)
        }
    }

    private fun doImport(payload: CompetitiveCompanionPayload): CphImportOutcome {
        val stateService = CphStateService.getInstance(project)
        if (!stateService.getState().cphEnabled) {
            return CphImportOutcome(success = false, message = "CPH is not enabled for this project.")
        }
        val settings = CphImportSettings.getInstance().state
        val coords = CphImportPaths.coordinates(payload)
        val relativePath = CphImportPaths.render(settings.pathTemplate, coords)
        val baseDir = project.basePath
            ?: return CphImportOutcome(success = false, message = "Project has no base directory.")

        val sourceFile = ensureSourceFile(
            baseDir = baseDir,
            sourceRoot = settings.sourceRoot,
            relativePath = relativePath,
            template = settings.cppTemplate,
            overwrite = settings.overwriteExisting,
        )

        val creation = computeOnEdt {
            val result = CphCppFileRunConfigurationFactory.findOrCreate(
                project = project,
                sourceFile = sourceFile,
                displayName = payload.name.ifBlank { coords.index },
                workingDirectory = stateService.getState().singleFileWorkingDirectory,
            )
            val identity = CphTargetResolver.fromSettings(result.settings)
            val targetCases = stateService.getOrCreateTargetCases(identity)
            applyCases(targetCases, payload)
            RunManager.getInstance(project).selectedConfiguration = result.settings
            project.messageBus.syncPublisher(CphCasesChangedListener.TOPIC).targetCasesChanged(identity.id)
            result
        }

        val refresh = CphCompileSettingsSynchronizer(project)
            .refreshCppFileWorkspace(creation.settings, waitForTarget = true)
        if (refresh.error != null) {
            logger.warn("CPH refresh failed: ${refresh.error}")
        }

        openInEditor(sourceFile)

        notify(
            "Imported ${payload.name} (${payload.tests.size} tests)",
            NotificationType.INFORMATION,
        )

        return CphImportOutcome(
            success = true,
            message = "Imported ${payload.name}",
            sourcePath = sourceFile.path,
            targetName = creation.settings.name,
            caseCount = payload.tests.size,
        )
    }

    private fun ensureSourceFile(
        baseDir: String,
        sourceRoot: String,
        relativePath: String,
        template: String,
        overwrite: Boolean,
    ): VirtualFile {
        val combined = listOf(sourceRoot.trim('/'), relativePath.trim('/'))
            .filter { it.isNotEmpty() }
            .joinToString("/")
        val targetFile = File(baseDir, combined).normalize()
        val parentFile = targetFile.parentFile
            ?: error("Cannot determine parent directory for ${targetFile.path}")

        return WriteAction.computeAndWait<VirtualFile, RuntimeException> {
            val baseVf = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(File(baseDir))
                ?: error("Cannot locate project base directory $baseDir")
            val parentVf = VfsUtil.createDirectoryIfMissing(
                baseVf,
                parentFile.relativeTo(File(baseDir)).invariantSeparatorsPath,
            ) ?: error("Cannot create directory ${parentFile.path}")

            val existing = parentVf.findChild(targetFile.name)
            if (existing != null && existing.exists()) {
                if (overwrite) {
                    existing.setBinaryContent(template.toByteArray(Charsets.UTF_8))
                }
                existing
            } else {
                val created = parentVf.createChildData(this, targetFile.name)
                created.setBinaryContent(template.toByteArray(Charsets.UTF_8))
                created
            }
        }
    }

    private fun applyCases(targetCases: CphTargetCases, payload: CompetitiveCompanionPayload) {
        val timeout = payload.timeLimit
            ?.coerceIn(CPH_MIN_TIMEOUT_MILLIS, CPH_MAX_TIMEOUT_MILLIS)
            ?: CPH_DEFAULT_TIMEOUT_MILLIS
        targetCases.timeoutMillis = timeout
        targetCases.cases.clear()
        if (payload.tests.isEmpty()) {
            targetCases.cases.add(CphTestCase(name = "Case 1"))
            return
        }
        payload.tests.forEachIndexed { index, test ->
            targetCases.cases.add(
                CphTestCase(
                    name = "Sample ${index + 1}",
                    input = test.input,
                    expectedOutput = test.output,
                ),
            )
        }
    }

    private fun openInEditor(file: VirtualFile) {
        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed) return@invokeLater
            FileEditorManager.getInstance(project).openFile(file, true)
        }
    }

    private fun notify(message: String, type: NotificationType) {
        runCatching {
            NotificationGroupManager.getInstance()
                .getNotificationGroup("CPH Target Runner")
                .createNotification(message, type)
                .notify(project)
        }
    }

    private fun <T> computeOnEdt(action: () -> T): T {
        val app = ApplicationManager.getApplication()
        if (app.isDispatchThread) return action()
        val result = AtomicReference<T>()
        val error = AtomicReference<Throwable>()
        app.invokeAndWait {
            runCatching { result.set(action()) }.onFailure { error.set(it) }
        }
        error.get()?.let { throw it }
        return result.get()
    }

    companion object {
        fun getInstance(project: Project): CphProblemImporter = project.getService(CphProblemImporter::class.java)
    }
}
