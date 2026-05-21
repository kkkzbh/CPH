package org.kkkzbh.cph.server

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

internal const val CPH_DEFAULT_SOURCE_ROOT = "problems"
internal const val CPH_DEFAULT_PATH_TEMPLATE = "\${source}/\${contest}/\${index}.cpp"
private const val CPH_LEGACY_DEFAULT_SOURCE_ROOT = "cf"
private const val CPH_LEGACY_DEFAULT_PATH_TEMPLATE = "\${contest}/\${index}.cpp"
internal const val CPH_DEFAULT_HTTP_PATH = "/"
internal const val CPH_DEFAULT_PORT = 10043
internal const val CPH_MIN_PORT = 1
internal const val CPH_MAX_PORT = 65535
internal const val CPH_DEFAULT_CPP_TEMPLATE = """#include <bits/stdc++.h>
using namespace std;

int main() {
    ios::sync_with_stdio(false);
    cin.tie(nullptr);

    return 0;
}
"""

internal data class CphImportSettingsState(
    var sourceRoot: String = CPH_DEFAULT_SOURCE_ROOT,
    var pathTemplate: String = CPH_DEFAULT_PATH_TEMPLATE,
    var httpPath: String = CPH_DEFAULT_HTTP_PATH,
    var cppTemplatePath: String = "",
    var cppTemplate: String = CPH_DEFAULT_CPP_TEMPLATE,
    var overwriteExisting: Boolean = false,
    var port: Int = CPH_DEFAULT_PORT,
    var enabled: Boolean = true,
)

@State(name = "CphImportSettings", storages = [Storage("cph-import-settings.xml")])
internal class CphImportSettings : PersistentStateComponent<CphImportSettingsState> {
    private var state = CphImportSettingsState()

    override fun getState(): CphImportSettingsState = state

    override fun loadState(state: CphImportSettingsState) {
        val loadedSourceRoot = state.sourceRoot.ifBlank { CPH_DEFAULT_SOURCE_ROOT }
        val loadedPathTemplate = state.pathTemplate.ifBlank { CPH_DEFAULT_PATH_TEMPLATE }
        val isLegacyDefault =
            loadedSourceRoot == CPH_LEGACY_DEFAULT_SOURCE_ROOT &&
                loadedPathTemplate == CPH_LEGACY_DEFAULT_PATH_TEMPLATE
        this.state = state.copy(
            sourceRoot = if (isLegacyDefault) CPH_DEFAULT_SOURCE_ROOT else loadedSourceRoot,
            pathTemplate = if (isLegacyDefault) CPH_DEFAULT_PATH_TEMPLATE else loadedPathTemplate,
            httpPath = normalizeHttpPath(state.httpPath),
            cppTemplatePath = state.cppTemplatePath.trim(),
            cppTemplate = state.cppTemplate.ifEmpty { CPH_DEFAULT_CPP_TEMPLATE },
            port = clampPort(state.port),
        )
    }

    companion object {
        fun getInstance(): CphImportSettings =
            ApplicationManager.getApplication().getService(CphImportSettings::class.java)

        fun clampPort(value: Int): Int =
            if (value in CPH_MIN_PORT..CPH_MAX_PORT) value else CPH_DEFAULT_PORT

        fun normalizeHttpPath(value: String): String {
            val trimmed = value.trim()
            if (trimmed.isBlank() || trimmed == "/") return CPH_DEFAULT_HTTP_PATH
            return "/" + trimmed.trim('/')
        }
    }
}
