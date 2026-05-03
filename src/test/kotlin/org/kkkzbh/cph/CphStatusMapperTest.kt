package org.kkkzbh.cph

import org.junit.Assert.assertEquals
import org.junit.Test

class CphStatusMapperTest {
    @Test
    fun normalModePreservesAcceptedAndWrongAnswerStatuses() {
        assertEquals(CphRunDisplayStatus.AC, CphStatusMapper.displayStatus(CphVerdict.AC, noExpectedMode = false))
        assertEquals(CphRunDisplayStatus.WA, CphStatusMapper.displayStatus(CphVerdict.WA, noExpectedMode = false))
    }

    @Test
    fun noExpectedModeMapsSuccessToOkAndFailuresToErr() {
        assertEquals(CphRunDisplayStatus.OK, CphStatusMapper.displayStatus(CphVerdict.OK, noExpectedMode = true))
        assertEquals(CphRunDisplayStatus.OK, CphStatusMapper.displayStatus(CphVerdict.AC, noExpectedMode = true))
        assertEquals(CphRunDisplayStatus.ERROR, CphStatusMapper.displayStatus(CphVerdict.WA, noExpectedMode = true))
        assertEquals(CphRunDisplayStatus.ERROR, CphStatusMapper.displayStatus(CphVerdict.RE, noExpectedMode = true))
        assertEquals(CphRunDisplayStatus.ERROR, CphStatusMapper.displayStatus(CphVerdict.TLE, noExpectedMode = true))
        assertEquals(CphRunDisplayStatus.ERROR, CphStatusMapper.displayStatus(CphVerdict.ERROR, noExpectedMode = true))
    }

    @Test
    fun noExpectedResultNormalizationConvertsAcceptedToOk() {
        val accepted = CphCaseResult(verdict = CphVerdict.AC)
        val wrong = CphCaseResult(verdict = CphVerdict.WA)

        assertEquals(CphVerdict.OK, CphStatusMapper.normalizeNoExpectedResult(accepted).verdict)
        assertEquals(CphVerdict.WA, CphStatusMapper.normalizeNoExpectedResult(wrong).verdict)
    }
}
