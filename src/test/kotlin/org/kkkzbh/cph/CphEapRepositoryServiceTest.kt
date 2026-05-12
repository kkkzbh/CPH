package org.kkkzbh.cph

import org.junit.Assert.assertEquals
import org.junit.Test

class CphEapRepositoryServiceTest {
    @Test
    fun enableAddsEapRepositoryWhenMissing() {
        val hosts = listOf("https://example.com/plugins")

        assertEquals(
            listOf("https://example.com/plugins", CphEapRepositoryService.CPH_EAP_REPOSITORY_URL),
            CphEapRepositoryService.enableInHosts(hosts),
        )
    }

    @Test
    fun enableDoesNotDuplicateEapRepository() {
        val hosts = listOf(
            CphEapRepositoryService.CPH_EAP_REPOSITORY_URL,
            "${CphEapRepositoryService.CPH_EAP_REPOSITORY_URL}/",
        )

        assertEquals(
            listOf(CphEapRepositoryService.CPH_EAP_REPOSITORY_URL),
            CphEapRepositoryService.enableInHosts(hosts),
        )
    }

    @Test
    fun disableRemovesOnlyEapRepository() {
        val hosts = listOf(
            "https://example.com/plugins",
            CphEapRepositoryService.CPH_EAP_REPOSITORY_URL,
            "https://another.example.com/plugins",
        )

        assertEquals(
            listOf("https://example.com/plugins", "https://another.example.com/plugins"),
            CphEapRepositoryService.disableFromHosts(hosts),
        )
    }

    @Test
    fun normalizeHostsTrimsDropsBlankAndPreservesOrder() {
        val hosts = listOf(
            "  https://example.com/plugins/  ",
            "",
            "https://example.com/plugins",
            "https://another.example.com/plugins/",
        )

        assertEquals(
            listOf("https://example.com/plugins", "https://another.example.com/plugins"),
            CphEapRepositoryService.normalizeHosts(hosts),
        )
    }
}
