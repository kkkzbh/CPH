package org.kkkzbh.cph

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.util.messages.Topic
import java.util.UUID

internal const val CPH_DEFAULT_TIMEOUT_MILLIS = 1000L
internal const val CPH_MIN_TIMEOUT_MILLIS = 100L
internal const val CPH_MAX_TIMEOUT_MILLIS = 60000L
internal const val CPH_DEFAULT_INPUT_HEIGHT = 150
internal const val CPH_DEFAULT_EXPECTED_HEIGHT = 135
internal const val CPH_DEFAULT_ACTUAL_HEIGHT = 135
internal const val CPH_MIN_EDITOR_HEIGHT = 80
internal const val CPH_MAX_EDITOR_HEIGHT = 800
internal const val CPH_DEFAULT_EDITOR_FONT_SIZE = 17
internal const val CPH_MIN_EDITOR_FONT_SIZE = 10
internal const val CPH_MAX_EDITOR_FONT_SIZE = 28
internal const val CPH_DEFAULT_SINGLE_FILE_WORKING_DIRECTORY = ".cph/"

enum class CphVerdict {
    NOT_RUN,
    OK,
    AC,
    WA,
    RE,
    TLE,
    ERROR,
}

data class CphCaseResult(
    var verdict: CphVerdict = CphVerdict.NOT_RUN,
    var actualOutput: String = "",
    var stderr: String = "",
    var exitCode: Int? = null,
    var durationMillis: Long = 0,
    var message: String = "",
)

data class CphTestCase(
    var id: String = UUID.randomUUID().toString(),
    var name: String = "Case",
    var input: String = "",
    var expectedOutput: String = "",
    var enabled: Boolean = true,
    var lastResult: CphCaseResult = CphCaseResult(),
)

data class CphTargetCases(
    var targetId: String = "",
    var displayName: String = "",
    var timeoutMillis: Long = CPH_DEFAULT_TIMEOUT_MILLIS,
    var ignoreTrailingWhitespace: Boolean = true,
    var cases: MutableList<CphTestCase> = mutableListOf(),
)

data class CphGlobalCompileSettings(
    var cppStandard: CphCppStandard = CphCppStandard.FOLLOW_TARGET,
    var compileOptions: String = "",
    var gccBitsPchEnabled: Boolean = false,
) {
    fun toCompileSettings(): CphCompileSettings {
        return CphCompileSettings(
            cppStandard = cppStandard,
            compileOptions = compileOptions,
            gccBitsPchEnabled = gccBitsPchEnabled,
        )
    }
}

data class CphUiState(
    var inputHeight: Int = CPH_DEFAULT_INPUT_HEIGHT,
    var expectedHeight: Int = CPH_DEFAULT_EXPECTED_HEIGHT,
    var actualHeight: Int = CPH_DEFAULT_ACTUAL_HEIGHT,
    var outputSplitEnabled: Boolean = true,
    var outputSplitRatio: Double = 0.5,
    var editorFontSize: Int = CPH_DEFAULT_EDITOR_FONT_SIZE,
    var noExpectedModeEnabled: Boolean = false,
    var showStderrEnabled: Boolean = false,
    var confidentSubmitEnabled: Boolean = false,
    var parallelCaseRunEnabled: Boolean = false,
    var settingsReturnHintShown: Boolean = false,
)

data class CphState(
    var targets: MutableMap<String, CphTargetCases> = linkedMapOf(),
    var compileSettings: CphGlobalCompileSettings = CphGlobalCompileSettings(),
    var ui: CphUiState = CphUiState(),
    var cphEnabled: Boolean = false,
    var singleFileModeEnabled: Boolean = true,
    var singleFileWorkingDirectory: String = CPH_DEFAULT_SINGLE_FILE_WORKING_DIRECTORY,
)

internal interface CphCasesChangedListener {
    fun targetCasesChanged(targetId: String)

    companion object {
        val TOPIC: Topic<CphCasesChangedListener> =
            Topic.create("CPH target cases changed", CphCasesChangedListener::class.java)
    }
}

@State(name = "CphTargetRunnerState", storages = [Storage("cph-target-runner.xml")])
class CphStateService : PersistentStateComponent<CphState> {
    private var state = CphState()

    override fun getState(): CphState = state

    override fun loadState(state: CphState) {
        if (!state.cphEnabled && state.targets.isNotEmpty()) {
            state.cphEnabled = true
        }
        state.targets.values.forEach {
            it.timeoutMillis = it.timeoutMillis.coerceIn(CPH_MIN_TIMEOUT_MILLIS, CPH_MAX_TIMEOUT_MILLIS)
        }
        state.ui.inputHeight = clampEditorHeight(state.ui.inputHeight)
        state.ui.expectedHeight = clampEditorHeight(state.ui.expectedHeight)
        state.ui.actualHeight = clampEditorHeight(state.ui.actualHeight)
        state.ui.outputSplitRatio = clampOutputSplitRatio(state.ui.outputSplitRatio)
        state.ui.editorFontSize = clampEditorFontSize(state.ui.editorFontSize)
        state.singleFileWorkingDirectory = normalizeSingleFileWorkingDirectory(state.singleFileWorkingDirectory)
        this.state = state
    }

    fun getOrCreateTargetCases(identity: CphTargetIdentity): CphTargetCases {
        return state.targets.getOrPut(identity.id) {
            CphTargetCases(
                targetId = identity.id,
                displayName = identity.displayName,
                cases = mutableListOf(CphTestCase(name = "Case 1")),
            )
        }.also {
            it.targetId = identity.id
            it.displayName = identity.displayName
        }
    }

    companion object {
        fun getInstance(project: Project): CphStateService = project.service()

        fun clampEditorHeight(height: Int): Int = height.coerceIn(CPH_MIN_EDITOR_HEIGHT, CPH_MAX_EDITOR_HEIGHT)

        fun clampOutputSplitRatio(ratio: Double): Double {
            return if (ratio.isFinite()) ratio.coerceIn(0.0, 1.0) else 0.5
        }

        fun clampEditorFontSize(size: Int): Int = size.coerceIn(CPH_MIN_EDITOR_FONT_SIZE, CPH_MAX_EDITOR_FONT_SIZE)

        fun normalizeSingleFileWorkingDirectory(path: String?): String {
            return path?.trim()?.takeIf { it.isNotBlank() } ?: CPH_DEFAULT_SINGLE_FILE_WORKING_DIRECTORY
        }
    }
}
