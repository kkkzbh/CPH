package org.kkkzbh.cph

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CphCompileOptionsTest {
    @Test
    fun mergeCompilerArgsAppendsAdditionalOptionsWhenFollowingTarget() {
        assertEquals(
            listOf("-O0", "-std=c++11", "-Wall"),
            CphCompileOptions.mergeCompilerArgs(
                originalArgs = listOf("-O0", "-std=c++11"),
                additionalArgs = listOf("-Wall"),
                standard = CphCppStandard.FOLLOW_TARGET,
            ),
        )
    }

    @Test
    fun mergeCompilerArgsLetsComboStandardOverrideExistingAndAdditionalStandards() {
        assertEquals(
            listOf("-O0", "-Wall", "-std=c++20"),
            CphCompileOptions.mergeCompilerArgs(
                originalArgs = listOf("-O0", "-std=c++11"),
                additionalArgs = listOf("-Wall", "-std=c++17"),
                standard = CphCppStandard.CPP20,
            ),
        )
    }

    @Test
    fun removeStandardFlagsHandlesGccClangAndMsvcForms() {
        assertEquals(
            listOf("-O2", "-Wall"),
            CphCompileOptions.removeStandardFlags(
                listOf("-O2", "-std=c++17", "/std:c++20", "-std", "c++11", "/std", "c++23", "-Wall"),
            ),
        )
    }

    @Test
    fun parseShellLikeSupportsQuotedArguments() {
        assertEquals(
            listOf("-DNAME=value with spaces", "-O2", "-I/tmp/a b"),
            CphCompileOptions.parseShellLike("'-DNAME=value with spaces' -O2 \"-I/tmp/a b\""),
        )
    }

    @Test
    fun quoteCMakeArgumentEscapesSpecialCharacters() {
        assertEquals(
            "\"-DNAME=value with spaces\"",
            CphCompileOptions.quoteCMakeArgument("-DNAME=value with spaces"),
        )
        assertEquals(
            "\"-DPATH=C:\\\\tmp\\\\a\"",
            CphCompileOptions.quoteCMakeArgument("-DPATH=C:\\tmp\\a"),
        )
        assertEquals(
            "\"-DVALUE=\\\"quoted\\\"\\${'$'}HOME\"",
            CphCompileOptions.quoteCMakeArgument("-DVALUE=\"quoted\"\$HOME"),
        )
    }

    @Test
    fun cmakeManagedBlockRendersStandardAndFilteredCompileOptions() {
        assertEquals(
            """
            # CPH Target Runner begin: app
            set_target_properties("app" PROPERTIES
                CXX_STANDARD 23
                CXX_STANDARD_REQUIRED ON
                CXX_EXTENSIONS OFF
            )
            target_compile_options("app" PRIVATE "-O2" "-Wall")
            # CPH Target Runner end: app
            """.trimIndent(),
            CphCMakeManagedBlock.render(
                "app",
                CphCompileSettings(CphCppStandard.CPP23, "-O2 -std=c++17 -Wall"),
            ),
        )
    }

    @Test
    fun cmakeManagedBlockCanAppendReplaceAndRemoveSameTarget() {
        val original = "cmake_minimum_required(VERSION 3.25)\nadd_executable(app main.cpp)\n"
        val first = CphCMakeManagedBlock.apply(
            original,
            "app",
            CphCompileSettings(CphCppStandard.CPP20, "-O2"),
        )
        val second = CphCMakeManagedBlock.apply(
            first,
            "app",
            CphCompileSettings(CphCppStandard.CPP23, "-Wall"),
        )
        val removed = CphCMakeManagedBlock.apply(
            second,
            "app",
            CphCompileSettings(),
        )

        assertEquals(1, Regex("CPH Target Runner begin: app").findAll(first).count())
        assertEquals(1, Regex("CPH Target Runner begin: app").findAll(second).count())
        assertEquals(0, Regex("CXX_STANDARD 20").findAll(second).count())
        assertEquals(1, Regex("CXX_STANDARD 23").findAll(second).count())
        assertEquals(original, removed)
    }

    @Test
    fun cmakeManagedBlockReturnsNullWhenFollowingTargetWithNoOptions() {
        assertNull(CphCMakeManagedBlock.render("app", CphCompileSettings()))
    }

    @Test
    fun cppFileCompilerOptionsSyncWritesDedupedOptionsWithComboStandardPriority() {
        val update = CphCppFileCompilerOptionsSync.compute(
            current = "-O0 -std=c++11",
            syncedBase = "",
            syncedApplied = "",
            settings = CphCompileSettings(CphCppStandard.CPP23, "-Wall -std=c++17"),
        )

        assertEquals("-O0 -Wall -std=c++23", update.compilerOptions)
        assertEquals("-O0 -std=c++11", update.syncedBase)
        assertEquals("-O0 -Wall -std=c++23", update.syncedApplied)
    }

    @Test
    fun cppFileCompilerOptionsSyncRestoresBaseWhenOverridesAreCleared() {
        val update = CphCppFileCompilerOptionsSync.compute(
            current = "-O0 -Wall -std=c++23",
            syncedBase = "-O0 -std=c++11",
            syncedApplied = "-O0 -Wall -std=c++23",
            settings = CphCompileSettings(),
        )

        assertEquals("-O0 -std=c++11", update.compilerOptions)
        assertEquals("", update.syncedBase)
        assertEquals("", update.syncedApplied)
    }
}
