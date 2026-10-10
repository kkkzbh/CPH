package org.kkkzbh.cph

import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.vfs.LocalFileSystem
import com.jetbrains.cidr.lang.daemon.clang.clangd.lsp.ClangIdeFacadeImpl
import com.jetbrains.cidr.lang.daemon.clang.clangd.lsp.Cpp20ModulesContext
import com.jetbrains.cidr.lang.workspace.OCWorkspace
import com.jetbrains.cidr.lang.workspace.OCVariant
import com.jetbrains.cidr.lang.workspace.compiler.GCCCompilerKind
import com.jetbrains.cidr.lang.toolchains.CidrCompilerSwitches
import com.jetbrains.cidr.lang.CLanguageKind
import java.io.File
import java.util.concurrent.TimeUnit
import com.intellij.codeInsight.CodeInsightSettings
import com.intellij.testFramework.HeavyPlatformTestCase
import com.jetbrains.cidr.lang.daemon.clang.clangd.lsp.ClangUrlConverter
import com.jetbrains.cidr.lang.daemon.clang.clangd.settings.CppModulesStateUtil
import com.jetbrains.cidr.lang.daemon.clang.clangd.lsp.params.ClionCompileCommandParams
import com.jetbrains.cidr.lang.daemon.clang.clangd.settings.CppModulesStateImpl
import com.jetbrains.cidr.lang.daemon.clang.clangd.ClangLanguageService
import com.jetbrains.cidr.lang.daemon.clang.clangd.lsp.ClangDaemonContext
import com.jetbrains.cidr.lang.daemon.clang.clangd.lsp.Cpp20Module
import java.lang.reflect.Proxy

class CphStdlibCodeInsightPlatformTest : HeavyPlatformTestCase() {
    override fun getOpenProjectOptions() = super.getOpenProjectOptions().runPostStartUpActivities(false)

    override fun setUp() {
        super.setUp()
        setDefaultCodeInsightSettings(CodeInsightSettings.getInstance().clone())
    }

    fun testProjectServiceLoadsThroughThePluginDependencies() {
        assertNotNull(project.getService(CphStdlibCodeInsightService::class.java))
    }

    fun testNativeFacadeBuildsModuleCommandsFromSourceContainingImportStd() {
        val directory = createTempDir("stdlib analysis")
        val diskSource = File(directory, "main.cpp")
        diskSource.writeText("\nimport std;\nint main() { std::print(\"{:.6f}\", .123456789); }\n")
        val source = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(diskSource)!!
        val workspace = OCWorkspace.getInstance(project)
        val model = workspace.getModifiableModel("cph-stdlib-test")
        val configuration = model.addConfiguration("stdlib-test", "stdlib", OCVariant("test"))
        val compiler = configuration.addSource(source, CLanguageKind.CPP)
        compiler.setCompiler(GCCCompilerKind, File("/usr/bin/c++"), directory)
        compiler.setCompilerSwitches(CidrCompilerSwitches(listOf("-std=c++26", "-DCPH_USER_MACRO=9")))
        compiler.setPreprocessorDefines(listOf("#define CPH_MODULE_TEST 7", "#define __GNUC__ 16",
            "#define __GNUC_MINOR__ 1", "#define __GNUC_PATCHLEVEL__ 0"))
        model.preCommit()
        WriteAction.run<RuntimeException> { model.commit() }
        val facade = ClangIdeFacadeImpl("cph-stdlib-test")
        try {
            val converter = ClangUrlConverter()
            val context = Cpp20ModulesContext(
                File(directory, "cpp20.modulemap").path,
                File(directory, "modules").path,
                File(directory, "module.modulemap").path,
            )
            val command = CphStdlibCodeInsightService.moduleCompilationCommand(
                facade, converter, project, source, context,
            ).get(30, TimeUnit.SECONDS)!!
            assertEquals(converter.toUri(source), command.ccParams.uri)
            assertTrue(command.ccParams.commandLine.contains("-std=c++26"))
            assertTrue(command.ccParams.commandLine.contains("-fmodules"))
            assertFalse(command.ccParams.commandLine.any { it.startsWith("-imacros") })
            assertTrue(command.ppDefines!!.contains("CPH_MODULE_TEST"))
            val module = File(directory, "std.cc")
            val definition = CphStdlibCodeInsightService.standardLibraryModule(
                "std", command.ccParams, converter.toUri(module, false), module.path,
            )
            assertEquals("", definition.ppDefines)
            val moduleCommand = definition.compileCommand!!
            assertEquals(module.path, moduleCommand.commandLine.last())
            assertTrue(moduleCommand.usePredefines)
            assertTrue(command.ccParams.commandLine.contains("-fgnuc-version=16.1.0"))
            assertFalse(moduleCommand.commandLine.any { it.startsWith("-fgnuc-version=") })
            assertFalse(moduleCommand.commandLine.contains("-fno-define-target-os-macros"))
            assertTrue(moduleCommand.commandLine.contains("-DCPH_USER_MACRO=9"))
        } finally {
            facade.stop()
        }
    }

    fun testStandardLibrarySourcesEnterTheNativeModuleMapWithTheirAnalysisEnvironment() {
        val native = CppModulesStateImpl(project)
        val owner = CphStdlibModuleIndex(native)
        val args = listOf("c++", "-std=c++26", "-isystem", "/sdk/include", "--", "/project/main.cpp")
        val consumer = ClionCompileCommandParams("file:///project/main.cpp", "file:///project/main.cpp", "/project", args, "", true)
        val modules = listOf("std", "std.compat").map { name ->
            val path = "/sdk/$name.cc"
            CphStdlibCodeInsightService.standardLibraryModule(name, consumer, "file://$path", path)
        }
        assertTrue(owner.replace(modules))
        val map = CppModulesStateUtil.getAsCpp20ModuleMapImpl(ClangUrlConverter(), native.byPath { it.values.toMutableList() })
        assertTrue(map.contains("module std {"))
        assertTrue(map.contains("header \"/sdk/std.cc\""))
        assertTrue(map.contains("module std.compat {"))
        assertTrue(map.contains("header \"/sdk/std.compat.cc\""))
        for (module in modules) {
            val registered = native.byPath { it[module.sourcePath] }!!
            assertEquals("", registered.ppDefines)
            assertTrue(registered.compileCommand!!.usePredefines)
            assertEquals(module.sourcePath, registered.compileCommand!!.commandLine.last())
            assertEquals(args.dropLast(1), registered.compileCommand!!.commandLine.dropLast(1))
        }
        assertTrue(owner.replace(emptyList()))
        assertTrue(native.isEmpty)
    }

    fun testModulePublicationPreservesProjectModulesAndClearsTheLanguageServerState() {
        var enabled = false
        val context = Proxy.newProxyInstance(
            ClangDaemonContext::class.java.classLoader, arrayOf(ClangDaemonContext::class.java),
        ) { _, method, args ->
            when (method.name) {
                "getCpp20ModuleMapPath" -> "/cache/cpp20.modulemap"
                "setCpp20ModulesEnabled" -> { enabled = args!![0] as Boolean; null }
                else -> error("Unexpected context call: ${method.name}")
            }
        } as ClangDaemonContext
        val converter = ClangUrlConverter()
        val mappingChanges = mutableListOf<List<Any?>>()
        val documentChanges = mutableListOf<Pair<String, String>>()
        val server = Proxy.newProxyInstance(
            ClangLanguageService::class.java.classLoader, arrayOf(ClangLanguageService::class.java),
        ) { _, method, args ->
            when (method.name) {
                "getContext" -> context
                "getUrlConverter" -> converter
                "notifyModuleMappingChanged" -> { mappingChanges.add(args!!.toList()); null }
                "notifyDocumentChanged" -> { documentChanges.add(args!![0] as String to args[1] as String); null }
                else -> error("Unexpected language service call: ${method.name}")
            }
        } as ClangLanguageService
        val native = CppModulesStateImpl(project)
        val projectModule = Cpp20Module("solution", null, null, "/project/solution.cppm", false)
        native.addCppModule(projectModule)
        val owner = CphStdlibModuleIndex(native)
        val command = ClionCompileCommandParams(
            "file:///project/main.cpp", "file:///project/main.cpp", "/project",
            listOf("c++", "-std=c++26", "--", "/project/main.cpp"), "", true,
        )
        val std = CphStdlibCodeInsightService.standardLibraryModule(
            "std", command, "file:///sdk/std.cc", "/sdk/std.cc",
        )
        owner.replace(listOf(std))
        CphStdlibCodeInsightService.publishModuleMap(server, native)
        assertEquals(listOf("std"), mappingChanges.last()[0])
        assertEquals(listOf(std.compileCommand), mappingChanges.last()[1])
        assertEquals(listOf(""), mappingChanges.last()[2])
        assertEquals("file:///cache/cpp20.modulemap", documentChanges.last().first)
        assertTrue(documentChanges.last().second.contains("module std {"))
        assertTrue(documentChanges.last().second.contains("module solution {"))
        assertTrue(enabled)

        owner.replace(emptyList())
        CphStdlibCodeInsightService.publishModuleMap(server, native)
        assertEquals(emptyList<String>(), mappingChanges.last()[0])
        assertFalse(documentChanges.last().second.contains("module std {"))
        assertTrue(documentChanges.last().second.contains("module solution {"))
        assertSame(projectModule, native.byPath { it[projectModule.sourcePath] })
        assertTrue(enabled)

        native.clearCppModules()
        CphStdlibCodeInsightService.publishModuleMap(server, native)
        assertFalse(documentChanges.last().second.contains("module solution {"))
        assertFalse(enabled)
        assertEquals(3, documentChanges.size)
    }
}
