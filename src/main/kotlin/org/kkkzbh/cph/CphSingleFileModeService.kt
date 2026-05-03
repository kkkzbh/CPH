package org.kkkzbh.cph

import com.intellij.execution.RunManager
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.StatusBar
import org.kkkzbh.cph.server.CphCppFileRunConfigurationFactory

internal data class CphSingleFileModeRequest(
    val path: String,
    val displayName: String,
    val workingDirectory: String,
)

internal object CphSingleFileModePolicy {
    fun request(
        enabled: Boolean,
        path: String?,
        extension: String?,
        fileName: String?,
        inProject: Boolean,
        lastObservedPath: String?,
        workingDirectory: String?,
        force: Boolean = false,
    ): CphSingleFileModeRequest? {
        if (!enabled || path.isNullOrBlank()) return null
        if (!force && path == lastObservedPath) return null
        if (!inProject || extension != "cpp") return null
        return CphSingleFileModeRequest(
            path = path,
            displayName = fileName?.takeIf { it.isNotBlank() } ?: path.substringAfterLast('/'),
            workingDirectory = CphStateService.normalizeSingleFileWorkingDirectory(workingDirectory),
        )
    }
}

internal class CphSingleFileModeService(private val project: Project) {
    private val logger = Logger.getInstance(CphSingleFileModeService::class.java)
    private val stateService = CphStateService.getInstance(project)
    private var started = false
    private var applyingSelection = false
    private var lastObservedPath: String? = null

    fun start() {
        if (started) return
        started = true
        project.messageBus.connect(project).subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER, object : FileEditorManagerListener {
            override fun selectionChanged(event: FileEditorManagerEvent) {
                syncForFile(event.newFile)
            }
        })
        ApplicationManager.getApplication().invokeLater {
            syncForCurrentFile(force = true)
        }
    }

    fun syncForCurrentFile(force: Boolean = false) {
        syncForFile(FileEditorManager.getInstance(project).selectedFiles.firstOrNull(), force)
    }

    private fun syncForFile(file: VirtualFile?, force: Boolean = false) {
        if (applyingSelection) return
        val previousPath = lastObservedPath
        lastObservedPath = file?.path
        val request = CphSingleFileModePolicy.request(
            enabled = stateService.getState().singleFileModeEnabled,
            path = file?.path,
            extension = file?.extension,
            fileName = file?.name,
            inProject = file?.let(::isProjectFile) == true,
            lastObservedPath = previousPath,
            workingDirectory = stateService.getState().singleFileWorkingDirectory,
            force = force,
        ) ?: return

        runCatching {
            val runManager = RunManager.getInstance(project)
            val settings = CphCppFileRunConfigurationFactory
                .findOrCreate(project, file ?: return, request.displayName, request.workingDirectory)
                .settings
            val refresh = CphCompileSettingsSynchronizer(project).refreshCppFileWorkspace(settings, waitForTarget = false)
            if (refresh.error != null) {
                StatusBar.Info.set("CPH single-file target refresh failed: ${refresh.error}", project)
            }
            if (runManager.selectedConfiguration !== settings) {
                applyingSelection = true
                try {
                    runManager.selectedConfiguration = settings
                } finally {
                    applyingSelection = false
                }
            }
        }.onFailure {
            lastObservedPath = previousPath
            logger.warn("Failed to sync CPH single-file mode for ${request.path}", it)
        }
    }

    private fun isProjectFile(file: VirtualFile): Boolean {
        return ProjectFileIndex.getInstance(project).isInContent(file)
    }

    companion object {
        fun getInstance(project: Project): CphSingleFileModeService = project.service()
    }
}
