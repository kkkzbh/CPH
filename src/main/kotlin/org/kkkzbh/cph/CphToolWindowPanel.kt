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
import com.intellij.util.IconUtil
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
import java.awt.BasicStroke
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
import java.awt.Toolkit
import java.awt.TexturePaint
import java.awt.datatransfer.StringSelection
import java.awt.geom.Rectangle2D
import java.awt.image.BufferedImage
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
import java.awt.event.MouseWheelEvent
import java.io.File
import kotlin.math.roundToInt

internal object CphUiText {
    fun errorTooltip(caseName: String, result: CphCaseResult): String {
        val summary = "$caseName: error in ${formatDuration(result.durationMillis)}"
        val message = result.message.takeIf { it.isNotBlank() } ?: return summary
        return "$summary: $message"
    }

    fun errorStatusMessage(caseName: String, result: CphCaseResult): String {
        val code = errorStatusCode(result)
        return "CPH: $caseName $code - ${errorSummary(result)}"
    }

    fun errorStatusCode(result: CphCaseResult): String {
        return if (isCompileLikeError(result)) "CE" else "ERR"
    }

    fun isCompileLikeError(result: CphCaseResult): Boolean {
        if (result.verdict != CphVerdict.ERROR) return false
        val text = listOf(result.message, result.stderr, result.actualOutput)
            .joinToString("\n")
            .lowercase()
        return COMPILE_ERROR_MARKERS.any { it in text }
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
    CE,
    ERROR,
}

internal object CphStatusMapper {
    fun displayStatus(result: CphCaseResult, noExpectedMode: Boolean): CphRunDisplayStatus {
        if (CphUiText.isCompileLikeError(result)) return CphRunDisplayStatus.CE
        return displayStatus(result.verdict, noExpectedMode)
    }

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
    private val themeAssetService = CphThemeAssetService.getInstance()
    private val theme: CphThemePalette
        get() = CphThemes.current()
    private var currentIdentity = CphTargetResolver.current(project)
    private var currentTargetCases = initialTargetCases(currentIdentity)
    private var selectedCase: CphTestCase? = null
    private var running = false
    private var settingsVisible = false
    private var activeSettingsTab = SettingsPanelTab.SETTINGS
    private var pendingTargetRefresh = false
    private var applyingTargetSettings = false
    private var applyingShortcutSettings = false
    private var applyingLanguageSettings = false
    private var applyingWorkingDirectorySettings = false
    private var tabScrollPaneHeightListenerInstalled = false
    private var activeRunButton: ActiveRunButton? = null
    private var runSpinnerIndex = 0

    private val compileSettingsSynchronizer = CphCompileSettingsSynchronizer(project)
    private val runtimeStates = linkedMapOf<String, RuntimeTabState>()
    private val caseTabComponents = linkedMapOf<String, CaseTab>()

    private val runAllButton = JButton(RUN_ALL_BUTTON_TEXT)
    private val submitButton = JButton("📤")
    private val helpButton = JButton("?")
    private val enableCphButton = OnboardingStartButton()
    private val settingsTabButton = JButton()
    private val utilitySettingsTabButton = JButton("", IconLoader.getIcon("/icons/plugin.svg", CphToolWindowPanel::class.java))
    private val themeSettingsTabButton = JButton("", IconLoader.getIcon("/icons/cphToolWindow.svg", CphToolWindowPanel::class.java))
    private val codeforcesSubmitHelpButton = JButton("?")
    private val codeforcesPluginToggleButton = JButton()
    private val eapRepositoryToggleButton = JButton()
    private val classicThemeToggleButton = JButton()
    private val aveMujicaThemeToggleButton = JButton()
    private val scaledIconCache = mutableMapOf<String, Icon>()
    private val generatedIconPresenceCache = mutableMapOf<String, Boolean>()
    private val aveMujicaStatusGlyphCache = mutableMapOf<String, BufferedImage?>()
    private val iconAnimationTimers = mutableMapOf<JButton, Timer>()
    private val titleActionFeedbackTimers = mutableMapOf<JButton, Timer>()
    private var aveMujicaLineHighlightTile: BufferedImage? = null
    private var aveMujicaLineHighlightAnimationStartedNanos = 0L
    private var aveMujicaStatusGlyphAnimationStartedNanos = 0L
    private var aveMujicaLineHighlightFrameOffset = 0.0
    private var aveMujicaStatusGlyphFrameOffset = 0.0
    private var aveMujicaFontLoaded = false
    private var aveMujicaFontCache: Font? = null
    private val aveMujicaFont: Font?
        get() {
            if (!aveMujicaFontLoaded) {
                aveMujicaFontCache = loadAveMujicaFont()
                aveMujicaFontLoaded = true
            }
            return aveMujicaFontCache
        }
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
    private val runSelectedCaseButton = JButton()
    private val debugSelectedCaseButton = JButton()
    private val inputCopyButton = JButton()
    private val expectedCopyButton = JButton()
    private val actualCopyButton = JButton()
    private val actualToExpectedButton = JButton()
    private val runSpinnerTimer = Timer(140) {
        updateRunSpinnerText()
        refreshRunActionButtons()
    }
    private val singleFileModeEnabled = JCheckBox()
    private val ignoreTrailingWhitespace = JCheckBox()
    private val outputSplitEnabled = JCheckBox()
    private val noExpectedModeEnabled = JCheckBox()
    private val showStderrEnabled = JCheckBox()
    private val compactCaseTabsEnabled = JCheckBox()
    private val confidentSubmitEnabled = JCheckBox()
    private val parallelCaseRunEnabled = JCheckBox()
    private val parallelCaseRunHelp = JBLabel("?")
    private val gccBitsPchEnabled = JCheckBox()
    private val gccBitsPchHelp = JBLabel("?")
    private val languageCombo = JComboBox(CphUiLanguage.entries.toTypedArray())
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
    private val compileDiagnosticsPanel = JPanel(BorderLayout())
    private val compileDiagnosticsLabel = JBLabel("")
    private val contentCards = JPanel(CardLayout())
    private val settingsContentCards = JPanel(CardLayout())
    private val settingsGrid = JPanel().also { it.layout = BoxLayout(it, BoxLayout.Y_AXIS) }
    private val settingsView = JPanel(BorderLayout())
    private var topView: JComponent? = null
    private val outputContainer = JPanel(BorderLayout())
    private val inputArea = JBTextArea()
    private val expectedArea = JBTextArea()
    private val actualArea = JBTextArea()
    private val stderrArea = JBTextArea()
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
        refreshLocalizedTexts(rebuildSettings = false)
        rebuildSettingsView()
        val builtTop = buildTop()
        topView = builtTop
        add(builtTop, BorderLayout.NORTH)
        add(buildCenter(), BorderLayout.CENTER)

        configureEnableCphButton()
        configureRunAllButton()
        configureResetCasesButton()
        configureRunSelectedCaseButton()
        configureDebugSelectedCaseButton()
        configureSubmitButton()
        configureHelpButton()
        configureTitleActionButton(codeforcesSubmitHelpButton, CODEFORCES_SUBMIT_HELP_BUTTON_WIDTH)
        configureTitleActionButton(inputCopyButton, TITLE_COPY_BUTTON_WIDTH)
        configureTitleActionButton(expectedCopyButton, TITLE_COPY_BUTTON_WIDTH)
        configureTitleActionButton(actualCopyButton, TITLE_COPY_BUTTON_WIDTH)
        configureTitleActionButton(actualToExpectedButton, TITLE_SET_EXPECTED_BUTTON_WIDTH)
        configureVerdictLabel()
        configureWorkingDirectoryChooserButton()

        enableCphButton.addActionListener { enableCphForProject() }
        runAllButton.addActionListener { runAllCases() }
        submitButton.addActionListener { CphSubmitOrchestrator.getInstance(project).submit() }
        helpButton.addActionListener { BrowserUtil.browse(CPH_DOCS_URL) }
        codeforcesSubmitHelpButton.addActionListener { BrowserUtil.browse(CPH_CODEFORCES_SUBMIT_DOCS_URL) }
        settingsTabButton.addActionListener { showSettingsTab(SettingsPanelTab.SETTINGS) }
        utilitySettingsTabButton.addActionListener { showSettingsTab(SettingsPanelTab.UTILITY) }
        themeSettingsTabButton.addActionListener { showSettingsTab(SettingsPanelTab.THEMES) }
        languageCombo.addActionListener { selectUiLanguage() }
        codeforcesPluginToggleButton.addActionListener { toggleCodeforcesSubmitPlugin() }
        eapRepositoryToggleButton.addActionListener { toggleEapRepository() }
        classicThemeToggleButton.addActionListener { selectPluginTheme(CphThemeId.CLASSIC) }
        aveMujicaThemeToggleButton.addActionListener { selectPluginTheme(CphThemeId.AVE_MUJICA) }
        resetCasesButton.addActionListener { resetCases() }
        runSelectedCaseButton.addActionListener { runSelectedCase() }
        debugSelectedCaseButton.addActionListener { debugSelectedCase() }
        inputCopyButton.addActionListener { copyEditorText(inputArea, inputCopyButton, CphText.current().input) }
        expectedCopyButton.addActionListener {
            copyEditorText(expectedArea, expectedCopyButton, CphText.current().expectedOutput)
        }
        actualCopyButton.addActionListener { copyEditorText(actualArea, actualCopyButton, CphText.current().standardOutput) }
        actualToExpectedButton.addActionListener { setActualOutputAsExpected() }
        singleFileModeEnabled.addActionListener {
            if (!applyingTargetSettings) {
                val enabled = singleFileModeEnabled.isSelected
                stateService.getState().singleFileModeEnabled = enabled
                refreshSubmitAvailability(singleFileModeEnabled = enabled)
                CphSingleFileModeService.getInstance(project).syncForCurrentFile(force = true)
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
        showStderrEnabled.addActionListener {
            if (!applyingTargetSettings) {
                stateService.getState().ui.showStderrEnabled = showStderrEnabled.isSelected
                rebuildOutputLayout()
            }
        }
        compactCaseTabsEnabled.addActionListener {
            if (!applyingTargetSettings) {
                stateService.getState().ui.compactCaseTabsEnabled = compactCaseTabsEnabled.isSelected
                refreshTabs {
                    revealCaseTab(selectedCase?.id)
                }
            }
        }
        confidentSubmitEnabled.addActionListener {
            if (!applyingTargetSettings) {
                stateService.getState().ui.confidentSubmitEnabled = confidentSubmitEnabled.isSelected
            }
        }
        parallelCaseRunEnabled.addActionListener {
            if (!applyingTargetSettings) {
                stateService.getState().ui.parallelCaseRunEnabled = parallelCaseRunEnabled.isSelected
            }
        }
        gccBitsPchEnabled.addActionListener {
            if (!applyingTargetSettings) {
                stateService.getState().compileSettings.gccBitsPchEnabled = gccBitsPchEnabled.isSelected
                syncCompileSettingsForCurrentTarget(reportStatus = true)
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
        stderrArea.isEditable = false
        listOf(inputArea, expectedArea, actualArea, stderrArea).forEach(::configureEditor)
        listOf(inputArea, expectedArea, actualArea, stderrArea).forEach(::installEditorFontWheel)
        listOf(expectedArea, actualArea).forEach(::installDiffRefreshListener)

        installTargetRefreshListeners()
        installPluginSettingsListeners()
        installSubmissionListeners()
        if (isCphEnabled()) {
            refreshTarget()
        } else {
            showActiveView()
        }
        refreshShortcutSettings()
        setSubmitBusy(false)
        refreshCodeforcesPluginUi()
        refreshSubmitFeatureVisibility()
        refreshSubmitButtonTooltip()
        if (CphBuildFeatures.aveMujicaThemeEnabled) {
            checkAveMujicaThemeUpdates()
        }
    }

    override fun dispose() {
        stopRunSpinner()
        submitSpinnerTimer.stop()
        hideSubmissionStatusTimer.stop()
        stopAveMujicaLineHighlightAnimation()
        stopAveMujicaStatusGlyphAnimation()
        iconAnimationTimers.values.forEach { it.stop() }
        iconAnimationTimers.clear()
        titleActionFeedbackTimers.values.forEach { it.stop() }
        titleActionFeedbackTimers.clear()
    }

    internal fun triggerShortcut(action: CphShortcutAction) {
        if (!isCphEnabled()) return
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

    fun showMainView() {
        settingsVisible = false
        showActiveView()
    }

    fun showSettingsView() {
        settingsVisible = true
        showActiveView()
    }

    fun toggleSettingsView() {
        settingsVisible = !settingsVisible
        showActiveView()
    }

    fun isSettingsViewVisible(): Boolean = settingsVisible

    private fun triggerShortcutAction(button: JButton, action: () -> Unit) {
        playThemedIconAnimation(button)
        action()
    }

    private fun initialTargetCases(identity: CphTargetIdentity): CphTargetCases {
        return if (isCphEnabled()) {
            stateService.getOrCreateTargetCases(identity)
        } else {
            CphTargetCases(targetId = identity.id, displayName = identity.displayName)
        }
    }

    private fun isCphEnabled(): Boolean = stateService.getState().cphEnabled

    private fun enableCphForProject() {
        if (!isCphEnabled()) {
            stateService.getState().cphEnabled = true
        }
        CphSingleFileModeService.getInstance(project).start()
        refreshTarget()
        showActiveView()
    }

    private fun installPluginSettingsListeners() {
        val connection = ApplicationManager.getApplication().messageBus.connect(this)
        connection.subscribe(CphPluginSettingsChangedListener.TOPIC, object : CphPluginSettingsChangedListener {
            override fun uiLanguageChanged() {
                SwingUtilities.invokeLater { rebuildLocalizedLayout() }
            }
        })
    }

    private fun selectUiLanguage() {
        if (applyingLanguageSettings) return
        val language = languageCombo.selectedItem as? CphUiLanguage ?: CphUiLanguage.ZH_CN
        CphPluginSettings.getInstance().setUiLanguage(language)
    }

    private fun refreshLocalizedTexts(rebuildSettings: Boolean = true) {
        val text = CphText.current()
        settingsTabButton.text = text.settingsTab
        utilitySettingsTabButton.text = text.utilityTab
        themeSettingsTabButton.text = text.themesTab
        enableCphButton.text = text.startCph
        singleFileModeEnabled.text = text.singleFileMode
        ignoreTrailingWhitespace.text = text.ignoreTrailingWhitespace
        outputSplitEnabled.text = text.outputSplit
        noExpectedModeEnabled.text = text.noExpectedMode
        showStderrEnabled.text = text.showStderr
        compactCaseTabsEnabled.text = text.compactCaseTabs
        confidentSubmitEnabled.text = text.confidentSubmit
        parallelCaseRunEnabled.text = text.parallelCaseRun
        parallelCaseRunEnabled.toolTipText = text.parallelCaseRunTooltip
        parallelCaseRunHelp.toolTipText = text.parallelCaseRunTooltip
        gccBitsPchEnabled.text = text.gccBitsPch
        val gccBitsTooltip = htmlTooltip(text.gccBitsPchTooltip)
        gccBitsPchEnabled.toolTipText = gccBitsTooltip
        gccBitsPchHelp.toolTipText = gccBitsTooltip
        runSelectedCaseButton.text = runSelectedButtonText()
        debugSelectedCaseButton.text = text.debug
        setTitleActionButtonText(inputCopyButton, text.copy)
        setTitleActionButtonText(expectedCopyButton, text.copy)
        setTitleActionButtonText(actualCopyButton, text.copy)
        setTitleActionButtonText(actualToExpectedButton, text.setExpectedShort)
        runAllButton.toolTipText = text.runAllEnabledCases
        resetCasesButton.toolTipText = text.resetCases
        runSelectedCaseButton.toolTipText = text.runThisCase
        debugSelectedCaseButton.toolTipText = text.debugThisCase
        inputCopyButton.toolTipText = text.copySection(text.input)
        expectedCopyButton.toolTipText = text.copySection(text.expectedOutput)
        actualCopyButton.toolTipText = text.copySection(text.standardOutput)
        actualToExpectedButton.toolTipText = text.setActualAsExpectedTooltip
        helpButton.toolTipText = text.cphDocs
        codeforcesSubmitHelpButton.toolTipText = text.codeforcesSubmitDocs
        setTitleActionButtonText(codeforcesSubmitHelpButton, "?")
        singleFileModeEnabled.toolTipText = text.singleFileTooltip
        singleFileWorkingDirectoryField.toolTipText = text.workingDirectoryTooltip
        singleFileWorkingDirectoryChooserButton.toolTipText = text.chooseWorkingDirectoryTooltip
        listOf(runAllShortcutField, runSelectedCaseShortcutField, debugSelectedCaseShortcutField, submitShortcutField)
            .forEach { it.refreshLocalizedText() }
        applyingLanguageSettings = true
        try {
            languageCombo.selectedItem = CphUiLanguage.current()
        } finally {
            applyingLanguageSettings = false
        }
        if (rebuildSettings) {
            rebuildSettingsGrid()
            refreshSettingsTabButtons()
            refreshCodeforcesPluginUi()
            refreshEapRepositoryUi()
            refreshThemePluginUi()
            refreshSettingsHelpButton()
            refreshSubmitButtonTooltip()
        }
    }

    private fun htmlTooltip(text: String): String {
        val escaped = text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
        return "<html>${escaped.replace("\n", "<br>")}</html>"
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

        compileDiagnosticsPanel.background = theme.surface
        compileDiagnosticsPanel.border = CompoundBorder(
            MatteBorder(1, 0, 1, 0, theme.run),
            EmptyBorder(4, 8, 4, 8),
        )
        compileDiagnosticsLabel.foreground = theme.run
        compileDiagnosticsPanel.add(compileDiagnosticsLabel, BorderLayout.CENTER)
        compileDiagnosticsPanel.isVisible = false
        compileDiagnosticsPanel.maximumSize = Dimension(Int.MAX_VALUE, 28)
        compileDiagnosticsPanel.alignmentX = Component.LEFT_ALIGNMENT

        top.add(toolbar)
        if (CphBuildFeatures.localDiagnosticsEnabled) {
            top.add(compileDiagnosticsPanel)
        }
        top.add(submissionStatusPanel)
        return top
    }

    private fun configureSubmitButton() {
        configureToolbarIconButton(submitButton, { theme.run })
    }

    private fun configureEnableCphButton() {
        enableCphButton.foreground = Color(0xF7FFF9)
        enableCphButton.background = theme.good
        enableCphButton.isOpaque = false
        enableCphButton.isContentAreaFilled = false
        enableCphButton.isBorderPainted = false
        enableCphButton.isFocusPainted = false
        enableCphButton.isRolloverEnabled = true
        enableCphButton.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        enableCphButton.font = decorativeFont(enableCphButton.font, Font.BOLD, 2.0f)
        enableCphButton.border = EmptyBorder(0, 0, 0, 0)
        enableCphButton.preferredSize = Dimension(JBUIScale.scale(156), JBUIScale.scale(46))
        enableCphButton.minimumSize = enableCphButton.preferredSize
        enableCphButton.maximumSize = enableCphButton.preferredSize
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
        applySubmitButtonAvailability(featureEnabled = featureEnabled)
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

    private fun applySubmitButtonAvailability(
        featureEnabled: Boolean = isCodeforcesSubmitPluginEnabled(),
        singleFileModeEnabled: Boolean = stateService.getState().singleFileModeEnabled,
    ) {
        submitButton.isVisible = featureEnabled
        submitButton.isEnabled = featureEnabled && !submitBusy && singleFileModeEnabled
    }

    private fun refreshSubmitButtonTooltip(singleFileModeEnabled: Boolean = stateService.getState().singleFileModeEnabled) {
        if (!isCodeforcesSubmitPluginEnabled()) {
            submitButton.toolTipText = null
            return
        }
        val tab = CphActiveTabService.getInstance().current()
        val ctx = tab?.let { CphSubmitContextResolver.resolve(it.url) }
        submitButton.toolTipText = when {
            !singleFileModeEnabled -> CphText.current().submitDisabledSingleFile
            ctx != null -> CphText.current().submitCurrentFile(ctx.displayId)
            tab != null -> CphText.current().activeTabNotCodeforces
            else -> CphText.current().noActiveCodeforcesTab
        }
    }

    private fun refreshSubmitFeatureVisibility() {
        val enabled = isCodeforcesSubmitPluginEnabled()
        applySubmitButtonAvailability(featureEnabled = enabled)
        if (!enabled) {
            hideSubmissionStatus()
        }
        setSubmitBusy(submitBusy)
        revalidate()
        repaint()
    }

    private fun refreshSubmitAvailability(singleFileModeEnabled: Boolean = stateService.getState().singleFileModeEnabled) {
        val featureEnabled = isCodeforcesSubmitPluginEnabled()
        applySubmitButtonAvailability(
            featureEnabled = featureEnabled,
            singleFileModeEnabled = singleFileModeEnabled,
        )
        refreshSubmitButtonTooltip(singleFileModeEnabled)
        revalidate()
        repaint()
    }

    private fun isCodeforcesSubmitPluginEnabled(): Boolean =
        CphCodeforcesSubmitFeature.isEnabled()

    private fun loadAveMujicaFont(): Font? {
        val stream = themeResourceStream(AVE_MUJICA_FONT_RESOURCE) ?: return null
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
        helpButton.isOpaque = highlighted
        helpButton.isContentAreaFilled = highlighted
        helpButton.isBorderPainted = false
        if (theme.id == CphThemeId.AVE_MUJICA) {
            helpButton.putClientProperty(AVE_MUJICA_ICON_NAME_PROPERTY, "help")
            helpButton.putClientProperty(AVE_MUJICA_ICON_SIZE_PROPERTY, settingsHelpIconSize())
            val icon = aveMujicaSettingsHelpIcon(pressed)
            helpButton.text = if (icon == null) CphText.current().help else null
            helpButton.icon = icon
            helpButton.iconTextGap = 0
            helpButton.font = decorativeFont(baseButtonFont(helpButton), Font.BOLD, 2.0f)
        } else {
            helpButton.putClientProperty(AVE_MUJICA_ICON_NAME_PROPERTY, null)
            helpButton.putClientProperty(AVE_MUJICA_ICON_SIZE_PROPERTY, null)
            helpButton.text = CphText.current().help
            helpButton.icon = HelpGlyphIcon(if (helpButton.isEnabled) theme.text else theme.muted, settingsClassicHelpIconSize())
            helpButton.iconTextGap = 6
            helpButton.font = actionButtonFont(helpButton)
        }
        val buttonSize = settingsHelpButtonSize()
        helpButton.preferredSize = buttonSize
        helpButton.minimumSize = buttonSize
        helpButton.maximumSize = buttonSize
        helpButton.revalidate()
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
            val insets = helpButton.insets
            val text = helpButton.text.orEmpty()
            val icon = helpButton.icon
            val iconWidth = icon?.iconWidth ?: 0
            val iconHeight = icon?.iconHeight ?: 0
            val textWidth = if (text.isNotEmpty()) helpButton.getFontMetrics(helpButton.font).stringWidth(text) else 0
            val textHeight = if (text.isNotEmpty()) helpButton.getFontMetrics(helpButton.font).height else 0
            val gap = if (iconWidth > 0 && text.isNotEmpty()) helpButton.iconTextGap else 0
            val width = insets.left + iconWidth + gap + textWidth + insets.right + 8
            val height = insets.top + maxOf(iconHeight, textHeight) + insets.bottom
            Dimension(width.coerceAtLeast(92), height.coerceAtLeast(32))
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
        }
    }

    private fun refreshThemedRunActionIcon(button: JButton, pressed: Boolean) {
        when (button) {
            runSelectedCaseButton -> setThemeButtonFace(button, "run_selected", pressed, runSelectedButtonText(), null, runActionIconSize())
            debugSelectedCaseButton -> setThemeButtonFace(button, "debug", pressed, CphText.current().debug, AllIcons.Actions.StartDebugger, runActionIconSize())
        }
    }

    private fun toolbarButtonSize(): Dimension {
        return Dimension(55, 45)
    }

    private fun toolbarIconSize(): Int {
        return if (theme.id == CphThemeId.AVE_MUJICA) aveMujicaIconSize(36) else 36
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
            runSelectedCaseButton -> CphText.current().run
            debugSelectedCaseButton -> CphText.current().debug
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
        val hasAnimation = themeResourceUrl(
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
        val url = themeResourceUrl(path) ?: return null
        val source = ImageIO.read(url) ?: return null
        val icon = HighQualityPngIcon(source, size, if (trimTransparentPadding) visibleIconSourceRect(source) else null)
        scaledIconCache[key] = icon
        return icon
    }

    private fun scaledCoreResourceIcon(path: String, size: Int, trimTransparentPadding: Boolean = false): Icon? {
        val key = "$path@$size@core@trim=$trimTransparentPadding"
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
        val url = themeResourceUrl(path) ?: return null
        val source = ImageIO.read(url) ?: return null
        val sourceRect = if (trimTransparentPadding) visibleContentSourceRect(source) else null
        val width = ((sourceRect?.width ?: source.width).toDouble() / (sourceRect?.height ?: source.height) * height)
            .roundToInt()
            .coerceAtLeast(1)
        val icon = HighQualityPngIcon(source, width, height, sourceRect)
        scaledIconCache[key] = icon
        return icon
    }

    private fun themeResourceUrl(path: String): java.net.URL? {
        val relativePath = path.removePrefix("/")
        if (relativePath.startsWith("icons/avemujica/") || relativePath == "fonts/AnglicanText.ttf") {
            return themeAssetService.resolve(CphThemeId.AVE_MUJICA, relativePath)
        }
        return CphToolWindowPanel::class.java.getResource(path)
    }

    private fun themeResourceStream(path: String) =
        themeResourceUrl(path)?.openStream()

    private fun clearAveMujicaResourceCaches() {
        scaledIconCache.keys.removeIf {
            it.startsWith("/icons/avemujica/") || it.startsWith("/fonts/AnglicanText.ttf")
        }
        generatedIconPresenceCache.clear()
        aveMujicaStatusGlyphCache.clear()
        aveMujicaLineHighlightTile = null
        aveMujicaFontLoaded = false
        aveMujicaFontCache = null
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

    private fun configureTitleActionButton(button: JButton, minWidth: Int) {
        button.foreground = theme.muted
        button.background = theme.panel
        button.isOpaque = false
        button.isContentAreaFilled = false
        button.isBorderPainted = false
        button.isFocusPainted = false
        button.isRolloverEnabled = true
        button.margin = Insets(0, 0, 0, 0)
        button.border = EmptyBorder(0, 4, 0, 4)
        button.font = baseButtonFont(button).deriveFont(Font.PLAIN, button.font.size2D - 1.0f)
        button.putClientProperty(TITLE_ACTION_MIN_WIDTH_PROPERTY, minWidth)
        resizeTitleActionButton(button)
        button.model.addChangeListener(ChangeListener { refreshTitleActionButton(button) })
        refreshTitleActionButton(button)
    }

    private fun setTitleActionButtonText(button: JButton, text: String) {
        button.putClientProperty(TITLE_ACTION_DEFAULT_TEXT_PROPERTY, text)
        if (button.getClientProperty(TITLE_ACTION_FEEDBACK_PROPERTY) != true) {
            button.text = text
        }
        resizeTitleActionButton(button)
    }

    private fun resizeTitleActionButton(button: JButton) {
        val minWidth = button.getClientProperty(TITLE_ACTION_MIN_WIDTH_PROPERTY) as? Int ?: TITLE_ACTION_MIN_WIDTH
        val metrics = button.getFontMetrics(button.font)
        val textWidth = metrics.stringWidth(button.text.orEmpty())
        val width = maxOf(minWidth, textWidth + TITLE_ACTION_HORIZONTAL_PADDING * 2)
        val height = maxOf(TITLE_ACTION_BUTTON_HEIGHT, metrics.height + TITLE_ACTION_VERTICAL_PADDING * 2)
        val size = Dimension(width, height)
        button.preferredSize = size
        button.minimumSize = size
        button.maximumSize = size
        button.revalidate()
    }

    private fun refreshTitleActionButton(button: JButton) {
        val model = button.model
        val pressed = model.isPressed && model.isArmed
        val success = button.getClientProperty(TITLE_ACTION_FEEDBACK_PROPERTY) == true
        button.foreground = when {
            !button.isEnabled -> theme.muted
            success -> theme.good
            model.isRollover -> theme.text
            else -> theme.muted
        }
        button.background = when {
            !button.isEnabled -> theme.panel
            pressed -> theme.actionPressed
            model.isRollover || success -> theme.actionHover
            else -> theme.panel
        }
        val highlighted = button.isEnabled && (pressed || model.isRollover || success)
        button.isOpaque = highlighted
        button.isContentAreaFilled = highlighted
        button.border = EmptyBorder(0, 4, 0, 4)
        button.repaint()
    }

    private fun buildCenter(): JComponent {
        contentCards.background = theme.panel
        contentCards.add(buildOnboardingView(), ONBOARDING_VIEW_CARD)
        contentCards.add(buildMainView(), MAIN_VIEW_CARD)
        contentCards.add(settingsView, SETTINGS_VIEW_CARD)
        showActiveView()
        return contentCards
    }

    private fun buildOnboardingView(): JComponent {
        val view = JPanel(GridBagLayout())
        view.background = theme.panel

        val content = JPanel()
        content.layout = BoxLayout(content, BoxLayout.Y_AXIS)
        content.background = theme.panel

        val icon = IconLoader.getIcon("/META-INF/pluginIcon.svg", CphToolWindowPanel::class.java)
        val iconLabel = JBLabel(IconUtil.scale(icon, null, WELCOME_ICON_SCALE)).also {
            it.alignmentX = Component.CENTER_ALIGNMENT
        }
        enableCphButton.alignmentX = Component.CENTER_ALIGNMENT

        content.add(iconLabel)
        content.add(Box.createVerticalStrut(JBUIScale.scale(22)))
        content.add(enableCphButton)

        val constraints = GridBagConstraints().also {
            it.gridx = 0
            it.gridy = 0
            it.weightx = 1.0
            it.weighty = 1.0
            it.anchor = GridBagConstraints.NORTH
            it.insets = Insets(JBUIScale.scale(96), 0, 0, 0)
        }
        view.add(content, constraints)
        return view
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
        if (compactCaseTabsEnabled()) return COMPACT_TAB_STRIP_BASE_HEIGHT
        return if (theme.id == CphThemeId.AVE_MUJICA) AVE_MUJICA_TAB_STRIP_BASE_HEIGHT else TAB_STRIP_BASE_HEIGHT
    }

    private fun compactCaseTabsEnabled(): Boolean = stateService.getState().ui.compactCaseTabsEnabled

    private fun rebuildSettingsView() {
        activeSettingsTab = normalizeSettingsTab(activeSettingsTab)
        settingsView.removeAll()
        settingsView.background = theme.panel
        settingsContentCards.background = theme.panel
        settingsContentCards.removeAll()
        settingsContentCards.add(buildSettingsFormView(), SettingsPanelTab.SETTINGS.cardName)
        if (CphBuildFeatures.utilitySettingsEnabled) {
            settingsContentCards.add(buildPluginUtilityView(), SettingsPanelTab.UTILITY.cardName)
        }
        if (CphBuildFeatures.themeSettingsEnabled) {
            settingsContentCards.add(buildPluginThemesView(), SettingsPanelTab.THEMES.cardName)
        }
        showCurrentSettingsTabCard()
        refreshSettingsTabButtons()
        if (visibleSettingsTabCount() > 1) {
            settingsView.add(buildSettingsTabStrip(), BorderLayout.NORTH)
        }
        settingsView.add(settingsContentCards, BorderLayout.CENTER)
        settingsView.revalidate()
        settingsView.repaint()
    }

    private fun buildSettingsFormView(): JComponent {
        singleFileModeEnabled.isOpaque = false
        ignoreTrailingWhitespace.isOpaque = false
        outputSplitEnabled.isOpaque = false
        noExpectedModeEnabled.isOpaque = false
        showStderrEnabled.isOpaque = false
        compactCaseTabsEnabled.isOpaque = false
        confidentSubmitEnabled.isOpaque = false
        parallelCaseRunEnabled.isOpaque = false
        gccBitsPchEnabled.isOpaque = false
        parallelCaseRunHelp.foreground = theme.run
        parallelCaseRunHelp.horizontalAlignment = SwingConstants.CENTER
        parallelCaseRunHelp.preferredSize = Dimension(JBUIScale.scale(18), parallelCaseRunHelp.preferredSize.height)
        gccBitsPchHelp.foreground = theme.run
        gccBitsPchHelp.horizontalAlignment = SwingConstants.CENTER
        gccBitsPchHelp.preferredSize = Dimension(JBUIScale.scale(18), gccBitsPchHelp.preferredSize.height)
        languageCombo.background = theme.surface
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
        settingsGrid.border = EmptyBorder(12, 0, 0, 0)
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
        val text = CphText.current()
        settingsGrid.add(settingsSection(text.general) {
            settingRow(text.interfaceLanguage, languageCombo)
            settingCheckBoxGroupRow(text.workMode, singleFileModeEnabled)
        })
        settingsGrid.add(Box.createVerticalStrut(14))
        settingsGrid.add(settingsSection(text.compileAndRun) {
            settingRow(text.workingDirectory, singleFileWorkingDirectoryControl())
            settingRow(text.timeLimits, shortSpinnerControl(timeoutSpinner, "ms"))
            settingRow("", parallelCaseRunControl())
            settingRow("", gccBitsPchControl())
            settingRow(text.cppStandard, cppStandardCombo)
            compileOptionsField.preferredSize = Dimension(260, compileOptionsField.preferredSize.height)
            settingRow(text.compileOptions, compileOptionsField)
        })
        settingsGrid.add(Box.createVerticalStrut(14))
        settingsGrid.add(settingsSection(text.outputAndDisplay) {
            settingSubheading(text.outputComparison)
            settingCheckBoxRow(ignoreTrailingWhitespace)
            settingCheckBoxRow(noExpectedModeEnabled)
            settingSubheading(text.displayMode)
            settingCheckBoxRow(outputSplitEnabled)
            settingCheckBoxRow(showStderrEnabled)
            settingCheckBoxRow(compactCaseTabsEnabled)
            settingRow(text.displayFontSize, shortSpinnerControl(editorFontSizeSpinner))
        })
        if (isCodeforcesSubmitPluginEnabled()) {
            settingsGrid.add(Box.createVerticalStrut(14))
            settingsGrid.add(settingsSection(text.submitSettings) {
                settingCheckBoxRow(confidentSubmitEnabled)
            })
        }
        settingsGrid.add(Box.createVerticalStrut(14))
        settingsGrid.add(settingsSection(text.shortcuts) {
            settingRow("${text.runAllShortcut}:", runAllShortcutField)
            settingRow("${text.runSelectedShortcut}:", runSelectedCaseShortcutField)
            settingRow("${text.debugSelectedShortcut}:", debugSelectedCaseShortcutField)
            if (isCodeforcesSubmitPluginEnabled()) {
                settingRow("${text.submitShortcut}:", submitShortcutField)
            }
        })
        settingsGrid.revalidate()
        settingsGrid.repaint()
    }

    private fun buildSettingsTabStrip(): JComponent {
        configureSettingsTabButton(settingsTabButton)
        if (CphBuildFeatures.utilitySettingsEnabled) {
            configureSettingsTabButton(utilitySettingsTabButton)
        }
        if (CphBuildFeatures.themeSettingsEnabled) {
            configureSettingsTabButton(themeSettingsTabButton)
        }

        return JPanel(FlowLayout(FlowLayout.LEFT, 8, 6)).also {
            it.background = theme.panel
            it.border = MatteBorder(1, 0, 1, 0, theme.border)
            it.add(settingsTabButton)
            if (CphBuildFeatures.utilitySettingsEnabled) {
                it.add(utilitySettingsTabButton)
            }
            if (CphBuildFeatures.themeSettingsEnabled) {
                it.add(themeSettingsTabButton)
            }
        }
    }

    private fun visibleSettingsTabCount(): Int {
        return 1 +
            (if (CphBuildFeatures.utilitySettingsEnabled) 1 else 0) +
            (if (CphBuildFeatures.themeSettingsEnabled) 1 else 0)
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
            it.add(Box.createVerticalStrut(8))
            it.add(buildEapRepositoryRow())
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
        val text = CphText.current()
        val icon = JBLabel(IconLoader.getIcon("/icons/codeforces.svg", CphToolWindowPanel::class.java)).also {
            it.horizontalAlignment = SwingConstants.CENTER
            it.preferredSize = Dimension(70, 64)
            it.minimumSize = it.preferredSize
            it.maximumSize = it.preferredSize
        }
        val title = JBLabel(text.codeforcesSubmitTitle).also {
            it.foreground = theme.text
            it.font = it.font.deriveFont(Font.BOLD)
            it.alignmentX = Component.LEFT_ALIGNMENT
        }
        val summary = JBTextArea(text.codeforcesSubmitSummary).also {
            it.foreground = theme.muted
            it.background = theme.surface
            it.isOpaque = false
            it.isEditable = false
            it.lineWrap = true
            it.wrapStyleWord = true
            it.border = EmptyBorder(0, 0, 0, 0)
            it.alignmentX = Component.LEFT_ALIGNMENT
        }
        val textPanel = JPanel().also {
            it.layout = BoxLayout(it, BoxLayout.Y_AXIS)
            it.background = theme.surface
            it.alignmentX = Component.LEFT_ALIGNMENT
            it.add(JPanel().also { row ->
                row.layout = BoxLayout(row, BoxLayout.X_AXIS)
                row.background = theme.surface
                row.isOpaque = false
                row.alignmentX = Component.LEFT_ALIGNMENT
                row.add(title)
                row.add(Box.createHorizontalStrut(6))
                row.add(codeforcesSubmitHelpButton)
                row.add(Box.createHorizontalGlue())
            })
            it.add(Box.createVerticalStrut(4))
            it.add(summary)
        }
        val left = JPanel(BorderLayout(12, 0)).also {
            it.background = theme.surface
            it.add(icon, BorderLayout.WEST)
            it.add(textPanel, BorderLayout.CENTER)
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
            refreshTitleActionButton(codeforcesSubmitHelpButton)
            refreshCodeforcesPluginUi()
        }
    }

    private fun buildEapRepositoryRow(): JComponent {
        val text = CphText.current()
        val icon = JBLabel(scaledResourceIcon("/icons/plugin-eap.png", 54)).also {
            it.horizontalAlignment = SwingConstants.CENTER
            it.preferredSize = Dimension(70, 64)
            it.minimumSize = it.preferredSize
            it.maximumSize = it.preferredSize
        }
        val title = JBLabel(text.eapRepositoryTitle).also {
            it.foreground = theme.text
            it.font = it.font.deriveFont(Font.BOLD)
            it.alignmentX = Component.LEFT_ALIGNMENT
        }
        val summary = JBTextArea(text.eapRepositorySummary).also {
            it.foreground = theme.muted
            it.background = theme.surface
            it.isOpaque = false
            it.isEditable = false
            it.lineWrap = true
            it.wrapStyleWord = true
            it.border = EmptyBorder(0, 0, 0, 0)
            it.alignmentX = Component.LEFT_ALIGNMENT
        }
        val textPanel = JPanel().also {
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
            it.add(textPanel, BorderLayout.CENTER)
        }
        val right = JPanel(GridBagLayout()).also {
            it.background = theme.surface
            it.preferredSize = Dimension(88, 42)
            it.minimumSize = it.preferredSize
            it.add(eapRepositoryToggleButton)
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
            refreshEapRepositoryUi()
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
        val text = CphText.current()
        codeforcesPluginToggleButton.text = if (enabled) text.disable else text.enable
        codeforcesPluginToggleButton.toolTipText = if (enabled) {
            text.codeforcesDisableTooltip
        } else {
            text.codeforcesEnableTooltip
        }
        codeforcesPluginToggleButton.foreground = if (enabled) theme.bad else theme.good
        codeforcesPluginToggleButton.background = theme.surface
        codeforcesPluginToggleButton.isOpaque = false
        codeforcesPluginToggleButton.isContentAreaFilled = false
        codeforcesPluginToggleButton.isBorderPainted = true
        codeforcesPluginToggleButton.isFocusPainted = false
    }

    private fun toggleEapRepository() {
        try {
            if (CphEapRepositoryService.isEapRepositoryEnabled()) {
                CphEapRepositoryService.disableEapRepository()
                showEapRepositoryNotification(CphText.current().eapRepositoryDisabledNotification, NotificationType.INFORMATION)
            } else {
                CphEapRepositoryService.enableEapRepository()
                showEapRepositoryNotification(CphText.current().eapRepositoryEnabledNotification, NotificationType.INFORMATION)
            }
        } catch (error: Throwable) {
            showEapRepositoryNotification(
                CphText.current().eapRepositoryUpdateFailed(error.message ?: error.javaClass.simpleName),
                NotificationType.ERROR,
            )
        }
        refreshEapRepositoryUi()
    }

    private fun refreshEapRepositoryUi() {
        val enabled = runCatching { CphEapRepositoryService.isEapRepositoryEnabled() }.getOrDefault(false)
        val text = CphText.current()
        eapRepositoryToggleButton.text = if (enabled) text.disable else text.enable
        eapRepositoryToggleButton.toolTipText = if (enabled) {
            text.eapRepositoryDisableTooltip
        } else {
            text.eapRepositoryEnableTooltip
        }
        eapRepositoryToggleButton.foreground = if (enabled) theme.bad else theme.good
        eapRepositoryToggleButton.background = theme.surface
        eapRepositoryToggleButton.isOpaque = false
        eapRepositoryToggleButton.isContentAreaFilled = false
        eapRepositoryToggleButton.isBorderPainted = true
        eapRepositoryToggleButton.isFocusPainted = false
    }

    private fun showEapRepositoryNotification(message: String, type: NotificationType) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup(CPH_NOTIFICATION_GROUP_ID)
            .createNotification(message, type)
            .notify(project)
    }

    private fun buildClassicThemeRow(): JComponent {
        return buildThemeRow(
            palette = CphThemes.classic,
            summary = CphText.current().classicThemeSummary,
            icon = IconLoader.getIcon("/icons/cphToolWindow.svg", CphToolWindowPanel::class.java),
            toggleButton = classicThemeToggleButton,
        )
    }

    private fun buildAveMujicaThemeRow(): JComponent {
        return buildThemeRow(
            palette = CphThemes.aveMujica,
            summary = CphText.current().aveMujicaThemeSummary,
            icon = scaledResourceIcon("/icons/avemujica/generated/512/theme/normal.png", aveMujicaIconSize(46))
                ?: scaledCoreResourceIcon(
                    AVE_MUJICA_THEME_PREVIEW_RESOURCE,
                    aveMujicaIconSize(46),
                    trimTransparentPadding = true,
                )
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
        val title = JBLabel(CphText.current().themeName(palette.id)).also {
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
        if (!CphBuildFeatures.themeSettingsEnabled && normalizedThemeId != CphThemeId.CLASSIC) {
            return
        }
        if (normalizedThemeId == CphThemeId.AVE_MUJICA) {
            handleAveMujicaThemeAction()
            return
        }
        val state = CphPluginSettings.getInstance().state
        if (state.selectedThemeId == normalizedThemeId) {
            refreshThemePluginUi()
            return
        }
        state.selectedThemeId = normalizedThemeId
        rebuildThemedLayout()
    }

    private fun handleAveMujicaThemeAction() {
        when (themeAssetService.state(CphThemeId.AVE_MUJICA).status) {
            CphThemePackageStatus.NOT_INSTALLED,
            CphThemePackageStatus.UPDATE_AVAILABLE,
            CphThemePackageStatus.FAILED -> installOrUpdateAveMujicaTheme()

            CphThemePackageStatus.INCOMPATIBLE -> {
                showThemeNotification(
                    themeAssetService.state(CphThemeId.AVE_MUJICA).message
                        ?: CphText.current().aveMujicaThemeNotInstalled,
                    NotificationType.WARNING,
                )
                refreshThemePluginUi()
            }

            CphThemePackageStatus.DOWNLOADING -> refreshThemePluginUi()

            CphThemePackageStatus.INSTALLED -> {
                val state = CphPluginSettings.getInstance().state
                if (state.selectedThemeId != CphThemeId.AVE_MUJICA) {
                    state.selectedThemeId = CphThemeId.AVE_MUJICA
                    clearAveMujicaResourceCaches()
                    rebuildThemedLayout()
                } else {
                    refreshThemePluginUi()
                }
            }
        }
    }

    private fun installOrUpdateAveMujicaTheme() {
        themeAssetService.installOrUpdateAveMujica(project) { installed ->
            clearAveMujicaResourceCaches()
            if (installed) {
                CphPluginSettings.getInstance().state.selectedThemeId = CphThemeId.AVE_MUJICA
                rebuildThemedLayout()
            } else {
                refreshThemePluginUi()
            }
        }
        refreshThemePluginUi()
    }

    private fun checkAveMujicaThemeUpdates() {
        if (!CphBuildFeatures.aveMujicaThemeEnabled) return
        themeAssetService.checkForUpdatesAsync {
            refreshThemePluginUi()
            if (activeSettingsTab == SettingsPanelTab.THEMES) {
                rebuildSettingsGrid()
            }
        }
    }

    private fun showThemeNotification(message: String, type: NotificationType) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup(CPH_NOTIFICATION_GROUP_ID)
            .createNotification(message, type)
            .notify(project)
    }

    private fun refreshThemePluginUi() {
        if (!CphBuildFeatures.themeSettingsEnabled) return
        val selectedThemeId = CphThemeId.normalize(CphPluginSettings.getInstance().state.selectedThemeId)
        val classicSelected = selectedThemeId == CphThemeId.CLASSIC
        val aveMujicaState = themeAssetService.state(CphThemeId.AVE_MUJICA)
        val aveMujicaSelected = selectedThemeId == CphThemeId.AVE_MUJICA && aveMujicaState.installed
        refreshThemeToggleButton(
            classicThemeToggleButton,
            classicSelected || selectedThemeId == CphThemeId.AVE_MUJICA && !aveMujicaState.installed,
            CphText.current().defaultThemeEnabled,
            CphText.current().enableDefaultTheme,
        )
        refreshAveMujicaThemeToggleButton(
            aveMujicaThemeToggleButton,
            aveMujicaSelected,
            aveMujicaState,
        )
    }

    private fun refreshAveMujicaThemeToggleButton(
        button: JButton,
        selected: Boolean,
        packageState: CphThemePackageState,
    ) {
        val text = CphText.current()
        when (packageState.status) {
            CphThemePackageStatus.NOT_INSTALLED -> {
                button.text = text.install
                button.toolTipText = text.aveMujicaThemeNotInstalled
                button.foreground = theme.good
                button.isEnabled = !running
            }

            CphThemePackageStatus.UPDATE_AVAILABLE -> {
                button.text = text.update
                button.toolTipText = text.aveMujicaThemeUpdateAvailable
                button.foreground = theme.warn
                button.isEnabled = !running
            }

            CphThemePackageStatus.FAILED -> {
                button.text = text.retry
                button.toolTipText = packageState.message ?: text.aveMujicaThemeNotInstalled
                button.foreground = theme.bad
                button.isEnabled = !running
            }

            CphThemePackageStatus.DOWNLOADING -> {
                button.text = text.installing
                button.toolTipText = text.installingAveMujicaTheme
                button.foreground = theme.muted
                button.isEnabled = false
            }

            CphThemePackageStatus.INCOMPATIBLE -> {
                button.text = text.update
                button.toolTipText = packageState.message ?: text.aveMujicaThemeUpdateAvailable
                button.foreground = theme.muted
                button.isEnabled = false
            }

            CphThemePackageStatus.INSTALLED -> {
                button.text = if (selected) text.enabled else text.enable
                button.toolTipText = if (selected) text.aveMujicaThemeEnabled else text.enableAveMujicaTheme
                button.foreground = if (selected) theme.run else theme.good
                button.isEnabled = !running && !selected
            }
        }
        button.background = theme.surface
        button.isOpaque = false
        button.isContentAreaFilled = false
        button.isBorderPainted = true
        button.isFocusPainted = false
    }

    private fun refreshThemeToggleButton(
        button: JButton,
        selected: Boolean,
        selectedTooltip: String,
        availableTooltip: String,
    ) {
        val text = CphText.current()
        button.text = if (selected) text.enabled else text.enable
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
        listOf(inputArea, expectedArea, actualArea, stderrArea).forEach(::configureEditor)
        contentCards.removeAll()
        settingsContentCards.removeAll()
        tabStrip.removeAll()
        rebuildSettingsView()
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

    private fun rebuildLocalizedLayout() {
        if (running) {
            refreshLocalizedTexts()
            rebuildOutputLayout()
            refreshTabs()
            updateActions()
            return
        }
        flushSelectedCase()
        refreshLocalizedTexts(rebuildSettings = false)
        rebuildThemedLayout()
    }

    private fun showSettingsTab(tab: SettingsPanelTab) {
        activeSettingsTab = normalizeSettingsTab(tab)
        showCurrentSettingsTabCard()
        refreshSettingsTabButtons()
        settingsContentCards.revalidate()
        settingsContentCards.repaint()
    }

    private fun showCurrentSettingsTabCard() {
        activeSettingsTab = normalizeSettingsTab(activeSettingsTab)
        (settingsContentCards.layout as? CardLayout)?.show(settingsContentCards, activeSettingsTab.cardName)
    }

    private fun refreshSettingsTabButtons() {
        activeSettingsTab = normalizeSettingsTab(activeSettingsTab)
        refreshSettingsTabButton(settingsTabButton, activeSettingsTab == SettingsPanelTab.SETTINGS)
        utilitySettingsTabButton.isVisible = CphBuildFeatures.utilitySettingsEnabled
        themeSettingsTabButton.isVisible = CphBuildFeatures.themeSettingsEnabled
        if (CphBuildFeatures.utilitySettingsEnabled) {
            refreshSettingsTabButton(utilitySettingsTabButton, activeSettingsTab == SettingsPanelTab.UTILITY)
        }
        if (CphBuildFeatures.themeSettingsEnabled) {
            refreshSettingsTabButton(themeSettingsTabButton, activeSettingsTab == SettingsPanelTab.THEMES)
        }
    }

    private fun normalizeSettingsTab(tab: SettingsPanelTab): SettingsPanelTab {
        return when (tab) {
            SettingsPanelTab.SETTINGS -> SettingsPanelTab.SETTINGS
            SettingsPanelTab.UTILITY -> if (CphBuildFeatures.utilitySettingsEnabled) tab else SettingsPanelTab.SETTINGS
            SettingsPanelTab.THEMES -> if (CphBuildFeatures.themeSettingsEnabled) tab else SettingsPanelTab.SETTINGS
        }
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

    private fun showActiveView() {
        val card = when {
            settingsVisible -> SETTINGS_VIEW_CARD
            !isCphEnabled() -> ONBOARDING_VIEW_CARD
            else -> MAIN_VIEW_CARD
        }
        topView?.isVisible = isCphEnabled() && !settingsVisible
        (contentCards.layout as? CardLayout)?.show(contentCards, card)
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
            .withTitle(CphText.current().chooseWorkingDirectory)
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
            language = CphUiLanguage.current(),
        )
        if (duplicateMessage != null) {
            StatusBar.Info.set(CphText.current().shortcutSettingsDuplicate(duplicateMessage), project)
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
            it.background = theme.panel
            it.isOpaque = false
            it.content()
        }
        val header = JPanel(BorderLayout(10, 0)).also {
            it.background = theme.panel
            it.isOpaque = false
            it.border = MatteBorder(0, 0, 1, 0, theme.border)
            it.add(JBLabel(title).also { label ->
                label.foreground = theme.text
                label.font = label.font.deriveFont(Font.BOLD)
                label.border = EmptyBorder(0, 0, 6, 0)
            }, BorderLayout.WEST)
            headerRight?.let { right ->
                right.background = theme.panel
                it.add(right, BorderLayout.EAST)
            }
        }
        return JPanel(BorderLayout(0, 10)).also {
            it.background = theme.panel
            it.border = EmptyBorder(0, 12, 0, 12)
            it.alignmentX = Component.LEFT_ALIGNMENT
            it.add(header, BorderLayout.NORTH)
            it.add(body, BorderLayout.CENTER)
            val preferred = it.preferredSize
            it.maximumSize = Dimension(Int.MAX_VALUE, preferred.height)
        }
    }

    private fun JPanel.settingRow(label: String, component: JComponent) {
        val row = nextSettingRow()
        add(settingLabel(label), settingConstraints(row, 0, weightx = 0.0))
        add(component, settingConstraints(row, 1, weightx = 1.0))
    }

    private fun JPanel.settingCheckBoxRow(checkBox: JCheckBox) {
        val row = nextSettingRow()
        checkBox.isOpaque = false
        add(checkBox, settingConstraints(row, 0, gridwidth = 2, weightx = 1.0))
    }

    private fun JPanel.settingCheckBoxGroupRow(label: String, vararg checkBoxes: JCheckBox) {
        val row = nextSettingRow()
        val group = JPanel().also {
            it.layout = BoxLayout(it, BoxLayout.Y_AXIS)
            it.background = theme.panel
            it.isOpaque = false
            checkBoxes.forEachIndexed { index, checkBox ->
                checkBox.isOpaque = false
                checkBox.alignmentX = Component.LEFT_ALIGNMENT
                it.add(checkBox)
                if (index < checkBoxes.lastIndex) {
                    it.add(Box.createVerticalStrut(4))
                }
            }
        }
        add(settingLabel(label), settingConstraints(row, 0, weightx = 0.0))
        add(group, settingConstraints(row, 1, weightx = 1.0))
    }

    private fun JPanel.settingSubheading(text: String) {
        val row = nextSettingRow()
        add(JBLabel(text.trimEnd(':', '：')).also {
            it.foreground = theme.muted
            it.font = it.font.deriveFont(Font.BOLD, it.font.size2D - 1.0f)
            it.border = EmptyBorder(8, 0, 2, 0)
        }, settingConstraints(row, 0, gridwidth = 2, weightx = 1.0))
    }

    private fun settingLabel(text: String): JBLabel {
        return JBLabel(text).also {
            it.foreground = theme.text
            it.preferredSize = Dimension(JBUIScale.scale(SETTING_LABEL_WIDTH), it.preferredSize.height)
            it.minimumSize = Dimension(JBUIScale.scale(SETTING_LABEL_WIDTH), it.minimumSize.height)
            it.horizontalAlignment = SwingConstants.LEFT
        }
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
            insets = Insets(4, 0, 4, if (column == 0 && gridwidth == 1) 14 else 0)
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

    private fun shortSpinnerControl(spinner: JSpinner, suffix: String? = null): JComponent {
        return JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).also {
            spinner.preferredSize = Dimension(JBUIScale.scale(110), spinner.preferredSize.height)
            spinner.minimumSize = Dimension(JBUIScale.scale(90), spinner.minimumSize.height)
            it.background = theme.panel
            it.isOpaque = false
            it.add(spinner)
            suffix?.let { suffixText ->
                it.add(Box.createHorizontalStrut(8))
                it.add(JBLabel(suffixText).also { label -> label.foreground = theme.text })
            }
        }
    }

    private fun parallelCaseRunControl(): JComponent {
        return JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).also {
            it.background = theme.panel
            it.isOpaque = false
            it.add(parallelCaseRunEnabled)
            it.add(Box.createHorizontalStrut(6))
            it.add(parallelCaseRunHelp)
        }
    }

    private fun gccBitsPchControl(): JComponent {
        return JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).also {
            it.background = theme.panel
            it.isOpaque = false
            it.add(gccBitsPchEnabled)
            it.add(Box.createHorizontalStrut(6))
            it.add(gccBitsPchHelp)
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
                    CphText.current().input,
                    scroll(inputArea, uiState.inputHeight),
                    uiState.inputHeight,
                    titleInlineAction = titleActionGroup(inputCopyButton),
                    titleAction = inputActions(),
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
        showStderrEnabled.isSelected = uiState.showStderrEnabled
        compactCaseTabsEnabled.isSelected = uiState.compactCaseTabsEnabled
        confidentSubmitEnabled.isSelected = uiState.confidentSubmitEnabled
        parallelCaseRunEnabled.isSelected = uiState.parallelCaseRunEnabled
        outputSplitEnabled.isEnabled = !running && !uiState.noExpectedModeEnabled

        val mainOutputView = when {
            uiState.noExpectedModeEnabled -> singleActualOutput(uiState)
            uiState.outputSplitEnabled -> horizontalOutputSplit(uiState)
            else -> verticalOutputSplit()
        }
        val outputView = if (uiState.showStderrEnabled) {
            outputWithStderr(mainOutputView, uiState)
        } else {
            mainOutputView
        }
        outputContainer.add(outputView, BorderLayout.CENTER)
        outputContainer.revalidate()
        outputContainer.repaint()
    }

    private fun singleActualOutput(uiState: CphUiState): JComponent {
        return labeledOutput(
            CphText.current().standardOutput,
            scroll(actualArea, uiState.actualHeight),
            titleActionGroup(actualCopyButton, actualToExpectedButton),
        )
    }

    private fun horizontalOutputSplit(uiState: CphUiState): JSplitPane {
        return outputSplitPane(
            orientation = JSplitPane.HORIZONTAL_SPLIT,
            first = labeledOutput(
                CphText.current().standardOutput,
                scroll(actualArea, uiState.actualHeight),
                titleActionGroup(actualCopyButton, actualToExpectedButton),
            ),
            second = labeledOutput(
                CphText.current().expectedOutput,
                scroll(expectedArea, uiState.expectedHeight),
                titleActionGroup(expectedCopyButton),
            ),
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
            first = labeledOutput(
                CphText.current().expectedOutput,
                scroll(expectedArea, uiState.expectedHeight),
                titleActionGroup(expectedCopyButton),
            ),
            second = labeledOutput(
                CphText.current().standardOutput,
                scroll(actualArea, uiState.actualHeight),
                titleActionGroup(actualCopyButton, actualToExpectedButton),
            ),
        ).also { splitPane ->
            splitPane.resizeWeight = 0.5
            SwingUtilities.invokeLater { splitPane.setDividerLocation(0.5) }
        }
    }

    private fun outputWithStderr(mainOutputView: JComponent, uiState: CphUiState): JSplitPane {
        return outputSplitPane(
            orientation = JSplitPane.VERTICAL_SPLIT,
            first = mainOutputView,
            second = labeledOutput(
                CphText.current().standardError,
                scroll(stderrArea, uiState.actualHeight),
            ),
        ).also { splitPane ->
            splitPane.resizeWeight = 0.72
            SwingUtilities.invokeLater { splitPane.setDividerLocation(0.72) }
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

    private fun labeledOutput(label: String, component: JComponent, titleInlineAction: JComponent? = null): JComponent {
        return JPanel(BorderLayout(0, 6)).also {
            it.background = theme.panel
            it.isOpaque = true
            it.border = EmptyBorder(0, 0, 0, 0)
            it.minimumSize = Dimension(0, 0)
            it.add(titleHeader(label, titleInlineAction), BorderLayout.NORTH)
            it.add(component, BorderLayout.CENTER)
        }
    }

    private fun resizableLabeled(
        label: String,
        component: JComponent,
        height: Int,
        titleInlineAction: JComponent? = null,
        titleAction: JComponent? = null,
        onHeightChanged: (Int) -> Unit,
    ): JComponent {
        return ResizableEditorSection(label, component, height, titleInlineAction, titleAction, onHeightChanged)
    }

    private fun titleHeader(
        label: String,
        titleInlineAction: JComponent? = null,
        titleAction: JComponent? = null,
    ): JComponent {
        return JPanel(BorderLayout(8, 0)).also { row ->
            row.background = theme.panel
            row.isOpaque = false
            val left = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).also {
                it.background = theme.panel
                it.isOpaque = false
                it.add(JBLabel(label).also { title -> title.foreground = theme.text })
                titleInlineAction?.let { action ->
                    it.add(Box.createHorizontalStrut(8))
                    it.add(action)
                }
            }
            row.add(left, BorderLayout.WEST)
            titleAction?.let { row.add(it, BorderLayout.EAST) }
        }
    }

    private fun titleActionGroup(vararg buttons: JButton): JComponent {
        return JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).also {
            it.background = theme.panel
            it.isOpaque = false
            buttons.forEach(it::add)
        }
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
        titleInlineAction: JComponent?,
        titleAction: JComponent?,
        private val onHeightChanged: (Int) -> Unit,
    ) : JPanel(BorderLayout(0, 6)) {
        private val titleRow = titleHeader(label, titleInlineAction, titleAction)
        private val dragHandle = ResizeHandle()
        private var editorHeight = CphStateService.clampEditorHeight(initialHeight)
        private var dragStartY = 0
        private var dragStartHeight = editorHeight

        init {
            background = theme.panel
            border = EmptyBorder(0, 0, 0, 0)
            alignmentX = Component.LEFT_ALIGNMENT

            val editorContainer = JPanel(BorderLayout())
            editorContainer.background = theme.panel
            editorContainer.add(component, BorderLayout.CENTER)
            editorContainer.add(dragHandle, BorderLayout.SOUTH)

            add(titleRow, BorderLayout.NORTH)
            add(editorContainer, BorderLayout.CENTER)

            dragHandle.toolTipText = CphText.current().dragToResize
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
            if (event.isControlDown) {
                event.consume()
                val delta = if (event.preciseWheelRotation < 0.0) 1 else -1
                val current = stateService.getState().ui.editorFontSize
                setEditorFontSize(current + delta, persist = true)
                return@addMouseWheelListener
            }

            forwardWheelToScrollPane(area, event)
        }
    }

    private fun forwardWheelToScrollPane(area: JBTextArea, event: MouseWheelEvent) {
        val scrollPane = enclosingScrollPane(area) ?: return
        val point = SwingUtilities.convertPoint(area, event.point, scrollPane)
        val forwarded = MouseWheelEvent(
            scrollPane,
            event.id,
            event.`when`,
            event.modifiersEx,
            point.x,
            point.y,
            event.xOnScreen,
            event.yOnScreen,
            event.clickCount,
            event.isPopupTrigger,
            event.scrollType,
            event.scrollAmount,
            event.wheelRotation,
            event.preciseWheelRotation,
        )
        scrollPane.dispatchEvent(forwarded)
        event.consume()
    }

    private fun enclosingScrollPane(component: Component): JScrollPane? {
        var current = component.parent
        while (current != null) {
            if (current is JScrollPane) return current
            current = current.parent
        }
        return null
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
        listOf(inputArea, expectedArea, actualArea, stderrArea).forEach { area ->
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

    private fun copyEditorText(area: JBTextArea, button: JButton, label: String) {
        Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(area.text), null)
        val message = CphText.current().copiedSection(label)
        showTitleActionFeedback(button, message)
    }

    private fun setActualOutputAsExpected() {
        val testCase = selectedCase ?: return
        val text = actualArea.text
        expectedArea.text = text
        testCase.expectedOutput = text
        refreshActualDiffHighlights()
        refreshTabs()
        showTitleActionFeedback(actualToExpectedButton, CphText.current().actualSetAsExpected)
    }

    private fun showTitleActionFeedback(button: JButton, message: String) {
        titleActionFeedbackTimers.remove(button)?.stop()
        button.putClientProperty(TITLE_ACTION_FEEDBACK_PROPERTY, true)
        button.text = CphText.current().doneShort
        resizeTitleActionButton(button)
        button.toolTipText = message
        StatusBar.Info.set(message, project)
        refreshTitleActionButton(button)

        val timer = Timer(TITLE_ACTION_FEEDBACK_VISIBLE_MILLIS) {
            button.putClientProperty(TITLE_ACTION_FEEDBACK_PROPERTY, false)
            button.text = button.getClientProperty(TITLE_ACTION_DEFAULT_TEXT_PROPERTY) as? String ?: button.text
            resizeTitleActionButton(button)
            restoreTitleActionTooltip(button)
            refreshTitleActionButton(button)
            titleActionFeedbackTimers.remove(button)
        }.also {
            it.isRepeats = false
        }
        titleActionFeedbackTimers[button] = timer
        timer.start()
    }

    private fun restoreTitleActionTooltip(button: JButton) {
        val text = CphText.current()
        button.toolTipText = when (button) {
            inputCopyButton -> text.copySection(text.input)
            expectedCopyButton -> text.copySection(text.expectedOutput)
            actualCopyButton -> text.copySection(text.standardOutput)
            actualToExpectedButton -> text.setActualAsExpectedTooltip
            else -> button.toolTipText
        }
    }

    private fun refreshTarget() {
        if (!isCphEnabled()) {
            showActiveView()
            return
        }
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
            showStderrEnabled.isSelected = stateService.getState().ui.showStderrEnabled
            compactCaseTabsEnabled.isSelected = stateService.getState().ui.compactCaseTabsEnabled
            confidentSubmitEnabled.isSelected = stateService.getState().ui.confidentSubmitEnabled
            parallelCaseRunEnabled.isSelected = stateService.getState().ui.parallelCaseRunEnabled
            ignoreTrailingWhitespace.isSelected = currentTargetCases.ignoreTrailingWhitespace
            val compileSettings = stateService.getState().compileSettings
            cppStandardCombo.selectedItem = compileSettings.cppStandard
            compileOptionsField.text = compileSettings.compileOptions
            gccBitsPchEnabled.isSelected = compileSettings.gccBitsPchEnabled
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
        if (!isCphEnabled()) {
            showActiveView()
            return
        }
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
        StatusBar.Info.set(CphText.current().debugPreparingStatus(testCase.name), project)
        object : Task.Backgroundable(project, CphText.current().preparingDebugTask(), false) {
            override fun run(indicator: ProgressIndicator) {
                indicator.text = CphText.current().preparingCase(testCase.name)
                val syncResult = compileSettingsSynchronizer.sync(
                    identity,
                    targetCases,
                    stateService.getState().compileSettings.toCompileSettings(),
                    waitForCppFileTarget = true,
                )
                val syncError = syncResult.error
                val preparation = if (syncError == null) {
                    CphRunner(project).prepareForRun(identity)
                } else {
                    null
                }
                ApplicationManager.getApplication().invokeLater {
                    if (syncError != null) {
                        reportDebugError("Failed to prepare C/C++ File target: $syncError")
                    } else if (preparation is CphRunPreparation.Failed) {
                        reportDebugError(preparation.result.message)
                    } else {
                        if (preparation is CphRunPreparation.Ready) {
                            showLocalCompileDiagnostics(
                                localCompileDiagnosticSummary(
                                    prefix = "调试准备",
                                    syncResult = syncResult,
                                    prepareDiagnostics = preparation.diagnostics,
                                ),
                            )
                        }
                        launchDebugCase(identity, testCase)
                    }
                }
            }
        }.queue()
    }

    private fun launchDebugCase(identity: CphTargetIdentity, testCase: CphTestCase) {
        StatusBar.Info.set(CphText.current().debugLaunchingStatus(testCase.name), project)
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

    private fun showLocalCompileDiagnostics(message: String) {
        if (!CphBuildFeatures.localDiagnosticsEnabled || message.isBlank()) return
        if (!SwingUtilities.isEventDispatchThread()) {
            ApplicationManager.getApplication().invokeLater { showLocalCompileDiagnostics(message) }
            return
        }
        compileDiagnosticsLabel.text = message
        compileDiagnosticsLabel.toolTipText = message
        compileDiagnosticsPanel.isVisible = true
        compileDiagnosticsPanel.revalidate()
        compileDiagnosticsPanel.repaint()
        StatusBar.Info.set(message, project)
    }

    private fun localCompileDiagnosticSummary(
        prefix: String,
        syncResult: CphCompileSyncResult,
        prepareDiagnostics: CphRunPrepareDiagnostics,
    ): String {
        val totalMillis = syncResult.syncMillis + prepareDiagnostics.totalPrepareMillis
        val pch = pchDiagnosticText(syncResult)
        val build = if (prepareDiagnostics.buildSkippedByCphCache) {
            "skipped"
        } else {
            CphUiText.formatDuration(prepareDiagnostics.buildMillis)
        }
        val cache = if (prepareDiagnostics.buildSkippedByCphCache) "hit" else "miss"
        return "$prefix ${CphUiText.formatDuration(totalMillis)} | bits accel: $pch | " +
            "加速准备 ${CphUiText.formatDuration(syncResult.managedArgsMillis)} | " +
            "CLion构建 $build | CPH缓存 $cache"
    }

    private fun pchDiagnosticText(syncResult: CphCompileSyncResult): String {
        val status = syncResult.pchStatus.name.lowercase()
        val message = syncResult.pchMessage.takeIf { it.isNotBlank() }
        return if (message == null) status else "$status($message)"
    }

    private fun runCases(cases: List<CphTestCase>, source: ActiveRunButton) {
        if (!isCphEnabled()) return
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
        runCases.forEach { runtimeStates[it.id] = RuntimeTabState.QUEUED }
        refreshTabs()
        startRunSpinner(source)
        setRunning(true)

        object : Task.Backgroundable(project, CphText.current().runningSamplesTask(), true) {
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
                val syncResult = compileSettingsSynchronizer.sync(
                    identity,
                    targetCases,
                    stateService.getState().compileSettings.toCompileSettings(),
                    waitForCppFileTarget = true,
                )
                val syncError = syncResult.error
                if (syncError != null) {
                    val result = CphCaseResult(
                        verdict = CphVerdict.ERROR,
                        message = "Failed to sync CPH compile settings: $syncError",
                    )
                    runCases.forEach { it.lastResult = result.copy() }
                    runCases.forEach { reportCaseError(it, it.lastResult) }
                    return
                }

                indicator.text = CphText.current().preparingRunTarget()
                val preparation = runner.prepareForRun(identity)
                val preparedTarget = when (preparation) {
                    is CphRunPreparation.Failed -> {
                        runCases.forEach { it.lastResult = preparation.result.copy() }
                        runCases.forEach { reportCaseError(it, it.lastResult) }
                        return
                    }
                    is CphRunPreparation.Ready -> preparation.target
                }
                showLocalCompileDiagnostics(
                    localCompileDiagnosticSummary(
                        prefix = "编译",
                        syncResult = syncResult,
                        prepareDiagnostics = preparation.diagnostics,
                    ),
                )

                val session = CphSampleRunSession(project, caseRunParallelism(runCases.size))
                indicator.text = CphText.current().runningSamples(runCases.size)
                val summary = session.run(
                    preparedTarget = preparedTarget,
                    cases = runCases,
                    options = CphCaseRunOptions(
                        timeoutMillis = timeoutMillis,
                        ignoreTrailingWhitespace = ignoreTrailing,
                        compareExpectedOutput = !noExpectedMode,
                        noExpectedMode = noExpectedMode,
                    ),
                    indicator = indicator,
                    onCaseStarted = { testCase ->
                        ApplicationManager.getApplication().invokeLater {
                            if (runtimeStates[testCase.id] == RuntimeTabState.QUEUED) {
                                runtimeStates[testCase.id] = RuntimeTabState.RUNNING
                                refreshTabs()
                            }
                        }
                    },
                ) { completion ->
                    indicator.text = CphText.current().completedSamples(completion.completedCases, completion.totalCases)
                    indicator.fraction = completion.completedCases.toDouble() / completion.totalCases
                    completion.testCase.lastResult = completion.result
                    ApplicationManager.getApplication().invokeLater {
                        reportCaseError(completion.testCase, completion.result)
                        runtimeStates.remove(completion.testCase.id)
                        refreshTabs()
                        if (selectedCase?.id == completion.testCase.id) {
                            renderSelectedCase()
                        }
                    }
                }
                if (indicator.isCanceled) {
                    return
                }
                completedAllCases = summary.completedAllCases
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
                        StatusBar.Info.set(CphText.current().autoSubmitAllAc(), project)
                        CphSubmitOrchestrator.getInstance(project).submit()
                    }
                }
            }
        }.queue()
    }

    private fun caseRunParallelism(caseCount: Int): Int {
        if (!stateService.getState().ui.parallelCaseRunEnabled) return 1
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
        val report = CphErrorReportBuilder.sample(project, currentIdentity, testCase, result)
        StatusBar.Info.set(statusMessage, project)
        if (CphUiText.isCompileLikeError(result)) {
            CphBuildOutputService.getInstance(project).showCompileError()
            return
        }
        NotificationGroupManager.getInstance()
            .getNotificationGroup(CPH_NOTIFICATION_GROUP_ID)
            .createNotification(CphText.current().sampleFailedTitle(), CphUiText.errorNotificationContent(testCase.name, result), NotificationType.ERROR)
            .addCphErrorActions(report)
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
            val statusMessage = "CPH compile settings sync failed: $error"
            val report = CphErrorReportBuilder.generic(project, currentIdentity, statusMessage, error)
            StatusBar.Info.set(statusMessage, project)
            NotificationGroupManager.getInstance()
                .getNotificationGroup(CPH_NOTIFICATION_GROUP_ID)
                .createNotification(CphText.current().cphErrorTitle, statusMessage, NotificationType.ERROR)
                .addCphErrorActions(report)
                .notify(project)
        }
    }

    private fun reportDebugError(message: String) {
        if (!SwingUtilities.isEventDispatchThread()) {
            ApplicationManager.getApplication().invokeLater { reportDebugError(message) }
            return
        }
        val statusMessage = CphText.current().debugStatus(message)
        val report = CphErrorReportBuilder.debug(project, currentIdentity, message)
        StatusBar.Info.set(statusMessage, project)
        NotificationGroupManager.getInstance()
            .getNotificationGroup(CPH_NOTIFICATION_GROUP_ID)
            .createNotification(CphText.current().debugFailedTitle(), statusMessage, NotificationType.ERROR)
            .addCphErrorActions(report)
            .notify(project)
    }

    private fun com.intellij.notification.Notification.addCphErrorActions(report: CphErrorReport): com.intellij.notification.Notification {
        addAction(CphViewErrorLogAction(project, report))
        addAction(CphReportErrorAction(project, report))
        return this
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
        runSelectedCaseButton.text = runSelectedButtonText()
    }

    private fun updateRunSpinnerText() {
        val frame = RUN_SPINNER_FRAMES[runSpinnerIndex % RUN_SPINNER_FRAMES.size]
        runSpinnerIndex++
        runAllButton.text = if (activeRunButton == ActiveRunButton.RUN_ALL) frame else RUN_ALL_BUTTON_TEXT
        runSelectedCaseButton.text = if (activeRunButton == ActiveRunButton.RUN_SELECTED) {
            "$frame ${CphText.current().run}"
        } else {
            runSelectedButtonText()
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
        stderrArea.isEnabled = enabled

        if (testCase == null) {
            inputArea.text = ""
            expectedArea.text = ""
            actualArea.text = ""
            stderrArea.text = ""
        } else {
            inputArea.text = testCase.input
            expectedArea.text = testCase.expectedOutput
            actualArea.text = testCase.lastResult.actualOutput
            stderrArea.text = testCase.lastResult.stderr
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
        val url = themeResourceUrl(AVE_MUJICA_LINE_HIGHLIGHT_TILE) ?: return null
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
            null -> tabStatusFor(CphStatusMapper.displayStatus(testCase.lastResult, noExpectedMode))
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
            CphRunDisplayStatus.CE -> TabStatus.CE
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
            TabStatus.CE,
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
                TabStatus.CE,
                TabStatus.TLE -> CphThemeTabStatus.WARN
                TabStatus.RUNNING -> CphThemeTabStatus.RUN
                TabStatus.QUEUED,
                TabStatus.NOT_RUN -> CphThemeTabStatus.DEFAULT
            },
        )
        return TabStyle(color, background, color)
    }

    private fun caseTabPreferredSize(): Dimension {
        if (compactCaseTabsEnabled()) return Dimension(86, 32)
        return if (theme.id == CphThemeId.AVE_MUJICA) Dimension(128, 64) else Dimension(123, 60)
    }

    private fun caseTabMinimumSize(): Dimension {
        if (compactCaseTabsEnabled()) return Dimension(58, 32)
        return if (theme.id == CphThemeId.AVE_MUJICA) Dimension(108, 64) else Dimension(102, 60)
    }

    private fun caseTabMaximumSize(): Dimension {
        if (compactCaseTabsEnabled()) return Dimension(108, 32)
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
        val image = themeResourceUrl(path)?.let { ImageIO.read(it) }
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
            TabStatus.CE,
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
        val hasSelectedCase = selectedCase != null
        runSelectedCaseButton.isEnabled = (hasSelectedCase && runnable) || runSelectedActive
        debugSelectedCaseButton.isEnabled = hasSelectedCase && !running &&
            currentIdentity.runnable &&
            (currentIdentity.kind == CphTargetKind.CMAKE_APP || currentIdentity.kind == CphTargetKind.CPP_FILE)
        inputCopyButton.isEnabled = hasSelectedCase
        expectedCopyButton.isEnabled = hasSelectedCase
        actualCopyButton.isEnabled = hasSelectedCase
        actualToExpectedButton.isEnabled = hasSelectedCase && !running
        runAllButton.isEnabled = (currentTargetCases.cases.isNotEmpty() && runnable) || runAllActive
        resetCasesButton.isEnabled = !running
        timeoutSpinner.isEnabled = !running
        editorFontSizeSpinner.isEnabled = !running
        languageCombo.isEnabled = !running
        noExpectedModeEnabled.isEnabled = !running
        showStderrEnabled.isEnabled = !running
        compactCaseTabsEnabled.isEnabled = !running
        confidentSubmitEnabled.isEnabled = codeforcesSubmitEnabled && !running && stateService.getState().singleFileModeEnabled
        parallelCaseRunEnabled.isEnabled = !running
        gccBitsPchEnabled.isEnabled = !running
        ignoreTrailingWhitespace.isEnabled = !running
        outputSplitEnabled.isEnabled = !running && !stateService.getState().ui.noExpectedModeEnabled
        cppStandardCombo.isEnabled = !running
        compileOptionsField.isEnabled = !running
        singleFileWorkingDirectoryField.isEnabled = !running
        singleFileWorkingDirectoryChooserButton.isEnabled = !running
        refreshToolbarIconButton(resetCasesButton, theme.text)
        refreshSettingsHelpButton()
        refreshSettingsTabButtons()
        refreshThemePluginUi()
        refreshSubmitFeatureVisibility()
        refreshRunActionButtons()
        listOf(
            codeforcesSubmitHelpButton,
            inputCopyButton,
            expectedCopyButton,
            actualCopyButton,
            actualToExpectedButton,
        ).forEach(::refreshTitleActionButton)
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

            if (compactCaseTabsEnabled()) {
                configureCompactCaseTab(index, testCase, status, style, isSelected, tabBackground, tabBorder)
            } else {
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
                deleteCaseButton.toolTipText = CphText.current().deleteCase
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
                    tabTitle(index, status)
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

        private fun configureCompactCaseTab(
            index: Int,
            testCase: CphTestCase,
            status: TabStatus,
            style: TabStyle,
            selected: Boolean,
            tabBackground: Color,
            tabBorder: Color,
        ) {
            background = tabBackground
            border = CompoundBorder(
                MatteBorder(1, 1, 2, 1, tabBorder),
                EmptyBorder(2, 6, 2, 4),
            )
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            toolTipText = tabTooltip(testCase, status)

            val content = JPanel()
            content.layout = BoxLayout(content, BoxLayout.X_AXIS)
            content.background = background
            content.isOpaque = false
            content.toolTipText = toolTipText

            val indexLabel = JBLabel(index.toString())
            indexLabel.foreground = if (selected) theme.text else style.foreground
            indexLabel.font = indexLabel.font.deriveFont(Font.BOLD, indexLabel.font.size2D + 0.5f)
            indexLabel.toolTipText = toolTipText
            fixedComponentSize(indexLabel, Dimension(compactCaseIndexWidth(index), 18))

            val icon = CompactStatusIcon(status, compactStatusColor(status, style))
            icon.toolTipText = toolTipText

            val detailText = compactTabDetail(testCase, status)
            val detailLabel = JBLabel(detailText)
            detailLabel.foreground = if (status == TabStatus.NOT_RUN || status == TabStatus.QUEUED) theme.muted else theme.text
            detailLabel.font = detailLabel.font.deriveFont(Font.PLAIN, detailLabel.font.size2D - 1.0f)
            detailLabel.toolTipText = toolTipText
            val detailWidth = compactCaseDetailWidth(detailLabel, detailText)
            if (detailWidth > 0) {
                fixedComponentSize(detailLabel, Dimension(detailWidth, 18))
            }
            val closeButton = CompactCloseButton(theme.muted, theme.border).also {
                it.active = selected
                it.closeEnabled = selected && !running
                it.toolTipText = CphText.current().deleteCase
                it.addMouseListener(object : MouseAdapter() {
                    override fun mouseClicked(e: MouseEvent) {
                        if (selected && it.closeEnabled) {
                            e.consume()
                            deleteCase(testCase)
                        }
                    }
                })
            }

            val collapsedWidth = compactCaseCollapsedWidth(index)
            val hoverExpandedWidth = compactCaseExpandedWidth(index, detailWidth, includeClose = false)
            val selectedExpandedWidth = compactCaseExpandedWidth(index, detailWidth, includeClose = true)
            var currentWidth = if (selected) selectedExpandedWidth else collapsedWidth
            var targetWidth = currentWidth
            setCompactCaseTabWidth(currentWidth)

            val animationTimer = Timer(COMPACT_TAB_ANIMATION_DELAY_MILLIS, null)
            animationTimer.addActionListener {
                val delta = targetWidth - currentWidth
                if (kotlin.math.abs(delta) <= 1) {
                    currentWidth = targetWidth
                    setCompactCaseTabWidth(currentWidth)
                    animationTimer.stop()
                    return@addActionListener
                }
                val step = (delta * COMPACT_TAB_ANIMATION_EASING).roundToInt()
                currentWidth += when {
                    delta > 0 -> step.coerceAtLeast(1).coerceAtMost(delta)
                    else -> step.coerceAtMost(-1).coerceAtLeast(delta)
                }
                setCompactCaseTabWidth(currentWidth)
            }

            fun animateExpanded(expanded: Boolean) {
                closeButton.active = selected
                val nextTarget = when {
                    selected -> selectedExpandedWidth
                    expanded -> hoverExpandedWidth
                    else -> collapsedWidth
                }
                if (targetWidth == nextTarget) return
                targetWidth = nextTarget
                if (!animationTimer.isRunning) {
                    animationTimer.start()
                }
            }

            content.add(indexLabel)
            content.add(Box.createRigidArea(Dimension(3, 0)))
            content.add(icon)
            if (detailWidth > 0) {
                content.add(Box.createRigidArea(Dimension(3, 0)))
                content.add(detailLabel)
            }
            content.add(Box.createRigidArea(Dimension(4, 0)))
            content.add(closeButton)

            val selectListener = object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    selectCase(testCase)
                }
            }
            val hoverListener = object : MouseAdapter() {
                override fun mouseEntered(e: MouseEvent) {
                    animateExpanded(true)
                }

                override fun mouseExited(e: MouseEvent) {
                    if (!selected && !isPointerInsideCaseTab()) {
                        animateExpanded(false)
                    }
                }
            }
            addMouseListener(selectListener)
            addMouseListener(hoverListener)
            content.addMouseListener(selectListener)
            content.addMouseListener(hoverListener)
            indexLabel.addMouseListener(selectListener)
            indexLabel.addMouseListener(hoverListener)
            icon.addMouseListener(selectListener)
            icon.addMouseListener(hoverListener)
            detailLabel.addMouseListener(selectListener)
            detailLabel.addMouseListener(hoverListener)
            closeButton.addMouseListener(hoverListener)

            add(content, BorderLayout.CENTER)
        }

        private fun setCompactCaseTabWidth(width: Int) {
            val size = Dimension(width, 32)
            preferredSize = size
            minimumSize = size
            maximumSize = size
            revalidate()
            parent?.revalidate()
            repaint()
        }

        private fun isPointerInsideCaseTab(): Boolean {
            val location = java.awt.MouseInfo.getPointerInfo()?.location ?: return false
            SwingUtilities.convertPointFromScreen(location, this)
            return contains(location)
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
            if (compactCaseTabsEnabled()) {
                val compactSize = Dimension(44, 32)
                preferredSize = compactSize
                minimumSize = compactSize
                maximumSize = compactSize
                border = CompoundBorder(
                    MatteBorder(1, 1, 2, 1, theme.border),
                    EmptyBorder(2, 8, 2, 8),
                )
            }
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            toolTipText = CphText.current().addCase

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

    private fun fixedComponentSize(component: JComponent, size: Dimension) {
        component.minimumSize = size
        component.preferredSize = size
        component.maximumSize = size
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

    private class CompactStatusIcon(
        private val status: TabStatus,
        private val color: Color,
    ) : JComponent() {
        init {
            val size = Dimension(15, 15)
            preferredSize = size
            minimumSize = size
            maximumSize = size
            isOpaque = false
        }

        override fun paintComponent(g: Graphics) {
            super.paintComponent(g)
            val g2 = g.create() as Graphics2D
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.color = color
                g2.stroke = BasicStroke(
                    JBUIScale.scale(1.7f),
                    BasicStroke.CAP_ROUND,
                    BasicStroke.JOIN_ROUND,
                )
                val w = width.coerceAtLeast(1)
                val h = height.coerceAtLeast(1)
                val cx = w / 2
                val cy = h / 2
                val pad = JBUIScale.scale(3)
                when (status) {
                    TabStatus.OK,
                    TabStatus.AC -> {
                        g2.drawLine(pad, cy, cx - JBUIScale.scale(1), h - pad)
                        g2.drawLine(cx - JBUIScale.scale(1), h - pad, w - pad, pad)
                    }
                    TabStatus.WA -> {
                        g2.drawLine(pad, pad, w - pad, h - pad)
                        g2.drawLine(w - pad, pad, pad, h - pad)
                    }
                    TabStatus.RE -> {
                        val diamond = java.awt.Polygon(
                            intArrayOf(cx, w - pad, cx, pad),
                            intArrayOf(pad, cy, h - pad, cy),
                            4,
                        )
                        g2.drawPolygon(diamond)
                        drawBang(g2, cx, cy, h)
                    }
                    TabStatus.TLE -> {
                        g2.drawOval(pad, pad, w - pad * 2, h - pad * 2)
                        g2.drawLine(cx, cy, cx, pad + JBUIScale.scale(2))
                        g2.drawLine(cx, cy, w - pad - JBUIScale.scale(1), cy)
                    }
                    TabStatus.CE,
                    TabStatus.ERROR -> {
                        val triangle = java.awt.Polygon(
                            intArrayOf(cx, w - pad, pad),
                            intArrayOf(pad, h - pad, h - pad),
                            3,
                        )
                        g2.drawPolygon(triangle)
                        drawBang(g2, cx, cy + JBUIScale.scale(1), h)
                    }
                    TabStatus.RUNNING -> {
                        val triangle = java.awt.Polygon(
                            intArrayOf(pad + JBUIScale.scale(1), w - pad, pad + JBUIScale.scale(1)),
                            intArrayOf(pad, cy, h - pad),
                            3,
                        )
                        g2.fillPolygon(triangle)
                    }
                    TabStatus.QUEUED -> {
                        val r = JBUIScale.scale(2)
                        listOf(cx - JBUIScale.scale(5), cx, cx + JBUIScale.scale(5)).forEach {
                            g2.fillOval(it - r / 2, cy - r / 2, r, r)
                        }
                    }
                    TabStatus.NOT_RUN -> {
                        g2.drawOval(pad + JBUIScale.scale(1), pad + JBUIScale.scale(1), w - pad * 2 - JBUIScale.scale(2), h - pad * 2 - JBUIScale.scale(2))
                    }
                }
            } finally {
                g2.dispose()
            }
        }

        private fun drawBang(g2: Graphics2D, cx: Int, cy: Int, height: Int) {
            g2.drawLine(cx, cy - JBUIScale.scale(4), cx, cy + JBUIScale.scale(1))
            val dot = JBUIScale.scale(2)
            g2.fillOval(cx - dot / 2, height - JBUIScale.scale(5), dot, dot)
        }
    }

    private class CompactCloseButton(
        private val activeColor: Color,
        private val disabledColor: Color,
    ) : JComponent() {
        var active: Boolean = false
            set(value) {
                field = value
                repaint()
            }

        var closeEnabled: Boolean = true
            set(value) {
                field = value
                cursor = if (value) Cursor.getPredefinedCursor(Cursor.HAND_CURSOR) else Cursor.getDefaultCursor()
                repaint()
            }

        init {
            val size = Dimension(COMPACT_TAB_CLOSE_WIDTH, 18)
            minimumSize = size
            preferredSize = size
            maximumSize = size
            isOpaque = false
        }

        override fun paintComponent(g: Graphics) {
            super.paintComponent(g)
            if (!active) return
            val g2 = g.create() as Graphics2D
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.color = if (closeEnabled) activeColor else disabledColor
                g2.stroke = BasicStroke(
                    JBUIScale.scale(1.6f),
                    BasicStroke.CAP_ROUND,
                    BasicStroke.JOIN_ROUND,
                )
                val padX = JBUIScale.scale(3)
                val padY = JBUIScale.scale(5)
                g2.drawLine(padX, padY, width - padX, height - padY)
                g2.drawLine(width - padX, padY, padX, height - padY)
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
        val glyphName: String? = null,
        val wideGlyph: Boolean = false,
    ) {
        OK("OK", "ok"),
        AC("AC", "ac"),
        WA("WA", "wa"),
        TLE("TLE", "tle", true),
        RE("RE", "re"),
        CE("CE", "err", true),
        ERROR("ERR", "err", true),
        RUNNING("RUN", "run", true),
        QUEUED("..."),
        NOT_RUN("-"),
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

    private fun runSelectedButtonText(): String = "▷ ${CphText.current().run}"

    private inner class OnboardingStartButton : JButton() {
        init {
            model.addChangeListener(ChangeListener { repaint() })
        }

        override fun paintComponent(g: Graphics) {
            val g2 = g.create() as Graphics2D
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                val width = width
                val height = height
                val pressed = model.isPressed && model.isArmed
                val hovered = model.isRollover
                val arc = JBUIScale.scale(18)
                val inset = JBUIScale.scale(2)
                val bodyX = inset
                val bodyY = inset + if (pressed) JBUIScale.scale(1) else 0
                val bodyW = width - inset * 2
                val bodyH = height - inset * 2

                g2.color = Color(0x0A0D15)
                g2.fillRoundRect(bodyX, bodyY + JBUIScale.scale(4), bodyW, bodyH, arc, arc)

                val top = if (pressed) Color(0x48C36B) else Color(0x72E98A)
                val bottom = if (pressed) Color(0x278F50) else Color(0x32B866)
                g2.paint = LinearGradientPaint(
                    0f,
                    bodyY.toFloat(),
                    0f,
                    (bodyY + bodyH).toFloat(),
                    floatArrayOf(0.0f, 0.58f, 1.0f),
                    arrayOf(top, bottom, Color(0x21804A)),
                )
                g2.fillRoundRect(bodyX, bodyY, bodyW, bodyH, arc, arc)

                g2.paint = LinearGradientPaint(
                    0f,
                    bodyY.toFloat(),
                    width.toFloat(),
                    (bodyY + bodyH).toFloat(),
                    floatArrayOf(0.0f, 0.54f, 1.0f),
                    arrayOf(Color(0xBDEBFF), Color(0xF4D6FF), Color(0x7EFFA6)),
                )
                g2.stroke = java.awt.BasicStroke(JBUIScale.scale(if (hovered) 3.0f else 2.0f))
                g2.drawRoundRect(bodyX, bodyY, bodyW, bodyH, arc, arc)

                g2.composite = AlphaComposite.SrcOver.derive(if (hovered) 0.28f else 0.18f)
                g2.color = Color.WHITE
                g2.fillRoundRect(
                    bodyX + JBUIScale.scale(9),
                    bodyY + JBUIScale.scale(6),
                    bodyW - JBUIScale.scale(18),
                    JBUIScale.scale(12),
                    JBUIScale.scale(12),
                    JBUIScale.scale(12),
                )
                g2.composite = AlphaComposite.SrcOver

                g2.color = Color(0xF8FFF9)
                g2.font = font
                val metrics = g2.fontMetrics
                val text = text.orEmpty()
                val textX = (width - metrics.stringWidth(text)) / 2
                val textY = (height - metrics.height) / 2 + metrics.ascent - if (pressed) 0 else JBUIScale.scale(1)
                g2.drawString(text, textX, textY)

                drawSparkle(g2, bodyX + JBUIScale.scale(13), bodyY + JBUIScale.scale(13), JBUIScale.scale(5), Color(0xFFF0A8))
                drawSparkle(g2, bodyX + bodyW - JBUIScale.scale(16), bodyY + bodyH - JBUIScale.scale(13), JBUIScale.scale(4), Color(0xD9C4FF))
            } finally {
                g2.dispose()
            }
        }

        private fun drawSparkle(g2: Graphics2D, cx: Int, cy: Int, radius: Int, color: Color) {
            g2.color = color
            g2.stroke = java.awt.BasicStroke(JBUIScale.scale(1.4f))
            g2.drawLine(cx - radius, cy, cx + radius, cy)
            g2.drawLine(cx, cy - radius, cx, cy + radius)
        }
    }

    private companion object {
        private const val ONBOARDING_VIEW_CARD = "onboarding"
        private const val MAIN_VIEW_CARD = "main"
        private const val SETTINGS_VIEW_CARD = "settings"
        private const val WELCOME_ICON_SCALE = 1.7f
        private const val RESIZE_HANDLE_HEIGHT = 8
        private const val OUTPUT_DIVIDER_SIZE = 6
        private const val TITLE_COPY_BUTTON_WIDTH = 30
        private const val TITLE_SET_EXPECTED_BUTTON_WIDTH = 30
        private const val TITLE_ACTION_MIN_WIDTH = 30
        private const val TITLE_ACTION_BUTTON_HEIGHT = 18
        private const val TITLE_ACTION_HORIZONTAL_PADDING = 8
        private const val TITLE_ACTION_VERTICAL_PADDING = 1
        private const val CODEFORCES_SUBMIT_HELP_BUTTON_WIDTH = 22
        private const val TITLE_ACTION_FEEDBACK_VISIBLE_MILLIS = 1100
        private const val TAB_STRIP_BASE_HEIGHT = 62
        private const val AVE_MUJICA_TAB_STRIP_BASE_HEIGHT = 66
        private const val COMPACT_TAB_STRIP_BASE_HEIGHT = 34
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
        private const val CPH_DOCS_URL = "https://cph.kkkzbh.cn"
        private const val CPH_CODEFORCES_SUBMIT_DOCS_URL = "https://cph.kkkzbh.cn/#/codeforces-submit"
        private const val CPH_NOTIFICATION_GROUP_ID = "CPH Target Runner"
        private const val AVE_MUJICA_FONT_RESOURCE = "/fonts/AnglicanText.ttf"
        private const val AVE_MUJICA_HELP_TEXT_RESOURCE = "/icons/avemujica/standalone/help_text.png"
        private const val AVE_MUJICA_HELP_TEXT_HEIGHT = 28
        private const val AVE_MUJICA_HELP_ICON_TEXT_GAP = 7
        private const val AVE_MUJICA_THEME_TITLE_RESOURCE = "/icons/avemujica/standalone/theme_title.png"
        private const val AVE_MUJICA_THEME_PREVIEW_RESOURCE = "/icons/aveMujicaThemePreview.png"
        private const val AVE_MUJICA_THEME_TITLE_HEIGHT = 32
        private const val AVE_MUJICA_ANIMATION_DELAY_MILLIS = 70
        private const val AVE_MUJICA_ANIMATION_FRAME_COUNT = 8
        private const val AVE_MUJICA_ICON_NAME_PROPERTY = "cph.aveMujica.iconName"
        private const val AVE_MUJICA_ICON_SIZE_PROPERTY = "cph.aveMujica.iconSize"
        private const val AVE_MUJICA_ICON_FRAME_PROPERTY = "cph.aveMujica.iconFrame"
        private const val AVE_MUJICA_ANIMATION_CONFIGURED_PROPERTY = "cph.aveMujica.animationConfigured"
        private const val SETTINGS_ROW_PROPERTY = "cph.settings.row"
        private const val SETTINGS_TAB_CONFIGURED_PROPERTY = "cph.settingsTab.configured"
        private const val SETTING_LABEL_WIDTH = 132
        private const val COMPACT_TAB_MIN_WIDTH = 44
        private const val COMPACT_TAB_INDEX_WIDTH = 14
        private const val COMPACT_TAB_CLOSE_WIDTH = 12
        private const val COMPACT_TAB_CHROME_WIDTH = 13
        private const val COMPACT_TAB_ANIMATION_DELAY_MILLIS = 16
        private const val COMPACT_TAB_ANIMATION_EASING = 0.35
        private const val BASE_FONT_PROPERTY = "cph.baseFont"
        private const val TITLE_ACTION_DEFAULT_TEXT_PROPERTY = "cph.titleAction.defaultText"
        private const val TITLE_ACTION_FEEDBACK_PROPERTY = "cph.titleAction.feedback"
        private const val TITLE_ACTION_MIN_WIDTH_PROPERTY = "cph.titleAction.minWidth"

        private fun tabTitle(index: Int, status: TabStatus): String {
            return if (status == TabStatus.NOT_RUN) {
                if (CphUiLanguage.current() == CphUiLanguage.ZH_CN) "$index 样例" else "$index CASE"
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
                TabStatus.CE,
                TabStatus.ERROR -> formatDuration(testCase.lastResult.durationMillis)
                TabStatus.RUNNING,
                TabStatus.QUEUED -> ""
                TabStatus.NOT_RUN -> ""
            }
        }

        private fun compactTabDetail(testCase: CphTestCase, status: TabStatus): String {
            return when (status) {
                TabStatus.OK,
                TabStatus.AC,
                TabStatus.WA,
                TabStatus.TLE,
                TabStatus.RE,
                TabStatus.CE,
                TabStatus.ERROR -> formatDuration(testCase.lastResult.durationMillis)
                TabStatus.RUNNING -> ""
                TabStatus.QUEUED -> ""
                TabStatus.NOT_RUN -> ""
            }
        }

        private fun compactCaseIndexWidth(index: Int): Int {
            return COMPACT_TAB_INDEX_WIDTH + ((index.toString().length - 1).coerceAtLeast(0) * 8)
        }

        private fun compactCaseDetailWidth(label: JBLabel, detail: String): Int {
            if (detail.isBlank()) return 0
            return (label.getFontMetrics(label.font).stringWidth(detail) + 2)
                .coerceAtLeast(24)
        }

        private fun compactCaseCollapsedWidth(index: Int): Int {
            return (
                compactCaseIndexWidth(index) +
                    3 +
                    15 +
                    COMPACT_TAB_CHROME_WIDTH
                ).coerceAtLeast(COMPACT_TAB_MIN_WIDTH)
        }

        private fun compactCaseExpandedWidth(index: Int, detailWidth: Int, includeClose: Boolean): Int {
            val detailGap = if (detailWidth > 0) 3 else 0
            val closeWidth = if (includeClose) 4 + COMPACT_TAB_CLOSE_WIDTH else 0
            return (
                compactCaseCollapsedWidth(index) +
                    detailGap +
                    detailWidth +
                    closeWidth
                ).coerceAtLeast(COMPACT_TAB_MIN_WIDTH)
        }

        private fun compactStatusColor(status: TabStatus, style: TabStyle): Color {
            return when (status) {
                TabStatus.OK,
                TabStatus.AC -> style.foreground
                TabStatus.WA -> style.foreground
                TabStatus.RE -> Color(0xC35CFF)
                TabStatus.TLE,
                TabStatus.CE -> style.foreground
                TabStatus.ERROR -> Color(0xFF6B6B)
                TabStatus.RUNNING -> style.foreground
                TabStatus.QUEUED,
                TabStatus.NOT_RUN -> style.foreground
            }
        }

        private fun tabTooltip(testCase: CphTestCase, status: TabStatus): String {
            val text = CphText.current()
            val duration = formatDuration(testCase.lastResult.durationMillis)
            return when (status) {
                TabStatus.OK -> text.caseOk(testCase.name, duration)
                TabStatus.AC,
                TabStatus.WA,
                TabStatus.TLE,
                TabStatus.RE -> text.caseVerdict(testCase.name, testCase.lastResult.verdict, duration)
                TabStatus.CE,
                TabStatus.ERROR -> CphUiText.errorTooltip(testCase.name, testCase.lastResult)
                TabStatus.RUNNING -> text.caseRunning(testCase.name)
                TabStatus.QUEUED -> text.caseQueued(testCase.name)
                TabStatus.NOT_RUN -> text.caseNotRun(testCase.name)
            }
        }

        private fun formatDuration(durationMillis: Long): String {
            return CphUiText.formatDuration(durationMillis)
        }
    }

}
