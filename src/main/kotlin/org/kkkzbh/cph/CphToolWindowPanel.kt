package org.kkkzbh.cph

import com.intellij.icons.AllIcons
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.execution.ExecutionTarget
import com.intellij.execution.ExecutionTargetListener
import com.intellij.execution.ExecutionTargetManager
import com.intellij.execution.RunManager
import com.intellij.execution.RunManagerListener
import com.intellij.execution.RunnerAndConfigurationSettings
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.WriteIntentReadAction
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.StatusBar
import com.intellij.ide.BrowserUtil
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import org.kkkzbh.cph.submit.CphActiveTab
import org.kkkzbh.cph.submit.CphActiveTabListener
import org.kkkzbh.cph.submit.CphActiveTabService
import org.kkkzbh.cph.submit.CphSubmissionPhase
import org.kkkzbh.cph.submit.CphSubmissionStatus
import org.kkkzbh.cph.submit.CphSubmissionStatusListener
import org.kkkzbh.cph.submit.CphSubmitContextResolver
import org.kkkzbh.cph.submit.CphSubmitOrchestrator
import java.awt.CardLayout
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Container
import java.awt.Cursor
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Graphics
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.awt.KeyboardFocusManager
import java.awt.Rectangle
import java.awt.Shape
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.ScrollPaneConstants
import javax.swing.JSplitPane
import javax.swing.JSpinner
import javax.swing.SpinnerNumberModel
import javax.swing.SwingConstants
import javax.swing.SwingUtilities
import javax.swing.Timer
import javax.swing.border.CompoundBorder
import javax.swing.border.EmptyBorder
import javax.swing.border.MatteBorder
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.event.ChangeListener
import javax.swing.plaf.basic.BasicSplitPaneDivider
import javax.swing.plaf.basic.BasicSplitPaneUI
import javax.swing.text.Highlighter
import javax.swing.text.JTextComponent
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.io.File
import kotlin.math.roundToInt

internal object CphUiText {
    fun errorTooltip(caseName: String, result: CphCaseResult): String {
        val summary = "$caseName: error in ${formatDuration(result.durationMillis)}"
        val message = result.message.takeIf { it.isNotBlank() } ?: return summary
        return "$summary: $message"
    }

    fun errorStatusMessage(caseName: String, result: CphCaseResult): String {
        val code = if (isCompileLikeError(result)) "CE" else "ERR"
        return "CPH: $caseName $code - ${errorSummary(result)}"
    }

    fun errorNotificationContent(caseName: String, result: CphCaseResult): String {
        val exit = result.exitCode?.let { ", exit $it" }.orEmpty()
        return "${errorStatusMessage(caseName, result)} (${formatDuration(result.durationMillis)}$exit)"
    }

    fun formatDuration(durationMillis: Long): String {
        return if (durationMillis >= 1000) {
            val seconds = durationMillis / 1000.0
            "%.1fs".format(seconds)
        } else {
            "${durationMillis}ms"
        }
    }

    private fun errorSummary(result: CphCaseResult): String {
        val message = result.message.trim().trimEnd('.', ':')
        val detail = firstNonBlankLine(result.stderr).ifBlank {
            firstNonBlankLine(result.actualOutput)
        }
        return when {
            message.isNotBlank() && detail.isNotBlank() -> "$message: $detail"
            message.isNotBlank() -> message
            detail.isNotBlank() -> detail
            else -> "Unknown CPH error."
        }
    }

    private fun firstNonBlankLine(text: String): String {
        return text.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.isNotBlank() }
            .orEmpty()
    }

    private fun isCompileLikeError(result: CphCaseResult): Boolean {
        val text = listOf(result.message, result.stderr, result.actualOutput)
            .joinToString("\n")
            .lowercase()
        return COMPILE_ERROR_MARKERS.any { it in text }
    }

    private val COMPILE_ERROR_MARKERS = listOf(
        "build failed",
        "build timed out",
        "cmake",
        "c/c++ file configuration",
    )
}

internal enum class CphRunDisplayStatus {
    NOT_RUN,
    OK,
    AC,
    WA,
    TLE,
    RE,
    ERROR,
}

internal object CphStatusMapper {
    fun displayStatus(verdict: CphVerdict, noExpectedMode: Boolean): CphRunDisplayStatus {
        return if (noExpectedMode) {
            when (verdict) {
                CphVerdict.NOT_RUN -> CphRunDisplayStatus.NOT_RUN
                CphVerdict.OK,
                CphVerdict.AC -> CphRunDisplayStatus.OK
                CphVerdict.WA,
                CphVerdict.TLE,
                CphVerdict.RE,
                CphVerdict.ERROR -> CphRunDisplayStatus.ERROR
            }
        } else {
            when (verdict) {
                CphVerdict.NOT_RUN -> CphRunDisplayStatus.NOT_RUN
                CphVerdict.OK,
                CphVerdict.AC -> CphRunDisplayStatus.AC
                CphVerdict.WA -> CphRunDisplayStatus.WA
                CphVerdict.TLE -> CphRunDisplayStatus.TLE
                CphVerdict.RE -> CphRunDisplayStatus.RE
                CphVerdict.ERROR -> CphRunDisplayStatus.ERROR
            }
        }
    }

    fun normalizeNoExpectedResult(result: CphCaseResult): CphCaseResult {
        return if (result.verdict == CphVerdict.AC) {
            result.copy(verdict = CphVerdict.OK)
        } else {
            result
        }
    }
}

class CphToolWindowPanel(private val project: Project) : JPanel(BorderLayout()), Disposable {
    private val stateService = CphStateService.getInstance(project)
    private var currentIdentity = CphTargetResolver.current(project)
    private var currentTargetCases = stateService.getOrCreateTargetCases(currentIdentity)
    private var selectedCase: CphTestCase? = null
    private var running = false
    private var settingsVisible = false
    private var pendingTargetRefresh = false
    private var applyingTargetSettings = false
    private var applyingShortcutSettings = false
    private var applyingWorkingDirectorySettings = false
    private var activeRunButton: ActiveRunButton? = null
    private var runSpinnerIndex = 0

    private val compileSettingsSynchronizer = CphCompileSettingsSynchronizer(project)
    private val runtimeStates = linkedMapOf<String, RuntimeTabState>()
    private val caseTabComponents = linkedMapOf<String, CaseTab>()

    private val settingsButton = JButton("⚙")
    private val runAllButton = JButton(RUN_ALL_BUTTON_TEXT)
    private val submitButton = JButton("📤")
    private val helpButton = JButton("?")
    private val verdictLabel = JBLabel("")
    private var verdictPageUrl: String? = null
    private var submitBusy = false
    private var submitSpinnerIndex = 0
    private val submitSpinnerTimer = Timer(120) {
        submitButton.text = SUBMIT_SPINNER_FRAMES[submitSpinnerIndex % SUBMIT_SPINNER_FRAMES.size]
        submitSpinnerIndex++
    }
    private val hideSubmissionStatusTimer = Timer(SUBMISSION_STATUS_VISIBLE_MILLIS) {
        hideSubmissionStatus()
    }.also {
        it.isRepeats = false
    }
    private val resetCasesButton = JButton("↺")
    private val runSelectedCaseButton = JButton(RUN_SELECTED_BUTTON_TEXT)
    private val debugSelectedCaseButton = JButton("Debug")
    private val runSpinnerTimer = Timer(140) {
        updateRunSpinnerText()
        refreshRunActionButtons()
    }
    private val singleFileModeEnabled = JCheckBox("纯单文件模式")
    private val ignoreTrailingWhitespace = JCheckBox("忽略行尾空格和多余换行")
    private val outputSplitEnabled = JCheckBox("双栏显示 Actual / Expected")
    private val noExpectedModeEnabled = JCheckBox("无 Expected 模式")
    private val confidentSubmitEnabled = JCheckBox("自信模式：本地全 AC 后自动提交 CF")
    private val cppStandardCombo = JComboBox(CphCppStandard.entries.toTypedArray())
    private val compileOptionsField = JBTextField()
    private val singleFileWorkingDirectoryField = JBTextField()
    private val singleFileWorkingDirectoryChooserButton = JButton(AllIcons.Nodes.Folder)
    private val runAllShortcutField = CphShortcutTextField()
    private val runSelectedCaseShortcutField = CphShortcutTextField()
    private val debugSelectedCaseShortcutField = CphShortcutTextField()
    private val submitShortcutField = CphShortcutTextField()
    private val compileOptionsSyncTimer = Timer(COMPILE_OPTIONS_SYNC_DELAY_MILLIS) {
        syncCompileSettingsForCurrentTarget(reportStatus = true)
    }.also {
        it.isRepeats = false
    }
    private val timeoutSpinner = JSpinner(
        SpinnerNumberModel(
            CPH_DEFAULT_TIMEOUT_MILLIS.toInt(),
            CPH_MIN_TIMEOUT_MILLIS.toInt(),
            CPH_MAX_TIMEOUT_MILLIS.toInt(),
            100,
        ),
    )
    private val editorFontSizeSpinner = JSpinner(
        SpinnerNumberModel(
            CPH_DEFAULT_EDITOR_FONT_SIZE,
            CPH_MIN_EDITOR_FONT_SIZE,
            CPH_MAX_EDITOR_FONT_SIZE,
            1,
        ),
    )

    private val tabStrip = JPanel()
    private val tabScrollPane = JBScrollPane(tabStrip)
    private val submissionStatusPanel = JPanel(BorderLayout())
    private val contentCards = JPanel(CardLayout())
    private val settingsGrid = JPanel().also { it.layout = BoxLayout(it, BoxLayout.Y_AXIS) }
    private val settingsReturnHintPanel = JPanel(BorderLayout())
    private val outputContainer = JPanel(BorderLayout())
    private val inputArea = JBTextArea()
    private val expectedArea = JBTextArea()
    private val actualArea = JBTextArea()

    init {
        border = BorderFactory.createEmptyBorder(8, 8, 8, 8)
        background = PANEL
        add(buildTop(), BorderLayout.NORTH)
        add(buildCenter(), BorderLayout.CENTER)

        runAllButton.toolTipText = "Run all enabled cases"
        resetCasesButton.toolTipText = "Delete all cases and create Case 1"
        runSelectedCaseButton.toolTipText = "Run this case"
        debugSelectedCaseButton.toolTipText = "Debug this case with the selected run target"
        settingsButton.toolTipText = "Settings"
        helpButton.toolTipText = "Open CPH documentation"
        singleFileModeEnabled.toolTipText = "Automatically select or create the C/C++ File run target for the focused .cpp file"
        singleFileWorkingDirectoryField.toolTipText = "Working directory for CPH-managed C/C++ File run targets"
        singleFileWorkingDirectoryChooserButton.toolTipText = "Choose working directory"
        configureRunAllButton()
        configureResetCasesButton()
        configureRunSelectedCaseButton()
        configureDebugSelectedCaseButton()
        configureSettingsButton()
        configureSubmitButton()
        configureHelpButton()
        configureVerdictLabel()
        configureWorkingDirectoryChooserButton()

        settingsButton.addActionListener { toggleSettingsPanel() }
        runAllButton.addActionListener { runAllCases() }
        submitButton.addActionListener { CphSubmitOrchestrator.getInstance(project).submit() }
        helpButton.addActionListener { BrowserUtil.browse(CPH_DOCS_URL) }
        resetCasesButton.addActionListener { resetCases() }
        runSelectedCaseButton.addActionListener { runSelectedCase() }
        debugSelectedCaseButton.addActionListener { debugSelectedCase() }
        singleFileModeEnabled.addActionListener {
            if (!applyingTargetSettings) {
                stateService.getState().singleFileModeEnabled = singleFileModeEnabled.isSelected
                CphSingleFileModeService.getInstance(project).syncForCurrentFile(force = true)
                refreshSubmitButtonTooltip()
                setSubmitBusy(submitBusy)
                refreshRunActionButtons()
            }
        }
        singleFileWorkingDirectoryChooserButton.addActionListener { chooseSingleFileWorkingDirectory() }
        singleFileWorkingDirectoryField.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = persist()
            override fun removeUpdate(e: DocumentEvent) = persist()
            override fun changedUpdate(e: DocumentEvent) = persist()

            private fun persist() {
                persistSingleFileWorkingDirectory()
            }
        })
        ignoreTrailingWhitespace.addActionListener {
            currentTargetCases.ignoreTrailingWhitespace = ignoreTrailingWhitespace.isSelected
            refreshActualDiffHighlights()
        }
        outputSplitEnabled.addActionListener {
            stateService.getState().ui.outputSplitEnabled = outputSplitEnabled.isSelected
            rebuildOutputLayout()
        }
        confidentSubmitEnabled.addActionListener {
            if (!applyingTargetSettings) {
                stateService.getState().ui.confidentSubmitEnabled = confidentSubmitEnabled.isSelected
            }
        }
        noExpectedModeEnabled.addActionListener {
            if (!applyingTargetSettings) {
                flushSelectedCase()
                stateService.getState().ui.noExpectedModeEnabled = noExpectedModeEnabled.isSelected
                rebuildOutputLayout()
                refreshActualDiffHighlights()
                refreshTabs()
                updateActions()
            }
        }
        editorFontSizeSpinner.addChangeListener {
            if (!applyingTargetSettings) {
                setEditorFontSize((editorFontSizeSpinner.value as Number).toInt(), persist = true)
            }
        }
        cppStandardCombo.addActionListener {
            if (!applyingTargetSettings) {
                stateService.getState().compileSettings.cppStandard = cppStandardCombo.selectedItem as? CphCppStandard
                    ?: CphCppStandard.FOLLOW_TARGET
                syncCompileSettingsForCurrentTarget(reportStatus = true)
            }
        }
        compileOptionsField.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = persist()
            override fun removeUpdate(e: DocumentEvent) = persist()
            override fun changedUpdate(e: DocumentEvent) = persist()

            private fun persist() {
                if (!applyingTargetSettings) {
                    stateService.getState().compileSettings.compileOptions = compileOptionsField.text
                    scheduleCompileSettingsSync()
                }
            }
        })
        timeoutSpinner.addChangeListener {
            currentTargetCases.timeoutMillis = (timeoutSpinner.value as Number).toLong()
        }
        listOf(runAllShortcutField, runSelectedCaseShortcutField, debugSelectedCaseShortcutField, submitShortcutField).forEach {
            it.onShortcutChanged = { persistShortcutSettings() }
        }

        actualArea.isEditable = false
        listOf(inputArea, expectedArea, actualArea).forEach(::configureEditor)
        listOf(inputArea, expectedArea, actualArea).forEach(::installEditorFontWheel)
        listOf(expectedArea, actualArea).forEach(::installDiffRefreshListener)

        installTargetRefreshListeners()
        installSubmissionListeners()
        refreshTarget()
        refreshShortcutSettings()
        setSubmitBusy(false)
        refreshSubmitButtonTooltip()
    }

    override fun dispose() {
        stopRunSpinner()
        submitSpinnerTimer.stop()
        hideSubmissionStatusTimer.stop()
    }

    internal fun triggerShortcut(action: CphShortcutAction) {
        when (action) {
            CphShortcutAction.RUN_ALL -> runAllCases()
            CphShortcutAction.RUN_SELECTED_CASE -> runSelectedCase()
            CphShortcutAction.DEBUG_SELECTED_CASE -> debugSelectedCase()
            CphShortcutAction.SUBMIT -> CphSubmitOrchestrator.getInstance(project).submit()
        }
    }

    private fun buildTop(): JComponent {
        val top = JPanel()
        top.layout = BoxLayout(top, BoxLayout.Y_AXIS)
        top.background = PANEL

        val toolbar = JPanel(FlowLayout(FlowLayout.LEFT, 8, 4))
        toolbar.background = PANEL
        toolbar.add(runAllButton)
        toolbar.add(submitButton)
        toolbar.add(resetCasesButton)
        toolbar.add(settingsButton)
        toolbar.add(helpButton)
        toolbar.alignmentX = Component.LEFT_ALIGNMENT

        submissionStatusPanel.background = SURFACE
        submissionStatusPanel.border = CompoundBorder(
            MatteBorder(1, 0, 1, 0, BORDER),
            EmptyBorder(4, 8, 4, 8),
        )
        submissionStatusPanel.add(verdictLabel, BorderLayout.CENTER)
        submissionStatusPanel.isVisible = false
        submissionStatusPanel.maximumSize = Dimension(Int.MAX_VALUE, 28)
        submissionStatusPanel.alignmentX = Component.LEFT_ALIGNMENT

        top.add(toolbar)
        top.add(submissionStatusPanel)
        return top
    }

    private fun configureSubmitButton() {
        configureToolbarIconButton(submitButton, RUN)
    }

    private fun configureVerdictLabel() {
        verdictLabel.foreground = MUTED
        verdictLabel.font = verdictLabel.font.deriveFont(Font.PLAIN)
        verdictLabel.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        verdictLabel.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                val url = verdictPageUrl ?: return
                BrowserUtil.browse(url)
            }
        })
    }

    private fun installSubmissionListeners() {
        val appConnection = ApplicationManager.getApplication().messageBus.connect(this)
        appConnection.subscribe(CphSubmissionStatusListener.TOPIC, object : CphSubmissionStatusListener {
            override fun submissionStatusChanged(status: CphSubmissionStatus) {
                SwingUtilities.invokeLater { applySubmissionStatus(status) }
            }
        })
        appConnection.subscribe(CphActiveTabListener.TOPIC, object : CphActiveTabListener {
            override fun activeTabChanged(tab: CphActiveTab?) {
                SwingUtilities.invokeLater { refreshSubmitButtonTooltip() }
            }
        })
    }

    private fun applySubmissionStatus(status: CphSubmissionStatus) {
        if (status.phase == CphSubmissionPhase.IDLE) {
            verdictPageUrl = null
            hideSubmissionStatus()
            setSubmitBusy(false)
            return
        }
        status.pageUrl?.let { verdictPageUrl = it }
        verdictLabel.text = status.text
        verdictLabel.foreground = when (status.phase) {
            CphSubmissionPhase.IDLE -> MUTED
            CphSubmissionPhase.SUBMITTING,
            CphSubmissionPhase.QUEUED,
            CphSubmissionPhase.RUNNING -> RUN
            CphSubmissionPhase.ACCEPTED -> GOOD
            CphSubmissionPhase.REJECTED,
            CphSubmissionPhase.ERROR -> BAD
        }
        verdictLabel.toolTipText = status.errorDetail
            ?: status.pageUrl
            ?: status.text.ifBlank { null }
        verdictLabel.cursor = if (verdictPageUrl != null) {
            Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        } else {
            Cursor.getDefaultCursor()
        }
        submissionStatusPanel.isVisible = true
        submissionStatusPanel.revalidate()
        submissionStatusPanel.repaint()

        val busy = status.phase == CphSubmissionPhase.SUBMITTING ||
            status.phase == CphSubmissionPhase.QUEUED ||
            status.phase == CphSubmissionPhase.RUNNING
        setSubmitBusy(busy)
        if (busy) {
            hideSubmissionStatusTimer.stop()
        } else {
            hideSubmissionStatusTimer.restart()
        }
    }

    private fun setSubmitBusy(busy: Boolean) {
        submitBusy = busy
        submitButton.isEnabled = !busy && stateService.getState().singleFileModeEnabled
        refreshToolbarIconButton(submitButton, RUN)
        if (busy) {
            if (!submitSpinnerTimer.isRunning) {
                submitSpinnerIndex = 0
                submitSpinnerTimer.start()
            }
            return
        }
        submitSpinnerTimer.stop()
        submitButton.text = "📤"
        refreshToolbarIconButton(submitButton, RUN)
    }

    private fun hideSubmissionStatus() {
        hideSubmissionStatusTimer.stop()
        verdictLabel.text = ""
        verdictLabel.toolTipText = null
        submissionStatusPanel.isVisible = false
        submissionStatusPanel.revalidate()
        submissionStatusPanel.repaint()
    }

    private fun refreshSubmitButtonTooltip() {
        val tab = CphActiveTabService.getInstance().current()
        val ctx = tab?.let { CphSubmitContextResolver.resolve(it.url) }
        submitButton.toolTipText = when {
            !stateService.getState().singleFileModeEnabled -> "Enable pure single-file mode before submitting to Codeforces"
            ctx != null -> "Submit current file → ${ctx.displayId}"
            tab != null -> "Active tab is not a Codeforces problem page"
            else -> "No active Codeforces tab — install the CPH Target Runner browser extension"
        }
    }

    private fun configureRunAllButton() {
        configureToolbarIconButton(runAllButton, GOOD)
        runAllButton.model.addChangeListener(ChangeListener { refreshRunActionButtons() })
        refreshToolbarRunAllButton()
    }

    private fun configureResetCasesButton() {
        configureToolbarIconButton(resetCasesButton, TEXT)
    }

    private fun configureRunSelectedCaseButton() {
        runSelectedCaseButton.isOpaque = true
        runSelectedCaseButton.isContentAreaFilled = true
        runSelectedCaseButton.isBorderPainted = true
        runSelectedCaseButton.isFocusPainted = false
        runSelectedCaseButton.isRolloverEnabled = true
        runSelectedCaseButton.border = EmptyBorder(1, 6, 1, 6)
        runSelectedCaseButton.font = runSelectedCaseButton.font.deriveFont(Font.BOLD)
        runSelectedCaseButton.model.addChangeListener(ChangeListener { refreshRunActionButtons() })
        refreshRunActionButton(runSelectedCaseButton, GOOD, false)
    }

    private fun configureDebugSelectedCaseButton() {
        debugSelectedCaseButton.icon = AllIcons.Actions.StartDebugger
        debugSelectedCaseButton.iconTextGap = 4
        debugSelectedCaseButton.isOpaque = true
        debugSelectedCaseButton.isContentAreaFilled = true
        debugSelectedCaseButton.isBorderPainted = true
        debugSelectedCaseButton.isFocusPainted = false
        debugSelectedCaseButton.isRolloverEnabled = true
        debugSelectedCaseButton.border = EmptyBorder(1, 6, 1, 6)
        debugSelectedCaseButton.font = debugSelectedCaseButton.font.deriveFont(Font.BOLD)
        debugSelectedCaseButton.model.addChangeListener(ChangeListener { refreshRunActionButtons() })
        refreshRunActionButton(debugSelectedCaseButton, RUN, false)
    }

    private fun refreshRunActionButtons() {
        refreshToolbarRunAllButton()
        refreshRunActionButton(runSelectedCaseButton, GOOD, activeRunButton == ActiveRunButton.RUN_SELECTED)
        refreshRunActionButton(debugSelectedCaseButton, RUN, false)
    }

    private fun refreshToolbarRunAllButton() {
        refreshToolbarIconButton(runAllButton, GOOD, activeRunButton == ActiveRunButton.RUN_ALL)
    }

    private fun refreshRunActionButton(button: JButton, baseColor: Color, active: Boolean) {
        val model = button.model
        val pressed = model.isPressed && model.isArmed
        val foreground = when {
            active -> baseColor
            button.isEnabled -> baseColor
            else -> MUTED
        }
        val background = when {
            active -> ACTION_BUTTON_HOVER
            !button.isEnabled -> PANEL
            pressed -> ACTION_BUTTON_PRESSED
            model.isRollover -> ACTION_BUTTON_HOVER
            else -> PANEL
        }
        button.foreground = foreground
        button.background = background
        button.border = EmptyBorder(2, 7, 2, 7)
        button.repaint()
    }

    private fun configureSettingsButton() {
        configureToolbarIconButton(settingsButton, { if (settingsVisible) RUN else TEXT }, fontDelta = 1.0f)
    }

    private fun configureHelpButton() {
        configureToolbarIconButton(helpButton, TEXT, fontDelta = 1.0f)
    }

    private fun configureToolbarIconButton(button: JButton, baseColor: Color, fontDelta: Float = 2.0f) {
        configureToolbarIconButton(button, { baseColor }, fontDelta)
    }

    private fun configureToolbarIconButton(button: JButton, baseColor: () -> Color, fontDelta: Float = 2.0f) {
        button.foreground = baseColor()
        button.background = PANEL
        button.isOpaque = false
        button.isContentAreaFilled = false
        button.isBorderPainted = false
        button.isFocusPainted = false
        button.isRolloverEnabled = true
        button.border = EmptyBorder(2, 6, 2, 6)
        button.preferredSize = Dimension(34, 28)
        button.minimumSize = button.preferredSize
        button.maximumSize = button.preferredSize
        button.font = button.font.deriveFont(Font.BOLD, button.font.size2D + fontDelta)
        button.model.addChangeListener(ChangeListener { refreshToolbarIconButton(button, baseColor()) })
        refreshToolbarIconButton(button, baseColor())
    }

    private fun refreshToolbarIconButton(button: JButton, baseColor: Color, active: Boolean = false) {
        val model = button.model
        val pressed = model.isPressed && model.isArmed
        val highlighted = active || button.isEnabled && (pressed || model.isRollover)
        button.foreground = if (button.isEnabled || active) baseColor else MUTED
        button.background = when {
            active -> ACTION_BUTTON_HOVER
            !button.isEnabled -> PANEL
            pressed -> ACTION_BUTTON_PRESSED
            model.isRollover -> ACTION_BUTTON_HOVER
            else -> PANEL
        }
        button.isOpaque = highlighted
        button.isContentAreaFilled = highlighted
        button.border = EmptyBorder(2, 6, 2, 6)
        button.repaint()
    }

    private fun configureWorkingDirectoryChooserButton() {
        singleFileWorkingDirectoryChooserButton.foreground = TEXT
        singleFileWorkingDirectoryChooserButton.background = SURFACE
        singleFileWorkingDirectoryChooserButton.isOpaque = false
        singleFileWorkingDirectoryChooserButton.isContentAreaFilled = false
        singleFileWorkingDirectoryChooserButton.isBorderPainted = false
        singleFileWorkingDirectoryChooserButton.isFocusPainted = false
        singleFileWorkingDirectoryChooserButton.border = EmptyBorder(0, 6, 0, 0)
        singleFileWorkingDirectoryChooserButton.preferredSize = Dimension(30, 28)
        singleFileWorkingDirectoryChooserButton.minimumSize = singleFileWorkingDirectoryChooserButton.preferredSize
        singleFileWorkingDirectoryChooserButton.maximumSize = singleFileWorkingDirectoryChooserButton.preferredSize
    }

    private fun buildCenter(): JComponent {
        contentCards.background = PANEL
        contentCards.add(buildMainView(), MAIN_VIEW_CARD)
        contentCards.add(buildSettingsView(), SETTINGS_VIEW_CARD)
        showActiveView()
        return contentCards
    }

    private fun buildMainView(): JComponent {
        tabStrip.layout = BoxLayout(tabStrip, BoxLayout.X_AXIS)
        tabStrip.background = PANEL
        tabScrollPane.border = MatteBorder(1, 0, 1, 0, BORDER)
        tabScrollPane.verticalScrollBarPolicy = JScrollPane.VERTICAL_SCROLLBAR_NEVER
        tabScrollPane.horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED
        tabScrollPane.preferredSize = Dimension(0, TAB_STRIP_BASE_HEIGHT)
        tabScrollPane.minimumSize = Dimension(0, TAB_STRIP_BASE_HEIGHT)
        tabScrollPane.viewport.background = PANEL
        tabScrollPane.horizontalScrollBar.addComponentListener(object : ComponentAdapter() {
            override fun componentShown(e: ComponentEvent) = adjustTabScrollPaneHeight(true)
            override fun componentHidden(e: ComponentEvent) = adjustTabScrollPaneHeight(false)
        })

        return JPanel(BorderLayout()).also {
            it.background = PANEL
            it.add(tabScrollPane, BorderLayout.NORTH)
            it.add(buildBody(), BorderLayout.CENTER)
        }
    }

    private fun adjustTabScrollPaneHeight(scrollBarVisible: Boolean) {
        val extra = if (scrollBarVisible) tabScrollPane.horizontalScrollBar.preferredSize.height else 0
        val newHeight = TAB_STRIP_BASE_HEIGHT + extra
        if (tabScrollPane.preferredSize.height == newHeight) return
        tabScrollPane.preferredSize = Dimension(0, newHeight)
        tabScrollPane.minimumSize = Dimension(0, newHeight)
        tabScrollPane.parent?.revalidate()
        tabScrollPane.parent?.repaint()
    }

    private fun buildSettingsView(): JComponent {
        singleFileModeEnabled.isOpaque = false
        ignoreTrailingWhitespace.isOpaque = false
        outputSplitEnabled.isOpaque = false
        noExpectedModeEnabled.isOpaque = false
        confidentSubmitEnabled.isOpaque = false
        cppStandardCombo.background = SURFACE
        compileOptionsField.background = EDITOR
        compileOptionsField.foreground = TEXT
        compileOptionsField.caretColor = TEXT
        compileOptionsField.emptyText.text = "-O2 -Wall"
        singleFileWorkingDirectoryField.background = EDITOR
        singleFileWorkingDirectoryField.foreground = TEXT
        singleFileWorkingDirectoryField.caretColor = TEXT
        singleFileWorkingDirectoryField.emptyText.text = CPH_DEFAULT_SINGLE_FILE_WORKING_DIRECTORY
        settingsGrid.isFocusable = true
        settingsGrid.background = PANEL
        settingsGrid.border = EmptyBorder(10, 0, 0, 0)
        settingsGrid.removeAll()
        settingsGrid.add(buildSettingsReturnHint())
        settingsGrid.add(settingsSection("运行设置") {
            settingCheckBoxRow(singleFileModeEnabled)
            settingRow("工作目录配置:", singleFileWorkingDirectoryControl())
            settingRow("Time Limits:", timeoutControl())
            settingRow("C++ 标准:", cppStandardCombo)
            compileOptionsField.preferredSize = Dimension(260, compileOptionsField.preferredSize.height)
            settingRow("Compile options:", compileOptionsField)
        })
        settingsGrid.add(Box.createVerticalStrut(8))
        settingsGrid.add(settingsSection("输出设置") {
            settingCheckBoxRow(ignoreTrailingWhitespace)
            settingRow("字体大小:", editorFontSizeSpinner)
            settingCheckBoxRow(noExpectedModeEnabled)
            settingCheckBoxRow(outputSplitEnabled)
        })
        settingsGrid.add(Box.createVerticalStrut(8))
        settingsGrid.add(settingsSection("提交设置") {
            settingCheckBoxRow(confidentSubmitEnabled)
        })
        settingsGrid.add(Box.createVerticalStrut(8))
        settingsGrid.add(settingsSection("快捷键") {
            settingRow("全局运行快捷键：", runAllShortcutField)
            settingRow("单CASE运行快捷键：", runSelectedCaseShortcutField)
            settingRow("单CASE调试快捷键：", debugSelectedCaseShortcutField)
            settingRow("提交CF快捷键：", submitShortcutField)
        })

        val viewport = JPanel(BorderLayout())
        viewport.isFocusable = true
        viewport.background = PANEL
        viewport.border = EmptyBorder(0, 0, 0, 0)
        viewport.add(settingsGrid, BorderLayout.NORTH)
        installSettingsFocusReset(viewport)

        return JBScrollPane(viewport).also {
            it.border = MatteBorder(1, 0, 0, 0, BORDER)
            it.viewport.background = PANEL
            it.horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
        }
    }

    private fun buildSettingsReturnHint(): JComponent {
        settingsReturnHintPanel.removeAll()
        settingsReturnHintPanel.background = SURFACE
        settingsReturnHintPanel.border = CompoundBorder(
            MatteBorder(1, 1, 1, 1, BORDER),
            EmptyBorder(8, 10, 8, 10),
        )
        settingsReturnHintPanel.alignmentX = Component.LEFT_ALIGNMENT
        settingsReturnHintPanel.isVisible = false
        settingsReturnHintPanel.add(
            JBLabel("提示：再次点击设置按钮可回到主界面").also {
                it.foreground = TEXT
            },
            BorderLayout.CENTER,
        )
        val preferred = settingsReturnHintPanel.preferredSize
        settingsReturnHintPanel.maximumSize = Dimension(Int.MAX_VALUE, preferred.height)
        return settingsReturnHintPanel
    }

    private fun toggleSettingsPanel() {
        settingsVisible = !settingsVisible
        if (settingsVisible) {
            showSettingsReturnHintOnce()
        } else {
            settingsReturnHintPanel.isVisible = false
        }
        showActiveView()
    }

    private fun showSettingsReturnHintOnce() {
        val uiState = stateService.getState().ui
        val shouldShow = !uiState.settingsReturnHintShown
        settingsReturnHintPanel.isVisible = shouldShow
        if (shouldShow) {
            uiState.settingsReturnHintShown = true
        }
    }

    private fun showActiveView() {
        (contentCards.layout as? CardLayout)?.show(
            contentCards,
            if (settingsVisible) SETTINGS_VIEW_CARD else MAIN_VIEW_CARD,
        )
        settingsButton.toolTipText = if (settingsVisible) "Hide settings" else "Settings"
        refreshToolbarIconButton(settingsButton, if (settingsVisible) RUN else TEXT)
        contentCards.revalidate()
        contentCards.repaint()
    }

    private fun installSettingsFocusReset(component: Component) {
        if (component is JComponent && shouldClearFocusOnSettingsClick(component)) {
            component.addMouseListener(object : MouseAdapter() {
                override fun mousePressed(e: MouseEvent) {
                    if (SwingUtilities.isLeftMouseButton(e)) {
                        clearSettingsFocus()
                    }
                }
            })
        }
        if (component is Container && !isInteractiveSettingsComponent(component)) {
            component.components.forEach(::installSettingsFocusReset)
        }
    }

    private fun shouldClearFocusOnSettingsClick(component: JComponent): Boolean {
        return !isInteractiveSettingsComponent(component) && (component is JPanel || component is JBLabel)
    }

    private fun isInteractiveSettingsComponent(component: Component): Boolean {
        return component is JTextComponent ||
            component is JComboBox<*> ||
            component is JSpinner ||
            component is JCheckBox ||
            component is JButton
    }

    private fun clearSettingsFocus() {
        if (!settingsGrid.requestFocusInWindow()) {
            KeyboardFocusManager.getCurrentKeyboardFocusManager().clearGlobalFocusOwner()
        }
    }

    private fun persistSingleFileWorkingDirectory() {
        if (applyingTargetSettings || applyingWorkingDirectorySettings) return
        val normalized = CphStateService.normalizeSingleFileWorkingDirectory(singleFileWorkingDirectoryField.text)
        stateService.getState().singleFileWorkingDirectory = normalized
        if (singleFileWorkingDirectoryField.text != normalized) {
            SwingUtilities.invokeLater { setSingleFileWorkingDirectoryText(normalized) }
        }
        CphSingleFileModeService.getInstance(project).syncForCurrentFile(force = true)
    }

    private fun setSingleFileWorkingDirectoryText(value: String) {
        applyingWorkingDirectorySettings = true
        try {
            singleFileWorkingDirectoryField.text = value
        } finally {
            applyingWorkingDirectorySettings = false
        }
    }

    private fun chooseSingleFileWorkingDirectory() {
        val descriptor = FileChooserDescriptorFactory.createSingleFolderDescriptor()
            .withTitle("Choose Working Directory")
        val selected = FileChooser.chooseFile(descriptor, project, null) ?: return
        setSingleFileWorkingDirectoryText(displayWorkingDirectoryPath(selected.path))
        persistSingleFileWorkingDirectory()
    }

    private fun displayWorkingDirectoryPath(path: String): String {
        val selectedPath = path.replace('\\', '/').removeSuffix("/")
        val projectPath = project.basePath?.replace('\\', '/')?.removeSuffix("/")
        if (!projectPath.isNullOrBlank()) {
            if (selectedPath == projectPath) return "."
            val prefix = "$projectPath/"
            if (selectedPath.startsWith(prefix)) {
                val relative = selectedPath.removePrefix(prefix).ifBlank { "." }
                return if (relative == ".") relative else "$relative/"
            }
        }
        return File(path).path
    }

    private fun refreshShortcutSettings() {
        applyingShortcutSettings = true
        try {
            val shortcutState = CphShortcutSettings.getInstance().state
            runAllShortcutField.setStoredShortcut(shortcutState.runAllShortcut, notify = false)
            runSelectedCaseShortcutField.setStoredShortcut(shortcutState.runSelectedCaseShortcut, notify = false)
            debugSelectedCaseShortcutField.setStoredShortcut(shortcutState.debugSelectedCaseShortcut, notify = false)
            submitShortcutField.setStoredShortcut(shortcutState.submitShortcut, notify = false)
        } finally {
            applyingShortcutSettings = false
        }
    }

    private fun persistShortcutSettings() {
        if (applyingShortcutSettings) return
        val nextState = CphShortcutSettingsState(
            runAllShortcut = runAllShortcutField.shortcutText,
            runSelectedCaseShortcut = runSelectedCaseShortcutField.shortcutText,
            debugSelectedCaseShortcut = debugSelectedCaseShortcutField.shortcutText,
            submitShortcut = submitShortcutField.shortcutText,
        )
        val duplicateMessage = CphShortcutMatcher.duplicateShortcutMessage(nextState)
        if (duplicateMessage != null) {
            StatusBar.Info.set("CPH shortcut settings: $duplicateMessage", project)
            return
        }
        CphShortcutSettings.getInstance().update(nextState)
    }

    private fun settingsSection(title: String, content: JPanel.() -> Unit): JPanel {
        val body = JPanel(GridBagLayout()).also {
            it.background = SURFACE
            it.content()
        }
        return JPanel(BorderLayout(0, 8)).also {
            it.background = SURFACE
            it.border = CompoundBorder(MatteBorder(1, 1, 1, 1, BORDER), EmptyBorder(10, 12, 10, 12))
            it.alignmentX = Component.LEFT_ALIGNMENT
            it.add(JBLabel(title).also { label -> label.foreground = TEXT }, BorderLayout.NORTH)
            it.add(body, BorderLayout.CENTER)
            val preferred = it.preferredSize
            it.maximumSize = Dimension(Int.MAX_VALUE, preferred.height)
        }
    }

    private fun JPanel.settingRow(label: String, component: JComponent) {
        val row = nextSettingRow()
        add(JBLabel(label).also { it.foreground = TEXT }, settingConstraints(row, 0, weightx = 0.0))
        add(component, settingConstraints(row, 1, weightx = 1.0))
    }

    private fun JPanel.settingCheckBoxRow(checkBox: JCheckBox) {
        val row = nextSettingRow()
        checkBox.isOpaque = false
        add(checkBox, settingConstraints(row, 0, gridwidth = 2, weightx = 1.0))
    }

    private fun JPanel.nextSettingRow(): Int {
        val row = (getClientProperty(SETTINGS_ROW_PROPERTY) as? Int) ?: 0
        putClientProperty(SETTINGS_ROW_PROPERTY, row + 1)
        return row
    }

    private fun settingConstraints(
        row: Int,
        column: Int,
        gridwidth: Int = 1,
        weightx: Double,
    ): GridBagConstraints {
        return GridBagConstraints().apply {
            gridx = column
            gridy = row
            this.gridwidth = gridwidth
            this.weightx = weightx
            fill = GridBagConstraints.HORIZONTAL
            anchor = GridBagConstraints.WEST
            insets = Insets(3, 0, 3, if (column == 0 && gridwidth == 1) 10 else 0)
        }
    }

    private fun singleFileWorkingDirectoryControl(): JComponent {
        return JPanel(BorderLayout(6, 0)).also {
            it.background = SURFACE
            it.isOpaque = false
            it.add(singleFileWorkingDirectoryField, BorderLayout.CENTER)
            it.add(singleFileWorkingDirectoryChooserButton, BorderLayout.EAST)
        }
    }

    private fun timeoutControl(): JComponent {
        return JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).also {
            it.background = SURFACE
            it.isOpaque = false
            it.add(timeoutSpinner)
            it.add(Box.createHorizontalStrut(8))
            it.add(JBLabel("ms").also { label -> label.foreground = TEXT })
        }
    }

    private fun buildBody(): JComponent {
        val uiState = stateService.getState().ui
        outputContainer.background = PANEL
        outputContainer.isOpaque = true
        rebuildOutputLayout()

        return JPanel(BorderLayout()).also { editorPanel ->
            editorPanel.background = PANEL
            editorPanel.border = EmptyBorder(0, 0, 0, 0)
            editorPanel.add(
                resizableLabeled(
                    "Input",
                    scroll(inputArea, uiState.inputHeight),
                    uiState.inputHeight,
                    inputActions(),
                ) {
                    stateService.getState().ui.inputHeight = it
                },
                BorderLayout.NORTH,
            )
            editorPanel.add(outputContainer, BorderLayout.CENTER)
        }
    }

    private fun rebuildOutputLayout() {
        outputContainer.removeAll()
        val uiState = stateService.getState().ui
        outputSplitEnabled.isSelected = uiState.outputSplitEnabled
        noExpectedModeEnabled.isSelected = uiState.noExpectedModeEnabled
        confidentSubmitEnabled.isSelected = uiState.confidentSubmitEnabled
        outputSplitEnabled.isEnabled = !running && !uiState.noExpectedModeEnabled

        val outputView = when {
            uiState.noExpectedModeEnabled -> singleActualOutput(uiState)
            uiState.outputSplitEnabled -> horizontalOutputSplit(uiState)
            else -> verticalOutputSplit()
        }
        outputContainer.add(outputView, BorderLayout.CENTER)
        outputContainer.revalidate()
        outputContainer.repaint()
    }

    private fun singleActualOutput(uiState: CphUiState): JComponent {
        return labeledOutput("Actual output", scroll(actualArea, uiState.actualHeight))
    }

    private fun horizontalOutputSplit(uiState: CphUiState): JSplitPane {
        return outputSplitPane(
            orientation = JSplitPane.HORIZONTAL_SPLIT,
            first = labeledOutput("Actual output", scroll(actualArea, uiState.actualHeight)),
            second = labeledOutput("Expected output", scroll(expectedArea, uiState.expectedHeight)),
        ).also { splitPane ->
            var applyingSavedRatio = true
            splitPane.resizeWeight = uiState.outputSplitRatio
            splitPane.addPropertyChangeListener(JSplitPane.DIVIDER_LOCATION_PROPERTY) {
                if (applyingSavedRatio) return@addPropertyChangeListener
                persistHorizontalOutputSplitRatio(splitPane)
            }
            SwingUtilities.invokeLater {
                applyHorizontalOutputSplitRatio(splitPane)
                applyingSavedRatio = false
            }
        }
    }

    private fun verticalOutputSplit(): JSplitPane {
        val uiState = stateService.getState().ui
        return outputSplitPane(
            orientation = JSplitPane.VERTICAL_SPLIT,
            first = labeledOutput("Expected output", scroll(expectedArea, uiState.expectedHeight)),
            second = labeledOutput("Actual output", scroll(actualArea, uiState.actualHeight)),
        ).also { splitPane ->
            splitPane.resizeWeight = 0.5
            SwingUtilities.invokeLater { splitPane.setDividerLocation(0.5) }
        }
    }

    private fun outputSplitPane(orientation: Int, first: JComponent, second: JComponent): JSplitPane {
        return JSplitPane(orientation, first, second).also {
            it.setUI(darkSplitPaneUi())
            it.background = PANEL
            it.isOpaque = true
            it.border = null
            it.dividerSize = OUTPUT_DIVIDER_SIZE
            it.isContinuousLayout = true
            it.isOneTouchExpandable = false
            it.minimumSize = Dimension(0, 0)
            first.background = PANEL
            first.isOpaque = true
            second.background = PANEL
            second.isOpaque = true
        }
    }

    private fun darkSplitPaneUi(): BasicSplitPaneUI {
        return object : BasicSplitPaneUI() {
            override fun createDefaultDivider(): BasicSplitPaneDivider {
                return object : BasicSplitPaneDivider(this) {
                    init {
                        background = PANEL
                        border = EmptyBorder(0, 0, 0, 0)
                        setOpaque(true)
                    }

                    override fun paint(g: Graphics) {
                        g.color = PANEL
                        g.fillRect(0, 0, width, height)
                    }
                }
            }
        }
    }

    private fun applyHorizontalOutputSplitRatio(splitPane: JSplitPane) {
        val ratio = CphStateService.clampOutputSplitRatio(stateService.getState().ui.outputSplitRatio)
        val span = splitPane.width - splitPane.dividerSize
        if (span > 0) {
            splitPane.dividerLocation = (span * ratio).roundToInt()
        } else {
            splitPane.setDividerLocation(ratio)
        }
    }

    private fun persistHorizontalOutputSplitRatio(splitPane: JSplitPane) {
        val span = splitPane.width - splitPane.dividerSize
        if (span <= 0) return
        stateService.getState().ui.outputSplitRatio = CphStateService.clampOutputSplitRatio(
            splitPane.dividerLocation.toDouble() / span.toDouble(),
        )
    }

    private fun labeledOutput(label: String, component: JComponent): JComponent {
        return JPanel(BorderLayout(0, 6)).also {
            it.background = PANEL
            it.isOpaque = true
            it.border = EmptyBorder(0, 0, 0, 0)
            it.minimumSize = Dimension(0, 0)
            it.add(JBLabel(label).also { title -> title.foreground = TEXT }, BorderLayout.NORTH)
            it.add(component, BorderLayout.CENTER)
        }
    }

    private fun resizableLabeled(
        label: String,
        component: JComponent,
        height: Int,
        titleAction: JComponent? = null,
        onHeightChanged: (Int) -> Unit,
    ): JComponent {
        return ResizableEditorSection(label, component, height, titleAction, onHeightChanged)
    }

    private fun inputActions(): JComponent {
        return JPanel(FlowLayout(FlowLayout.RIGHT, 4, 0)).also {
            it.background = PANEL
            it.isOpaque = false
            it.add(debugSelectedCaseButton)
            it.add(runSelectedCaseButton)
        }
    }

    private fun scroll(area: JBTextArea, height: Int): JComponent {
        return JBScrollPane(area).also {
            it.preferredSize = Dimension(320, height)
            it.minimumSize = Dimension(100, CPH_MIN_EDITOR_HEIGHT)
            it.border = MatteBorder(1, 1, 1, 1, BORDER)
            it.background = EDITOR
            it.isOpaque = true
            it.viewport.background = EDITOR
            it.viewport.isOpaque = true
            it.horizontalScrollBar.background = EDITOR
            it.verticalScrollBar.background = EDITOR
            it.horizontalScrollBar.border = BorderFactory.createEmptyBorder()
            it.verticalScrollBar.border = BorderFactory.createEmptyBorder()
            val gutter = LineNumberGutter(area)
            it.setRowHeaderView(gutter)
            it.rowHeader?.background = EDITOR
            it.rowHeader?.isOpaque = true
            it.setCorner(ScrollPaneConstants.UPPER_LEFT_CORNER, darkCorner())
            it.setCorner(ScrollPaneConstants.LOWER_LEFT_CORNER, darkCorner())
            it.setCorner(ScrollPaneConstants.UPPER_RIGHT_CORNER, darkCorner())
            it.setCorner(ScrollPaneConstants.LOWER_RIGHT_CORNER, darkCorner())
        }
    }

    private fun darkCorner(): JComponent {
        return JPanel().also {
            it.background = EDITOR
            it.isOpaque = true
            it.border = BorderFactory.createEmptyBorder()
        }
    }

    private inner class ResizableEditorSection(
        label: String,
        private val component: JComponent,
        initialHeight: Int,
        titleAction: JComponent?,
        private val onHeightChanged: (Int) -> Unit,
    ) : JPanel(BorderLayout(0, 6)) {
        private val title = JBLabel(label)
        private val titleRow = JPanel(BorderLayout())
        private val dragHandle = ResizeHandle()
        private var editorHeight = CphStateService.clampEditorHeight(initialHeight)
        private var dragStartY = 0
        private var dragStartHeight = editorHeight

        init {
            background = PANEL
            border = EmptyBorder(0, 0, 0, 0)
            alignmentX = Component.LEFT_ALIGNMENT

            titleRow.background = PANEL
            titleRow.add(title, BorderLayout.WEST)
            titleAction?.let { titleRow.add(it, BorderLayout.EAST) }

            val editorContainer = JPanel(BorderLayout())
            editorContainer.background = PANEL
            editorContainer.add(component, BorderLayout.CENTER)
            editorContainer.add(dragHandle, BorderLayout.SOUTH)

            add(titleRow, BorderLayout.NORTH)
            add(editorContainer, BorderLayout.CENTER)

            dragHandle.toolTipText = "Drag to resize"
            dragHandle.cursor = Cursor.getPredefinedCursor(Cursor.S_RESIZE_CURSOR)
            dragHandle.addMouseListener(object : MouseAdapter() {
                override fun mousePressed(e: MouseEvent) {
                    dragStartY = e.yOnScreen
                    dragStartHeight = editorHeight
                }
            })
            dragHandle.addMouseMotionListener(object : MouseAdapter() {
                override fun mouseDragged(e: MouseEvent) {
                    setEditorHeight(dragStartHeight + e.yOnScreen - dragStartY, persist = true)
                }
            })

            setEditorHeight(editorHeight, persist = false)
        }

        private fun setEditorHeight(height: Int, persist: Boolean) {
            editorHeight = CphStateService.clampEditorHeight(height)
            component.preferredSize = Dimension(320, editorHeight)
            component.minimumSize = Dimension(100, CPH_MIN_EDITOR_HEIGHT)
            component.maximumSize = Dimension(Int.MAX_VALUE, editorHeight)

            val insets = insets
            val totalHeight = editorHeight +
                titleRow.preferredSize.height +
                RESIZE_HANDLE_HEIGHT +
                insets.top +
                insets.bottom +
                6
            preferredSize = Dimension(320, totalHeight)
            minimumSize = Dimension(
                100,
                CPH_MIN_EDITOR_HEIGHT +
                    titleRow.preferredSize.height +
                    RESIZE_HANDLE_HEIGHT +
                    insets.top +
                    insets.bottom +
                    6,
            )
            maximumSize = Dimension(Int.MAX_VALUE, totalHeight)

            if (persist) {
                onHeightChanged(editorHeight)
            }
            revalidate()
            parent?.revalidate()
            repaint()
        }
    }

    private class ResizeHandle : JPanel() {
        init {
            isOpaque = false
            preferredSize = Dimension(0, RESIZE_HANDLE_HEIGHT)
            minimumSize = preferredSize
            maximumSize = Dimension(Int.MAX_VALUE, RESIZE_HANDLE_HEIGHT)
        }
    }

    private fun configureEditor(area: JBTextArea) {
        area.lineWrap = false
        area.font = Font(Font.MONOSPACED, Font.PLAIN, stateService.getState().ui.editorFontSize)
        area.background = EDITOR
        area.foreground = TEXT
        area.caretColor = TEXT
        area.border = EmptyBorder(8, 10, 8, 10)
    }

    private fun installEditorFontWheel(area: JBTextArea) {
        area.addMouseWheelListener { event ->
            if (!event.isControlDown) return@addMouseWheelListener
            event.consume()
            val delta = if (event.preciseWheelRotation < 0.0) 1 else -1
            val current = stateService.getState().ui.editorFontSize
            setEditorFontSize(current + delta, persist = true)
        }
    }

    private fun setEditorFontSize(size: Int, persist: Boolean) {
        val clamped = CphStateService.clampEditorFontSize(size)
        if (persist) {
            stateService.getState().ui.editorFontSize = clamped
        }
        if ((editorFontSizeSpinner.value as? Number)?.toInt() != clamped) {
            editorFontSizeSpinner.value = clamped
        }
        val font = Font(Font.MONOSPACED, Font.PLAIN, clamped)
        listOf(inputArea, expectedArea, actualArea).forEach { area ->
            area.font = font
            area.revalidate()
            area.repaint()
        }
        revalidate()
        repaint()
    }

    private fun installDiffRefreshListener(area: JBTextArea) {
        area.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = schedule()
            override fun removeUpdate(e: DocumentEvent) = schedule()
            override fun changedUpdate(e: DocumentEvent) = schedule()

            private fun schedule() {
                SwingUtilities.invokeLater { refreshActualDiffHighlights() }
            }
        })
    }

    private fun refreshTarget() {
        flushSelectedCase()
        currentIdentity = CphTargetResolver.current(project)
        currentTargetCases = stateService.getOrCreateTargetCases(currentIdentity)
        runtimeStates.clear()
        selectedCase = null

        applyingTargetSettings = true
        try {
            timeoutSpinner.value = currentTargetCases.timeoutMillis.toInt()
            singleFileModeEnabled.isSelected = stateService.getState().singleFileModeEnabled
            setSingleFileWorkingDirectoryText(stateService.getState().singleFileWorkingDirectory)
            editorFontSizeSpinner.value = stateService.getState().ui.editorFontSize
            noExpectedModeEnabled.isSelected = stateService.getState().ui.noExpectedModeEnabled
            confidentSubmitEnabled.isSelected = stateService.getState().ui.confidentSubmitEnabled
            ignoreTrailingWhitespace.isSelected = currentTargetCases.ignoreTrailingWhitespace
            val compileSettings = stateService.getState().compileSettings
            cppStandardCombo.selectedItem = compileSettings.cppStandard
            compileOptionsField.text = compileSettings.compileOptions
        } finally {
            applyingTargetSettings = false
        }

        refreshTabs()
        selectCase(currentTargetCases.cases.firstOrNull())
        updateActions()
        syncCompileSettingsForCurrentTarget(reportStatus = true)
    }

    private fun installTargetRefreshListeners() {
        val connection = project.messageBus.connect(this)
        connection.subscribe(RunManagerListener.TOPIC, object : RunManagerListener {
            override fun runConfigurationSelected(settings: RunnerAndConfigurationSettings?) {
                scheduleTargetRefresh()
            }

            override fun runConfigurationChanged(settings: RunnerAndConfigurationSettings) {
                if (settings === RunManager.getInstance(project).selectedConfiguration) {
                    scheduleTargetRefresh()
                }
            }
        })
        connection.subscribe(ExecutionTargetManager.TOPIC, object : ExecutionTargetListener {
            override fun activeTargetChanged(target: ExecutionTarget) {
                scheduleTargetRefresh()
            }
        })
        connection.subscribe(CphCasesChangedListener.TOPIC, object : CphCasesChangedListener {
            override fun targetCasesChanged(targetId: String) {
                if (targetId == currentIdentity.id) {
                    scheduleTargetRefresh()
                }
            }
        })
    }

    private fun scheduleTargetRefresh() {
        if (running) {
            pendingTargetRefresh = true
            return
        }
        if (SwingUtilities.isEventDispatchThread()) {
            refreshTarget()
        } else {
            ApplicationManager.getApplication().invokeLater {
                if (running) {
                    pendingTargetRefresh = true
                } else {
                    refreshTarget()
                }
            }
        }
    }

    private fun addCase() {
        if (running) return
        flushSelectedCase()
        val index = currentTargetCases.cases.size + 1
        val testCase = CphTestCase(name = "Case $index")
        currentTargetCases.cases.add(testCase)
        refreshTabs()
        selectCase(testCase)
        updateActions()
    }

    private fun resetCases() {
        if (running) return
        flushSelectedCase()
        currentTargetCases.cases.clear()
        runtimeStates.clear()
        val testCase = CphTestCase(name = "Case 1")
        currentTargetCases.cases.add(testCase)
        selectedCase = null
        refreshTabs()
        selectCase(testCase)
        updateActions()
    }

    private fun deleteCase(testCase: CphTestCase) {
        if (running) return
        val index = currentTargetCases.cases.indexOfFirst { it.id == testCase.id }
        val deletingSelected = selectedCase?.id == testCase.id
        currentTargetCases.cases.remove(testCase)
        runtimeStates.remove(testCase.id)
        if (deletingSelected) {
            selectedCase = null
            refreshTabs()
            selectCase(currentTargetCases.cases.getOrNull(index.coerceAtMost(currentTargetCases.cases.lastIndex)))
        } else {
            refreshTabs()
            updateActions()
        }
    }

    private fun runSelectedCase() {
        flushSelectedCase()
        val testCase = selectedCase ?: return
        runCases(listOf(testCase), ActiveRunButton.RUN_SELECTED)
    }

    private fun debugSelectedCase() {
        if (running) return
        flushSelectedCase()
        val identity = currentIdentity
        val testCase = selectedCase ?: return
        val saveError = saveAllDocumentsBeforeRun()
        if (saveError != null) {
            reportDebugError("Save failed before debugging CPH sample: ${saveError.message ?: saveError.javaClass.simpleName}")
            return
        }

        if (identity.kind == CphTargetKind.CPP_FILE) {
            prepareAndDebugCppFileCase(identity, currentTargetCases, testCase)
            return
        }
        launchDebugCase(identity, testCase)
    }

    private fun prepareAndDebugCppFileCase(
        identity: CphTargetIdentity,
        targetCases: CphTargetCases,
        testCase: CphTestCase,
    ) {
        StatusBar.Info.set("CPH Debug: preparing ${testCase.name}", project)
        object : Task.Backgroundable(project, "Preparing CPH debug", false) {
            override fun run(indicator: ProgressIndicator) {
                indicator.text = "Preparing ${testCase.name}"
                val syncError = compileSettingsSynchronizer.sync(
                    identity,
                    targetCases,
                    stateService.getState().compileSettings.toCompileSettings(),
                    waitForCppFileTarget = true,
                ).error
                ApplicationManager.getApplication().invokeLater {
                    if (syncError != null) {
                        reportDebugError("Failed to prepare C/C++ File target: $syncError")
                    } else {
                        launchDebugCase(identity, testCase)
                    }
                }
            }
        }.queue()
    }

    private fun launchDebugCase(identity: CphTargetIdentity, testCase: CphTestCase) {
        StatusBar.Info.set("CPH Debug: launching ${testCase.name}", project)
        try {
            CphRunner(project).debugCase(identity, testCase)
        } catch (e: Throwable) {
            reportDebugError(e.message ?: e.javaClass.simpleName)
        }
    }

    private fun runAllCases() {
        flushSelectedCase()
        runCases(currentTargetCases.cases.filter { it.enabled }, ActiveRunButton.RUN_ALL)
    }

    private fun runCases(cases: List<CphTestCase>, source: ActiveRunButton) {
        if (cases.isEmpty() || running) return
        val identity = currentIdentity
        val targetCases = currentTargetCases
        val timeoutMillis = currentTargetCases.timeoutMillis
        val ignoreTrailing = currentTargetCases.ignoreTrailingWhitespace
        val noExpectedMode = stateService.getState().ui.noExpectedModeEnabled
        val shouldAutoSubmitOnAllAccepted = stateService.getState().ui.confidentSubmitEnabled &&
            stateService.getState().singleFileModeEnabled &&
            source == ActiveRunButton.RUN_ALL
        var completedAllCases = false
        runtimeStates.clear()
        cases.forEach { runtimeStates[it.id] = RuntimeTabState.QUEUED }
        refreshTabs()
        startRunSpinner(source)
        setRunning(true)

        object : Task.Backgroundable(project, "Running CPH samples", true) {
            override fun run(indicator: ProgressIndicator) {
                val runner = CphRunner(project)
                val saveError = saveAllDocumentsBeforeRun()
                if (saveError != null) {
                    val result = CphCaseResult(
                        verdict = CphVerdict.ERROR,
                        message = "Save failed before running CPH samples: ${saveError.message ?: saveError.javaClass.simpleName}",
                    )
                    cases.forEach { it.lastResult = result.copy() }
                    cases.forEach { reportCaseError(it, it.lastResult) }
                    return
                }
                val syncError = compileSettingsSynchronizer.sync(
                    identity,
                    targetCases,
                    stateService.getState().compileSettings.toCompileSettings(),
                    waitForCppFileTarget = true,
                ).error
                if (syncError != null) {
                    val result = CphCaseResult(
                        verdict = CphVerdict.ERROR,
                        message = "Failed to sync CPH compile settings: $syncError",
                    )
                    cases.forEach { it.lastResult = result.copy() }
                    cases.forEach { reportCaseError(it, it.lastResult) }
                    return
                }
                for ((index, testCase) in cases.withIndex()) {
                    if (indicator.isCanceled) break
                    ApplicationManager.getApplication().invokeLater {
                        runtimeStates[testCase.id] = RuntimeTabState.RUNNING
                        refreshTabs()
                        if (selectedCase?.id == testCase.id) renderSelectedCase()
                    }
                    indicator.text = "Running ${testCase.name}"
                    indicator.fraction = index.toDouble() / cases.size
                    val result = runner.runCase(
                        identity,
                        testCase,
                        timeoutMillis,
                        ignoreTrailing,
                        compareExpectedOutput = !noExpectedMode,
                    ).let {
                        if (noExpectedMode) CphStatusMapper.normalizeNoExpectedResult(it) else it
                    }
                    testCase.lastResult = result
                    ApplicationManager.getApplication().invokeLater {
                        reportCaseError(testCase, result)
                        runtimeStates.remove(testCase.id)
                        refreshTabs()
                        if (selectedCase?.id == testCase.id) {
                            renderSelectedCase()
                        }
                    }
                }
                completedAllCases = !indicator.isCanceled
            }

            override fun onFinished() {
                ApplicationManager.getApplication().invokeLater {
                    val shouldSubmit = shouldAutoSubmitOnAllAccepted &&
                        completedAllCases &&
                        !pendingTargetRefresh &&
                        cases.all { it.lastResult.verdict == CphVerdict.AC }
                    runtimeStates.clear()
                    setRunning(false)
                    if (pendingTargetRefresh) {
                        pendingTargetRefresh = false
                        refreshTarget()
                    } else {
                        refreshTabs()
                        renderSelectedCase()
                    }
                    if (shouldSubmit) {
                        StatusBar.Info.set("CPH 自信模式：本地全部 AC，自动提交 Codeforces", project)
                        CphSubmitOrchestrator.getInstance(project).submit()
                    }
                }
            }
        }.queue()
    }

    private fun reportCaseError(testCase: CphTestCase, result: CphCaseResult) {
        if (result.verdict != CphVerdict.ERROR) return
        if (!SwingUtilities.isEventDispatchThread()) {
            ApplicationManager.getApplication().invokeLater { reportCaseError(testCase, result) }
            return
        }
        val statusMessage = CphUiText.errorStatusMessage(testCase.name, result)
        StatusBar.Info.set(statusMessage, project)
        NotificationGroupManager.getInstance()
            .getNotificationGroup(CPH_NOTIFICATION_GROUP_ID)
            .createNotification("CPH sample failed", CphUiText.errorNotificationContent(testCase.name, result), NotificationType.ERROR)
            .notify(project)
    }

    private fun scheduleCompileSettingsSync() {
        compileOptionsSyncTimer.restart()
    }

    private fun syncCompileSettingsForCurrentTarget(reportStatus: Boolean) {
        if (applyingTargetSettings || running) return
        val result = compileSettingsSynchronizer.sync(
            currentIdentity,
            currentTargetCases,
            stateService.getState().compileSettings.toCompileSettings(),
        )
        val error = result.error ?: return
        if (reportStatus) {
            StatusBar.Info.set("CPH compile settings sync failed: $error", project)
        }
    }

    private fun reportDebugError(message: String) {
        if (!SwingUtilities.isEventDispatchThread()) {
            ApplicationManager.getApplication().invokeLater { reportDebugError(message) }
            return
        }
        val statusMessage = "CPH Debug: $message"
        StatusBar.Info.set(statusMessage, project)
        NotificationGroupManager.getInstance()
            .getNotificationGroup(CPH_NOTIFICATION_GROUP_ID)
            .createNotification("CPH debug failed", statusMessage, NotificationType.ERROR)
            .notify(project)
    }

    private fun saveAllDocumentsBeforeRun(): Throwable? {
        return runCatching {
            val saveDocuments = Runnable {
                WriteIntentReadAction.run {
                    FileDocumentManager.getInstance().saveAllDocuments()
                }
            }
            if (SwingUtilities.isEventDispatchThread()) {
                saveDocuments.run()
            } else {
                ApplicationManager.getApplication().invokeAndWait {
                    saveDocuments.run()
                }
            }
        }.exceptionOrNull()
    }

    private fun setRunning(value: Boolean) {
        running = value
        if (!value) {
            stopRunSpinner()
        }
        if (SwingUtilities.isEventDispatchThread()) {
            updateActions()
        } else {
            ApplicationManager.getApplication().invokeLater { updateActions() }
        }
    }

    private fun startRunSpinner(source: ActiveRunButton) {
        activeRunButton = source
        runSpinnerIndex = 0
        updateRunSpinnerText()
        if (!runSpinnerTimer.isRunning) {
            runSpinnerTimer.start()
        }
    }

    private fun stopRunSpinner() {
        runSpinnerTimer.stop()
        activeRunButton = null
        runAllButton.text = RUN_ALL_BUTTON_TEXT
        runSelectedCaseButton.text = RUN_SELECTED_BUTTON_TEXT
    }

    private fun updateRunSpinnerText() {
        val frame = RUN_SPINNER_FRAMES[runSpinnerIndex % RUN_SPINNER_FRAMES.size]
        runSpinnerIndex++
        runAllButton.text = if (activeRunButton == ActiveRunButton.RUN_ALL) frame else RUN_ALL_BUTTON_TEXT
        runSelectedCaseButton.text = if (activeRunButton == ActiveRunButton.RUN_SELECTED) {
            "$frame Run"
        } else {
            RUN_SELECTED_BUTTON_TEXT
        }
    }

    private fun flushSelectedCase() {
        val testCase = selectedCase ?: return
        testCase.input = inputArea.text
        if (!stateService.getState().ui.noExpectedModeEnabled) {
            testCase.expectedOutput = expectedArea.text
        }
        refreshTabs()
    }

    private fun selectCase(testCase: CphTestCase?) {
        if (selectedCase?.id != testCase?.id) {
            flushSelectedCase()
        }
        selectedCase = testCase
        renderSelectedCase()
        refreshTabs()
        testCase?.id?.let { id ->
            caseTabComponents[id]?.let { tab ->
                tab.scrollRectToVisible(Rectangle(0, 0, tab.width.coerceAtLeast(1), tab.height.coerceAtLeast(1)))
            }
        }
    }

    private fun renderSelectedCase() {
        val testCase = selectedCase
        val enabled = testCase != null
        inputArea.isEnabled = enabled
        expectedArea.isEnabled = enabled
        actualArea.isEnabled = enabled

        if (testCase == null) {
            inputArea.text = ""
            expectedArea.text = ""
            actualArea.text = ""
        } else {
            inputArea.text = testCase.input
            expectedArea.text = testCase.expectedOutput
            actualArea.text = testCase.lastResult.actualOutput
        }
        refreshActualDiffHighlights()
        updateActions()
    }

    private fun refreshActualDiffHighlights() {
        actualArea.highlighter.removeAllHighlights()
        val testCase = selectedCase ?: return
        if (stateService.getState().ui.noExpectedModeEnabled) return
        val status = statusFor(testCase)
        if (status == TabStatus.NOT_RUN || status == TabStatus.AC) return

        CphComparator.differingActualLines(
            actual = actualArea.text,
            expected = expectedArea.text,
            ignoreTrailingWhitespace = currentTargetCases.ignoreTrailingWhitespace,
        ).forEach(::highlightActualLine)
    }

    private fun highlightActualLine(line: Int) {
        if (line !in 0 until actualArea.lineCount) return
        runCatching {
            val start = actualArea.getLineStartOffset(line)
            val end = actualArea.getLineEndOffset(line)
                .minus(1)
                .coerceAtLeast(start)
                .coerceAtMost(actualArea.document.length)
            actualArea.highlighter.addHighlight(start, end.coerceAtLeast(start), DIFF_LINE_PAINTER)
        }
    }

    private fun refreshTabs() {
        tabStrip.removeAll()
        caseTabComponents.clear()
        currentTargetCases.cases.forEachIndexed { index, testCase ->
            val tab = CaseTab(index + 1, testCase)
            caseTabComponents[testCase.id] = tab
            tabStrip.add(tab)
        }
        tabStrip.add(AddCaseTab())
        tabStrip.add(Box.createHorizontalGlue())
        tabStrip.revalidate()
        tabStrip.repaint()
    }

    private fun statusFor(testCase: CphTestCase): TabStatus {
        val noExpectedMode = stateService.getState().ui.noExpectedModeEnabled
        return when (runtimeStates[testCase.id]) {
            RuntimeTabState.RUNNING -> TabStatus.RUNNING
            RuntimeTabState.QUEUED -> TabStatus.QUEUED
            null -> tabStatusFor(CphStatusMapper.displayStatus(testCase.lastResult.verdict, noExpectedMode))
        }
    }

    private fun tabStatusFor(status: CphRunDisplayStatus): TabStatus {
        return when (status) {
            CphRunDisplayStatus.NOT_RUN -> TabStatus.NOT_RUN
            CphRunDisplayStatus.OK -> TabStatus.OK
            CphRunDisplayStatus.AC -> TabStatus.AC
            CphRunDisplayStatus.WA -> TabStatus.WA
            CphRunDisplayStatus.TLE -> TabStatus.TLE
            CphRunDisplayStatus.RE -> TabStatus.RE
            CphRunDisplayStatus.ERROR -> TabStatus.ERROR
        }
    }

    private fun updateActions() {
        val runnable = currentIdentity.runnable && !running
        val runAllActive = activeRunButton == ActiveRunButton.RUN_ALL
        val runSelectedActive = activeRunButton == ActiveRunButton.RUN_SELECTED
        settingsButton.isEnabled = !running
        runSelectedCaseButton.isEnabled = (selectedCase != null && runnable) || runSelectedActive
        debugSelectedCaseButton.isEnabled = selectedCase != null && !running &&
            currentIdentity.runnable &&
            (currentIdentity.kind == CphTargetKind.CMAKE_APP || currentIdentity.kind == CphTargetKind.CPP_FILE)
        runAllButton.isEnabled = (currentTargetCases.cases.any { it.enabled } && runnable) || runAllActive
        resetCasesButton.isEnabled = !running
        timeoutSpinner.isEnabled = !running
        editorFontSizeSpinner.isEnabled = !running
        noExpectedModeEnabled.isEnabled = !running
        confidentSubmitEnabled.isEnabled = !running && stateService.getState().singleFileModeEnabled
        ignoreTrailingWhitespace.isEnabled = !running
        outputSplitEnabled.isEnabled = !running && !stateService.getState().ui.noExpectedModeEnabled
        cppStandardCombo.isEnabled = !running
        compileOptionsField.isEnabled = !running
        singleFileWorkingDirectoryField.isEnabled = !running
        singleFileWorkingDirectoryChooserButton.isEnabled = !running
        refreshToolbarIconButton(settingsButton, if (settingsVisible) RUN else TEXT)
        refreshToolbarIconButton(resetCasesButton, TEXT)
        refreshToolbarIconButton(helpButton, TEXT)
        refreshRunActionButtons()
    }

    private inner class CaseTab(
        private val index: Int,
        private val testCase: CphTestCase,
    ) : JPanel(BorderLayout()) {
        private val enabledToggle = JCheckBox()
        private val deleteCaseButton = JButton("×")
        private val title = JBLabel()
        private val detail = JBLabel()

        init {
            val status = statusFor(testCase)
            val displayStatus = if (status == TabStatus.RUNNING || status == TabStatus.QUEUED) {
                tabStatusFor(
                    CphStatusMapper.displayStatus(
                        testCase.lastResult.verdict,
                        stateService.getState().ui.noExpectedModeEnabled,
                    ),
                )
            } else {
                status
            }
            val style = styleFor(status)
            val isSelected = selectedCase?.id == testCase.id
            val isCaseEnabled = testCase.enabled
            val foreground = if (isCaseEnabled) style.foreground else MUTED
            val tabBackground = when {
                isSelected && isCaseEnabled -> SELECTED
                isSelected -> SURFACE
                else -> PANEL
            }
            val tabBorder = when {
                !isCaseEnabled -> BORDER
                isSelected -> style.foreground
                else -> BORDER
            }

            background = tabBackground
            border = CompoundBorder(
                MatteBorder(1, 1, 2, 1, tabBorder),
                EmptyBorder(3, 10, 3, 8),
            )
            preferredSize = Dimension(123, 60)
            minimumSize = Dimension(102, 60)
            maximumSize = Dimension(161, 60)
            cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)

            enabledToggle.isSelected = testCase.enabled
            enabledToggle.isEnabled = !running
            enabledToggle.isOpaque = false
            enabledToggle.toolTipText = if (testCase.enabled) "Enabled" else "Disabled"
            enabledToggle.horizontalAlignment = SwingConstants.CENTER
            enabledToggle.preferredSize = Dimension(24, 20)
            enabledToggle.maximumSize = enabledToggle.preferredSize
            enabledToggle.addActionListener {
                testCase.enabled = enabledToggle.isSelected
                selectCase(testCase)
                refreshTabs()
                updateActions()
            }

            deleteCaseButton.isEnabled = !running
            deleteCaseButton.toolTipText = "Delete case"
            deleteCaseButton.foreground = if (deleteCaseButton.isEnabled) Color.WHITE else MUTED
            deleteCaseButton.background = tabBackground
            deleteCaseButton.isOpaque = false
            deleteCaseButton.isContentAreaFilled = false
            deleteCaseButton.isBorderPainted = false
            deleteCaseButton.isFocusPainted = false
            deleteCaseButton.horizontalAlignment = SwingConstants.CENTER
            deleteCaseButton.border = EmptyBorder(0, 0, 0, 0)
            deleteCaseButton.preferredSize = Dimension(24, 20)
            deleteCaseButton.maximumSize = deleteCaseButton.preferredSize
            deleteCaseButton.font = deleteCaseButton.font.deriveFont(Font.BOLD, deleteCaseButton.font.size2D + 1.0f)
            deleteCaseButton.addActionListener { deleteCase(testCase) }

            title.text = tabTitle(index, displayStatus)
            title.foreground = foreground
            title.font = title.font.deriveFont(Font.BOLD)
            title.alignmentX = Component.LEFT_ALIGNMENT

            detail.text = tabDetail(testCase, displayStatus)
            detail.foreground = MUTED
            detail.font = detail.font.deriveFont(detail.font.size2D - 1.0f)
            detail.alignmentX = Component.LEFT_ALIGNMENT
            toolTipText = tabTooltip(testCase, status)

            val content = JPanel(BorderLayout(8, 0))
            content.background = background
            val textPanel = JPanel()
            textPanel.layout = BoxLayout(textPanel, BoxLayout.Y_AXIS)
            textPanel.background = background
            textPanel.add(title)
            textPanel.add(detail)
            val actionPanel = JPanel(BorderLayout())
            actionPanel.background = background
            actionPanel.preferredSize = Dimension(24, 1)
            actionPanel.add(deleteCaseButton, BorderLayout.NORTH)
            actionPanel.add(enabledToggle, BorderLayout.SOUTH)
            content.add(textPanel, BorderLayout.CENTER)
            content.add(actionPanel, BorderLayout.EAST)
            content.toolTipText = toolTipText
            title.toolTipText = toolTipText
            detail.toolTipText = toolTipText
            textPanel.toolTipText = toolTipText
            actionPanel.toolTipText = toolTipText

            val selectListener = object : java.awt.event.MouseAdapter() {
                override fun mouseClicked(e: java.awt.event.MouseEvent) {
                    selectCase(testCase)
                }
            }
            addMouseListener(selectListener)
            content.addMouseListener(selectListener)
            title.addMouseListener(selectListener)
            detail.addMouseListener(selectListener)
            textPanel.addMouseListener(selectListener)

            add(content, BorderLayout.CENTER)
        }
    }

    private inner class AddCaseTab : JPanel(BorderLayout()) {
        private val button = JButton("+")

        init {
            background = PANEL
            border = CompoundBorder(
                MatteBorder(1, 1, 2, 1, BORDER),
                EmptyBorder(3, 10, 3, 8),
            )
            preferredSize = Dimension(123, 60)
            minimumSize = Dimension(102, 60)
            maximumSize = Dimension(161, 60)
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            toolTipText = "Add case"

            button.isEnabled = !running
            button.toolTipText = toolTipText
            button.foreground = TEXT
            button.background = PANEL
            button.isOpaque = false
            button.isContentAreaFilled = false
            button.isBorderPainted = false
            button.isFocusPainted = false
            button.font = button.font.deriveFont(Font.BOLD, button.font.size2D + 2.0f)
            button.addActionListener { addCase() }

            val listener = object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    addCase()
                }
            }
            addMouseListener(listener)
            add(button, BorderLayout.CENTER)
        }
    }

    private class LineNumberGutter(private val area: JBTextArea) : JComponent(), DocumentListener {
        init {
            isOpaque = true
            font = area.font
            foreground = MUTED
            background = EDITOR
            border = MatteBorder(0, 0, 0, 1, BORDER)
            area.document.addDocumentListener(this)
            area.addPropertyChangeListener("font") { update() }
            updatePreferredSize()
        }

        override fun insertUpdate(e: DocumentEvent) = update()
        override fun removeUpdate(e: DocumentEvent) = update()
        override fun changedUpdate(e: DocumentEvent) = update()

        override fun paintComponent(g: Graphics) {
            super.paintComponent(g)
            val clip = g.clipBounds ?: Rectangle(0, 0, width, height)
            g.color = background
            g.fillRect(0, clip.y, width, clip.height)
            g.color = foreground
            g.font = font

            val metrics = area.getFontMetrics(area.font)
            val lineHeight = metrics.height.coerceAtLeast(1)
            val topInset = area.insets.top
            val firstLine = ((clip.y - topInset).coerceAtLeast(0) / lineHeight).coerceAtMost(area.lineCount - 1)
            val lastLine = ((clip.y + clip.height - topInset).coerceAtLeast(0) / lineHeight)
                .coerceAtMost(area.lineCount - 1)
            val right = width - 8
            for (line in firstLine..lastLine) {
                val text = (line + 1).toString()
                val x = right - metrics.stringWidth(text)
                val y = topInset + line * lineHeight + metrics.ascent
                g.drawString(text, x, y)
            }
        }

        private fun update() {
            updatePreferredSize()
            revalidate()
            repaint()
        }

        private fun updatePreferredSize() {
            val metrics = area.getFontMetrics(area.font)
            val digits = area.lineCount.toString().length.coerceAtLeast(2)
            val width = metrics.stringWidth("9".repeat(digits)) + 16
            val height = area.insets.top + area.insets.bottom + metrics.height * area.lineCount.coerceAtLeast(1)
            preferredSize = Dimension(width, height)
        }
    }

    private class LineBackgroundPainter(private val color: Color) : Highlighter.HighlightPainter {
        override fun paint(g: Graphics, p0: Int, p1: Int, bounds: Shape, c: JTextComponent) {
            val area = c as? JBTextArea ?: return
            val line = runCatching { area.getLineOfOffset(p0.coerceAtMost(area.document.length)) }.getOrNull() ?: return
            val startOffset = runCatching { area.getLineStartOffset(line) }.getOrNull() ?: return
            val start = c.modelToView2D(startOffset) ?: return
            val height = c.getFontMetrics(c.font).height
            val nextOffset = if (line + 1 < area.lineCount) {
                runCatching { area.getLineStartOffset(line + 1) }.getOrNull()
            } else {
                null
            }
            val nextY = nextOffset?.let { c.modelToView2D(it)?.y?.toInt() }
            val y = start.y.toInt()
            val rowHeight = (nextY?.minus(y) ?: height).coerceAtLeast(height)
            g.color = color
            g.fillRect(0, y, c.width, rowHeight)
        }
    }

    private enum class RuntimeTabState {
        QUEUED,
        RUNNING,
    }

    private enum class ActiveRunButton {
        RUN_ALL,
        RUN_SELECTED,
    }

    private enum class TabStatus(
        val label: String,
        val icon: String,
    ) {
        OK("OK", "✓"),
        AC("AC", "✓"),
        WA("WA", "✕"),
        TLE("TLE", "◷"),
        RE("RE", "!"),
        ERROR("ERR", "!"),
        RUNNING("RUN", "●"),
        QUEUED("...", "○"),
        NOT_RUN("-", "○"),
    }

    private data class TabStyle(
        val foreground: Color,
        val background: Color,
        val border: Color,
    )

    private companion object {
        private val PANEL = Color(0x151923)
        private val SURFACE = Color(0x1B202B)
        private val SELECTED = Color(0x202A3A)
        private val EDITOR = Color(0x111620)
        private val BORDER = Color(0x343A46)
        private val TEXT = Color(0xD8DEE9)
        private val MUTED = Color(0x9BA3AF)
        private val GOOD = Color(0x65C466)
        private val BAD = Color(0xFF5A57)
        private val ACTION_BUTTON_HOVER = Color(0x242B38)
        private val ACTION_BUTTON_PRESSED = Color(0x2A3548)
        private val DIFF_BACKGROUND = Color(0x3A1F25)
        private val WARN = Color(0xF2A93B)
        private val RUN = Color(0x6EA2FF)
        private val DIFF_LINE_PAINTER = LineBackgroundPainter(DIFF_BACKGROUND)
        private const val MAIN_VIEW_CARD = "main"
        private const val SETTINGS_VIEW_CARD = "settings"
        private const val RESIZE_HANDLE_HEIGHT = 8
        private const val OUTPUT_DIVIDER_SIZE = 6
        private const val TAB_STRIP_BASE_HEIGHT = 62
        private const val COMPILE_OPTIONS_SYNC_DELAY_MILLIS = 650
        private const val SUBMISSION_STATUS_VISIBLE_MILLIS = 15_000
        private val SUBMIT_SPINNER_FRAMES = arrayOf("⠋", "⠙", "⠹", "⠸", "⠼", "⠴", "⠦", "⠧", "⠇", "⠏")
        private val RUN_SPINNER_FRAMES = arrayOf("○", "◔", "◑", "◕")
        private const val RUN_ALL_BUTTON_TEXT = "▷"
        private const val RUN_SELECTED_BUTTON_TEXT = "▷ Run"
        private const val CPH_DOCS_URL = "https://cph.kkkzbh.cn"
        private const val CPH_NOTIFICATION_GROUP_ID = "CPH Target Runner"
        private const val SETTINGS_ROW_PROPERTY = "cph.settings.row"

        private fun tabTitle(index: Int, status: TabStatus): String {
            return if (status == TabStatus.NOT_RUN) {
                "$index CASE"
            } else {
                "$index ${status.icon} ${status.label}"
            }
        }

        private fun tabDetail(testCase: CphTestCase, status: TabStatus): String {
            return when (status) {
                TabStatus.OK,
                TabStatus.AC,
                TabStatus.WA,
                TabStatus.TLE,
                TabStatus.RE,
                TabStatus.ERROR -> formatDuration(testCase.lastResult.durationMillis)
                TabStatus.RUNNING,
                TabStatus.QUEUED -> ""
                TabStatus.NOT_RUN -> ""
            }
        }

        private fun tabTooltip(testCase: CphTestCase, status: TabStatus): String {
            return when (status) {
                TabStatus.OK -> "${testCase.name}: OK in ${formatDuration(testCase.lastResult.durationMillis)}"
                TabStatus.AC,
                TabStatus.WA,
                TabStatus.TLE,
                TabStatus.RE -> "${testCase.name}: ${testCase.lastResult.verdict} in ${formatDuration(testCase.lastResult.durationMillis)}"
                TabStatus.ERROR -> CphUiText.errorTooltip(testCase.name, testCase.lastResult)
                TabStatus.RUNNING -> "${testCase.name}: running"
                TabStatus.QUEUED -> "${testCase.name}: queued"
                TabStatus.NOT_RUN -> "${testCase.name}: not run"
            }
        }

        private fun formatDuration(durationMillis: Long): String {
            return CphUiText.formatDuration(durationMillis)
        }

        private fun styleFor(status: TabStatus): TabStyle {
            val color = when (status) {
                TabStatus.OK,
                TabStatus.AC -> GOOD
                TabStatus.WA,
                TabStatus.RE,
                TabStatus.ERROR -> BAD
                TabStatus.TLE -> WARN
                TabStatus.RUNNING -> RUN
                TabStatus.QUEUED,
                TabStatus.NOT_RUN -> MUTED
            }
            val background = when (status) {
                TabStatus.OK,
                TabStatus.AC -> Color(0x18321F)
                TabStatus.WA,
                TabStatus.RE,
                TabStatus.ERROR -> Color(0x321B1F)
                TabStatus.TLE -> Color(0x332816)
                TabStatus.RUNNING -> Color(0x18263B)
                TabStatus.QUEUED,
                TabStatus.NOT_RUN -> SURFACE
            }
            return TabStyle(color, background, color)
        }
    }
}
