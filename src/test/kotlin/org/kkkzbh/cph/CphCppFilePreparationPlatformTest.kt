package org.kkkzbh.cph

import com.intellij.codeInsight.CodeInsightSettings
import com.intellij.execution.BeforeRunTask
import com.intellij.execution.BeforeRunTaskProvider
import com.intellij.execution.RunManager
import com.intellij.testFramework.HeavyPlatformTestCase
import com.jetbrains.cidr.cpp.runfile.CppFileBuildBeforeRunTaskProvider
import com.jetbrains.cidr.cpp.runfile.CppFileRunConfiguration
import com.jetbrains.cidr.cpp.runfile.CppFileRunConfigurationType

class CphCppFilePreparationPlatformTest : HeavyPlatformTestCase() {
    override fun getOpenProjectOptions() = super.getOpenProjectOptions().runPostStartUpActivities(false)

    override fun setUp() {
        super.setUp()
        // Use CLion's application defaults as the platform fixture baseline.
        setDefaultCodeInsightSettings(CodeInsightSettings.getInstance().clone())
    }


    @Suppress("UNCHECKED_CAST")
    fun testExistingAndNewNativeConfigurationsPrepareBeforeBuilding() {
        val type = CppFileRunConfigurationType()
        val native = CppFileBuildBeforeRunTaskProvider()
        BeforeRunTaskProvider.EP_NAME.getPoint(project).registerExtension(native as BeforeRunTaskProvider<BeforeRunTask<*>>, testRootDisposable)
        BeforeRunTaskProvider.EP_NAME.getPoint(project).registerExtension(CphCppFilePreparationProvider() as BeforeRunTaskProvider<BeforeRunTask<*>>, testRootDisposable)
        val manager = RunManager.getInstance(project)
        val factory = type.configurationFactories.single()
        val existing = manager.createConfiguration("existing.cpp", factory)
        manager.addConfiguration(existing)
        CphCppFilePreparationService(project).start()
        val created = manager.createConfiguration("created.cpp", factory)
        manager.addConfiguration(created)
        for (settings in listOf(existing, created)) {
            val tasks = (settings.configuration as CppFileRunConfiguration).beforeRunTasks
            assertEquals(CphCppFilePreparationTask.ID, tasks.first().providerId)
            assertTrue(tasks.first().isEnabled)
            assertTrue(tasks.indexOfFirst { it.providerId == native.id } > 0)
            assertEquals(1, tasks.count { it.providerId == CphCppFilePreparationTask.ID })
        }
        assertNotNull(BeforeRunTaskProvider.getProvider(project, CphCppFilePreparationTask.ID))
    }
}
