package org.kkkzbh.cph

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CphSingleFileModeServiceTest {
    @Test
    fun disabledModeDoesNotRequestSync() {
        assertNull(
            CphSingleFileModePolicy.request(
                enabled = false,
                path = "/project/main.cpp",
                extension = "cpp",
                fileName = "main.cpp",
                inProject = true,
                lastObservedPath = null,
            ),
        )
    }

    @Test
    fun nonCppFileDoesNotRequestSync() {
        assertNull(
            CphSingleFileModePolicy.request(
                enabled = true,
                path = "/project/main.h",
                extension = "h",
                fileName = "main.h",
                inProject = true,
                lastObservedPath = null,
            ),
        )
    }

    @Test
    fun externalCppFileDoesNotRequestSync() {
        assertNull(
            CphSingleFileModePolicy.request(
                enabled = true,
                path = "/tmp/main.cpp",
                extension = "cpp",
                fileName = "main.cpp",
                inProject = false,
                lastObservedPath = null,
            ),
        )
    }

    @Test
    fun repeatedFocusedPathDoesNotRequestSync() {
        assertNull(
            CphSingleFileModePolicy.request(
                enabled = true,
                path = "/project/main.cpp",
                extension = "cpp",
                fileName = "main.cpp",
                inProject = true,
                lastObservedPath = "/project/main.cpp",
            ),
        )
    }

    @Test
    fun cppFileRequestsSync() {
        val request = CphSingleFileModePolicy.request(
            enabled = true,
            path = "/project/main.cpp",
            extension = "cpp",
            fileName = "main.cpp",
            inProject = true,
            lastObservedPath = "/project/other.cpp",
        )

        assertEquals(CphSingleFileModeRequest("/project/main.cpp", "main.cpp"), request)
    }

    @Test
    fun forceAllowsSameFocusedCppPath() {
        val request = CphSingleFileModePolicy.request(
            enabled = true,
            path = "/project/main.cpp",
            extension = "cpp",
            fileName = "main.cpp",
            inProject = true,
            lastObservedPath = "/project/main.cpp",
            force = true,
        )

        assertEquals(CphSingleFileModeRequest("/project/main.cpp", "main.cpp"), request)
    }
}
