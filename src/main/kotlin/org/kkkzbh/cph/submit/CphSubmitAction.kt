package org.kkkzbh.cph.submit

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import org.kkkzbh.cph.CphStateService

internal class CphSubmitAction : AnAction("CPH: Submit Current File to Codeforces") {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val project = e.project
        e.presentation.isEnabled = project != null &&
            CphStateService.getInstance(project).state.singleFileModeEnabled
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        CphSubmitOrchestrator.getInstance(project).submit()
    }
}
