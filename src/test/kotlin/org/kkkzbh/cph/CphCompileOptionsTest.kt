package org.kkkzbh.cph

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
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
            current = "",
            settings = CphCompileSettings(CphCppStandard.CPP23, "-Wall -std=c++17"),
        )

        assertEquals("-Wall -std=c++23", update.compilerOptions)
        assertEquals(true, update.changed)
    }

    @Test
    fun cppFileCompilerOptionsSyncClearsOptionsWhenOverridesAreCleared() {
        val update = CphCppFileCompilerOptionsSync.compute(
            current = "-O0 -Wall -std=c++23",
            settings = CphCompileSettings(),
        )

        assertEquals("", update.compilerOptions)
        assertEquals(true, update.changed)
    }

    @Test
    fun cppFileCompilerOptionsSyncAppendsManagedPchArgs() {
        val update = CphCppFileCompilerOptionsSync.compute(
            current = "",
            settings = CphCompileSettings(CphCppStandard.CPP20, "-O2"),
            managedArgs = listOf("-I", "/tmp/cph pch"),
        )

        assertEquals("-O2 -std=c++20 -I '/tmp/cph pch'", update.compilerOptions)
        assertEquals(true, update.changed)
    }

    @Test
    fun cppFileCompilerOptionsSyncStripsOnlyManagedPchArgs() {
        assertEquals(
            "-O2 -I /tmp/keep -Wall",
            CphCppFileCompilerOptionsSync.withoutManagedGccAccelArgs(
                "-O2 -I /tmp/keep -I /home/u/.cache/JetBrains/CLion/cph-target-runner/pch/abc -Wall",
            ),
        )
        assertEquals(
            "-O2",
            CphCppFileCompilerOptionsSync.withoutManagedGccAccelArgs(
                "-O2 -IC:\\Users\\u\\AppData\\Local\\JetBrains\\CLion\\cph-target-runner\\pch\\abc",
            ),
        )
    }

    @Test
    fun cppFileCompilerOptionsSyncStripsManagedStdModuleArgs() {
        assertEquals(
            "-O2 -Wall",
            CphCppFileCompilerOptionsSync.withoutManagedGccAccelArgs(
                "-O2 -fmodules -fmodule-mapper=/home/u/.cache/JetBrains/CLion/cph-target-runner/std-modules/abc/mapper.txt -Wall",
            ),
        )
        assertEquals(
            "-O2 -fmodules",
            CphCppFileCompilerOptionsSync.withoutManagedGccAccelArgs("-O2 -fmodules"),
        )
    }

    @Test
    fun gccPchDetectsBitsHeaderImportStdAndGccVersion() {
        assertEquals(true, CphGccPchService.sourceIncludesBitsHeader("#include <bits/stdc++.h>\n"))
        assertEquals(true, CphGccPchService.sourceIncludesBitsHeader(" # include \"bits/stdc++.h\"\n"))
        assertEquals(false, CphGccPchService.sourceIncludesBitsHeader("#include <vector>\n"))
        assertEquals(false, CphGccPchService.sourceIncludesBitsHeader("// #include <bits/stdc++.h>\n"))
        assertEquals(false, CphGccPchService.sourceIncludesBitsHeader("/*\n#include <bits/stdc++.h>\n*/\n"))
        assertEquals(true, CphGccPchService.sourceUsesImportStd("import std;\n"))
        assertEquals(true, CphGccPchService.sourceUsesImportStd(" import std.compat;\n"))
        assertEquals(false, CphGccPchService.sourceUsesImportStd("// import std;\n"))
        assertEquals(false, CphGccPchService.sourceUsesImportStd("/* import std; */\n"))
        assertEquals(true, CphGccPchService.isGccVersion("g++ (GCC) 15.1.1"))
        assertEquals(false, CphGccPchService.isGccVersion("clang version 19.1.0"))
    }

    @Test
    fun gccMajorVersionParsesGccAndRejectsClang() {
        assertEquals(16, CphGccPchService.gccMajorVersion("g++ (GCC) 16.1.1 20260501 (Red Hat 16.1.1-1)"))
        assertEquals(15, CphGccPchService.gccMajorVersion("g++.exe (Rev2, Built by MSYS2 project) 15.2.0"))
        assertNull(CphGccPchService.gccMajorVersion("clang version 19.1.0"))
    }

    @Test
    fun gccPchKeyChangesWithCompileOptions() {
        val header = File("/usr/include/c++/bits/stdc++.h")
        val first = CphGccPchService.pchKey("g++", "g++ (GCC) 15.1.1", header, CphCompileSettings(compileOptions = "-O2"))
        val second = CphGccPchService.pchKey("g++", "g++ (GCC) 15.1.1", header, CphCompileSettings(compileOptions = "-O0"))

        assertEquals(false, first == second)
    }

    @Test
    fun gccPchKeyChangesWithCompilerPath() {
        val header = File("/usr/include/c++/bits/stdc++.h")
        val first = CphGccPchService.pchKey("/opt/toolchains/gcc/bin/g++", "g++ (GCC) 15.1.1", header, CphCompileSettings())
        val second = CphGccPchService.pchKey("C:\\mingw\\bin\\g++.exe", "g++ (GCC) 15.1.1", header, CphCompileSettings())

        assertEquals(false, first == second)
    }

    @Test
    fun gccPchParsesDependencyOutputForBitsHeader() {
        assertEquals(
            "/usr/include/c++/16/x86_64-redhat-linux/bits/stdc++.h",
            CphGccPchService.parseBitsHeaderDependency(
                """
                cph_pch_probe: /tmp/main.cpp \
                 /usr/include/c++/16/x86_64-redhat-linux/bits/stdc++.h \
                 /usr/include/c++/16/vector
                """.trimIndent(),
            ),
        )
        assertEquals(
            "C:/msys64/ucrt64/include/c++/15/x86_64-w64-mingw32/bits/stdc++.h",
            CphGccPchService.parseBitsHeaderDependency(
                "cph_pch_probe: C:/msys64/ucrt64/include/c++/15/x86_64-w64-mingw32/bits/stdc++.h",
            ),
        )
        assertEquals(
            "C:/msys64/ucrt64/include/c++/15/x86_64-w64-mingw32/bits/stdc++.h",
            CphGccPchService.parseBitsHeaderDependency(
                "cph_pch_probe: C\\:/msys64/ucrt64/include/c++/15/x86_64-w64-mingw32/bits/stdc++.h",
            ),
        )
        assertEquals(
            "C:\\msys64\\ucrt64\\include\\c++\\15\\x86_64-w64-mingw32\\bits\\stdc++.h",
            CphGccPchService.parseBitsHeaderDependency(
                "cph_pch_probe: C:\\msys64\\ucrt64\\include\\c++\\15\\x86_64-w64-mingw32\\bits\\stdc++.h",
            ),
        )
        assertEquals(
            "/opt/gcc 16/include/c++/bits/stdc++.h",
            CphGccPchService.parseBitsHeaderDependency(
                "cph_pch_probe: /opt/gcc\\ 16/include/c++/bits/stdc++.h /usr/include/vector",
            ),
        )
    }

    @Test
    fun gccPchProbeKeyChangesWithCompilerToolchainStandardAndOptions() {
        val base = CphBitsHeaderProbeInput(
            compilerPath = "/usr/bin/g++",
            compilerLastModified = 1L,
            compilerLength = 2L,
            toolchainName = "Default",
            toolchainEnvironment = "PATH=/usr/bin",
            standardFlag = "-std=c++26",
            compileOptions = "-O2",
        )
        val baseKey = CphGccPchService.probeKey(base)

        listOf(
            base.copy(compilerPath = "/opt/gcc/bin/g++"),
            base.copy(compilerLastModified = 3L),
            base.copy(compilerLength = 4L),
            base.copy(toolchainName = "MinGW"),
            base.copy(toolchainEnvironment = "PATH=C:\\msys64\\ucrt64\\bin"),
            base.copy(standardFlag = "-std=c++23"),
            base.copy(compileOptions = "-O0"),
        ).forEach {
            assertEquals(false, baseKey == CphGccPchService.probeKey(it))
        }
    }

    @Test
    fun gccStdModuleKeyChangesWithCompilerToolchainStandardOptionsAndVersion() {
        val base = CphBitsHeaderProbeInput(
            compilerPath = "/usr/bin/g++",
            compilerLastModified = 1L,
            compilerLength = 2L,
            toolchainName = "Default",
            toolchainEnvironment = "PATH=/usr/bin",
            standardFlag = "-std=c++26",
            compileOptions = "-O2",
        )
        val baseKey = CphGccPchService.stdModuleKey(base, "g++ (GCC) 16.1.1")

        listOf(
            CphGccPchService.stdModuleKey(base.copy(compilerPath = "/opt/gcc/bin/g++"), "g++ (GCC) 16.1.1"),
            CphGccPchService.stdModuleKey(base.copy(compilerLastModified = 3L), "g++ (GCC) 16.1.1"),
            CphGccPchService.stdModuleKey(base.copy(toolchainEnvironment = "PATH=/opt/gcc/bin"), "g++ (GCC) 16.1.1"),
            CphGccPchService.stdModuleKey(base.copy(standardFlag = "-std=c++23"), "g++ (GCC) 16.1.1"),
            CphGccPchService.stdModuleKey(base.copy(compileOptions = "-O0"), "g++ (GCC) 16.1.1"),
            CphGccPchService.stdModuleKey(base, "g++ (GCC) 16.2.0"),
        ).forEach {
            assertEquals(false, baseKey == it)
        }
    }

    @Test
    fun gccPchPositiveProbeCacheHitDoesNotInvokeProbe() {
        val dir = Files.createTempDirectory("cph-probe-cache").toFile()
        try {
            val header = File(dir, "bits/stdc++.h")
            header.parentFile.mkdirs()
            header.writeText("// header")
            val input = sampleProbeInput(dir)
            val cache = CphBitsHeaderProbeCache(dir)
            cache.resolve(input) {
                CphBitsHeaderProbeResolution.Found(header, "g++ (GCC) 16.1.1", "header probe 1ms")
            }

            val reloaded = CphBitsHeaderProbeCache(dir)
            var called = false
            val hit = reloaded.resolve(input) {
                called = true
                CphBitsHeaderProbeResolution.Missing("should not run")
            }

            assertEquals(false, called)
            assertEquals(true, hit is CphBitsHeaderProbeResolution.Found)
            assertEquals("probe cache hit", (hit as CphBitsHeaderProbeResolution.Found).summary)
            assertEquals(header.canonicalFile, hit.header)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun gccPchNegativeProbeCacheIsInMemoryOnly() {
        val dir = Files.createTempDirectory("cph-probe-cache").toFile()
        try {
            val input = sampleProbeInput(dir)
            val cache = CphBitsHeaderProbeCache(dir)
            cache.resolve(input) {
                CphBitsHeaderProbeResolution.Missing("no bits header: g++")
            }

            var calledAgain = false
            val cachedMiss = cache.resolve(input) {
                calledAgain = true
                CphBitsHeaderProbeResolution.Missing("should not run")
            }
            assertEquals(false, calledAgain)
            assertEquals("no bits header: g++ | probe cached", (cachedMiss as CphBitsHeaderProbeResolution.Missing).summary)

            val reloaded = CphBitsHeaderProbeCache(dir)
            var calledAfterReload = false
            reloaded.resolve(input) {
                calledAfterReload = true
                CphBitsHeaderProbeResolution.Missing("no bits header: g++")
            }
            assertEquals(true, calledAfterReload)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun gccPchEscapesWindowsIncludePaths() {
        assertEquals(
            "C:\\\\mingw\\\\include\\\\c++\\\\bits\\\\stdc++.h",
            CphGccPchService.escapeIncludePath("C:\\mingw\\include\\c++\\bits\\stdc++.h"),
        )
    }

    @Test
    fun cppFileCompilerResolverKeepsBareCompilerCommandRelative() {
        assertEquals("c++", CphCppFileCompilerResolver.compilerCommandPath(Path.of("c++")))
    }

    private fun sampleProbeInput(dir: File): CphBitsHeaderProbeInput =
        CphBitsHeaderProbeInput(
            compilerPath = File(dir, "g++").absolutePath,
            compilerLastModified = 1L,
            compilerLength = 2L,
            toolchainName = "Default",
            toolchainEnvironment = "PATH=/usr/bin",
            standardFlag = "-std=c++26",
            compileOptions = "-O2",
        )
}
