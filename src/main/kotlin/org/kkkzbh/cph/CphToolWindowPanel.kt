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
import com.intellij.openapi.util.IconLoader
import com.intellij.openapi.wm.StatusBar
import com.intellij.ide.BrowserUtil
import com.intellij.ui.scale.JBUIScale
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
import java.awt.AlphaComposite
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
import java.awt.Graphics2D
import java.awt.GradientPaint
import java.awt.GraphicsEnvironment
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.awt.KeyboardFocusManager
import java.awt.LinearGradientPaint
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.Shape
import java.awt.TexturePaint
import java.awt.geom.Rectangle2D
import java.awt.image.BufferedImage
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.Icon
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
    private val theme: CphThemePalette
        get() = CphThemes.current()
    private var currentIdentity = CphTargetResolver.current(project)
    private var currentTargetCases = stateService.getOrCreateTargetCases(currentIdentity)
    private var selectedCase: CphTestCase? = null
    private var running = false
    private var settingsVisible = false
    private var activeSettingsTab = SettingsPanelTab.SETTINGS
    private var pendingTargetRefresh = false
    private var applyingTargetSettings = false
    private var applyingShortcutSettings = false
    private var applyingWorkingDirectorySettings = false
    private var tabScrollPaneHeightListenerInstalled = false
    private var activeRunButton: ActiveRunButton? = null
    private var runSpinnerIndex = 0

    private val compileSettingsSynchronizer = CphCompileSettingsSynchronizer(project)
    private val runtimeStates = linkedMapOf<String, RuntimeTabState>()
    private val caseTabComponents = linkedMapOf<String, CaseTab>()

    private val settingsButton = JButton("⚙")
    private val runAllButton = JButton(RUN_ALL_BUTTON_TEXT)
    private val submitButton = JButton("📤")
    private val helpButton = JButton("?")
    private val settingsTabButton = JButton("设置")
    private val utilitySettingsTabButton = JButton("实用", IconLoader.getIcon("/icons/plugin.svg", CphToolWindowPanel::class.java))
    private val themeSettingsTabButton = JButton("主题", IconLoader.getIcon("/icons/cphToolWindow.svg", CphToolWindowPanel::class.java))
    private val codeforcesPluginToggleButton = JButton()
    private val classicThemeToggleButton = JButton()
    private val aveMujicaThemeToggleButton = JButton()
    private val scaledIconCache = mutableMapOf<String, Icon>()
    private val generatedIconPresenceCache = mutableMapOf<String, Boolean>()
    private val aveMujicaStatusGlyphCache = mutableMapOf<String, BufferedImage?>()
    private val iconAnimationTimers = mutableMapOf<JButton, Timer>()
    private var aveMujicaLineHighlightTile: BufferedImage? = null
    private var aveMujicaLineHighlightAnimationStartedNanos = 0L
    private var aveMujicaStatusGlyphAnimationStartedNanos = 0L
    private var aveMujicaLineHighlightFrameOffset = 0.0
    private var aveMujicaStatusGlyphFrameOffset = 0.0
    private val aveMujicaFont: Font? by lazy { loadAveMujicaFont() }
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
    private val settingsContentCards = JPanel(CardLayout())
    private val settingsGrid = JPanel().also { it.layout = BoxLayout(it, BoxLayout.Y_AXIS) }
    private val settingsReturnHintPanel = JPanel(BorderLayout())
    private val outputContainer = JPanel(BorderLayout())
    private val inputArea = JBTextArea()
    private val expectedArea = JBTextArea()
    private val actualArea = JBTextArea()
    private val aveMujicaLineHighlightTimer = Timer(AVE_MUJICA_FLOW_ANIMATION_DELAY_MILLIS) {
        aveMujicaLineHighlightFrameOffset = animationOffset(
            startedNanos = aveMujicaLineHighlightAnimationStartedNanos,
            pixelsPerSecond = AVE_MUJICA_LINE_HIGHLIGHT_PIXELS_PER_SECOND,
        )
        actualArea.repaint()
    }
    private val aveMujicaStatusGlyphTimer = Timer(AVE_MUJICA_FLOW_ANIMATION_DELAY_MILLIS) {
        aveMujicaStatusGlyphFrameOffset = animationOffset(
            startedNanos = aveMujicaStatusGlyphAnimationStartedNanos,
            pixelsPerSecond = AVE_MUJICA_STATUS_GLYPH_PIXELS_PER_SECOND,
        )
        tabStrip.repaint()
    }
    init {
        border = BorderFactory.createEmptyBorder(8, 8, 8, 8)
        background = theme.panel
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
        settingsTabButton.addActionListener { showSettingsTab(SettingsPanelTab.SETTINGS) }
        utilitySettingsTabButton.addActionListener { showSettingsTab(SettingsPanelTab.UTILITY) }
        themeSettingsTabButton.addActionListener { showSettingsTab(SettingsPanelTab.THEMES) }
        codeforcesPluginToggleButton.addActionListener { toggleCodeforcesSubmitPlugin() }
        classicThemeToggleButton.addActionListener { selectPluginTheme(CphThemeId.CLASSIC) }
        aveMujicaThemeToggleButton.addActionListener { selectPluginTheme(CphThemeId.AVE_MUJICA) }
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
        refreshCodeforcesPluginUi()
        refreshSubmitFeatureVisibility()
        refreshSubmitButtonTooltip()
    }

    override fun dispose() {
        stopRunSpinner()
        submitSpinnerTimer.stop()
        hideSubmissionStatusTimer.stop()
        stopAveMujicaLineHighlightAnimation()
        stopAveMujicaStatusGlyphAnimation()
        iconAnimationTimers.values.forEach { it.stop() }
        iconAnimationTimers.clear()
    }

    internal fun triggerShortcut(action: CphShortcutAction) {
        when (action) {
            CphShortcutAction.RUN_ALL -> triggerShortcutAction(runAllButton) { runAllCases() }
            CphShortcutAction.RUN_SELECTED_CASE -> triggerShortcutAction(runSelectedCaseButton) { runSelectedCase() }
            CphShortcutAction.DEBUG_SELECTED_CASE -> triggerShortcutAction(debugSelectedCaseButton) { debugSelectedCase() }
            CphShortcutAction.SUBMIT -> {
                if (isCodeforcesSubmitPluginEnabled()) {
                    triggerShortcutAction(submitButton) {
                        CphSubmitOrchestrator.getInstance(project).submit()
                    }
                }
            }
        }
    }

    private fun triggerShortcutAction(button: JButton, action: () -> Unit) {
        playThemedIconAnimation(button)
        action()
    }

    private fun buildTop(): JComponent {
        val top = JPanel()
        top.layout = BoxLayout(top, BoxLayout.Y_AXIS)
        top.background = theme.panel

        val toolbar = JPanel(BorderLayout())
        toolbar.background = theme.panel
        val leftActions = JPanel(FlowLayout(FlowLayout.LEFT, 8, 4)).also {
            it.background = theme.panel
            it.add(runAllButton)
            it.add(submitButton)
        }
        val rightActions = JPanel(FlowLayout(FlowLayout.RIGHT, 8, 4)).also {
            it.background = theme.panel
            it.add(resetCasesButton)
            it.add(settingsButton)
        }
        toolbar.add(leftActions, BorderLayout.WEST)
        toolbar.add(rightActions, BorderLayout.EAST)
        toolbar.alignmentX = Component.LEFT_ALIGNMENT

        submissionStatusPanel.background = theme.surface
        submissionStatusPanel.border = CompoundBorder(
            MatteBorder(1, 0, 1, 0, theme.border),
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
        configureToolbarIconButton(submitButton, { theme.run })
    }

    private fun configureVerdictLabel() {
        verdictLabel.foreground = theme.muted
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
        if (!isCodeforcesSubmitPluginEnabled()) {
            hideSubmissionStatus()
            setSubmitBusy(false)
            return
        }
        if (status.phase == CphSubmissionPhase.IDLE) {
            verdictPageUrl = null
            hideSubmissionStatus()
            setSubmitBusy(false)
            return
        }
        status.pageUrl?.let { verdictPageUrl = it }
        verdictLabel.text = status.text
        verdictLabel.foreground = when (status.phase) {
            CphSubmissionPhase.IDLE -> theme.muted
            CphSubmissionPhase.SUBMITTING,
            CphSubmissionPhase.QUEUED,
            CphSubmissionPhase.RUNNING -> theme.run
            CphSubmissionPhase.ACCEPTED -> theme.good
            CphSubmissionPhase.REJECTED,
            CphSubmissionPhase.ERROR -> theme.bad
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
        val featureEnabled = isCodeforcesSubmitPluginEnabled()
        submitBusy = busy && featureEnabled
        submitButton.isVisible = featureEnabled
        submitButton.isEnabled = featureEnabled && !submitBusy && stateService.getState().singleFileModeEnabled
        refreshToolbarIconButton(submitButton, theme.run)
        if (submitBusy) {
            if (!submitSpinnerTimer.isRunning) {
                submitSpinnerIndex = 0
                submitSpinnerTimer.start()
            }
            return
        }
        submitSpinnerTimer.stop()
        submitButton.text = "📤"
        refreshToolbarIconButton(submitButton, theme.run)
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
        if (!isCodeforcesSubmitPluginEnabled()) {
            submitButton.toolTipText = null
            return
        }
        val tab = CphActiveTabService.getInstance().current()
        val ctx = tab?.let { CphSubmitContextResolver.resolve(it.url) }
        submitButton.toolTipText = when {
            !stateService.getState().singleFileModeEnabled -> "Enable pure single-file mode before submitting to Codeforces"
            ctx != null -> "Submit current file → ${ctx.displayId}"
            tab != null -> "Active tab is not a Codeforces problem page"
            else -> "No active Codeforces tab — install the CPH Target Runner browser extension"
        }
    }

    private fun refreshSubmitFeatureVisibility() {
        val enabled = isCodeforcesSubmitPluginEnabled()
        submitButton.isVisible = enabled
        if (!enabled) {
            hideSubmissionStatus()
        }
        setSubmitBusy(submitBusy)
        revalidate()
        repaint()
    }

    private fun isCodeforcesSubmitPluginEnabled(): Boolean =
        CphCodeforcesSubmitFeature.isEnabled()

    private fun loadAveMujicaFont(): Font? {
        val stream = CphToolWindowPanel::class.java.getResourceAsStream(AVE_MUJICA_FONT_RESOURCE) ?: return null
        return stream.use {
            Font.createFont(Font.TRUETYPE_FONT, it).also { font ->
                GraphicsEnvironment.getLocalGraphicsEnvironment().registerFont(font)
            }
        }
    }

    private fun decorativeFont(base: Font, style: Int = base.style, sizeDelta: Float = 0.0f): Font {
        val size = base.size2D + sizeDelta
        return if (theme.id == CphThemeId.AVE_MUJICA) {
            aveMujicaFont?.deriveFont(style, size) ?: base.deriveFont(style, size)
        } else {
            base.deriveFont(style, size)
        }
    }

    private fun baseButtonFont(button: JButton): Font {
        (button.getClientProperty(BASE_FONT_PROPERTY) as? Font)?.let { return it }
        return button.font.also { button.putClientProperty(BASE_FONT_PROPERTY, it) }
    }

    private fun actionButtonFont(button: JButton): Font {
        val base = baseButtonFont(button)
        return base.deriveFont(Font.BOLD, base.size2D + 1.0f)
    }

    private fun configureRunAllButton() {
        configureToolbarIconButton(runAllButton, { theme.good })
        runAllButton.model.addChangeListener(ChangeListener { refreshRunActionButtons() })
        refreshToolbarRunAllButton()
    }

    private fun configureResetCasesButton() {
        configureToolbarIconButton(resetCasesButton, { theme.text })
    }

    private fun configureRunSelectedCaseButton() {
        runSelectedCaseButton.isOpaque = true
        runSelectedCaseButton.isContentAreaFilled = true
        runSelectedCaseButton.isBorderPainted = true
        runSelectedCaseButton.isFocusPainted = false
        runSelectedCaseButton.isRolloverEnabled = true
        runSelectedCaseButton.border = EmptyBorder(1, 6, 1, 6)
        runSelectedCaseButton.font = actionButtonFont(runSelectedCaseButton)
        runSelectedCaseButton.model.addChangeListener(ChangeListener { refreshRunActionButtons() })
        installThemedIconAnimation(runSelectedCaseButton)
        refreshRunActionButton(runSelectedCaseButton, theme.good, false)
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
        debugSelectedCaseButton.font = actionButtonFont(debugSelectedCaseButton)
        debugSelectedCaseButton.model.addChangeListener(ChangeListener { refreshRunActionButtons() })
        installThemedIconAnimation(debugSelectedCaseButton)
        refreshRunActionButton(debugSelectedCaseButton, theme.run, false)
    }

    private fun refreshRunActionButtons() {
        refreshToolbarRunAllButton()
        refreshRunActionButton(runSelectedCaseButton, theme.good, activeRunButton == ActiveRunButton.RUN_SELECTED)
        refreshRunActionButton(debugSelectedCaseButton, theme.run, false)
    }

    private fun refreshToolbarRunAllButton() {
        refreshToolbarIconButton(runAllButton, theme.good, activeRunButton == ActiveRunButton.RUN_ALL)
    }

    private fun refreshRunActionButton(button: JButton, baseColor: Color, active: Boolean) {
        val model = button.model
        val pressed = model.isPressed && model.isArmed
        refreshThemedRunActionIcon(button, pressed || active)
        if (button == runSelectedCaseButton || button == debugSelectedCaseButton) {
            button.font = actionButtonFont(button)
        }
        val foreground = when {
            active -> baseColor
            button.isEnabled -> baseColor
            else -> theme.muted
        }
        val background = when {
            active -> theme.actionHover
            !button.isEnabled -> theme.panel
            pressed -> theme.actionPressed
            model.isRollover -> theme.actionHover
            else -> theme.panel
        }
        button.foreground = foreground
        button.background = background
        val highlighted = active || button.isEnabled && (pressed || model.isRollover)
        button.isOpaque = highlighted
        button.isContentAreaFilled = highlighted
        button.border = EmptyBorder(2, 7, 2, 7)
        button.repaint()
    }

    private fun configureSettingsButton() {
        configureToolbarIconButton(settingsButton, { if (settingsVisible) theme.run else theme.text }, fontDelta = 1.0f)
    }

    private fun configureHelpButton() {
        helpButton.foreground = theme.text
        helpButton.background = theme.panel
        helpButton.isOpaque = false
        helpButton.isContentAreaFilled = false
        helpButton.isBorderPainted = false
        helpButton.isFocusPainted = false
        helpButton.isRolloverEnabled = true
        helpButton.horizontalAlignment = SwingConstants.CENTER
        helpButton.horizontalTextPosition = SwingConstants.RIGHT
        helpButton.font = actionButtonFont(helpButton)
        helpButton.model.addChangeListener(ChangeListener { refreshSettingsHelpButton() })
        installThemedIconAnimation(helpButton)
        refreshSettingsHelpButton()
    }

    private fun refreshSettingsHelpButton() {
        val model = helpButton.model
        val pressed = model.isPressed && model.isArmed
        val highlighted = helpButton.isEnabled && (pressed || model.isRollover)
        helpButton.foreground = if (helpButton.isEnabled) theme.text else theme.muted
        helpButton.background = when {
            !helpButton.isEnabled -> theme.panel
            pressed -> theme.actionPressed
            model.isRollover -> theme.actionHover
            else -> theme.panel
        }
        helpButton.border = EmptyBorder(4, 8, 4, 8)
        helpButton.preferredSize = settingsHelpButtonSize()
        helpButton.minimumSize = helpButton.preferredSize
        helpButton.maximumSize = helpButton.preferredSize
        helpButton.isOpaque = highlighted
        helpButton.isContentAreaFilled = highlighted
        helpButton.isBorderPainted = false
        if (theme.id == CphThemeId.AVE_MUJICA) {
            helpButton.putClientProperty(AVE_MUJICA_ICON_NAME_PROPERTY, "help")
            helpButton.putClientProperty(AVE_MUJICA_ICON_SIZE_PROPERTY, settingsHelpIconSize())
            val icon = aveMujicaSettingsHelpIcon(pressed)
            helpButton.text = if (icon == null) "帮助" else null
            helpButton.icon = icon
            helpButton.iconTextGap = 0
            helpButton.font = decorativeFont(baseButtonFont(helpButton), Font.BOLD, 2.0f)
        } else {
            helpButton.putClientProperty(AVE_MUJICA_ICON_NAME_PROPERTY, null)
            helpButton.putClientProperty(AVE_MUJICA_ICON_SIZE_PROPERTY, null)
            helpButton.text = "帮助"
            helpButton.icon = HelpGlyphIcon(if (helpButton.isEnabled) theme.text else theme.muted, settingsClassicHelpIconSize())
            helpButton.iconTextGap = 6
            helpButton.font = actionButtonFont(helpButton)
        }
        helpButton.repaint()
    }

    private fun aveMujicaSettingsHelpIcon(pressed: Boolean): Icon? {
        val icon = themedIcon("help", helpButton, pressed, settingsHelpIconSize()) ?: return null
        val text = scaledAspectResourceIcon(
            AVE_MUJICA_HELP_TEXT_RESOURCE,
            AVE_MUJICA_HELP_TEXT_HEIGHT,
            trimTransparentPadding = true,
        ) ?: return null
        return HorizontalIcon(icon, text, AVE_MUJICA_HELP_ICON_TEXT_GAP)
    }

    private fun settingsHelpButtonSize(): Dimension {
        return if (theme.id == CphThemeId.AVE_MUJICA) {
            Dimension(122, 45)
        } else {
            Dimension(78, 32)
        }
    }

    private fun settingsHelpIconSize(): Int {
        return if (theme.id == CphThemeId.AVE_MUJICA) aveMujicaIconSize(30) else settingsClassicHelpIconSize()
    }

    private fun settingsClassicHelpIconSize(): Int = 18

    private fun configureToolbarIconButton(button: JButton, baseColor: Color, fontDelta: Float = 2.0f) {
        configureToolbarIconButton(button, { baseColor }, fontDelta)
    }

    private fun configureToolbarIconButton(button: JButton, baseColor: () -> Color, fontDelta: Float = 2.0f) {
        button.foreground = baseColor()
        button.background = theme.panel
        button.isOpaque = false
        button.isContentAreaFilled = false
        button.isBorderPainted = false
        button.isFocusPainted = false
        button.isRolloverEnabled = true
        button.border = EmptyBorder(2, 6, 2, 6)
        button.preferredSize = toolbarButtonSize()
        button.minimumSize = button.preferredSize
        button.maximumSize = button.preferredSize
        button.font = button.font.deriveFont(Font.BOLD, button.font.size2D + fontDelta)
        button.model.addChangeListener(ChangeListener { refreshToolbarIconButton(button, baseColor()) })
        installThemedIconAnimation(button)
        refreshToolbarIconButton(button, baseColor())
    }

    private fun refreshToolbarIconButton(button: JButton, baseColor: Color, active: Boolean = false) {
        val model = button.model
        val pressed = model.isPressed && model.isArmed
        val highlighted = active || button.isEnabled && (pressed || model.isRollover)
        refreshThemedToolbarIcon(button, pressed || active)
        button.foreground = if (button.isEnabled || active) baseColor else theme.muted
        button.background = when {
            active -> theme.actionHover
            !button.isEnabled -> theme.panel
            pressed -> theme.actionPressed
            model.isRollover -> theme.actionHover
            else -> theme.panel
        }
        button.isOpaque = highlighted
        button.isContentAreaFilled = highlighted
        button.border = EmptyBorder(2, 6, 2, 6)
        button.repaint()
    }

    private fun refreshThemedToolbarIcon(button: JButton, pressed: Boolean) {
        when (button) {
            runAllButton -> setThemeButtonFace(button, "run_all", pressed, RUN_ALL_BUTTON_TEXT, null, toolbarIconSize())
            submitButton -> setThemeButtonFace(button, "upload", pressed, "📤", null, toolbarIconSize())
            resetCasesButton -> setThemeButtonFace(button, "reset_rerun", pressed, "↺", null, toolbarIconSize())
            settingsButton -> setThemeButtonFace(button, "settings", pressed || settingsVisible, "⚙", null, toolbarIconSize())
        }
    }

    private fun refreshThemedRunActionIcon(button: JButton, pressed: Boolean) {
        when (button) {
            runSelectedCaseButton -> setThemeButtonFace(button, "run_selected", pressed, RUN_SELECTED_BUTTON_TEXT, null, runActionIconSize())
            debugSelectedCaseButton -> setThemeButtonFace(button, "debug", pressed, "Debug", AllIcons.Actions.StartDebugger, runActionIconSize())
        }
    }

    private fun toolbarButtonSize(): Dimension {
        return if (theme.id == CphThemeId.AVE_MUJICA) Dimension(55, 45) else Dimension(34, 28)
    }

    private fun toolbarIconSize(): Int {
        return if (theme.id == CphThemeId.AVE_MUJICA) aveMujicaIconSize(36) else 28
    }

    private fun runActionIconSize(): Int {
        return if (theme.id == CphThemeId.AVE_MUJICA) aveMujicaIconSize(34) else 30
    }

    private fun aveMujicaIconSize(baseSize: Int): Int {
        return baseSize
    }

    private fun setThemeButtonFace(
        button: JButton,
        iconName: String,
        pressed: Boolean,
        fallbackText: String?,
        fallbackIcon: Icon?,
        iconSize: Int,
    ) {
        button.putClientProperty(AVE_MUJICA_ICON_NAME_PROPERTY, iconName)
        button.putClientProperty(AVE_MUJICA_ICON_SIZE_PROPERTY, iconSize)
        if (theme.id != CphThemeId.AVE_MUJICA) {
            val preserveDynamicText =
                button == runAllButton && activeRunButton == ActiveRunButton.RUN_ALL ||
                    button == runSelectedCaseButton && activeRunButton == ActiveRunButton.RUN_SELECTED ||
                    button == submitButton && submitBusy
            if (!preserveDynamicText) {
                button.text = fallbackText
            }
            button.icon = fallbackIcon
            button.iconTextGap = if (fallbackText != null && fallbackIcon != null) 4 else 0
            return
        }
        button.text = when (button) {
            runSelectedCaseButton -> "Run"
            debugSelectedCaseButton -> "Debug"
            else -> null
        }
        button.icon = themedIcon(iconName, button, pressed, iconSize)
        button.iconTextGap = if (button.text.isNullOrBlank()) 0 else 6
    }

    private fun themedIcon(iconName: String, button: JButton, pressed: Boolean, size: Int): Icon? {
        val frame = button.getClientProperty(AVE_MUJICA_ICON_FRAME_PROPERTY) as? Int
        if (frame != null) {
            themedAnimationIcon(iconName, frame, size)?.let { return it }
        }
        val mousePressed = button.model.isPressed && button.model.isArmed
        val suppressPressedState = mousePressed && hasThemedAnimation(iconName)
        val state = when {
            pressed && !suppressPressedState -> "pressed_static"
            button.model.isRollover -> "hover"
            else -> "normal"
        }
        return themedStateIcon(iconName, state, size)
    }

    private fun hasThemedAnimation(iconName: String): Boolean {
        val key = "animation:$iconName"
        generatedIconPresenceCache[key]?.let { return it }
        val hasAnimation = CphToolWindowPanel::class.java.getResource(
            "/icons/avemujica/generated/512/$iconName/pressed_01.png",
        ) != null
        generatedIconPresenceCache[key] = hasAnimation
        return hasAnimation
    }

    private fun themedStateIcon(iconName: String, state: String, size: Int): Icon? {
        val generatedPath = "/icons/avemujica/generated/512/$iconName/$state.png"
        val legacyAnimatedState = if (state == "pressed_static") "pressed" else "idle"
        val legacyAnimatedPath = "/icons/avemujica/animated/512/${iconName}_$legacyAnimatedState.png"
        val legacyStandalonePath = "/icons/avemujica/standalone/512/$iconName.png"
        return scaledResourceIcon(generatedPath, size, trimTransparentPadding = true)
            ?: scaledResourceIcon(legacyAnimatedPath, size)
            ?: scaledResourceIcon(legacyStandalonePath, size)
    }

    private fun themedAnimationIcon(iconName: String, frame: Int, size: Int): Icon? {
        val framePath = "/icons/avemujica/generated/512/$iconName/pressed_${frame.toString().padStart(2, '0')}.png"
        return scaledResourceIcon(framePath, size, trimTransparentPadding = true)
    }

    private fun installThemedIconAnimation(button: JButton) {
        if (button.getClientProperty(AVE_MUJICA_ANIMATION_CONFIGURED_PROPERTY) == true) {
            return
        }
        button.addActionListener {
            playThemedIconAnimation(button)
        }
        button.putClientProperty(AVE_MUJICA_ANIMATION_CONFIGURED_PROPERTY, true)
    }

    private fun playThemedIconAnimation(button: JButton) {
        if (theme.id != CphThemeId.AVE_MUJICA) return
        val iconName = button.getClientProperty(AVE_MUJICA_ICON_NAME_PROPERTY) as? String ?: return
        val iconSize = button.getClientProperty(AVE_MUJICA_ICON_SIZE_PROPERTY) as? Int ?: return
        if (themedAnimationIcon(iconName, 1, iconSize) == null) return
        iconAnimationTimers.remove(button)?.stop()
        button.putClientProperty(AVE_MUJICA_ICON_FRAME_PROPERTY, 1)
        if (button == helpButton) {
            refreshSettingsHelpButton()
        } else {
            button.icon = themedAnimationIcon(iconName, 1, iconSize)
        }
        button.repaint()

        val timer = Timer(AVE_MUJICA_ANIMATION_DELAY_MILLIS, null)
        timer.addActionListener {
            val frame = (button.getClientProperty(AVE_MUJICA_ICON_FRAME_PROPERTY) as? Int ?: 1) + 1
            if (frame > AVE_MUJICA_ANIMATION_FRAME_COUNT) {
                timer.stop()
                iconAnimationTimers.remove(button)
                button.putClientProperty(AVE_MUJICA_ICON_FRAME_PROPERTY, null)
                refreshButtonAfterThemedAnimation(button)
            } else {
                button.putClientProperty(AVE_MUJICA_ICON_FRAME_PROPERTY, frame)
                if (button == helpButton) {
                    refreshSettingsHelpButton()
                } else {
                    button.icon = themedAnimationIcon(iconName, frame, iconSize)
                        ?: themedStateIcon(iconName, "pressed_static", iconSize)
                }
                button.repaint()
            }
        }
        iconAnimationTimers[button] = timer
        timer.start()
    }

    private fun refreshButtonAfterThemedAnimation(button: JButton) {
        when (button) {
            runAllButton -> refreshToolbarRunAllButton()
            submitButton -> refreshToolbarIconButton(submitButton, theme.run)
            resetCasesButton -> refreshToolbarIconButton(resetCasesButton, theme.text)
            settingsButton -> refreshToolbarIconButton(settingsButton, if (settingsVisible) theme.run else theme.text)
            helpButton -> refreshSettingsHelpButton()
            runSelectedCaseButton -> refreshRunActionButton(
                runSelectedCaseButton,
                theme.good,
                activeRunButton == ActiveRunButton.RUN_SELECTED,
            )
            debugSelectedCaseButton -> refreshRunActionButton(debugSelectedCaseButton, theme.run, false)
            else -> refreshGeneratedIconOnly(button)
        }
    }

    private fun refreshGeneratedIconOnly(button: JButton, pressed: Boolean = button.model.isPressed && button.model.isArmed) {
        if (theme.id != CphThemeId.AVE_MUJICA) return
        val iconName = button.getClientProperty(AVE_MUJICA_ICON_NAME_PROPERTY) as? String ?: return
        val iconSize = button.getClientProperty(AVE_MUJICA_ICON_SIZE_PROPERTY) as? Int ?: return
        button.icon = themedIcon(iconName, button, pressed, iconSize)
        button.repaint()
    }

    private fun scaledResourceIcon(path: String, size: Int, trimTransparentPadding: Boolean = false): Icon? {
        val key = "$path@$size@painted@trim=$trimTransparentPadding"
        scaledIconCache[key]?.let { return it }
        val url = CphToolWindowPanel::class.java.getResource(path) ?: return null
        val source = ImageIO.read(url) ?: return null
        val icon = HighQualityPngIcon(source, size, if (trimTransparentPadding) visibleIconSourceRect(source) else null)
        scaledIconCache[key] = icon
        return icon
    }

    private fun scaledAspectResourceIcon(path: String, height: Int, trimTransparentPadding: Boolean = false): Icon? {
        val key = "$path@h=$height@painted@trim=$trimTransparentPadding"
        scaledIconCache[key]?.let { return it }
        val url = CphToolWindowPanel::class.java.getResource(path) ?: return null
        val source = ImageIO.read(url) ?: return null
        val sourceRect = if (trimTransparentPadding) visibleContentSourceRect(source) else null
        val width = ((sourceRect?.width ?: source.width).toDouble() / (sourceRect?.height ?: source.height) * height)
            .roundToInt()
            .coerceAtLeast(1)
        val icon = HighQualityPngIcon(source, width, height, sourceRect)
        scaledIconCache[key] = icon
        return icon
    }

    private fun visibleIconSourceRect(image: BufferedImage): Rectangle? {
        var minX = image.width
        var minY = image.height
        var maxX = -1
        var maxY = -1
        for (y in 0 until image.height) {
            for (x in 0 until image.width) {
                val alpha = image.getRGB(x, y) ushr 24
                if (alpha > AVE_MUJICA_VISIBLE_ALPHA_THRESHOLD) {
                    minX = minOf(minX, x)
                    minY = minOf(minY, y)
                    maxX = maxOf(maxX, x)
                    maxY = maxOf(maxY, y)
                }
            }
        }
        if (maxX < minX || maxY < minY) return null

        val visibleWidth = maxX - minX + 1
        val visibleHeight = maxY - minY + 1
        val paddedSide = (maxOf(visibleWidth, visibleHeight) * AVE_MUJICA_ICON_TRIM_PADDING_SCALE)
            .roundToInt()
            .coerceAtLeast(maxOf(visibleWidth, visibleHeight))
            .coerceAtMost(minOf(image.width, image.height))
        val centerX = (minX + maxX) / 2
        val centerY = (minY + maxY) / 2
        val x = (centerX - paddedSide / 2).coerceIn(0, image.width - paddedSide)
        val y = (centerY - paddedSide / 2).coerceIn(0, image.height - paddedSide)
        return Rectangle(x, y, paddedSide, paddedSide)
    }

    private fun visibleContentSourceRect(image: BufferedImage): Rectangle? {
        var minX = image.width
        var minY = image.height
        var maxX = -1
        var maxY = -1
        for (y in 0 until image.height) {
            for (x in 0 until image.width) {
                val alpha = image.getRGB(x, y) ushr 24
                if (alpha > AVE_MUJICA_VISIBLE_ALPHA_THRESHOLD) {
                    minX = minOf(minX, x)
                    minY = minOf(minY, y)
                    maxX = maxOf(maxX, x)
                    maxY = maxOf(maxY, y)
                }
            }
        }
        if (maxX < minX || maxY < minY) return null
        return Rectangle(minX, minY, maxX - minX + 1, maxY - minY + 1)
    }

    private fun configureWorkingDirectoryChooserButton() {
        singleFileWorkingDirectoryChooserButton.foreground = theme.text
        singleFileWorkingDirectoryChooserButton.background = theme.surface
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
        contentCards.background = theme.panel
        contentCards.add(buildMainView(), MAIN_VIEW_CARD)
        contentCards.add(buildSettingsView(), SETTINGS_VIEW_CARD)
        showActiveView()
        return contentCards
    }

    private fun buildMainView(): JComponent {
        tabStrip.layout = BoxLayout(tabStrip, BoxLayout.X_AXIS)
        tabStrip.background = theme.panel
        tabScrollPane.border = MatteBorder(1, 0, 1, 0, theme.border)
        tabScrollPane.verticalScrollBarPolicy = JScrollPane.VERTICAL_SCROLLBAR_NEVER
        tabScrollPane.horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED
        tabScrollPane.preferredSize = Dimension(0, tabStripBaseHeight())
        tabScrollPane.minimumSize = Dimension(0, tabStripBaseHeight())
        tabScrollPane.viewport.background = theme.panel
        installTabScrollPaneHeightListener()

        return JPanel(BorderLayout()).also {
            it.background = theme.panel
            it.add(tabScrollPane, BorderLayout.NORTH)
            it.add(buildBody(), BorderLayout.CENTER)
        }
    }

    private fun installTabScrollPaneHeightListener() {
        if (tabScrollPaneHeightListenerInstalled) return
        tabScrollPaneHeightListenerInstalled = true
        tabScrollPane.horizontalScrollBar.addComponentListener(object : ComponentAdapter() {
            override fun componentShown(e: ComponentEvent) = adjustTabScrollPaneHeight(true)
            override fun componentHidden(e: ComponentEvent) = adjustTabScrollPaneHeight(false)
        })
    }

    private fun syncTabScrollPaneHeight() {
        adjustTabScrollPaneHeight(tabScrollPane.horizontalScrollBar.isVisible)
    }

    private fun adjustTabScrollPaneHeight(scrollBarVisible: Boolean) {
        val extra = if (scrollBarVisible) tabScrollPane.horizontalScrollBar.preferredSize.height else 0
        val newHeight = tabStripBaseHeight() + extra
        if (tabScrollPane.preferredSize.height == newHeight) return
        tabScrollPane.preferredSize = Dimension(0, newHeight)
        tabScrollPane.minimumSize = Dimension(0, newHeight)
        tabScrollPane.parent?.revalidate()
        tabScrollPane.parent?.repaint()
    }

    private fun tabStripBaseHeight(): Int {
        return if (theme.id == CphThemeId.AVE_MUJICA) AVE_MUJICA_TAB_STRIP_BASE_HEIGHT else TAB_STRIP_BASE_HEIGHT
    }

    private fun buildSettingsView(): JComponent {
        settingsContentCards.background = theme.panel
        settingsContentCards.add(buildSettingsFormView(), SettingsPanelTab.SETTINGS.cardName)
        settingsContentCards.add(buildPluginUtilityView(), SettingsPanelTab.UTILITY.cardName)
        settingsContentCards.add(buildPluginThemesView(), SettingsPanelTab.THEMES.cardName)
        showCurrentSettingsTabCard()
        val tabStrip = buildSettingsTabStrip()
        refreshSettingsTabButtons()
        return JPanel(BorderLayout()).also {
            it.background = theme.panel
            it.add(tabStrip, BorderLayout.NORTH)
            it.add(settingsContentCards, BorderLayout.CENTER)
        }
    }

    private fun buildSettingsFormView(): JComponent {
        singleFileModeEnabled.isOpaque = false
        ignoreTrailingWhitespace.isOpaque = false
        outputSplitEnabled.isOpaque = false
        noExpectedModeEnabled.isOpaque = false
        confidentSubmitEnabled.isOpaque = false
        cppStandardCombo.background = theme.surface
        compileOptionsField.background = theme.editor
        compileOptionsField.foreground = theme.text
        compileOptionsField.caretColor = theme.text
        compileOptionsField.emptyText.text = "-O2 -Wall"
        singleFileWorkingDirectoryField.background = theme.editor
        singleFileWorkingDirectoryField.foreground = theme.text
        singleFileWorkingDirectoryField.caretColor = theme.text
        singleFileWorkingDirectoryField.emptyText.text = CPH_DEFAULT_SINGLE_FILE_WORKING_DIRECTORY
        settingsGrid.isFocusable = true
        settingsGrid.background = theme.panel
        settingsGrid.border = EmptyBorder(10, 0, 0, 0)
        rebuildSettingsGrid()

        val viewport = JPanel(BorderLayout())
        viewport.isFocusable = true
        viewport.background = theme.panel
        viewport.border = EmptyBorder(0, 0, 0, 0)
        viewport.add(settingsGrid, BorderLayout.NORTH)
        installSettingsFocusReset(viewport)

        return JBScrollPane(viewport).also {
            it.border = MatteBorder(1, 0, 0, 0, theme.border)
            it.viewport.background = theme.panel
            it.horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
        }
    }

    private fun rebuildSettingsGrid() {
        settingsGrid.removeAll()
        settingsGrid.add(settingsSection("运行设置", buildSettingsHelpHeaderComponent()) {
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
        if (isCodeforcesSubmitPluginEnabled()) {
            settingsGrid.add(Box.createVerticalStrut(8))
            settingsGrid.add(settingsSection("提交设置") {
                settingCheckBoxRow(confidentSubmitEnabled)
            })
        }
        settingsGrid.add(Box.createVerticalStrut(8))
        settingsGrid.add(settingsSection("快捷键") {
            settingRow("全局运行快捷键：", runAllShortcutField)
            settingRow("单CASE运行快捷键：", runSelectedCaseShortcutField)
            settingRow("单CASE调试快捷键：", debugSelectedCaseShortcutField)
            if (isCodeforcesSubmitPluginEnabled()) {
                settingRow("提交CF快捷键：", submitShortcutField)
            }
        })
        settingsGrid.revalidate()
        settingsGrid.repaint()
    }

    private fun buildSettingsHelpHeaderComponent(): JComponent {
        refreshSettingsHelpButton()
        return JPanel(FlowLayout(FlowLayout.RIGHT, 0, 0)).also {
            it.background = theme.panel
            it.isOpaque = false
            it.add(helpButton)
        }
    }

    private fun buildSettingsTabStrip(): JComponent {
        configureSettingsTabButton(settingsTabButton)
        configureSettingsTabButton(utilitySettingsTabButton)
        configureSettingsTabButton(themeSettingsTabButton)

        return JPanel(FlowLayout(FlowLayout.LEFT, 8, 6)).also {
            it.background = theme.panel
            it.border = MatteBorder(1, 0, 1, 0, theme.border)
            it.add(settingsTabButton)
            it.add(utilitySettingsTabButton)
            it.add(themeSettingsTabButton)
        }
    }

    private fun configureSettingsTabButton(button: JButton) {
        button.iconTextGap = 6
        button.isOpaque = true
        button.isContentAreaFilled = true
        button.isBorderPainted = true
        button.isFocusPainted = false
        button.isRolloverEnabled = true
        button.border = EmptyBorder(4, 10, 4, 10)
        button.font = button.font.deriveFont(Font.BOLD)
        if (button.getClientProperty(SETTINGS_TAB_CONFIGURED_PROPERTY) != true) {
            button.model.addChangeListener(ChangeListener { refreshSettingsTabButtons() })
            button.putClientProperty(SETTINGS_TAB_CONFIGURED_PROPERTY, true)
        }
    }

    private fun buildPluginUtilityView(): JComponent {
        val list = JPanel().also {
            it.layout = BoxLayout(it, BoxLayout.Y_AXIS)
            it.background = theme.panel
            it.border = EmptyBorder(10, 0, 0, 0)
            it.add(buildCodeforcesSubmitPluginRow())
            it.add(Box.createVerticalGlue())
        }
        val viewport = JPanel(BorderLayout()).also {
            it.background = theme.panel
            it.border = EmptyBorder(0, 0, 0, 0)
            it.add(list, BorderLayout.NORTH)
        }
        return JBScrollPane(viewport).also {
            it.border = MatteBorder(1, 0, 0, 0, theme.border)
            it.viewport.background = theme.panel
            it.horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
        }
    }

    private fun buildPluginThemesView(): JComponent {
        val list = JPanel().also {
            it.layout = BoxLayout(it, BoxLayout.Y_AXIS)
            it.background = theme.panel
            it.border = EmptyBorder(10, 0, 0, 0)
            it.add(buildClassicThemeRow())
            it.add(Box.createVerticalStrut(8))
            it.add(buildAveMujicaThemeRow())
            it.add(Box.createVerticalGlue())
        }
        val viewport = JPanel(BorderLayout()).also {
            it.background = theme.panel
            it.border = EmptyBorder(0, 0, 0, 0)
            it.add(list, BorderLayout.NORTH)
        }
        return JBScrollPane(viewport).also {
            it.border = MatteBorder(1, 0, 0, 0, theme.border)
            it.viewport.background = theme.panel
            it.horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
        }
    }

    private fun buildCodeforcesSubmitPluginRow(): JComponent {
        val icon = JBLabel(IconLoader.getIcon("/icons/codeforces.svg", CphToolWindowPanel::class.java)).also {
            it.horizontalAlignment = SwingConstants.CENTER
            it.preferredSize = Dimension(70, 64)
            it.minimumSize = it.preferredSize
            it.maximumSize = it.preferredSize
        }
        val title = JBLabel("Codeforces 远程提交").also {
            it.foreground = theme.text
            it.font = it.font.deriveFont(Font.BOLD)
            it.alignmentX = Component.LEFT_ALIGNMENT
        }
        val summary = JBTextArea("使用浏览器当前 Codeforces 题面和登录态提交当前 C++ 文件。").also {
            it.foreground = theme.muted
            it.background = theme.surface
            it.isOpaque = false
            it.isEditable = false
            it.lineWrap = true
            it.wrapStyleWord = true
            it.border = EmptyBorder(0, 0, 0, 0)
            it.alignmentX = Component.LEFT_ALIGNMENT
        }
        val text = JPanel().also {
            it.layout = BoxLayout(it, BoxLayout.Y_AXIS)
            it.background = theme.surface
            it.alignmentX = Component.LEFT_ALIGNMENT
            it.add(title)
            it.add(Box.createVerticalStrut(4))
            it.add(summary)
        }
        val left = JPanel(BorderLayout(12, 0)).also {
            it.background = theme.surface
            it.add(icon, BorderLayout.WEST)
            it.add(text, BorderLayout.CENTER)
        }
        val right = JPanel(GridBagLayout()).also {
            it.background = theme.surface
            it.preferredSize = Dimension(88, 42)
            it.minimumSize = it.preferredSize
            it.add(codeforcesPluginToggleButton)
        }
        return JPanel(BorderLayout(12, 0)).also {
            it.background = theme.surface
            it.border = CompoundBorder(
                MatteBorder(1, 1, 1, 1, theme.border),
                EmptyBorder(12, 12, 12, 12),
            )
            it.alignmentX = Component.LEFT_ALIGNMENT
            it.add(left, BorderLayout.CENTER)
            it.add(right, BorderLayout.EAST)
            val preferred = it.preferredSize
            it.maximumSize = Dimension(Int.MAX_VALUE, preferred.height)
            refreshCodeforcesPluginUi()
        }
    }

    private fun toggleCodeforcesSubmitPlugin() {
        val state = CphPluginSettings.getInstance().state
        state.codeforcesRemoteSubmitEnabled = !state.codeforcesRemoteSubmitEnabled
        refreshCodeforcesPluginUi()
        rebuildSettingsGrid()
        refreshSubmitFeatureVisibility()
        refreshSubmitButtonTooltip()
        updateActions()
    }

    private fun refreshCodeforcesPluginUi() {
        val enabled = isCodeforcesSubmitPluginEnabled()
        codeforcesPluginToggleButton.text = if (enabled) "禁用" else "启用"
        codeforcesPluginToggleButton.toolTipText = if (enabled) {
            "Disable Codeforces remote submit"
        } else {
            "Enable Codeforces remote submit"
        }
        codeforcesPluginToggleButton.foreground = if (enabled) theme.bad else theme.good
        codeforcesPluginToggleButton.background = theme.surface
        codeforcesPluginToggleButton.isOpaque = false
        codeforcesPluginToggleButton.isContentAreaFilled = false
        codeforcesPluginToggleButton.isBorderPainted = true
        codeforcesPluginToggleButton.isFocusPainted = false
    }

    private fun buildClassicThemeRow(): JComponent {
        return buildThemeRow(
            palette = CphThemes.classic,
            summary = "沿用当前 CPH 深色界面与状态配色。",
            icon = IconLoader.getIcon("/icons/cphToolWindow.svg", CphToolWindowPanel::class.java),
            toggleButton = classicThemeToggleButton,
        )
    }

    private fun buildAveMujicaThemeRow(): JComponent {
        return buildThemeRow(
            palette = CphThemes.aveMujica,
            summary = "Ave Mujica 暗色面板、金色操作高亮与角色功能图标。",
            icon = scaledResourceIcon("/icons/avemujica/generated/512/theme/normal.png", aveMujicaIconSize(46))
                ?: IconLoader.getIcon("/icons/cphToolWindow.svg", CphToolWindowPanel::class.java),
            titleIcon = scaledAspectResourceIcon(
                AVE_MUJICA_THEME_TITLE_RESOURCE,
                AVE_MUJICA_THEME_TITLE_HEIGHT,
                trimTransparentPadding = true,
            ),
            toggleButton = aveMujicaThemeToggleButton,
        )
    }

    private fun buildThemeRow(
        palette: CphThemePalette,
        summary: String,
        icon: Icon,
        titleIcon: Icon? = null,
        toggleButton: JButton,
    ): JComponent {
        val iconLabel = JBLabel(icon).also {
            it.horizontalAlignment = SwingConstants.CENTER
            it.preferredSize = Dimension(70, 64)
            it.minimumSize = it.preferredSize
            it.maximumSize = it.preferredSize
        }
        val title = JBLabel(palette.displayName).also {
            if (titleIcon != null) {
                it.text = null
                it.icon = titleIcon
            }
            it.foreground = theme.text
            it.font = if (palette.id == CphThemeId.AVE_MUJICA && titleIcon == null) {
                decorativeFont(it.font, Font.BOLD, 3.0f)
            } else {
                it.font.deriveFont(Font.BOLD)
            }
            it.alignmentX = Component.LEFT_ALIGNMENT
        }
        val summaryText = JBTextArea(summary).also {
            it.foreground = theme.muted
            it.background = theme.surface
            it.isOpaque = false
            it.isEditable = false
            it.lineWrap = true
            it.wrapStyleWord = true
            it.border = EmptyBorder(0, 0, 0, 0)
            it.alignmentX = Component.LEFT_ALIGNMENT
        }
        val text = JPanel().also {
            it.layout = BoxLayout(it, BoxLayout.Y_AXIS)
            it.background = theme.surface
            it.alignmentX = Component.LEFT_ALIGNMENT
            it.add(title)
            it.add(Box.createVerticalStrut(4))
            it.add(summaryText)
        }
        val left = JPanel(BorderLayout(12, 0)).also {
            it.background = theme.surface
            it.add(iconLabel, BorderLayout.WEST)
            it.add(text, BorderLayout.CENTER)
        }
        val right = JPanel(GridBagLayout()).also {
            it.background = theme.surface
            it.preferredSize = Dimension(88, 42)
            it.minimumSize = it.preferredSize
            it.add(toggleButton)
        }
        return JPanel(BorderLayout(12, 0)).also {
            it.background = theme.surface
            it.border = CompoundBorder(
                MatteBorder(1, 1, 1, 1, theme.border),
                EmptyBorder(12, 12, 12, 12),
            )
            it.alignmentX = Component.LEFT_ALIGNMENT
            it.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            it.add(left, BorderLayout.CENTER)
            it.add(right, BorderLayout.EAST)
            it.addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    selectPluginTheme(palette.id)
                }
            })
            val preferred = it.preferredSize
            it.maximumSize = Dimension(Int.MAX_VALUE, preferred.height)
            refreshThemePluginUi()
        }
    }

    private fun selectPluginTheme(themeId: String) {
        if (running) return
        val normalizedThemeId = CphThemeId.normalize(themeId)
        val state = CphPluginSettings.getInstance().state
        if (state.selectedThemeId == normalizedThemeId) {
            refreshThemePluginUi()
            return
        }
        state.selectedThemeId = normalizedThemeId
        rebuildThemedLayout()
    }

    private fun refreshThemePluginUi() {
        val selectedThemeId = CphThemeId.normalize(CphPluginSettings.getInstance().state.selectedThemeId)
        val classicSelected = selectedThemeId == CphThemeId.CLASSIC
        val aveMujicaSelected = selectedThemeId == CphThemeId.AVE_MUJICA
        refreshThemeToggleButton(
            classicThemeToggleButton,
            classicSelected,
            "Default theme is enabled",
            "Enable default theme",
        )
        refreshThemeToggleButton(
            aveMujicaThemeToggleButton,
            aveMujicaSelected,
            "Ave Mujica theme is enabled",
            "Enable Ave Mujica theme",
        )
    }

    private fun refreshThemeToggleButton(
        button: JButton,
        selected: Boolean,
        selectedTooltip: String,
        availableTooltip: String,
    ) {
        button.text = if (selected) "已启用" else "启用"
        button.toolTipText = if (selected) selectedTooltip else availableTooltip
        button.foreground = if (selected) theme.run else theme.good
        button.background = theme.surface
        button.isOpaque = false
        button.isContentAreaFilled = false
        button.isBorderPainted = true
        button.isFocusPainted = false
        button.isEnabled = !running && !selected
    }

    private fun rebuildThemedLayout() {
        val selectedCaseId = selectedCase?.id
        stopAveMujicaLineHighlightAnimation()
        stopAveMujicaStatusGlyphAnimation()
        background = theme.panel
        listOf(inputArea, expectedArea, actualArea).forEach(::configureEditor)
        contentCards.removeAll()
        settingsContentCards.removeAll()
        tabStrip.removeAll()
        removeAll()
        add(buildTop(), BorderLayout.NORTH)
        add(buildCenter(), BorderLayout.CENTER)
        refreshTabs()
        selectCase(currentTargetCases.cases.firstOrNull { it.id == selectedCaseId } ?: currentTargetCases.cases.firstOrNull())
        refreshCodeforcesPluginUi()
        refreshSubmitFeatureVisibility()
        refreshSubmitButtonTooltip()
        showActiveView()
        updateActions()
        revalidate()
        repaint()
    }

    private fun buildSettingsReturnHint(): JComponent {
        settingsReturnHintPanel.removeAll()
        settingsReturnHintPanel.background = theme.surface
        settingsReturnHintPanel.border = CompoundBorder(
            MatteBorder(1, 1, 1, 1, theme.border),
            EmptyBorder(8, 10, 8, 10),
        )
        settingsReturnHintPanel.alignmentX = Component.LEFT_ALIGNMENT
        settingsReturnHintPanel.isVisible = false
        settingsReturnHintPanel.add(
            JBLabel("提示：再次点击设置按钮可回到主界面").also {
                it.foreground = theme.text
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
            activeSettingsTab = SettingsPanelTab.SETTINGS
            showCurrentSettingsTabCard()
            showSettingsReturnHintOnce()
        } else {
            settingsReturnHintPanel.isVisible = false
        }
        showActiveView()
    }

    private fun showSettingsTab(tab: SettingsPanelTab) {
        activeSettingsTab = tab
        showCurrentSettingsTabCard()
        refreshSettingsTabButtons()
        settingsContentCards.revalidate()
        settingsContentCards.repaint()
    }

    private fun showCurrentSettingsTabCard() {
        (settingsContentCards.layout as? CardLayout)?.show(settingsContentCards, activeSettingsTab.cardName)
    }

    private fun refreshSettingsTabButtons() {
        refreshSettingsTabButton(settingsTabButton, activeSettingsTab == SettingsPanelTab.SETTINGS)
        refreshSettingsTabButton(utilitySettingsTabButton, activeSettingsTab == SettingsPanelTab.UTILITY)
        refreshSettingsTabButton(themeSettingsTabButton, activeSettingsTab == SettingsPanelTab.THEMES)
    }

    private fun refreshSettingsTabButton(button: JButton, selected: Boolean) {
        val model = button.model
        val pressed = model.isPressed && model.isArmed
        val enabled = !running
        if (button.isEnabled != enabled) {
            button.isEnabled = enabled
        }
        val highlighted = selected || button.isEnabled && (pressed || model.isRollover)
        button.foreground = when {
            selected -> theme.run
            button.isEnabled -> theme.text
            else -> theme.muted
        }
        button.background = when {
            selected -> theme.actionHover
            !button.isEnabled -> theme.panel
            pressed -> theme.actionPressed
            model.isRollover -> theme.actionHover
            else -> theme.panel
        }
        button.border = CompoundBorder(
            MatteBorder(1, 1, 1, 1, if (selected) theme.run else theme.border),
            EmptyBorder(3, 9, 3, 9),
        )
        button.isOpaque = highlighted
        button.isContentAreaFilled = highlighted
        button.repaint()
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
        val card = when {
            settingsVisible -> SETTINGS_VIEW_CARD
            else -> MAIN_VIEW_CARD
        }
        (contentCards.layout as? CardLayout)?.show(contentCards, card)
        settingsButton.toolTipText = if (settingsVisible) "Hide settings" else "Settings"
        refreshToolbarIconButton(settingsButton, if (settingsVisible) theme.run else theme.text)
        refreshSettingsHelpButton()
        refreshSettingsTabButtons()
        refreshThemePluginUi()
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
        val duplicateMessage = CphShortcutMatcher.duplicateShortcutMessage(
            nextState,
            codeforcesSubmitEnabled = isCodeforcesSubmitPluginEnabled(),
        )
        if (duplicateMessage != null) {
            StatusBar.Info.set("CPH shortcut settings: $duplicateMessage", project)
            return
        }
        CphShortcutSettings.getInstance().update(nextState)
    }

    private fun settingsSection(
        title: String,
        headerRight: JComponent? = null,
        content: JPanel.() -> Unit,
    ): JPanel {
        val body = JPanel(GridBagLayout()).also {
            it.background = theme.surface
            it.content()
        }
        val header = JPanel(BorderLayout(8, 0)).also {
            it.background = theme.surface
            it.add(JBLabel(title).also { label -> label.foreground = theme.text }, BorderLayout.WEST)
            headerRight?.let { right ->
                right.background = theme.surface
                it.add(right, BorderLayout.EAST)
            }
        }
        return JPanel(BorderLayout(0, 8)).also {
            it.background = theme.surface
            it.border = CompoundBorder(MatteBorder(1, 1, 1, 1, theme.border), EmptyBorder(10, 12, 10, 12))
            it.alignmentX = Component.LEFT_ALIGNMENT
            it.add(header, BorderLayout.NORTH)
            it.add(body, BorderLayout.CENTER)
            val preferred = it.preferredSize
            it.maximumSize = Dimension(Int.MAX_VALUE, preferred.height)
        }
    }

    private fun JPanel.settingRow(label: String, component: JComponent) {
        val row = nextSettingRow()
        add(JBLabel(label).also { it.foreground = theme.text }, settingConstraints(row, 0, weightx = 0.0))
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
            it.background = theme.surface
            it.isOpaque = false
            it.add(singleFileWorkingDirectoryField, BorderLayout.CENTER)
            it.add(singleFileWorkingDirectoryChooserButton, BorderLayout.EAST)
        }
    }

    private fun timeoutControl(): JComponent {
        return JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).also {
            it.background = theme.surface
            it.isOpaque = false
            it.add(timeoutSpinner)
            it.add(Box.createHorizontalStrut(8))
            it.add(JBLabel("ms").also { label -> label.foreground = theme.text })
        }
    }

    private fun buildBody(): JComponent {
        val uiState = stateService.getState().ui
        outputContainer.background = theme.panel
        outputContainer.isOpaque = true
        rebuildOutputLayout()

        return JPanel(BorderLayout()).also { editorPanel ->
            editorPanel.background = theme.panel
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
            it.background = theme.panel
            it.isOpaque = true
            it.border = null
            it.dividerSize = OUTPUT_DIVIDER_SIZE
            it.isContinuousLayout = true
            it.isOneTouchExpandable = false
            it.minimumSize = Dimension(0, 0)
            first.background = theme.panel
            first.isOpaque = true
            second.background = theme.panel
            second.isOpaque = true
        }
    }

    private fun darkSplitPaneUi(): BasicSplitPaneUI {
        return object : BasicSplitPaneUI() {
            override fun createDefaultDivider(): BasicSplitPaneDivider {
                return object : BasicSplitPaneDivider(this) {
                    init {
                        background = theme.panel
                        border = EmptyBorder(0, 0, 0, 0)
                        setOpaque(true)
                    }

                    override fun paint(g: Graphics) {
                        g.color = theme.panel
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
            it.background = theme.panel
            it.isOpaque = true
            it.border = EmptyBorder(0, 0, 0, 0)
            it.minimumSize = Dimension(0, 0)
            it.add(JBLabel(label).also { title -> title.foreground = theme.text }, BorderLayout.NORTH)
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
            it.background = theme.panel
            it.isOpaque = false
            it.add(debugSelectedCaseButton)
            it.add(runSelectedCaseButton)
        }
    }

    private fun scroll(area: JBTextArea, height: Int): JComponent {
        return JBScrollPane(area).also {
            it.preferredSize = Dimension(320, height)
            it.minimumSize = Dimension(100, CPH_MIN_EDITOR_HEIGHT)
            it.border = MatteBorder(1, 1, 1, 1, theme.border)
            it.background = theme.editor
            it.isOpaque = true
            it.viewport.background = theme.editor
            it.viewport.isOpaque = true
            it.horizontalScrollBar.background = theme.editor
            it.verticalScrollBar.background = theme.editor
            it.horizontalScrollBar.border = BorderFactory.createEmptyBorder()
            it.verticalScrollBar.border = BorderFactory.createEmptyBorder()
            val gutter = LineNumberGutter(area)
            it.setRowHeaderView(gutter)
            it.rowHeader?.background = theme.editor
            it.rowHeader?.isOpaque = true
            it.setCorner(ScrollPaneConstants.UPPER_LEFT_CORNER, darkCorner())
            it.setCorner(ScrollPaneConstants.LOWER_LEFT_CORNER, darkCorner())
            it.setCorner(ScrollPaneConstants.UPPER_RIGHT_CORNER, darkCorner())
            it.setCorner(ScrollPaneConstants.LOWER_RIGHT_CORNER, darkCorner())
        }
    }

    private fun darkCorner(): JComponent {
        return JPanel().also {
            it.background = theme.editor
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
            background = theme.panel
            border = EmptyBorder(0, 0, 0, 0)
            alignmentX = Component.LEFT_ALIGNMENT

            titleRow.background = theme.panel
            titleRow.add(title, BorderLayout.WEST)
            titleAction?.let { titleRow.add(it, BorderLayout.EAST) }

            val editorContainer = JPanel(BorderLayout())
            editorContainer.background = theme.panel
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
        area.background = theme.editor
        area.foreground = theme.text
        area.caretColor = theme.text
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
        selectCase(testCase, reveal = false)
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
        runCases(currentTargetCases.cases, ActiveRunButton.RUN_ALL)
    }

    private fun runCases(cases: List<CphTestCase>, source: ActiveRunButton) {
        if (cases.isEmpty() || running) return
        val runCases = cases.toList()
        val identity = currentIdentity
        val targetCases = currentTargetCases
        val timeoutMillis = currentTargetCases.timeoutMillis
        val ignoreTrailing = currentTargetCases.ignoreTrailingWhitespace
        val noExpectedMode = stateService.getState().ui.noExpectedModeEnabled
        val shouldAutoSubmitOnAllAccepted = isCodeforcesSubmitPluginEnabled() &&
            stateService.getState().ui.confidentSubmitEnabled &&
            stateService.getState().singleFileModeEnabled &&
            source == ActiveRunButton.RUN_ALL
        var completedAllCases = false
        runtimeStates.clear()
        runCases.forEach { runtimeStates[it.id] = RuntimeTabState.RUNNING }
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
                    runCases.forEach { it.lastResult = result.copy() }
                    runCases.forEach { reportCaseError(it, it.lastResult) }
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
                    runCases.forEach { it.lastResult = result.copy() }
                    runCases.forEach { reportCaseError(it, it.lastResult) }
                    return
                }

                indicator.text = "Preparing CPH run target"
                val preparedTarget = when (val preparation = runner.prepareForRun(identity)) {
                    is CphRunPreparation.Failed -> {
                        runCases.forEach { it.lastResult = preparation.result.copy() }
                        runCases.forEach { reportCaseError(it, it.lastResult) }
                        return
                    }
                    is CphRunPreparation.Ready -> preparation.target
                }

                val parallelism = caseRunParallelism(runCases.size)
                val executor = Executors.newFixedThreadPool(parallelism) { task ->
                    Thread(task, "CPH case runner").apply { isDaemon = true }
                }
                val completionService = ExecutorCompletionService<Pair<CphTestCase, CphCaseResult>>(executor)
                indicator.text = "Running ${runCases.size} CPH samples"
                runCases.forEach { testCase ->
                    completionService.submit {
                        val result = runCatching {
                            runner.runPreparedCase(
                                preparedTarget,
                                testCase,
                                timeoutMillis,
                                ignoreTrailing,
                                compareExpectedOutput = !noExpectedMode,
                            ).let {
                                if (noExpectedMode) CphStatusMapper.normalizeNoExpectedResult(it) else it
                            }
                        }.getOrElse { error ->
                            CphCaseResult(
                                verdict = CphVerdict.ERROR,
                                message = error.message ?: error.javaClass.simpleName,
                            )
                        }
                        testCase to result
                    }
                }

                var completedCases = 0
                try {
                    while (completedCases < runCases.size && !indicator.isCanceled) {
                        val (testCase, result) = completionService.take().get()
                        completedCases += 1
                        indicator.text = "Completed $completedCases/${runCases.size} CPH samples"
                        indicator.fraction = completedCases.toDouble() / runCases.size
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
                } finally {
                    executor.shutdownNow()
                    executor.awaitTermination(1, TimeUnit.SECONDS)
                }
                if (indicator.isCanceled) {
                    return
                }
                completedAllCases = completedCases == runCases.size
            }

            override fun onFinished() {
                ApplicationManager.getApplication().invokeLater {
                    val shouldSubmit = shouldAutoSubmitOnAllAccepted &&
                        completedAllCases &&
                        !pendingTargetRefresh &&
                        runCases.all { it.lastResult.verdict == CphVerdict.AC }
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

    private fun caseRunParallelism(caseCount: Int): Int {
        val cpuCount = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        return minOf(caseCount, cpuCount, MAX_PARALLEL_CASE_RUNS).coerceAtLeast(1)
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

    private fun selectCase(testCase: CphTestCase?, reveal: Boolean = true) {
        if (selectedCase?.id != testCase?.id) {
            flushSelectedCase()
        }
        selectedCase = testCase
        renderSelectedCase()
        refreshTabs {
            if (reveal) revealCaseTab(testCase?.id)
        }
    }

    private fun revealCaseTab(caseId: String?) {
        caseId?.let { id ->
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
        stopAveMujicaLineHighlightAnimation()
        val testCase = selectedCase ?: return
        if (stateService.getState().ui.noExpectedModeEnabled) return
        val status = statusFor(testCase)
        if (status == TabStatus.NOT_RUN || status == TabStatus.AC) return

        val differingLines = CphComparator.differingActualLines(
            actual = actualArea.text,
            expected = expectedArea.text,
            ignoreTrailingWhitespace = currentTargetCases.ignoreTrailingWhitespace,
        )
        differingLines.forEach(::highlightActualLine)
        updateAveMujicaLineHighlightAnimation(differingLines.isNotEmpty())
    }

    private fun highlightActualLine(line: Int) {
        if (line !in 0 until actualArea.lineCount) return
        runCatching {
            val start = actualArea.getLineStartOffset(line)
            val end = actualArea.getLineEndOffset(line)
                .minus(1)
                .coerceAtLeast(start)
                .coerceAtMost(actualArea.document.length)
            actualArea.highlighter.addHighlight(
                start,
                end.coerceAtLeast(start),
                LineBackgroundPainter(theme.diff, lineHighlightTile(), ::aveMujicaLineHighlightOffset),
            )
        }
    }

    private fun updateAveMujicaLineHighlightAnimation(hasDiffHighlights: Boolean) {
        val shouldAnimate = hasDiffHighlights && theme.id == CphThemeId.AVE_MUJICA && lineHighlightTile() != null
        if (!shouldAnimate) {
            stopAveMujicaLineHighlightAnimation()
            return
        }
        if (!aveMujicaLineHighlightTimer.isRunning) {
            aveMujicaLineHighlightAnimationStartedNanos = System.nanoTime()
            aveMujicaLineHighlightFrameOffset = 0.0
            aveMujicaLineHighlightTimer.start()
        }
    }

    private fun aveMujicaLineHighlightOffset(): Double {
        val tileWidth = aveMujicaLineHighlightTile?.width ?: AVE_MUJICA_LINE_HIGHLIGHT_TILE_FALLBACK_WIDTH
        return aveMujicaLineHighlightFrameOffset % tileWidth
    }

    private fun stopAveMujicaLineHighlightAnimation() {
        if (aveMujicaLineHighlightTimer.isRunning) {
            aveMujicaLineHighlightTimer.stop()
        }
        aveMujicaLineHighlightAnimationStartedNanos = 0L
        aveMujicaLineHighlightFrameOffset = 0.0
    }

    private fun lineHighlightTile(): BufferedImage? {
        if (theme.id != CphThemeId.AVE_MUJICA) return null
        aveMujicaLineHighlightTile?.let { return it }
        val url = CphToolWindowPanel::class.java.getResource(AVE_MUJICA_LINE_HIGHLIGHT_TILE) ?: return null
        return ImageIO.read(url)?.also { aveMujicaLineHighlightTile = it }
    }

    private fun animationOffset(startedNanos: Long, pixelsPerSecond: Double): Double {
        if (startedNanos == 0L) return 0.0
        val elapsedSeconds = (System.nanoTime() - startedNanos).coerceAtLeast(0L) / 1_000_000_000.0
        return elapsedSeconds * pixelsPerSecond
    }

    private fun refreshTabs(afterLayout: (() -> Unit)? = null) {
        val scrollSnapshot = captureTabHorizontalScroll()
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
        updateAveMujicaStatusGlyphAnimation()
        syncTabScrollPaneHeight()
        restoreTabHorizontalScroll(scrollSnapshot)
        afterLayout?.invoke()
        SwingUtilities.invokeLater {
            syncTabScrollPaneHeight()
            restoreTabHorizontalScroll(scrollSnapshot)
            afterLayout?.invoke()
            updateAveMujicaStatusGlyphAnimation()
        }
    }

    private fun captureTabHorizontalScroll(): TabHorizontalScrollSnapshot {
        val scrollBar = tabScrollPane.horizontalScrollBar
        val maxValue = (scrollBar.maximum - scrollBar.visibleAmount).coerceAtLeast(scrollBar.minimum)
        return TabHorizontalScrollSnapshot(
            value = scrollBar.value,
            atEnd = maxValue > scrollBar.minimum && scrollBar.value >= maxValue - 1,
        )
    }

    private fun restoreTabHorizontalScroll(snapshot: TabHorizontalScrollSnapshot) {
        val scrollBar = tabScrollPane.horizontalScrollBar
        val maxValue = (scrollBar.maximum - scrollBar.visibleAmount).coerceAtLeast(scrollBar.minimum)
        scrollBar.value = if (snapshot.atEnd) {
            maxValue
        } else {
            snapshot.value.coerceIn(scrollBar.minimum, maxValue)
        }
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

    private fun styleFor(status: TabStatus): TabStyle {
        val color = when (status) {
            TabStatus.OK,
            TabStatus.AC -> theme.good
            TabStatus.WA,
            TabStatus.RE,
            TabStatus.ERROR -> theme.bad
            TabStatus.TLE -> theme.warn
            TabStatus.RUNNING -> theme.run
            TabStatus.QUEUED,
            TabStatus.NOT_RUN -> theme.muted
        }
        val background = theme.tabStatusBackground(
            when (status) {
                TabStatus.OK,
                TabStatus.AC -> CphThemeTabStatus.GOOD
                TabStatus.WA,
                TabStatus.RE,
                TabStatus.ERROR -> CphThemeTabStatus.BAD
                TabStatus.TLE -> CphThemeTabStatus.WARN
                TabStatus.RUNNING -> CphThemeTabStatus.RUN
                TabStatus.QUEUED,
                TabStatus.NOT_RUN -> CphThemeTabStatus.DEFAULT
            },
        )
        return TabStyle(color, background, color)
    }

    private fun caseTabPreferredSize(): Dimension {
        return if (theme.id == CphThemeId.AVE_MUJICA) Dimension(128, 64) else Dimension(123, 60)
    }

    private fun caseTabMinimumSize(): Dimension {
        return if (theme.id == CphThemeId.AVE_MUJICA) Dimension(108, 64) else Dimension(102, 60)
    }

    private fun caseTabMaximumSize(): Dimension {
        return if (theme.id == CphThemeId.AVE_MUJICA) Dimension(161, 64) else Dimension(161, 60)
    }

    private fun caseTabTitleFontDelta(): Float {
        return if (theme.id == CphThemeId.AVE_MUJICA) 0.5f else 2.0f
    }

    private fun aveMujicaCaseTextWidth(label: JBLabel, hasStatusGlyph: Boolean): Int {
        if (hasStatusGlyph) return AVE_MUJICA_STATUS_ROW_WIDTH
        val metrics = label.getFontMetrics(label.font)
        return listOf("99 CASE", "99 TLE", label.text)
            .maxOf { metrics.stringWidth(it) } + 8
    }

    private fun aveMujicaStatusGlyph(status: TabStatus): JComponent? {
        val image = aveMujicaStatusGlyphImage(status) ?: return null
        val size = AveMujicaStatusGlyph.preferredSizeFor(image, status.wideGlyph)
        return AveMujicaStatusGlyph(
            mask = image,
            colors = aveMujicaStatusGlyphColors(status),
            textureOffset = ::aveMujicaStatusGlyphOffset,
        ).apply {
            preferredSize = size
            minimumSize = preferredSize
            maximumSize = preferredSize
        }
    }

    private fun aveMujicaStatusGlyphImage(status: TabStatus): BufferedImage? {
        if (theme.id != CphThemeId.AVE_MUJICA) return null
        val glyphName = status.glyphName ?: return null
        if (aveMujicaStatusGlyphCache.containsKey(glyphName)) {
            return aveMujicaStatusGlyphCache[glyphName]
        }
        val path = "/icons/avemujica/status/512/$glyphName.png"
        val image = CphToolWindowPanel::class.java.getResource(path)?.let { ImageIO.read(it) }
        aveMujicaStatusGlyphCache[glyphName] = image
        return image
    }

    private fun aveMujicaStatusGlyphColors(status: TabStatus): StatusGlyphColors {
        return when (status) {
            TabStatus.OK,
            TabStatus.AC -> StatusGlyphColors(Color(0x1B5B2A), Color(0x73E37E), Color(0xCBFFD0))
            TabStatus.WA,
            TabStatus.RE,
            TabStatus.ERROR -> StatusGlyphColors(Color(0x62000C), Color(0xFF2A3D), Color(0xFFC0C8))
            TabStatus.TLE -> StatusGlyphColors(Color(0x765012), Color(0xF2A93B), Color(0xFFE9A6))
            TabStatus.RUNNING -> StatusGlyphColors(Color(0x384E95), Color(0x7FA7FF), Color(0xD7E5FF))
            TabStatus.QUEUED,
            TabStatus.NOT_RUN -> StatusGlyphColors(theme.muted.darker(), theme.muted, theme.text)
        }
    }

    private fun updateAveMujicaStatusGlyphAnimation() {
        val shouldAnimate = theme.id == CphThemeId.AVE_MUJICA &&
            currentTargetCases.cases.any { aveMujicaStatusGlyphImage(statusFor(it)) != null }
        if (shouldAnimate) {
            if (!aveMujicaStatusGlyphTimer.isRunning) {
                aveMujicaStatusGlyphAnimationStartedNanos = System.nanoTime()
                aveMujicaStatusGlyphFrameOffset = 0.0
                aveMujicaStatusGlyphTimer.start()
            }
        } else {
            stopAveMujicaStatusGlyphAnimation()
        }
    }

    private fun aveMujicaStatusGlyphOffset(): Double {
        return aveMujicaStatusGlyphFrameOffset
    }

    private fun stopAveMujicaStatusGlyphAnimation() {
        if (aveMujicaStatusGlyphTimer.isRunning) {
            aveMujicaStatusGlyphTimer.stop()
        }
        aveMujicaStatusGlyphAnimationStartedNanos = 0L
        aveMujicaStatusGlyphFrameOffset = 0.0
    }

    private fun caseDeleteActionWidth(): Int {
        return if (theme.id == CphThemeId.AVE_MUJICA) 38 else 24
    }

    private fun caseDeleteButtonSize(): Dimension {
        return if (theme.id == CphThemeId.AVE_MUJICA) Dimension(32, 32) else Dimension(24, 20)
    }

    private fun caseDeleteIconSize(): Int {
        return if (theme.id == CphThemeId.AVE_MUJICA) aveMujicaIconSize(24) else 16
    }

    private fun updateActions() {
        val runnable = currentIdentity.runnable && !running
        val runAllActive = activeRunButton == ActiveRunButton.RUN_ALL
        val runSelectedActive = activeRunButton == ActiveRunButton.RUN_SELECTED
        val codeforcesSubmitEnabled = isCodeforcesSubmitPluginEnabled()
        settingsButton.isEnabled = !running
        runSelectedCaseButton.isEnabled = (selectedCase != null && runnable) || runSelectedActive
        debugSelectedCaseButton.isEnabled = selectedCase != null && !running &&
            currentIdentity.runnable &&
            (currentIdentity.kind == CphTargetKind.CMAKE_APP || currentIdentity.kind == CphTargetKind.CPP_FILE)
        runAllButton.isEnabled = (currentTargetCases.cases.isNotEmpty() && runnable) || runAllActive
        resetCasesButton.isEnabled = !running
        timeoutSpinner.isEnabled = !running
        editorFontSizeSpinner.isEnabled = !running
        noExpectedModeEnabled.isEnabled = !running
        confidentSubmitEnabled.isEnabled = codeforcesSubmitEnabled && !running && stateService.getState().singleFileModeEnabled
        ignoreTrailingWhitespace.isEnabled = !running
        outputSplitEnabled.isEnabled = !running && !stateService.getState().ui.noExpectedModeEnabled
        cppStandardCombo.isEnabled = !running
        compileOptionsField.isEnabled = !running
        singleFileWorkingDirectoryField.isEnabled = !running
        singleFileWorkingDirectoryChooserButton.isEnabled = !running
        refreshToolbarIconButton(settingsButton, if (settingsVisible) theme.run else theme.text)
        refreshToolbarIconButton(resetCasesButton, theme.text)
        refreshSettingsHelpButton()
        refreshSettingsTabButtons()
        refreshThemePluginUi()
        refreshSubmitFeatureVisibility()
        refreshRunActionButtons()
    }

    private inner class CaseTab(
        private val index: Int,
        private val testCase: CphTestCase,
    ) : JPanel(BorderLayout()) {
        private val deleteCaseButton = JButton("×")
        private val title = JBLabel()
        private val detail = JBLabel()

        init {
            val status = statusFor(testCase)
            val style = styleFor(status)
            val isSelected = selectedCase?.id == testCase.id
            val foreground = style.foreground
            val statusGlyph = aveMujicaStatusGlyph(status)
            val tabBackground = when {
                isSelected -> theme.selected
                else -> theme.panel
            }
            val tabBorder = when {
                isSelected -> style.foreground
                else -> theme.border
            }

            background = tabBackground
            border = CompoundBorder(
                MatteBorder(1, 1, 2, 1, tabBorder),
                EmptyBorder(3, 10, 3, 8),
            )
            preferredSize = caseTabPreferredSize()
            minimumSize = caseTabMinimumSize()
            maximumSize = caseTabMaximumSize()
            cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)

            deleteCaseButton.isEnabled = !running
            deleteCaseButton.toolTipText = "Delete case"
            deleteCaseButton.foreground = if (deleteCaseButton.isEnabled) Color.WHITE else theme.muted
            deleteCaseButton.background = tabBackground
            deleteCaseButton.isOpaque = false
            deleteCaseButton.isContentAreaFilled = false
            deleteCaseButton.isBorderPainted = false
            deleteCaseButton.isFocusPainted = false
            deleteCaseButton.horizontalAlignment = SwingConstants.CENTER
            deleteCaseButton.border = EmptyBorder(0, 0, 0, 0)
            deleteCaseButton.preferredSize = caseDeleteButtonSize()
            deleteCaseButton.maximumSize = deleteCaseButton.preferredSize
            deleteCaseButton.font = deleteCaseButton.font.deriveFont(Font.BOLD, deleteCaseButton.font.size2D + 1.0f)
            if (theme.id == CphThemeId.AVE_MUJICA) {
                setThemeButtonFace(deleteCaseButton, "delete_case", false, "×", null, caseDeleteIconSize())
                deleteCaseButton.model.addChangeListener(ChangeListener { refreshGeneratedIconOnly(deleteCaseButton) })
                installThemedIconAnimation(deleteCaseButton)
            }
            deleteCaseButton.addActionListener { deleteCase(testCase) }

            title.text = if (statusGlyph != null) {
                index.toString()
            } else {
                tabTitle(index, status, includeStatusIcon = theme.id != CphThemeId.AVE_MUJICA)
            }
            title.foreground = foreground
            title.font = title.font.deriveFont(Font.BOLD, title.font.size2D + caseTabTitleFontDelta())
            title.alignmentX = Component.LEFT_ALIGNMENT

            detail.text = tabDetail(testCase, status)
            detail.foreground = theme.muted
            detail.font = detail.font.deriveFont(Font.PLAIN, detail.font.size2D - 1.0f)
            detail.alignmentX = Component.LEFT_ALIGNMENT
            toolTipText = tabTooltip(testCase, status)

            val content = JPanel(BorderLayout(if (theme.id == CphThemeId.AVE_MUJICA) 4 else 8, 0))
            content.background = background
            val textPanel = JPanel()
            textPanel.layout = BoxLayout(textPanel, BoxLayout.Y_AXIS)
            textPanel.background = background
            val titleRow = if (statusGlyph != null) {
                JPanel().apply {
                    layout = BoxLayout(this, BoxLayout.X_AXIS)
                    background = tabBackground
                    alignmentX = Component.LEFT_ALIGNMENT
                    add(title)
                    add(Box.createRigidArea(Dimension(2, 0)))
                    add(statusGlyph)
                }
            } else {
                null
            }
            textPanel.add(titleRow ?: title)
            textPanel.add(detail)
            if (theme.id == CphThemeId.AVE_MUJICA) {
                val textWidth = aveMujicaCaseTextWidth(title, statusGlyph != null)
                val textHeight = textPanel.preferredSize.height
                textPanel.minimumSize = Dimension(textWidth, textHeight)
                textPanel.preferredSize = Dimension(textWidth, textHeight)
            }
            val actionPanel = JPanel(GridBagLayout())
            actionPanel.background = background
            actionPanel.preferredSize = Dimension(caseDeleteActionWidth(), 1)
            val deleteConstraints = GridBagConstraints().apply {
                gridx = 0
                gridy = 0
                weightx = 1.0
                weighty = 1.0
                anchor = GridBagConstraints.NORTHEAST
            }
            actionPanel.add(deleteCaseButton, deleteConstraints)
            content.add(textPanel, BorderLayout.CENTER)
            content.add(actionPanel, BorderLayout.EAST)
            content.toolTipText = toolTipText
            title.toolTipText = toolTipText
            detail.toolTipText = toolTipText
            textPanel.toolTipText = toolTipText
            actionPanel.toolTipText = toolTipText
            titleRow?.toolTipText = toolTipText
            statusGlyph?.toolTipText = toolTipText

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
            titleRow?.addMouseListener(selectListener)
            statusGlyph?.addMouseListener(selectListener)

            add(content, BorderLayout.CENTER)
        }
    }

    private inner class AddCaseTab : JPanel(BorderLayout()) {
        private val button = JButton("+")

        init {
            background = theme.panel
            border = CompoundBorder(
                MatteBorder(1, 1, 2, 1, theme.border),
                EmptyBorder(3, 10, 3, 8),
            )
            preferredSize = caseTabPreferredSize()
            minimumSize = caseTabMinimumSize()
            maximumSize = caseTabMaximumSize()
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            toolTipText = "Add case"

            button.isEnabled = !running
            button.toolTipText = toolTipText
            button.foreground = theme.text
            button.background = theme.panel
            button.isOpaque = false
            button.isContentAreaFilled = false
            button.isBorderPainted = false
            button.isFocusPainted = false
            button.font = button.font.deriveFont(Font.BOLD, button.font.size2D + 2.0f)
            if (theme.id == CphThemeId.AVE_MUJICA) {
                setThemeButtonFace(button, "add_case", false, "+", null, aveMujicaIconSize(42))
                button.model.addChangeListener(ChangeListener { refreshGeneratedIconOnly(button) })
                installThemedIconAnimation(button)
            }
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

    private inner class LineNumberGutter(private val area: JBTextArea) : JComponent(), DocumentListener {
        init {
            isOpaque = true
            font = area.font
            foreground = theme.muted
            background = theme.editor
            border = MatteBorder(0, 0, 0, 1, theme.border)
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

    private class LineBackgroundPainter(
        private val color: Color,
        private val tile: BufferedImage? = null,
        private val tileOffset: () -> Double = { 0.0 },
    ) : Highlighter.HighlightPainter {
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
            if (tile == null) {
                g.color = color
                g.fillRect(0, y, c.width, rowHeight)
                return
            }

            val g2 = g.create() as Graphics2D
            try {
                g2.color = color
                g2.fillRect(0, y, c.width, rowHeight)
                g2.paint = TexturePaint(
                    tile,
                    Rectangle2D.Double(
                        -tileOffset(),
                        y.toDouble(),
                        tile.width.toDouble(),
                        tile.height.toDouble(),
                    ),
                )
                g2.fillRect(0, y, c.width, rowHeight)
            } finally {
                g2.dispose()
            }
        }
    }

    private data class TabHorizontalScrollSnapshot(
        val value: Int,
        val atEnd: Boolean,
    )

    private enum class RuntimeTabState {
        QUEUED,
        RUNNING,
    }

    private enum class ActiveRunButton {
        RUN_ALL,
        RUN_SELECTED,
    }

    private enum class SettingsPanelTab(val cardName: String) {
        SETTINGS("settings"),
        UTILITY("utility"),
        THEMES("themes"),
    }

    private enum class TabStatus(
        val label: String,
        val icon: String,
        val glyphName: String? = null,
        val wideGlyph: Boolean = false,
    ) {
        OK("OK", "✓", "ok"),
        AC("AC", "✓", "ac"),
        WA("WA", "✕", "wa"),
        TLE("TLE", "◷", "tle", true),
        RE("RE", "!", "re"),
        ERROR("ERR", "!", "err", true),
        RUNNING("RUN", "●", "run", true),
        QUEUED("...", "○"),
        NOT_RUN("-", "○"),
    }

    private data class TabStyle(
        val foreground: Color,
        val background: Color,
        val border: Color,
    )

    private data class StatusGlyphColors(
        val dark: Color,
        val base: Color,
        val highlight: Color,
    )

    private class HighQualityPngIcon(
        private val image: BufferedImage,
        private val targetWidth: Int,
        private val targetHeight: Int,
        private val sourceRect: Rectangle? = null,
    ) : Icon {
        constructor(image: BufferedImage, size: Int, sourceRect: Rectangle? = null) : this(image, size, size, sourceRect)

        private val renderedByScale = mutableMapOf<String, BufferedImage>()

        override fun getIconWidth(): Int = targetWidth

        override fun getIconHeight(): Int = targetHeight

        override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) {
            val g2 = g.create() as Graphics2D
            try {
                val scale = JBUIScale.sysScale(g2).coerceAtLeast(1.0f)
                val deviceWidth = (targetWidth * scale).roundToInt().coerceAtLeast(targetWidth)
                val deviceHeight = (targetHeight * scale).roundToInt().coerceAtLeast(targetHeight)
                val rendered = renderedByScale.getOrPut("$deviceWidth:$deviceHeight") {
                    renderForDeviceSize(deviceWidth, deviceHeight)
                }
                g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
                g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.setRenderingHint(RenderingHints.KEY_ALPHA_INTERPOLATION, RenderingHints.VALUE_ALPHA_INTERPOLATION_QUALITY)
                g2.setRenderingHint(RenderingHints.KEY_COLOR_RENDERING, RenderingHints.VALUE_COLOR_RENDER_QUALITY)
                g2.drawImage(rendered, x, y, targetWidth, targetHeight, null)
            } finally {
                g2.dispose()
            }
        }

        private fun renderForDeviceSize(deviceWidth: Int, deviceHeight: Int): BufferedImage {
            var current: BufferedImage = image
            var width = sourceRect?.width ?: image.width
            var height = sourceRect?.height ?: image.height
            if (sourceRect != null) {
                current = current.getSubimage(sourceRect.x, sourceRect.y, sourceRect.width, sourceRect.height)
            }
            while (width / 2 >= deviceWidth && height / 2 >= deviceHeight) {
                width /= 2
                height /= 2
                current = resizeImage(current, width, height)
            }
            return resizeImage(current, deviceWidth, deviceHeight)
        }

        private fun resizeImage(source: BufferedImage, width: Int, height: Int): BufferedImage {
            val target = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
            val g2 = target.createGraphics()
            try {
                g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
                g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.setRenderingHint(RenderingHints.KEY_ALPHA_INTERPOLATION, RenderingHints.VALUE_ALPHA_INTERPOLATION_QUALITY)
                g2.setRenderingHint(RenderingHints.KEY_COLOR_RENDERING, RenderingHints.VALUE_COLOR_RENDER_QUALITY)
                g2.drawImage(source, 0, 0, width, height, null)
            } finally {
                g2.dispose()
            }
            return target
        }
    }

    private class HorizontalIcon(
        private val left: Icon,
        private val right: Icon,
        private val gap: Int,
    ) : Icon {
        override fun getIconWidth(): Int = left.iconWidth + gap + right.iconWidth

        override fun getIconHeight(): Int = maxOf(left.iconHeight, right.iconHeight)

        override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) {
            val leftY = y + (iconHeight - left.iconHeight) / 2
            val rightY = y + (iconHeight - right.iconHeight) / 2
            left.paintIcon(c, g, x, leftY)
            right.paintIcon(c, g, x + left.iconWidth + gap, rightY)
        }
    }

    private class HelpGlyphIcon(
        private val color: Color,
        private val size: Int,
    ) : Icon {
        override fun getIconWidth(): Int = size

        override fun getIconHeight(): Int = size

        override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) {
            val g2 = g.create() as Graphics2D
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.color = Color(color.red, color.green, color.blue, 70)
                g2.drawOval(x + 1, y + 1, size - 3, size - 3)
                g2.color = color
                val baseFont = c?.font ?: g2.font
                g2.font = baseFont.deriveFont(Font.BOLD, size * 0.82f)
                val metrics = g2.fontMetrics
                val text = "?"
                val textX = x + (size - metrics.stringWidth(text)) / 2
                val textY = y + (size - metrics.height) / 2 + metrics.ascent - 1
                g2.drawString(text, textX, textY)
            } finally {
                g2.dispose()
            }
        }
    }

    private class AveMujicaStatusGlyph(
        private val mask: BufferedImage,
        private val colors: StatusGlyphColors,
        private val textureOffset: () -> Double,
    ) : JComponent() {
        private val sourceRect = visibleAlphaRect(mask)

        init {
            isOpaque = false
        }

        override fun paintComponent(g: Graphics) {
            super.paintComponent(g)
            val rect = sourceRect ?: return
            if (width <= 0 || height <= 0) return

            val scale = minOf(width.toDouble() / rect.width, height.toDouble() / rect.height)
            val drawWidth = (rect.width * scale).roundToInt().coerceAtLeast(1)
            val drawHeight = (rect.height * scale).roundToInt().coerceAtLeast(1)
            val rendered = BufferedImage(drawWidth, drawHeight, BufferedImage.TYPE_INT_ARGB)
            val g2 = rendered.createGraphics()
            try {
                applyQualityHints(g2)
                paintStatusTexture(g2, drawWidth, drawHeight, textureOffset())
                g2.composite = AlphaComposite.DstIn
                g2.drawImage(
                    mask.getSubimage(rect.x, rect.y, rect.width, rect.height),
                    0,
                    0,
                    drawWidth,
                    drawHeight,
                    null,
                )
            } finally {
                g2.dispose()
            }

            val out = g.create() as Graphics2D
            try {
                applyQualityHints(out)
                out.drawImage(rendered, (width - drawWidth) / 2, (height - drawHeight) / 2, null)
            } finally {
                out.dispose()
            }
        }

        private fun paintStatusTexture(g2: Graphics2D, width: Int, height: Int, offset: Double) {
            g2.paint = GradientPaint(
                0f,
                0f,
                colors.base,
                0f,
                height.toFloat(),
                colors.dark,
                false,
            )
            g2.fillRect(0, 0, width, height)

            val highlightWidth = AVE_MUJICA_STATUS_GLYPH_HIGHLIGHT_WIDTH
            val travel = AVE_MUJICA_STATUS_GLYPH_FLOW_CYCLE_WIDTH
            val x = (offset % travel) - highlightWidth
            val transparentHighlight = Color(colors.highlight.red, colors.highlight.green, colors.highlight.blue, 0)
            g2.paint = LinearGradientPaint(
                x.toFloat(),
                0f,
                (x + highlightWidth).toFloat(),
                0f,
                floatArrayOf(0f, 0.5f, 1f),
                arrayOf(
                    transparentHighlight,
                    Color(colors.highlight.red, colors.highlight.green, colors.highlight.blue, 150),
                    transparentHighlight,
                ),
            )
            g2.fill(Rectangle2D.Double(x, 0.0, highlightWidth, height.toDouble()))
        }

        private fun applyQualityHints(g2: Graphics2D) {
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
            g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.setRenderingHint(RenderingHints.KEY_ALPHA_INTERPOLATION, RenderingHints.VALUE_ALPHA_INTERPOLATION_QUALITY)
            g2.setRenderingHint(RenderingHints.KEY_COLOR_RENDERING, RenderingHints.VALUE_COLOR_RENDER_QUALITY)
        }

        companion object {
            fun preferredSizeFor(image: BufferedImage, wideGlyph: Boolean): Dimension {
                val rect = visibleAlphaRect(image)
                val targetWidth = if (wideGlyph) {
                    AVE_MUJICA_STATUS_GLYPH_WIDE_WIDTH
                } else {
                    AVE_MUJICA_STATUS_GLYPH_NARROW_WIDTH
                }
                if (rect == null) return Dimension(targetWidth, AVE_MUJICA_STATUS_GLYPH_HEIGHT)

                val proportionalWidth = (rect.width.toDouble() / rect.height * AVE_MUJICA_STATUS_GLYPH_HEIGHT)
                    .roundToInt()
                val width = maxOf(targetWidth, proportionalWidth)
                    .coerceAtMost(if (wideGlyph) AVE_MUJICA_STATUS_GLYPH_WIDE_MAX_WIDTH else targetWidth + 4)
                return Dimension(width, AVE_MUJICA_STATUS_GLYPH_HEIGHT)
            }

            private fun visibleAlphaRect(image: BufferedImage): Rectangle? {
                var minX = image.width
                var minY = image.height
                var maxX = -1
                var maxY = -1
                for (y in 0 until image.height) {
                    for (x in 0 until image.width) {
                        val alpha = image.getRGB(x, y) ushr 24
                        if (alpha > AVE_MUJICA_VISIBLE_ALPHA_THRESHOLD) {
                            minX = minOf(minX, x)
                            minY = minOf(minY, y)
                            maxX = maxOf(maxX, x)
                            maxY = maxOf(maxY, y)
                        }
                    }
                }
                if (maxX < minX || maxY < minY) return null
                return Rectangle(minX, minY, maxX - minX + 1, maxY - minY + 1)
            }

        }
    }

    private companion object {
        private const val MAIN_VIEW_CARD = "main"
        private const val SETTINGS_VIEW_CARD = "settings"
        private const val RESIZE_HANDLE_HEIGHT = 8
        private const val OUTPUT_DIVIDER_SIZE = 6
        private const val TAB_STRIP_BASE_HEIGHT = 62
        private const val AVE_MUJICA_TAB_STRIP_BASE_HEIGHT = 66
        private const val AVE_MUJICA_VISIBLE_ALPHA_THRESHOLD = 8
        private const val AVE_MUJICA_ICON_TRIM_PADDING_SCALE = 1.12
        private const val AVE_MUJICA_LINE_HIGHLIGHT_TILE = "/icons/avemujica/highlight/line_tile.png"
        private const val AVE_MUJICA_FLOW_ANIMATION_DELAY_MILLIS = 16
        private const val AVE_MUJICA_LINE_HIGHLIGHT_PIXELS_PER_SECOND = 36.0
        private const val AVE_MUJICA_LINE_HIGHLIGHT_TILE_FALLBACK_WIDTH = 256
        private const val AVE_MUJICA_STATUS_GLYPH_PIXELS_PER_SECOND = 58.0
        private const val AVE_MUJICA_STATUS_GLYPH_HIGHLIGHT_WIDTH = 32.0
        private const val AVE_MUJICA_STATUS_GLYPH_FLOW_CYCLE_WIDTH = 96.0
        private const val AVE_MUJICA_STATUS_GLYPH_NARROW_WIDTH = 42
        private const val AVE_MUJICA_STATUS_GLYPH_WIDE_WIDTH = 56
        private const val AVE_MUJICA_STATUS_GLYPH_WIDE_MAX_WIDTH = 60
        private const val AVE_MUJICA_STATUS_GLYPH_HEIGHT = 25
        private const val AVE_MUJICA_STATUS_ROW_WIDTH = 76
        private const val COMPILE_OPTIONS_SYNC_DELAY_MILLIS = 650
        private const val SUBMISSION_STATUS_VISIBLE_MILLIS = 15_000
        private const val MAX_PARALLEL_CASE_RUNS = 8
        private val SUBMIT_SPINNER_FRAMES = arrayOf("⠋", "⠙", "⠹", "⠸", "⠼", "⠴", "⠦", "⠧", "⠇", "⠏")
        private val RUN_SPINNER_FRAMES = arrayOf("○", "◔", "◑", "◕")
        private const val RUN_ALL_BUTTON_TEXT = "▷"
        private const val RUN_SELECTED_BUTTON_TEXT = "▷ Run"
        private const val CPH_DOCS_URL = "https://cph.kkkzbh.cn"
        private const val CPH_NOTIFICATION_GROUP_ID = "CPH Target Runner"
        private const val AVE_MUJICA_FONT_RESOURCE = "/fonts/AnglicanText.ttf"
        private const val AVE_MUJICA_HELP_TEXT_RESOURCE = "/icons/avemujica/standalone/help_text.png"
        private const val AVE_MUJICA_HELP_TEXT_HEIGHT = 28
        private const val AVE_MUJICA_HELP_ICON_TEXT_GAP = 7
        private const val AVE_MUJICA_THEME_TITLE_RESOURCE = "/icons/avemujica/standalone/theme_title.png"
        private const val AVE_MUJICA_THEME_TITLE_HEIGHT = 32
        private const val AVE_MUJICA_ANIMATION_DELAY_MILLIS = 70
        private const val AVE_MUJICA_ANIMATION_FRAME_COUNT = 8
        private const val AVE_MUJICA_ICON_NAME_PROPERTY = "cph.aveMujica.iconName"
        private const val AVE_MUJICA_ICON_SIZE_PROPERTY = "cph.aveMujica.iconSize"
        private const val AVE_MUJICA_ICON_FRAME_PROPERTY = "cph.aveMujica.iconFrame"
        private const val AVE_MUJICA_ANIMATION_CONFIGURED_PROPERTY = "cph.aveMujica.animationConfigured"
        private const val SETTINGS_ROW_PROPERTY = "cph.settings.row"
        private const val SETTINGS_TAB_CONFIGURED_PROPERTY = "cph.settingsTab.configured"
        private const val BASE_FONT_PROPERTY = "cph.baseFont"

        private fun tabTitle(index: Int, status: TabStatus, includeStatusIcon: Boolean = true): String {
            return if (status == TabStatus.NOT_RUN) {
                "$index CASE"
            } else if (includeStatusIcon) {
                "$index ${status.icon} ${status.label}"
            } else {
                "$index ${status.label}"
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

    }
}
