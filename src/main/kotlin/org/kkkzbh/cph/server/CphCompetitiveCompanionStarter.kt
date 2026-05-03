package org.kkkzbh.cph.server

import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import org.kkkzbh.cph.CphShortcutDispatcher
import org.kkkzbh.cph.CphSingleFileModeService

internal class CphCompetitiveCompanionStarter : ProjectActivity {
    override suspend fun execute(project: Project) {
        CphShortcutDispatcher.getInstance(project)
        CphSingleFileModeService.getInstance(project).start()
        CphCompetitiveCompanionServer.getInstance().init()
    }
}
