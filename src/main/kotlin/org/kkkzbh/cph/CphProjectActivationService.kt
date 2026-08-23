package org.kkkzbh.cph

import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.project.Project

class CphProjectActivationService internal constructor(
    private val properties: PropertiesComponent,
) {
    fun isEnabled(): Boolean = properties.getBoolean(ENABLED_PROPERTY)

    fun enable() {
        properties.setValue(ENABLED_PROPERTY, true)
    }

    companion object {
        private const val ENABLED_PROPERTY = "org.kkkzbh.cph.project.enabled"

        fun getInstance(project: Project): CphProjectActivationService =
            CphProjectActivationService(PropertiesComponent.getInstance(project))
    }
}
