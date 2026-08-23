package org.kkkzbh.cph

import com.intellij.configurationStore.StoreUtil
import com.intellij.ide.util.PropertiesComponent
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class CphProjectActivationServiceTest : BasePlatformTestCase() {
    fun testProjectActivationSurvivesSettingsSaveAndServiceRecreation() {
        val properties = PropertiesComponent.getInstance(project)
        val activation = CphProjectActivationService(properties)

        assertFalse(activation.isEnabled())
        activation.enable()
        StoreUtil.saveSettings(project, true)

        assertTrue(CphProjectActivationService(properties).isEnabled())
    }
}
