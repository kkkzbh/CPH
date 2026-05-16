package org.kkkzbh.cph

import com.intellij.build.BuildContentManager
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowAnchor
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.openapi.wm.ex.ToolWindowEx

internal class CphBuildOutputService(private val project: Project) {
    fun showCompileError() {
        if (!ApplicationManager.getApplication().isDispatchThread) {
            ApplicationManager.getApplication().invokeLater {
                if (!project.isDisposed) showCompileError()
            }
            return
        }

        activateAndResize(BuildContentManager.getInstance(project).getOrCreateToolWindow())
    }

    private fun activateAndResize(toolWindow: ToolWindow) {
        toolWindow.activate(
            {
                ToolWindowManager.getInstance(project).invokeLater {
                    if (!project.isDisposed) restoreReasonableHeight(toolWindow)
                }
            },
            true,
            true,
        )
    }

    private fun restoreReasonableHeight(toolWindow: ToolWindow) {
        if (toolWindow.anchor != ToolWindowAnchor.BOTTOM && toolWindow.anchor != ToolWindowAnchor.TOP) return
        val toolWindowEx = toolWindow as ToolWindowEx
        val currentHeight = toolWindowEx.decorator.height
        val delta = stretchDeltaForHeight(currentHeight)
        if (delta <= 0) return

        runCatching {
            toolWindowEx.stretchHeight(delta)
        }.onFailure {
            log.warn("CPH failed to restore Build tool window height.", it)
        }
    }

    companion object {
        private val log = Logger.getInstance(CphBuildOutputService::class.java)
        private const val TARGET_BUILD_WINDOW_HEIGHT = 300

        internal fun stretchDeltaForHeight(currentHeight: Int): Int {
            if (currentHeight <= 0 || currentHeight >= TARGET_BUILD_WINDOW_HEIGHT) return 0
            return TARGET_BUILD_WINDOW_HEIGHT - currentHeight
        }

        fun getInstance(project: Project): CphBuildOutputService = project.service()
    }
}
