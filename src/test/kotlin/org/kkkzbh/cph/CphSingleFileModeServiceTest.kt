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
                workingDirectory = ".cph/",
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
                workingDirectory = ".cph/",
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
                workingDirectory = ".cph/",
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
                workingDirectory = ".cph/",
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
            workingDirectory = ".cph/",
        )

        assertEquals(CphSingleFileModeRequest("/project/main.cpp", "main.cpp", ".cph/"), request)
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
            workingDirectory = ".cph/",
            force = true,
        )

        assertEquals(CphSingleFileModeRequest("/project/main.cpp", "main.cpp", ".cph/"), request)
    }

    @Test
    fun blankWorkingDirectoryUsesDefault() {
        val request = CphSingleFileModePolicy.request(
            enabled = true,
            path = "/project/main.cpp",
            extension = "cpp",
            fileName = "main.cpp",
            inProject = true,
            lastObservedPath = "/project/other.cpp",
            workingDirectory = " ",
        )

        assertEquals(CphSingleFileModeRequest("/project/main.cpp", "main.cpp", ".cph/"), request)
    }
}
