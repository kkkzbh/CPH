package org.kkkzbh.cph.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class CompetitiveCompanionParserTest {
    @Test
    fun parsesStandardPayload() {
        val payload = CompetitiveCompanionParser.parse(
            """
            {"name":"A. Theatre Square","group":"Codeforces - Codeforces Beta Round 1",
             "url":"https://codeforces.com/contest/1/problem/A","interactive":false,
             "memoryLimit":256,"timeLimit":1000,
             "tests":[{"input":"6 6 4\n","output":"4\n"},{"input":"1 1 1\n","output":"1\n"}]}
            """.trimIndent()
        )
        assertNotNull(payload)
        assertEquals("A. Theatre Square", payload!!.name)
        assertEquals("https://codeforces.com/contest/1/problem/A", payload.url)
        assertEquals(1000L, payload.timeLimit)
        assertEquals(2, payload.tests.size)
        assertEquals("6 6 4\n", payload.tests[0].input)
        assertEquals("4\n", payload.tests[0].output)
    }

    @Test
    fun returnsNullOnInvalidJson() {
        assertNull(CompetitiveCompanionParser.parse("not json"))
        assertNull(CompetitiveCompanionParser.parse(""))
        assertNull(CompetitiveCompanionParser.parse("{"))
    }

    @Test
    fun returnsNullOnEmptyShell() {
        assertNull(CompetitiveCompanionParser.parse("{}"))
    }

    @Test
    fun toleratesMissingOptionalFields() {
        val payload = CompetitiveCompanionParser.parse(
            """{"name":"X","tests":[{"input":"a","output":"b"}]}"""
        )
        assertNotNull(payload)
        assertEquals("X", payload!!.name)
        assertEquals(1, payload.tests.size)
        assertEquals(null, payload.timeLimit)
    }
}
