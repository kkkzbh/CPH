package org.kkkzbh.cph.submit

import com.google.gson.JsonParser
import org.kkkzbh.cph.CphCppStandard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class CphSubmitBridgeTest {
    @Test
    fun resolvesSupportedCodeforcesUrls() {
        assertEquals("1/A", CphSubmitContextResolver.resolve("https://codeforces.com/contest/1/problem/A")?.displayId)
        assertEquals("4/B", CphSubmitContextResolver.resolve("https://codeforces.com/problemset/problem/4/B")?.displayId)
        assertEquals("gym/100123/C", CphSubmitContextResolver.resolve("https://codeforces.com/gym/100123/problem/C")?.displayId)
        assertEquals("acmsguru/100", CphSubmitContextResolver.resolve("https://codeforces.com/problemsets/acmsguru/problem/99999/100")?.displayId)
        assertNull(CphSubmitContextResolver.resolve("https://codeforces.com/blog/entry/1"))
    }

    @Test
    fun serializesBridgeJobPayload() {
        val ctx = CphSubmitContextResolver.resolve("https://codeforces.com/contest/1/problem/A")!!
        val json = CphSubmitBridgeJob(
            jobId = "job-1",
            context = ctx,
            programTypeId = 91,
            source = "int main(){}",
        ).toJson()
        val obj = JsonParser.parseString(json).asJsonObject
        assertEquals("job-1", obj.get("jobId").asString)
        assertEquals("https://codeforces.com/contest/1/problem/A", obj.get("problemUrl").asString)
        assertEquals("https://codeforces.com/contest/1/submit", obj.get("submitPageUrl").asString)
        assertEquals("CONTEST", obj.get("kind").asString)
        assertEquals("1", obj.get("contestId").asString)
        assertEquals("A", obj.get("problemIndex").asString)
        assertEquals(91, obj.get("programTypeId").asInt)
        assertEquals("int main(){}", obj.get("source").asString)
    }

    @Test
    fun codeforcesLanguageIdsMatchCurrentSubmitCodeOptions() {
        assertEquals(42, CphCfLanguage.CPP_11.defaultProgramTypeId)
        assertEquals(54, CphCfLanguage.CPP_17.defaultProgramTypeId)
        assertEquals(89, CphCfLanguage.CPP_20.defaultProgramTypeId)
        assertEquals(91, CphCfLanguage.CPP_23.defaultProgramTypeId)
    }

    @Test
    fun codeforcesLanguageFollowsCphCppStandardWithCurrentCfCeiling() {
        assertEquals(CphCfLanguage.CPP_23, CphCfLanguage.fromCppStandard(CphCppStandard.FOLLOW_TARGET))
        assertEquals(CphCfLanguage.CPP_11, CphCfLanguage.fromCppStandard(CphCppStandard.CPP11))
        assertEquals(CphCfLanguage.CPP_17, CphCfLanguage.fromCppStandard(CphCppStandard.CPP17))
        assertEquals(CphCfLanguage.CPP_20, CphCfLanguage.fromCppStandard(CphCppStandard.CPP20))
        assertEquals(CphCfLanguage.CPP_23, CphCfLanguage.fromCppStandard(CphCppStandard.CPP23))
        assertEquals(CphCfLanguage.CPP_23, CphCfLanguage.fromCppStandard(CphCppStandard.CPP26))
    }

    @Test
    fun parsesBridgeUpdatePayload() {
        val update = CphSubmitBridgeUpdate.parse(
            """{"jobId":"job-1","phase":"RUNNING","text":"#42 Running on test 3","submissionId":42,"pageUrl":"https://codeforces.com/contest/1/submission/42"}""",
        )
        assertNotNull(update)
        assertEquals("job-1", update!!.jobId)
        assertEquals(CphSubmissionPhase.RUNNING, update.phase)
        assertEquals("#42 Running on test 3", update.text)
        assertEquals(42L, update.submissionId)
        assertEquals("https://codeforces.com/contest/1/submission/42", update.pageUrl)
    }

    @Test
    fun rejectsMalformedBridgeUpdatePayload() {
        assertNull(CphSubmitBridgeUpdate.parse("""{"jobId":"job-1","phase":"BOGUS"}"""))
        assertNull(CphSubmitBridgeUpdate.parse("""{"phase":"RUNNING"}"""))
        assertNull(CphSubmitBridgeUpdate.parse("not-json"))
    }
}
