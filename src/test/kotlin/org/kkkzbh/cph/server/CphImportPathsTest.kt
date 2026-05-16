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
    fun extractsAcmsguruUrl() {
        val coords = CphImportPaths.coordinates(
            CompetitiveCompanionPayload(
                name = "100. A+B",
                url = "https://codeforces.com/problemsets/acmsguru/problem/99999/100",
            )
        )
        assertEquals("acmsguru", coords.contest)
        assertEquals("100", coords.index)
        assertEquals("codeforces", coords.source)
    }

    @Test
    fun extractsAtCoderTaskFromUrlAndNamePrefix() {
        val coords = CphImportPaths.coordinates(
            CompetitiveCompanionPayload(
                name = "D - AABCC",
                group = "AtCoder Beginner Contest 300",
                url = "https://atcoder.jp/contests/abc300/tasks/abc300_d",
            )
        )
        assertEquals("abc300", coords.contest)
        assertEquals("D", coords.index)
        assertEquals("atcoder", coords.source)
    }

    @Test
    fun extractsAtCoderTaskIndexFromTaskIdWhenNameHasNoPrefix() {
        val coords = CphImportPaths.coordinates(
            CompetitiveCompanionPayload(
                name = "AABCC",
                url = "https://atcoder.jp/contests/abc300/tasks/abc300_d",
            )
        )
        assertEquals("abc300", coords.contest)
        assertEquals("D", coords.index)
        assertEquals("atcoder", coords.source)
    }

    @Test
    fun extractsLuoguProblemId() {
        val coords = CphImportPaths.coordinates(
            CompetitiveCompanionPayload(
                name = "P1000 超级玛丽游戏",
                url = "https://www.luogu.com.cn/problem/P1000",
            )
        )
        assertEquals("problems", coords.contest)
        assertEquals("P1000", coords.index)
        assertEquals("luogu", coords.source)
    }

    @Test
    fun extractsLuoguContestAndProblemId() {
        val coords = CphImportPaths.coordinates(
            CompetitiveCompanionPayload(
                name = "P1000 超级玛丽游戏",
                url = "https://www.luogu.com.cn/contest/12345/problem/P1000",
            )
        )
        assertEquals("12345", coords.contest)
        assertEquals("P1000", coords.index)
        assertEquals("luogu", coords.source)
    }

    @Test
    fun extractsKattisProblemId() {
        val coords = CphImportPaths.coordinates(
            CompetitiveCompanionPayload(
                name = "Hello World!",
                url = "https://open.kattis.com/problems/hello",
            )
        )
        assertEquals("problems", coords.contest)
        assertEquals("hello", coords.index)
        assertEquals("kattis", coords.source)
    }

    @Test
    fun extractsKattisContestAndProblemId() {
        val coords = CphImportPaths.coordinates(
            CompetitiveCompanionPayload(
                name = "Hello World!",
                url = "https://open.kattis.com/contests/nwerc2024/problems/hello",
            )
        )
        assertEquals("nwerc2024", coords.contest)
        assertEquals("hello", coords.index)
        assertEquals("kattis", coords.source)
    }

    @Test
    fun fallsBackToGroupAndNamePrefixWhenUrlIsForeign() {
        val coords = CphImportPaths.coordinates(
            CompetitiveCompanionPayload(
                name = "D. Mystery",
                group = "Unknown Platform",
                url = "https://example.com/problem/abc",
            )
        )
        assertEquals("Unknown_Platform", coords.contest)
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
    fun rendersNewDefaultPathTemplate() {
        val coords = CphImportPaths.coordinates(
            CompetitiveCompanionPayload(
                name = "A. Theatre Square",
                url = "https://codeforces.com/contest/1/problem/A",
            )
        )
        val rendered = CphImportPaths.render(CPH_DEFAULT_PATH_TEMPLATE, coords)
        assertEquals("codeforces/1/A.cpp", rendered)
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
