package org.kkkzbh.cph

import org.junit.Assert.assertEquals
import org.junit.Test

class CphBuildOutputServiceTest {
    @Test
    fun stretchDeltaForHeightKeepsBuildWindowAtLeastTargetHeight() {
        assertEquals(0, CphBuildOutputService.stretchDeltaForHeight(0))
        assertEquals(81, CphBuildOutputService.stretchDeltaForHeight(219))
        assertEquals(50, CphBuildOutputService.stretchDeltaForHeight(250))
        assertEquals(0, CphBuildOutputService.stretchDeltaForHeight(300))
        assertEquals(0, CphBuildOutputService.stretchDeltaForHeight(360))
    }
}
