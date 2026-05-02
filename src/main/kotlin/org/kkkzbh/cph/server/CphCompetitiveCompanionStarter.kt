package org.kkkzbh.cph.server

import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import org.kkkzbh.cph.CphShortcutDispatcher

internal class CphCompetitiveCompanionStarter : ProjectActivity {
    override suspend fun execute(project: Project) {
        CphShortcutDispatcher.getInstance(project)
        CphCompetitiveCompanionServer.getInstance().init()
    }
}
