package org.kkkzbh.cph

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

class CphErrorReportTest {
    @Test
    fun sampleFullLogIncludesResultAndTargetDetails() {
        val identity = CphTargetIdentity(
            id = "target-id",
            displayName = "main.cpp",
            settings = null,
            runnable = true,
            message = "Ready",
            kind = CphTargetKind.CPP_FILE,
        )
        val result = CphCaseResult(
            verdict = CphVerdict.ERROR,
            actualOutput = "partial output",
            stderr = "main.cpp:3:5: error: expected ';'",
            exitCode = 1,
            durationMillis = 42,
            message = "Build failed for C/C++ File configuration 'main.cpp'.",
        )

        val log = CphErrorReportBuilder.sampleFullLog(
            projectName = "demo",
            projectPath = "/tmp/demo",
            identity = identity,
            caseName = "Case 1",
            result = result,
        )

        assertTrue(log.contains("CPH sample error"))
        assertTrue(log.contains("Project path: /tmp/demo"))
        assertTrue(log.contains("Target display name: main.cpp"))
        assertTrue(log.contains("Target kind: CPP_FILE"))
        assertTrue(log.contains("Case: Case 1"))
        assertTrue(log.contains("Verdict: ERROR"))
        assertTrue(log.contains("Duration millis: 42"))
        assertTrue(log.contains("Exit code: 1"))
        assertTrue(log.contains("Build failed for C/C++ File configuration 'main.cpp'."))
        assertTrue(log.contains("main.cpp:3:5: error: expected ';'"))
        assertTrue(log.contains("partial output"))
    }

    @Test
    fun issueUrlEncodesChineseAndNewlines() {
        val report = CphErrorReport(
            title = "CPH error: 编译失败",
            shortMessage = "CPH: Case 1 CE - 编译失败",
            fullLog = "line1\nline2",
            issueBody = "描述\n```text\n中文错误\n```",
        )

        val prepared = CphGithubIssueReporter.prepareIssue(report)

        assertTrue(prepared.url.startsWith("https://github.com/kkkzbh/CPH/issues/new?"))
        assertTrue(prepared.url.contains("title=CPH%20error%3A%20%E7%BC%96%E8%AF%91%E5%A4%B1%E8%B4%A5"))
        val body = prepared.url.substringAfter("body=")
        assertEquals(report.issueBody, URLDecoder.decode(body, StandardCharsets.UTF_8))
    }

    @Test
    fun longIssueBodyIsTruncatedForUrl() {
        val report = CphErrorReport(
            title = "CPH error: long",
            shortMessage = "long",
            fullLog = "x".repeat(10_000),
            issueBody = "x".repeat(10_000),
        )

        val prepared = CphGithubIssueReporter.prepareIssue(report)

        assertTrue(prepared.truncated)
        assertTrue(prepared.url.length < report.issueBody.length)
        assertTrue(URLDecoder.decode(prepared.url.substringAfter("body="), StandardCharsets.UTF_8).contains("copied to the clipboard"))
    }

    @Test
    fun issueTitleUsesShortMessage() {
        assertEquals(
            "CPH error: Case 1 CE - Build failed",
            CphErrorReportBuilder.issueTitle("CPH: Case 1 CE - Build failed"),
        )
    }
}
