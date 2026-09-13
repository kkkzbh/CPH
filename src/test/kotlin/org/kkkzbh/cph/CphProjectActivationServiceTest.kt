package org.kkkzbh.cph

import com.intellij.codeInsight.CodeInsightSettings
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.testFramework.HeavyPlatformTestCase

class CphProjectActivationServiceTest : HeavyPlatformTestCase() {
    override fun getOpenProjectOptions() = super.getOpenProjectOptions().runPostStartUpActivities(false)

    override fun setUp() {
        super.setUp()
        setDefaultCodeInsightSettings(CodeInsightSettings.getInstance().clone())
    }

    @Suppress("UNCHECKED_CAST")
    fun testProjectActivationSurvivesStateRestorationAndServiceRecreation() {
        val properties = PropertiesComponent.getInstance(project)
        val activation = CphProjectActivationService(properties)
        val storage = properties as PersistentStateComponent<Any>
        val initialState = requireNotNull(storage.state)

        assertFalse(activation.isEnabled())
        activation.enable()
        val savedState = requireNotNull(storage.state)
        storage.loadState(initialState)
        assertFalse(CphProjectActivationService(properties).isEnabled())
        storage.loadState(savedState)

        assertTrue(CphProjectActivationService(properties).isEnabled())
    }
}
