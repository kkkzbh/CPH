package org.kkkzbh.cph

import com.intellij.codeInsight.CodeInsightSettings
import com.intellij.execution.RunManager
import com.intellij.mock.Mock
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.roots.ModuleRootModificationUtil
import com.intellij.testFramework.HeavyPlatformTestCase
import com.intellij.testFramework.replaceService
import com.intellij.testFramework.runInEdtAndWait
import com.jetbrains.cidr.cpp.runfile.CppFileRunConfiguration
import java.io.File

class CphSingleFileModePlatformTest : HeavyPlatformTestCase() {
    override fun getOpenProjectOptions() = super.getOpenProjectOptions().runPostStartUpActivities(false)
    override fun runInDispatchThread() = false

    override fun setUp() {
        super.setUp()
        setDefaultCodeInsightSettings(CodeInsightSettings.getInstance().clone())
        CphProjectActivationService.getInstance(project).enable()
    }

    fun testDisabledModeFromUnlockedEdtLeavesConfigurationsUnchanged() {
        selectCppFile(inProject = true)
        val state = CphStateService.getInstance(project)
        state.updateState { singleFileModeEnabled = false }
        val manager = RunManager.getInstance(project)
        val configurations = manager.allSettings.toList()
        val selection = manager.selectedConfiguration

        onUnlockedEdt {
            CphSingleFileModeService.getInstance(project).syncForCurrentFile(force = true)
        }

        assertEquals(configurations, manager.allSettings.toList())
        assertSame(selection, manager.selectedConfiguration)
    }

    fun testEnabledModeFromUnlockedEdtRejectsExternalFile() {
        selectCppFile(inProject = false)
        val manager = RunManager.getInstance(project)
        val configurations = manager.allSettings.toList()

        onUnlockedEdt {
            CphSingleFileModeService.getInstance(project).syncForCurrentFile(force = true)
        }

        assertEquals(configurations, manager.allSettings.toList())
    }

    fun testEnabledModeFromUnlockedEdtSelectsProjectFileAndCanBeToggled() {
        val source = selectCppFile(inProject = true)
        val state = CphStateService.getInstance(project)
        val service = CphSingleFileModeService.getInstance(project)
        val manager = RunManager.getInstance(project)

        onUnlockedEdt { service.syncForCurrentFile(force = true) }
        val selected = manager.selectedConfiguration!!
        assertEquals(source.path, (selected.configuration as CppFileRunConfiguration).options.sourceFile)

        onUnlockedEdt {
            state.updateState { singleFileModeEnabled = false }
            service.syncForCurrentFile(force = true)
            assertSame(selected, manager.selectedConfiguration)
            state.updateState { singleFileModeEnabled = true }
            service.syncForCurrentFile(force = true)
        }

        assertSame(selected, manager.selectedConfiguration)
        assertEquals(1, manager.allSettings.count {
            (it.configuration as? CppFileRunConfiguration)?.options?.sourceFile == source.path
        })
    }

    private fun selectCppFile(inProject: Boolean): File {
        val directory = createTempDir("single-file-mode")
        val source = File(directory, "main.cpp").apply { writeText("int main() {}\n") }
        CphStateService.getInstance(project).updateState {
            singleFileWorkingDirectory = directory.path
        }
        runInEdtAndWait {
            if (inProject) ModuleRootModificationUtil.addContentRoot(module, directory.path)
            val file = getVirtualFile(source)
            // Supply editor selection while keeping the real VFS and project index.
            project.replaceService(FileEditorManager::class.java, object : Mock.MyFileEditorManager() {
                override fun getSelectedFiles() = arrayOf(file)
            }, testRootDisposable)
        }
        return source
    }

    private fun onUnlockedEdt(action: () -> Unit) {
        runInEdtAndWait(writeIntent = false) {
            assertTrue(ApplicationManager.getApplication().isDispatchThread)
            assertFalse(ApplicationManager.getApplication().isReadAccessAllowed)
            action()
        }
    }
}
