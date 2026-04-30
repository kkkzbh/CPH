package org.kkkzbh.cph

import org.junit.Assert.assertTrue
import org.junit.Test

class CphStateServiceTest {
    @Test
    fun loadStateMigratesWhitespaceComparisonToEnabled() {
        val service = CphStateService()
        service.loadState(
            CphState(
                targets = linkedMapOf(
                    "target" to CphTargetCases(ignoreTrailingWhitespace = false),
                ),
            ),
        )

        assertTrue(service.getState().targets.getValue("target").ignoreTrailingWhitespace)
    }
}
