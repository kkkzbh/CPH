package org.kkkzbh.cph

import com.intellij.ide.BrowserUtil
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.notification.Notification
import com.intellij.notification.NotificationAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.util.SystemInfo
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.datatransfer.StringSelection
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import javax.swing.AbstractAction
import javax.swing.Action
import javax.swing.JComponent
import javax.swing.JPanel

internal data class CphErrorReport(
    val title: String,
    val shortMessage: String,
    val fullLog: String,
    val issueBody: String,
)

internal object CphErrorReportBuilder {
    fun sample(
        project: Project,
        identity: CphTargetIdentity,
        testCase: CphTestCase,
        result: CphCaseResult,
    ): CphErrorReport {
        val shortMessage = CphUiText.errorStatusMessage(testCase.name, result)
        val fullLog = sampleFullLog(project.name, project.basePath, identity, testCase.name, result)
        return CphErrorReport(
            title = issueTitle(shortMessage),
            shortMessage = shortMessage,
            fullLog = fullLog,
            issueBody = issueBody(shortMessage, fullLog),
        )
    }

    internal fun sampleFullLog(
        projectName: String,
        projectPath: String?,
        identity: CphTargetIdentity,
        caseName: String,
        result: CphCaseResult,
    ): String {
        return buildString {
            appendHeader("CPH sample error")
            appendLine("Project: $projectName")
            appendLine("Project path: ${projectPath ?: "unknown"}")
            appendTarget(identity)
            appendLine("Case: $caseName")
            appendResult(result)
            appendEnvironment()
        }
    }

    fun debug(
        project: Project,
        identity: CphTargetIdentity,
        message: String,
    ): CphErrorReport {
        val shortMessage = CphText.current().debugStatus(message)
        val fullLog = buildString {
            appendHeader("CPH debug error")
            appendLine("Project: ${project.name}")
            appendLine("Project path: ${project.basePath ?: "unknown"}")
            appendTarget(identity)
            appendLine("Message:")
            appendCodeBlock(message)
            appendEnvironment()
        }
        return CphErrorReport(
            title = issueTitle(shortMessage),
            shortMessage = shortMessage,
            fullLog = fullLog,
            issueBody = issueBody(shortMessage, fullLog),
        )
    }

    fun generic(
        project: Project,
        identity: CphTargetIdentity,
        shortMessage: String,
        details: String,
    ): CphErrorReport {
        val fullLog = buildString {
            appendHeader("CPH error")
            appendLine("Project: ${project.name}")
            appendLine("Project path: ${project.basePath ?: "unknown"}")
            appendTarget(identity)
            appendLine("Details:")
            appendCodeBlock(details)
            appendEnvironment()
        }
        return CphErrorReport(
            title = issueTitle(shortMessage),
            shortMessage = shortMessage,
            fullLog = fullLog,
            issueBody = issueBody(shortMessage, fullLog),
        )
    }

    internal fun issueTitle(shortMessage: String): String {
        return "CPH error: ${shortMessage.removePrefix("CPH: ").take(120)}"
    }

    internal fun issueBody(shortMessage: String, fullLog: String): String {
        return """
            ## Problem description

            Please describe what you were doing when this CPH error happened.

            ## Error summary

            $shortMessage

            ## CPH log

            ```text
            $fullLog
            ```
        """.trimIndent()
    }

    private fun StringBuilder.appendHeader(title: String) {
        appendLine(title)
        appendLine("=".repeat(title.length))
    }

    private fun StringBuilder.appendTarget(identity: CphTargetIdentity) {
        appendLine("Target id: ${identity.id}")
        appendLine("Target display name: ${identity.displayName}")
        appendLine("Target kind: ${identity.kind}")
        appendLine("Target runnable: ${identity.runnable}")
        appendLine("Target message: ${identity.message}")
        appendLine("Run configuration: ${identity.settings?.name ?: "none"}")
    }

    private fun StringBuilder.appendResult(result: CphCaseResult) {
        appendLine("Verdict: ${result.verdict}")
        appendLine("Duration millis: ${result.durationMillis}")
        appendLine("Exit code: ${result.exitCode ?: "none"}")
        appendLine("Message:")
        appendCodeBlock(result.message)
        appendLine("stderr:")
        appendCodeBlock(result.stderr)
        appendLine("actualOutput:")
        appendCodeBlock(result.actualOutput)
    }

    private fun StringBuilder.appendEnvironment() {
        appendLine("Environment:")
        appendLine("Plugin version: ${pluginVersion()}")
        appendLine("IDE: ${ideVersion()}")
        appendLine("OS: ${SystemInfo.OS_NAME} ${SystemInfo.OS_VERSION}")
        appendLine("Java: ${System.getProperty("java.version")}")
    }

    private fun StringBuilder.appendCodeBlock(text: String) {
        val value = text.ifBlank { "<empty>" }
        appendLine(value)
    }

    private fun pluginVersion(): String {
        return runCatching {
            PluginManagerCore.getPlugin(PluginId.getId(CPH_PLUGIN_ID))?.version
        }.getOrNull() ?: "unknown"
    }

    private fun ideVersion(): String {
        return runCatching {
            com.intellij.openapi.application.ApplicationInfo.getInstance().fullVersion
        }.getOrNull() ?: "unknown"
    }
}

internal object CphGithubIssueReporter {
    fun open(project: Project, report: CphErrorReport) {
        val prepared = prepareIssue(report)
        if (prepared.truncated) {
            copyToClipboard(report.issueBody)
        }
        BrowserUtil.browse(prepared.url)
    }

    internal fun prepareIssue(report: CphErrorReport): PreparedIssue {
        val fullBody = report.issueBody
        val body = if (fullBody.length > ISSUE_BODY_URL_LIMIT) {
            fullBody.take(ISSUE_BODY_URL_LIMIT) +
                "\n\n[Full CPH log was too long for the URL and has been copied to the clipboard.]"
        } else {
            fullBody
        }
        val url = "$CPH_GITHUB_ISSUE_URL?title=${urlEncode(report.title)}&body=${urlEncode(body)}"
        return PreparedIssue(url = url, truncated = body.length != fullBody.length)
    }

    fun copyToClipboard(text: String) {
        CopyPasteManager.getInstance().setContents(StringSelection(text))
    }

    internal data class PreparedIssue(
        val url: String,
        val truncated: Boolean,
    )

    private fun urlEncode(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20")

    private const val ISSUE_BODY_URL_LIMIT = 7_000
}

internal class CphErrorDetailsDialog(
    private val project: Project,
    private val report: CphErrorReport,
) : DialogWrapper(project) {
    init {
        title = CphText.current().errorDetailsTitle
        init()
    }

    override fun createCenterPanel(): JComponent {
        val textArea = JBTextArea(report.fullLog).also {
            it.isEditable = false
            it.lineWrap = false
            it.caretPosition = 0
        }
        return JPanel(BorderLayout()).also {
            it.preferredSize = Dimension(760, 460)
            it.add(JBScrollPane(textArea), BorderLayout.CENTER)
        }
    }

    override fun createActions(): Array<Action> {
        return arrayOf(copyLogAction(), reportErrorAction(), okAction)
    }

    private fun copyLogAction(): Action {
        return object : AbstractAction(CphText.current().copyLog) {
            override fun actionPerformed(e: java.awt.event.ActionEvent?) {
                CphGithubIssueReporter.copyToClipboard(report.fullLog)
            }
        }
    }

    private fun reportErrorAction(): Action {
        return object : AbstractAction(CphText.current().reportError) {
            override fun actionPerformed(e: java.awt.event.ActionEvent?) {
                CphGithubIssueReporter.open(project, report)
            }
        }
    }
}

internal class CphViewErrorLogAction(
    private val project: Project,
    private val report: CphErrorReport,
) : NotificationAction(CphText.current().viewDetailedLog) {
    override fun actionPerformed(e: AnActionEvent, notification: Notification) {
        CphErrorDetailsDialog(project, report).show()
    }
}

internal class CphReportErrorAction(
    private val project: Project,
    private val report: CphErrorReport,
) : NotificationAction(CphText.current().reportError) {
    override fun actionPerformed(e: AnActionEvent, notification: Notification) {
        CphGithubIssueReporter.open(project, report)
    }
}

internal const val CPH_PLUGIN_ID = "org.kkkzbh.cph"
private const val CPH_GITHUB_ISSUE_URL = "https://github.com/kkkzbh/CPH/issues/new"
