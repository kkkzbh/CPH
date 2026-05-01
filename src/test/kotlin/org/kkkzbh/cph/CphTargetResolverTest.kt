package org.kkkzbh.cph

import org.junit.Assert.assertEquals
import org.junit.Test

class CphTargetResolverTest {
    @Test
    fun detectsCMakeApplicationConfigurations() {
        assertEquals(
            CphTargetKind.CMAKE_APP,
            CphTargetResolver.detectTargetKind(
                typeId = "CMakeRunConfiguration",
                typeName = "CMake Application",
                className = "com.jetbrains.cidr.cpp.execution.CMakeAppRunConfiguration",
            ),
        )
    }

    @Test
    fun detectsCppFileConfigurations() {
        assertEquals(
            CphTargetKind.CPP_FILE,
            CphTargetResolver.detectTargetKind(
                typeId = "CLionRunFile",
                typeName = "C/C++ File",
                className = "com.jetbrains.cidr.cpp.runfile.CppFileRunConfiguration",
            ),
        )
    }

    @Test
    fun detectsUnsupportedConfigurations() {
        assertEquals(
            CphTargetKind.UNSUPPORTED,
            CphTargetResolver.detectTargetKind(
                typeId = "PythonConfigurationType",
                typeName = "Python",
                className = "com.jetbrains.python.run.PythonRunConfiguration",
            ),
        )
    }

    @Test
    fun cppFileDisplayNamePrefersSourceFileName() {
        assertEquals("a.cpp", CphTargetResolver.cppFileDisplayName("configuration", "/tmp/project/a.cpp"))
        assertEquals("configuration", CphTargetResolver.cppFileDisplayName("configuration", null))
    }

    @Test
    fun errorTooltipIncludesResultMessage() {
        val tooltip = CphUiText.errorTooltip(
            "Case",
            CphCaseResult(
                verdict = CphVerdict.ERROR,
                durationMillis = 7,
                message = "Build failed for C/C++ File configuration 'a.cpp'.",
            ),
        )

        assertEquals("Case: error in 7ms: Build failed for C/C++ File configuration 'a.cpp'.", tooltip)
    }

    @Test
    fun errorStatusMessageMarksBuildFailureAsCompileError() {
        val message = CphUiText.errorStatusMessage(
            "Case 1",
            CphCaseResult(
                verdict = CphVerdict.ERROR,
                stderr = "main.cpp:3:5: error: expected ';'\n",
                durationMillis = 42,
                message = "Build failed for target 'main'.",
            ),
        )

        assertEquals("CPH: Case 1 CE - Build failed for target 'main': main.cpp:3:5: error: expected ';'", message)
    }

    @Test
    fun errorStatusMessageMarksSetupFailureAsErr() {
        val message = CphUiText.errorStatusMessage(
            "Case 2",
            CphCaseResult(
                verdict = CphVerdict.ERROR,
                message = "Cannot resolve executable for 'demo'.",
            ),
        )

        assertEquals("CPH: Case 2 ERR - Cannot resolve executable for 'demo'", message)
    }

    @Test
    fun errorStatusMessageFallsBackForEmptyResult() {
        val message = CphUiText.errorStatusMessage("Case 3", CphCaseResult(verdict = CphVerdict.ERROR))

        assertEquals("CPH: Case 3 ERR - Unknown CPH error.", message)
    }
}
