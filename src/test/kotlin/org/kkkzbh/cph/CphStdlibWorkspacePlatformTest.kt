package org.kkkzbh.cph

import com.intellij.codeInsight.CodeInsightSettings
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.testFramework.HeavyPlatformTestCase
import com.intellij.testFramework.PlatformTestUtil
import com.jetbrains.cidr.lang.CLanguageKind
import com.jetbrains.cidr.lang.OCLanguageKind
import com.jetbrains.cidr.lang.toolchains.CidrCompilerSwitches
import com.jetbrains.cidr.lang.workspace.OCCompilerSettings
import com.jetbrains.cidr.lang.workspace.OCVariant
import com.jetbrains.cidr.lang.workspace.OCWorkspace
import com.jetbrains.cidr.lang.workspace.compiler.GCCCompilerKind
import com.jetbrains.cidr.lang.workspace.moduleRoots.ModuleSearchPath
import java.io.File

class CphStdlibWorkspacePlatformTest : HeavyPlatformTestCase() {
    override fun getOpenProjectOptions() = super.getOpenProjectOptions().runPostStartUpActivities(false)
    override fun setUp() {
        super.setUp()
        setDefaultCodeInsightSettings(CodeInsightSettings.getInstance().clone())
    }

    fun testModuleSourcesReachNovaAndSurviveNativeConfigurationRebuilds() {
        val dir = createTempDir("nova std modules")
        val disk = File(dir, "main.cpp").apply { writeText("import std;\nint main() {}") }
        val source = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(disk)!!
        val modules = listOf("std.cc", "std.compat.cc").map { File(dir, it).apply { writeText("export module ${it.removeSuffix(".cc")};") } }
        val workspace = OCWorkspace.getInstance(project)
        workspace.javaClass.getMethod("initComponent").invoke(workspace)
        fun rebuild() {
            val model = workspace.getModifiableModel("SINGLE_FILE", true)
            val configuration = model.addConfiguration("main-test", "main.cpp", OCVariant("test"))
            configuration.addSource(source, CLanguageKind.CPP)
            val settings = configuration.getLanguageCompilerSettings(CLanguageKind.CPP)
            settings.setCompiler(GCCCompilerKind, File("/usr/bin/c++"), dir)
            settings.setCompilerSwitches(CidrCompilerSwitches(listOf("-std=c++26", "-DVALUE=9")))
            settings.setCompilerFeatures(mapOf(com.jetbrains.cidr.lang.workspace.compiler.OCCompilerFeatures.LANGUAGE_STANDARD
                to com.jetbrains.cidr.lang.OCLanguageStandard.CPP26))
            settings.setPreprocessorDefines(listOf("#define __GNUC__ 16", "#define __cplusplus 202400L"))
            settings.setModuleSearchPaths(listOf(ModuleSearchPath(File(dir, "project.cppm").toPath())))
            model.preCommit()
            WriteAction.run<RuntimeException> { model.commit() }
        }
        fun paths() = ReadAction.compute<List<String>, RuntimeException> {
            workspace.getConfigurationById("main-test")!!.getCompilerSettings(CLanguageKind.CPP, source)
                .moduleSearchPaths.map { it.modulePath.toString() }
        }
        rebuild()
        val service = project.getService(CphStdlibWorkspaceService::class.java)
        PlatformTestUtil.waitForFuture(ApplicationManager.getApplication().executeOnPooledThread {
            service.update(disk, modules)
        })
        val expected = listOf(File(dir, "project.cppm").path) + modules.map { it.path }
        assertEquals(expected, paths())

        // Exercise the real Nova snapshot conversion rather than clangd's separate module map.
        val plugin = PluginManagerCore.getPlugin(PluginId.getId("org.jetbrains.plugins.clion.radler"))!!
        val type = plugin.pluginClassLoader!!.loadClass("com.intellij.rider.cpp.core.projectModel.RadProjectSnapshotBuilder")
        val builder = type.getConstructor().newInstance()
        val nativeSettings = ReadAction.compute<Any, RuntimeException> {
            type.getMethod("convertSettings", OCLanguageKind::class.java, OCCompilerSettings::class.java)
                .invoke(builder, CLanguageKind.CPP,
                    workspace.getConfigurationById("main-test")!!.getCompilerSettings(CLanguageKind.CPP, source))
        }
        val novaPaths = nativeSettings.javaClass.getMethod("getModuleSearchPaths").invoke(nativeSettings) as List<*>
        assertEquals(expected, novaPaths.map { it!!.javaClass.getMethod("getModulePath").invoke(it) })
        rebuild()
        PlatformTestUtil.waitWithEventsDispatching("Restore std module paths after native rebuild", { paths() == expected }, 20)
        PlatformTestUtil.waitForFuture(ApplicationManager.getApplication().executeOnPooledThread {
            service.update(disk, emptyList())
        })
        assertEquals(listOf(File(dir, "project.cppm").path), paths())
    }
}
