package org.kkkzbh.cph

import com.jetbrains.cidr.lang.daemon.clang.clangd.lsp.Cpp20Module
import com.jetbrains.cidr.lang.daemon.clang.clangd.lsp.params.ClionCompileCommandParams
import com.jetbrains.cidr.lang.daemon.clang.clangd.settings.CppModulesState
import org.junit.Assert.*
import org.junit.Test
import java.util.function.Function
import java.nio.file.Files
import org.junit.Rule
import org.junit.rules.TemporaryFolder

class CphStdlibCodeInsightTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun moduleAnalysisUsesClangPredefinesAndTheConsumersFlags() {
        val args = listOf("c++", "-std=c++26", "-DVALUE=3", "--target=x86_64-unknown-linux-gnu",
            "-fgnuc-version=16.1.0", "-fno-define-target-os-macros", "-isystem", "/sdk/include",
            "-Xclang", "-fcpp20-module-map-file=/cache/cpp.modulemap", "-fprebuilt-module-path=/cache/pcm",
            "--", "/project/main.cpp")
        val original = ClionCompileCommandParams("file:///project/main.cpp", "file:///project/main.cpp", "/project", args, "", false)
        val module = CphStdlibCodeInsightService.moduleCommand(original, "file:///sdk/std.cc", "/sdk/std.cc")
        assertEquals(args.dropLast(1).filterNot {
            it.startsWith("-fgnuc-version=") || it == "-fno-define-target-os-macros"
        }, module.commandLine.dropLast(1))
        assertTrue(module.commandLine.contains("--target=x86_64-unknown-linux-gnu"))
        assertTrue(module.commandLine.contains("-DVALUE=3"))
        assertEquals("/sdk/std.cc", module.commandLine.last())
        assertEquals("file:///sdk/std.cc", module.uri)
        assertEquals(module.uri, module.entryUri)
        assertEquals(original.directory, module.directory)
        assertTrue(module.usePredefines)
        assertFalse(original.usePredefines)
        assertEquals("/project/main.cpp", original.commandLine.last())
    }

    @Test
    fun unexpectedNativeCommandFailsBeforeChangingTheInput() {
        val original = ClionCompileCommandParams("file:///main.cpp", "file:///main.cpp", "/", listOf("c++", "main.cpp"), "", true)
        assertThrows(IllegalStateException::class.java) {
            CphStdlibCodeInsightService.moduleCommand(original, "file:///std.cc", "/std.cc")
        }
    }

    @Test
    fun indexRestoresAfterScanAndReplacesCompilerSources() {
        val state = ModuleState()
        val index = CphStdlibModuleIndex(state)
        val projectModule = module("solution", "/project/solution.cppm")
        state.addCppModule(projectModule)
        val old = listOf(module("std", "/gcc16/std.cc"), module("std.compat", "/gcc16/std.compat.cc"))
        assertTrue(index.replace(old))
        assertFalse(index.restore())
        assertFalse(index.replace(listOf(module("std", "/gcc16/std.cc"), module("std.compat", "/gcc16/std.compat.cc"))))
        state.clearCppModules()
        assertTrue(index.restore())
        assertEquals(old.toSet(), state.byPath { it.values.toSet() })
        state.addCppModule(projectModule)
        val next = listOf(module("std", "/gcc17/std.cc"), module("std.compat", "/gcc17/std.compat.cc"))
        assertTrue(index.replace(next))
        assertEquals((next + projectModule).toSet(), state.byPath { it.values.toSet() })
        assertTrue(index.replace(emptyList()))
        assertEquals(setOf(projectModule), state.byPath { it.values.toSet() })
        assertFalse(index.restore())
    }

    @Test
    fun cleanupPreservesAnEntryReplacedByAnotherOwner() {
        val state = ModuleState()
        val index = CphStdlibModuleIndex(state)
        index.replace(listOf(module("std", "/sdk/std.cc")))
        val external = module("std", "/sdk/std.cc")
        state.addCppModule(external)
        index.replace(emptyList())
        assertSame(external, state.byPath { it["/sdk/std.cc"] })
    }

    @Test
    fun analysisCacheFollowsTheCompilerStandardAndSourceVersion() {
        val dir = temporary.newFolder("analysis").toPath()
        val source = temporary.newFile("std.cc")
        source.writeText("export module std;")
        val unrelated = dir.resolve("solution.pcm")
        Files.writeString(unrelated, "project module")
        fun definition(standard: String, compiler: String = "c++") = listOf(Cpp20Module("std",
            ClionCompileCommandParams(source.toURI().toString(), source.toURI().toString(), dir.toString(),
                listOf(compiler, "-std=$standard", "--", source.absolutePath), "", true),
            "#define __cplusplus 202400L", source.absolutePath, false))
        val cache = CphStdlibAnalysisCache(dir)
        assertTrue(cache.prepare(definition("c++26"), "262"))
        Files.writeString(dir.resolve("std.pcm"), "26")
        Files.writeString(dir.resolve("std.compat.pcm"), "26")
        assertFalse(cache.prepare(definition("c++26"), "262"))
        assertEquals("26", Files.readString(dir.resolve("std.pcm")))
        assertTrue(cache.prepare(definition("c++20"), "262"))
        assertFalse(Files.exists(dir.resolve("std.pcm")))
        assertFalse(Files.exists(dir.resolve("std.compat.pcm")))
        assertTrue(cache.prepare(definition("c++20", "other-g++"), "262"))
        source.appendText("\nexport int value;")
        assertTrue(cache.prepare(definition("c++20", "other-g++"), "262"))
        assertTrue(cache.prepare(definition("c++20", "other-g++"), "263"))
        cache.clear()
        assertFalse(Files.exists(dir.resolve("cph-stdlib.sha256")))
        assertEquals("project module", Files.readString(unrelated))
    }

    private fun module(name: String, path: String) = Cpp20Module(name, null, null, path, false)

    private class ModuleState : CppModulesState {
        private val entries = linkedMapOf<String, Cpp20Module>()
        override fun <T : Any?> byPath(action: Function<MutableMap<String, Cpp20Module>, T>): T = action.apply(entries)
        override fun addCppModule(module: Cpp20Module) { entries[module.sourcePath] = module }
        override fun removeCppModuleByPath(path: String) { entries.remove(path) }
        override fun clearCppModules() = entries.clear()
        override fun size() = entries.size
        override fun isEmpty() = entries.isEmpty()
        override fun removeCppModulesInDir(path: String) { entries.keys.removeIf { it.startsWith(path) } }
        override fun dirContainsCppModule(path: String) = entries.keys.any { it.startsWith(path) }
        override fun hasCppModule(path: String) = entries.containsKey(path)
    }
}
