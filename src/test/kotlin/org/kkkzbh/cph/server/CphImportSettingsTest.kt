package org.kkkzbh.cph.server

import org.junit.Assert.assertEquals
import org.junit.Test

class CphImportSettingsTest {
    @Test
    fun defaultStateUsesMultiOjImportPath() {
        val service = CphImportSettings()

        assertEquals("problems", service.state.sourceRoot)
        assertEquals("\${source}/\${contest}/\${index}.cpp", service.state.pathTemplate)
    }

    @Test
    fun blankLoadedStateUsesMultiOjImportPath() {
        val service = CphImportSettings()

        service.loadState(CphImportSettingsState(sourceRoot = "", pathTemplate = ""))

        assertEquals("problems", service.state.sourceRoot)
        assertEquals("\${source}/\${contest}/\${index}.cpp", service.state.pathTemplate)
    }

    @Test
    fun legacyDefaultImportPathMigratesToMultiOjDefault() {
        val service = CphImportSettings()

        service.loadState(
            CphImportSettingsState(
                sourceRoot = "cf",
                pathTemplate = "\${contest}/\${index}.cpp",
            ),
        )

        assertEquals("problems", service.state.sourceRoot)
        assertEquals("\${source}/\${contest}/\${index}.cpp", service.state.pathTemplate)
    }

    @Test
    fun customImportPathIsNotMigrated() {
        val service = CphImportSettings()

        service.loadState(
            CphImportSettingsState(
                sourceRoot = "cf",
                pathTemplate = "custom/\${contest}/\${index}.cpp",
            ),
        )

        assertEquals("cf", service.state.sourceRoot)
        assertEquals("custom/\${contest}/\${index}.cpp", service.state.pathTemplate)
    }
}
