package org.kkkzbh.cph

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import java.util.UUID

enum class CphVerdict {
    NOT_RUN,
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
    var timeoutMillis: Long = 1000,
    var ignoreTrailingWhitespace: Boolean = true,
    var cases: MutableList<CphTestCase> = mutableListOf(),
)

data class CphState(
    var targets: MutableMap<String, CphTargetCases> = linkedMapOf(),
)

@State(name = "CphTargetRunnerState", storages = [Storage("cph-target-runner.xml")])
class CphStateService : PersistentStateComponent<CphState> {
    private var state = CphState()

    override fun getState(): CphState = state

    override fun loadState(state: CphState) {
        state.targets.values.forEach {
            if (it.timeoutMillis == 2000L) {
                it.timeoutMillis = 1000L
            }
            it.ignoreTrailingWhitespace = true
        }
        this.state = state
    }

    fun getOrCreateTargetCases(identity: CphTargetIdentity): CphTargetCases {
        return state.targets.getOrPut(identity.id) {
            CphTargetCases(
                targetId = identity.id,
                displayName = identity.displayName,
            )
        }.also {
            it.targetId = identity.id
            it.displayName = identity.displayName
        }
    }

    companion object {
        fun getInstance(project: Project): CphStateService = project.service()
    }
}
