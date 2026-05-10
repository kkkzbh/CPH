package org.kkkzbh.cph.server

import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import org.kkkzbh.cph.CphShortcutDispatcher
import org.kkkzbh.cph.CphSingleFileModeService
import org.kkkzbh.cph.CphStateService

internal class CphCompetitiveCompanionStarter : ProjectActivity {
    override suspend fun execute(project: Project) {
        CphShortcutDispatcher.getInstance(project)
        if (CphStateService.getInstance(project).state.cphEnabled) {
            CphSingleFileModeService.getInstance(project).start()
        }
        CphCompetitiveCompanionServer.getInstance().init()
    }
}
