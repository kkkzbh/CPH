package org.kkkzbh.cph.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CphImportPathsTest {
    @Test
    fun extractsCodeforcesContestAndIndex() {
        val coords = CphImportPaths.coordinates(
            CompetitiveCompanionPayload(
                name = "A. Theatre Square",
                url = "https://codeforces.com/contest/1/problem/A",
            )
        )
        assertEquals("1", coords.contest)
        assertEquals("A", coords.index)
        assertEquals("codeforces", coords.source)
    }

    @Test
    fun extractsProblemSetUrl() {
        val coords = CphImportPaths.coordinates(
            CompetitiveCompanionPayload(
                name = "B. Watermelon",
                url = "https://codeforces.com/problemset/problem/4/B",
            )
        )
        assertEquals("4", coords.contest)
        assertEquals("B", coords.index)
    }

    @Test
    fun extractsGymUrl() {
        val coords = CphImportPaths.coordinates(
            CompetitiveCompanionPayload(
                name = "C. Foo",
                url = "https://codeforces.com/gym/100123/problem/C",
            )
        )
        assertEquals("100123", coords.contest)
        assertEquals("C", coords.index)
    }

    @Test
    fun fallsBackToGroupAndNamePrefixWhenUrlIsForeign() {
        val coords = CphImportPaths.coordinates(
            CompetitiveCompanionPayload(
                name = "D. Mystery",
                group = "AtCoder Beginner Contest 300",
                url = "https://atcoder.jp/contests/abc300/tasks/abc300_d",
            )
        )
        assertEquals("AtCoder_Beginner_Contest_300", coords.contest)
        assertEquals("D", coords.index)
        assertEquals("generic", coords.source)
    }

    @Test
    fun rendersPathTemplate() {
        val coords = CphImportPaths.coordinates(
            CompetitiveCompanionPayload(
                name = "A. Theatre Square",
                url = "https://codeforces.com/contest/1/problem/A",
            )
        )
        val rendered = CphImportPaths.render("\${contest}/\${index}.cpp", coords)
        assertEquals("1/A.cpp", rendered)
    }

    @Test
    fun rendersSlugFromName() {
        val coords = CphImportPaths.coordinates(
            CompetitiveCompanionPayload(
                name = "A. Theatre Square",
                url = "https://codeforces.com/contest/1/problem/A",
            )
        )
        val rendered = CphImportPaths.render("\${contest}/\${slug}.cpp", coords)
        assertEquals("1/a-theatre-square.cpp", rendered)
    }

    @Test
    fun stripsPathTraversalSegments() {
        val coords = CphImportPaths.coordinates(
            CompetitiveCompanionPayload(
                name = "../oops",
                group = "..",
                url = "",
            )
        )
        val rendered = CphImportPaths.render("\${contest}/\${name}.cpp", coords)
        assertTrue("expected sanitized path, got: $rendered", !rendered.contains(".."))
    }
}
