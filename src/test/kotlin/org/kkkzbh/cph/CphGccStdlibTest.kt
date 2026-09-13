package org.kkkzbh.cph

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class CphGccStdlibTest {
    @Test
    fun compilerSelectionUsesConfigurationThenRegistryThenToolchain() {
        val platform = CphCppCompilerPlatform.UNIX
        assertEquals("/explicit/g++", CphCppFileCompilerResolver.selectCompiler("/explicit/g++", "/registry/g++", "/toolchain/g++", platform))
        assertEquals("/registry/g++", CphCppFileCompilerResolver.selectCompiler(null, "/registry/g++", "/toolchain/g++", platform))
        assertEquals("/toolchain/g++", CphCppFileCompilerResolver.selectCompiler(null, null, "/toolchain/g++", platform))
    }

    @Test
    fun compilerDefaultsMatchTheSelectedClionToolchainPlatform() {
        for ((platform, command) in mapOf(
            CphCppCompilerPlatform.UNIX to "c++",
            CphCppCompilerPlatform.MINGW to "g++.exe",
            CphCppCompilerPlatform.CYGWIN to "c++.exe",
            CphCppCompilerPlatform.MSVC to "cl.exe",
        )) {
            assertEquals(command, CphCppFileCompilerResolver.selectCompiler(null, null, null, platform))
        }
    }

    @Test
    fun automaticCppPreparationAppliesToCppSources() {
        assertTrue(CphCppFileCompilerResolver.isCppSource("main.cpp"))
        assertTrue(CphCppFileCompilerResolver.isCppSource("main.cc"))
        assertTrue(CphCppFileCompilerResolver.isCppSource("main.cxx"))
        assertFalse(CphCppFileCompilerResolver.isCppSource("main.c"))
    }

    @Test
    fun automaticModulesRequireGcc16AndAnEffectiveCpp20Standard() {
        for (gcc in listOf(15, 16, 17)) {
            for (standard in listOf(201103L, 201703L, 202002L, 202302L, 202400L)) {
                assertEquals(gcc >= 16 && standard >= 202002L, CphGccStdlibService.supportsStdModules(gcc, standard))
            }
        }
    }

    @Test
    fun effectiveStandardComesFromCompilerMacrosIncludingFollowTarget() {
        assertEquals(202400L, CphGccStdlibService.parseCppVersion("#define __GNUC__ 16\n#define __cplusplus 202400L\n"))
        assertEquals(201703L, CphGccStdlibService.parseCppVersion("#define __cplusplus 201703L\n"))
        assertThrows(IllegalStateException::class.java) { CphGccStdlibService.parseCppVersion("") }
    }

    @Test
    fun disablingBitsAccelerationKeepsNamedModuleMappings() {
        val cache = File("/cache/named")
        val header = File("/include/bits/stdc++.h")
        val named = CphGccStdlibService.stdModuleMapperText(cache, header, false)
        val bits = CphGccStdlibService.stdModuleMapperText(cache, header, true)
        assertTrue(named.contains("std std.gcm\nstd.compat std.compat.gcm\n"))
        assertFalse(named.contains("stdc++.h"))
        assertTrue(bits.startsWith(named))
        assertTrue(bits.contains(header.absolutePath))
    }

    @Test
    fun repeatedSyncAndBitsToggleKeepAutomaticModuleArguments() {
        val settings = CphCompileSettings(CphCppStandard.CPP26, "-O2 -Wall", false)
        val namedArgs = listOf("-fmodules", "-fmodule-mapper=/cache/named/mapper.txt")
        val bitsArgs = listOf("-fmodules", "-fmodule-mapper=/cache/bits/mapper.txt")
        val first = CphCppFileCompilerOptionsSync.compute("-std=c++17", settings, namedArgs)
        assertFalse(CphCppFileCompilerOptionsSync.compute(first.compilerOptions, settings, namedArgs).changed)
        val enabled = CphCppFileCompilerOptionsSync.compute(first.compilerOptions, settings.copy(gccBitsPchEnabled = true), bitsArgs)
        val disabled = CphCppFileCompilerOptionsSync.compute(enabled.compilerOptions, settings, namedArgs)
        assertEquals(first.compilerOptions, disabled.compilerOptions)
        assertTrue(disabled.compilerOptions.contains("-fmodules"))
        assertFalse(disabled.compilerOptions.contains("/cache/bits/"))
        val olderStandard = CphCppFileCompilerOptionsSync.compute(disabled.compilerOptions, settings.copy(cppStandard = CphCppStandard.CPP17), emptyList())
        assertEquals("-O2 -Wall -std=c++17", olderStandard.compilerOptions)
    }

    @Test
    fun moduleMetadataResolvesRelativeSourcesInDependencyOrder() {
        val root = Files.createTempDirectory("cph-module-metadata").toFile()
        try {
            File(root, "std.cc").writeText("export module std;")
            File(root, "std.compat.cc").writeText("export module std.compat;")
            val metadata = File(root, "lib/libstdc++.modules.json")
            metadata.parentFile.mkdirs()
            metadata.writeText("""{"modules":[{"logical-name":"std.compat","source-path":"../std.compat.cc"},{"logical-name":"std","source-path":"../std.cc"}]}""")
            assertEquals(listOf(File(root, "std.cc"), File(root, "std.compat.cc")), CphGccStdlibService.parseStdModuleSources(metadata))
            File(root, "std.cc").delete()
            assertThrows(IllegalStateException::class.java) { CphGccStdlibService.parseStdModuleSources(metadata) }
        } finally {
            root.deleteRecursively()
        }
    }
}
