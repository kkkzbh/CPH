package org.kkkzbh.cph

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import java.awt.CardLayout
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Graphics
import java.awt.GridLayout
import java.awt.Rectangle
import java.awt.Shape
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JSpinner
import javax.swing.SpinnerNumberModel
import javax.swing.SwingUtilities
import javax.swing.border.CompoundBorder
import javax.swing.border.EmptyBorder
import javax.swing.border.MatteBorder
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.text.Highlighter
import javax.swing.text.JTextComponent

class CphToolWindowPanel(private val project: Project) : JPanel(BorderLayout()) {
    private val stateService = CphStateService.getInstance(project)
    private var currentIdentity = CphTargetResolver.current(project)
    private var currentTargetCases = stateService.getOrCreateTargetCases(currentIdentity)
    private var selectedCase: CphTestCase? = null
    private var running = false
    private var settingsVisible = false

    private val runtimeStates = linkedMapOf<String, RuntimeTabState>()
    private val caseTabComponents = linkedMapOf<String, CaseTab>()

    private val targetLabel = JBLabel()
    private val refreshButton = JButton("↻")
    private val addButton = JButton("+")
    private val deleteButton = JButton("⌫")
    private val settingsButton = JButton("⚙")
    private val defaultSettingsButtonBorder = settingsButton.border
    private val runSelectedButton = JButton("▷ Run")
    private val runAllButton = JButton("▷ Run All")
    private val ignoreTrailingWhitespace = JCheckBox("忽略行尾空格和多余换行")
    private val timeoutSpinner = JSpinner(SpinnerNumberModel(1000, 100, 60000, 100))

    private val tabStrip = JPanel()
    private val tabScrollPane = JBScrollPane(tabStrip)
    private val contentCards = JPanel(CardLayout())
    private val settingsGrid = JPanel(GridLayout(0, 2, 12, 12))
    private val inputArea = JBTextArea()
    private val expectedArea = JBTextArea()
    private val actualArea = JBTextArea()
    private val stderrArea = JBTextArea()
    private val resultLabel = JBLabel("No case selected")

    init {
        border = BorderFactory.createEmptyBorder(8, 8, 8, 8)
        background = PANEL
        add(buildTop(), BorderLayout.NORTH)
        add(buildCenter(), BorderLayout.CENTER)

        refreshButton.toolTipText = "Refresh target"
        addButton.toolTipText = "Add case"
        deleteButton.toolTipText = "Delete selected case"
        settingsButton.toolTipText = "Settings"

        refreshButton.addActionListener { refreshTarget() }
        addButton.addActionListener { addCase() }
        deleteButton.addActionListener { deleteSelectedCase() }
        settingsButton.addActionListener { toggleSettingsPanel() }
        runSelectedButton.addActionListener { runSelectedCase() }
        runAllButton.addActionListener { runAllCases() }
        ignoreTrailingWhitespace.addActionListener {
            currentTargetCases.ignoreTrailingWhitespace = ignoreTrailingWhitespace.isSelected
            refreshActualDiffHighlights()
        }
        timeoutSpinner.addChangeListener {
            currentTargetCases.timeoutMillis = (timeoutSpinner.value as Number).toLong()
        }

        actualArea.isEditable = false
        stderrArea.isEditable = false
        listOf(inputArea, expectedArea, actualArea, stderrArea).forEach(::configureEditor)
        listOf(expectedArea, actualArea).forEach(::installDiffRefreshListener)

        refreshTarget()
    }

    private fun buildTop(): JComponent {
        val toolbar = JPanel(FlowLayout(FlowLayout.LEFT, 8, 4))
        toolbar.background = PANEL
        toolbar.add(JBLabel("Target:"))
        toolbar.add(targetLabel)
        toolbar.add(refreshButton)
        toolbar.add(JSeparatorPanel())
        toolbar.add(runAllButton)
        toolbar.add(runSelectedButton)
        toolbar.add(addButton)
        toolbar.add(deleteButton)
        toolbar.add(settingsButton)
        return toolbar
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
        tabScrollPane.horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_ALWAYS
        tabScrollPane.preferredSize = Dimension(0, 76)
        tabScrollPane.minimumSize = Dimension(0, 76)
        tabScrollPane.viewport.background = PANEL

        return JPanel(BorderLayout()).also {
            it.background = PANEL
            it.add(tabScrollPane, BorderLayout.NORTH)
            it.add(buildBody(), BorderLayout.CENTER)
        }
    }

    private fun buildSettingsView(): JComponent {
        ignoreTrailingWhitespace.isOpaque = false
        settingsGrid.background = PANEL
        settingsGrid.border = EmptyBorder(12, 0, 0, 0)
        settingsGrid.add(settingTile("Time Limits:") {
            add(timeoutSpinner)
            add(JBLabel("ms"))
        })
        settingsGrid.add(settingTile("输出比较") {
            add(ignoreTrailingWhitespace)
        })

        val viewport = JPanel(BorderLayout())
        viewport.background = PANEL
        viewport.border = EmptyBorder(0, 0, 0, 0)
        viewport.add(settingsGrid, BorderLayout.NORTH)

        return JBScrollPane(viewport).also {
            it.border = MatteBorder(1, 0, 0, 0, BORDER)
            it.viewport.background = PANEL
            it.horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
        }
    }

    private fun toggleSettingsPanel() {
        settingsVisible = !settingsVisible
        showActiveView()
    }

    private fun showActiveView() {
        (contentCards.layout as? CardLayout)?.show(
            contentCards,
            if (settingsVisible) SETTINGS_VIEW_CARD else MAIN_VIEW_CARD,
        )
        settingsButton.toolTipText = if (settingsVisible) "Hide settings" else "Settings"
        settingsButton.border = if (settingsVisible) {
            CompoundBorder(MatteBorder(1, 1, 1, 1, RUN), EmptyBorder(0, 0, 0, 0))
        } else {
            defaultSettingsButtonBorder
        }
        contentCards.revalidate()
        contentCards.repaint()
    }

    private fun settingTile(title: String, content: JPanel.() -> Unit): JPanel {
        val body = JPanel(FlowLayout(FlowLayout.LEFT, 8, 0)).also {
            it.background = SURFACE
            it.content()
        }
        return JPanel(BorderLayout(0, 10)).also {
            it.background = SURFACE
            it.border = CompoundBorder(MatteBorder(1, 1, 1, 1, BORDER), EmptyBorder(14, 16, 14, 16))
            it.add(JBLabel(title).also { label -> label.foreground = TEXT }, BorderLayout.NORTH)
            it.add(body, BorderLayout.CENTER)
        }
    }

    private fun buildBody(): JComponent {
        val editorPanel = JPanel()
        editorPanel.layout = BoxLayout(editorPanel, BoxLayout.Y_AXIS)
        editorPanel.background = PANEL
        editorPanel.border = EmptyBorder(0, 0, 0, 0)
        editorPanel.add(labeled("Input", scroll(inputArea, 150), 150))
        editorPanel.add(labeled("Expected output", scroll(expectedArea, 135), 135))
        editorPanel.add(labeled("Actual output", scroll(actualArea, 135), 135))
        editorPanel.add(labeled("stderr", scroll(stderrArea, 115), 115))
        editorPanel.add(buildResultPanel())
        editorPanel.add(Box.createVerticalGlue())

        return JBScrollPane(editorPanel).also {
            it.border = null
            it.viewport.background = PANEL
            it.horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
        }
    }

    private fun buildResultPanel(): JComponent {
        resultLabel.isOpaque = true
        resultLabel.background = SURFACE
        resultLabel.border = CompoundBorder(MatteBorder(1, 1, 1, 1, BORDER), EmptyBorder(10, 12, 10, 12))
        resultLabel.maximumSize = Dimension(Int.MAX_VALUE, 48)
        return resultLabel
    }

    private fun labeled(label: String, component: JComponent, height: Int): JComponent {
        val panel = JPanel(BorderLayout(0, 6))
        panel.background = PANEL
        panel.border = EmptyBorder(8, 0, 8, 0)
        panel.add(JBLabel(label), BorderLayout.NORTH)
        panel.add(component, BorderLayout.CENTER)
        return fullWidth(panel, height + 34)
    }

    private fun scroll(area: JBTextArea, height: Int): JComponent {
        return JBScrollPane(area).also {
            it.preferredSize = Dimension(320, height)
            it.minimumSize = Dimension(100, height)
            it.border = MatteBorder(1, 1, 1, 1, BORDER)
            it.viewport.background = EDITOR
            it.setRowHeaderView(LineNumberGutter(area))
        }
    }

    private fun configureEditor(area: JBTextArea) {
        area.lineWrap = false
        area.font = Font(Font.MONOSPACED, Font.PLAIN, area.font.size)
        area.background = EDITOR
        area.foreground = TEXT
        area.caretColor = TEXT
        area.border = EmptyBorder(8, 10, 8, 10)
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

        targetLabel.text = currentIdentity.displayName
        targetLabel.border = CompoundBorder(MatteBorder(1, 1, 1, 1, BORDER), EmptyBorder(5, 12, 5, 12))
        targetLabel.toolTipText = currentIdentity.message
        timeoutSpinner.value = currentTargetCases.timeoutMillis.toInt()
        ignoreTrailingWhitespace.isSelected = currentTargetCases.ignoreTrailingWhitespace

        refreshTabs()
        selectCase(currentTargetCases.cases.firstOrNull())
        updateActions()
    }

    private fun addCase() {
        flushSelectedCase()
        val index = currentTargetCases.cases.size + 1
        val testCase = CphTestCase(name = "Case $index")
        currentTargetCases.cases.add(testCase)
        refreshTabs()
        selectCase(testCase)
        updateActions()
    }

    private fun deleteSelectedCase() {
        val testCase = selectedCase ?: return
        val index = currentTargetCases.cases.indexOfFirst { it.id == testCase.id }
        currentTargetCases.cases.remove(testCase)
        runtimeStates.remove(testCase.id)
        selectedCase = null
        refreshTabs()
        selectCase(currentTargetCases.cases.getOrNull(index.coerceAtMost(currentTargetCases.cases.lastIndex)))
        updateActions()
    }

    private fun runSelectedCase() {
        flushSelectedCase()
        val testCase = selectedCase ?: return
        runCases(listOf(testCase))
    }

    private fun runAllCases() {
        flushSelectedCase()
        runCases(currentTargetCases.cases.filter { it.enabled })
    }

    private fun runCases(cases: List<CphTestCase>) {
        if (cases.isEmpty() || running) return
        val identity = currentIdentity
        val timeoutMillis = currentTargetCases.timeoutMillis
        val ignoreTrailing = currentTargetCases.ignoreTrailingWhitespace
        runtimeStates.clear()
        cases.forEach { runtimeStates[it.id] = RuntimeTabState.QUEUED }
        refreshTabs()
        setRunning(true)

        object : Task.Backgroundable(project, "Running CPH samples", true) {
            override fun run(indicator: ProgressIndicator) {
                val runner = CphRunner(project)
                for ((index, testCase) in cases.withIndex()) {
                    if (indicator.isCanceled) break
                    ApplicationManager.getApplication().invokeLater {
                        runtimeStates[testCase.id] = RuntimeTabState.RUNNING
                        refreshTabs()
                        if (selectedCase?.id == testCase.id) renderSelectedCase()
                    }
                    indicator.text = "Running ${testCase.name}"
                    indicator.fraction = index.toDouble() / cases.size
                    val result = runner.runCase(identity, testCase, timeoutMillis, ignoreTrailing)
                    testCase.lastResult = result
                    ApplicationManager.getApplication().invokeLater {
                        runtimeStates.remove(testCase.id)
                        refreshTabs()
                        if (selectedCase?.id == testCase.id) {
                            renderSelectedCase()
                        }
                    }
                }
            }

            override fun onFinished() {
                ApplicationManager.getApplication().invokeLater {
                    runtimeStates.clear()
                    refreshTabs()
                    renderSelectedCase()
                    setRunning(false)
                }
            }
        }.queue()
    }

    private fun setRunning(value: Boolean) {
        running = value
        if (SwingUtilities.isEventDispatchThread()) {
            updateActions()
        } else {
            ApplicationManager.getApplication().invokeLater { updateActions() }
        }
    }

    private fun flushSelectedCase() {
        val testCase = selectedCase ?: return
        testCase.input = inputArea.text
        testCase.expectedOutput = expectedArea.text
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
        stderrArea.isEnabled = enabled

        if (testCase == null) {
            inputArea.text = ""
            expectedArea.text = ""
            actualArea.text = ""
            stderrArea.text = ""
            resultLabel.text = "No case selected"
            resultLabel.foreground = MUTED
        } else {
            inputArea.text = testCase.input
            expectedArea.text = testCase.expectedOutput
            actualArea.text = testCase.lastResult.actualOutput
            stderrArea.text = testCase.lastResult.stderr
            val status = statusFor(testCase)
            val style = styleFor(status)
            resultLabel.text = renderResult(testCase, status)
            resultLabel.foreground = style.foreground
        }
        refreshActualDiffHighlights()
        updateActions()
    }

    private fun refreshActualDiffHighlights() {
        actualArea.highlighter.removeAllHighlights()
        val testCase = selectedCase ?: return
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
        tabStrip.add(Box.createHorizontalGlue())
        tabStrip.revalidate()
        tabStrip.repaint()
    }

    private fun statusFor(testCase: CphTestCase): TabStatus {
        return when (runtimeStates[testCase.id]) {
            RuntimeTabState.RUNNING -> TabStatus.RUNNING
            RuntimeTabState.QUEUED -> TabStatus.QUEUED
            null -> when (testCase.lastResult.verdict) {
                CphVerdict.AC -> TabStatus.AC
                CphVerdict.WA -> TabStatus.WA
                CphVerdict.TLE -> TabStatus.TLE
                CphVerdict.RE -> TabStatus.RE
                CphVerdict.ERROR -> TabStatus.ERROR
                CphVerdict.NOT_RUN -> TabStatus.NOT_RUN
            }
        }
    }

    private fun renderResult(testCase: CphTestCase, status: TabStatus): String {
        if (status == TabStatus.NOT_RUN) return "Not run"
        if (status == TabStatus.QUEUED) return "Queued"
        if (status == TabStatus.RUNNING) return "Running ${testCase.name}"
        val result = testCase.lastResult
        val exit = result.exitCode?.let { ", exit $it" }.orEmpty()
        return "${status.icon} ${result.verdict} in ${result.durationMillis}ms$exit: ${result.message}"
    }

    private fun updateActions() {
        val hasCase = selectedCase != null
        val runnable = currentIdentity.runnable && !running
        addButton.isEnabled = !running
        refreshButton.isEnabled = !running
        settingsButton.isEnabled = !running
        deleteButton.isEnabled = hasCase && !running
        runSelectedButton.isEnabled = hasCase && runnable
        runAllButton.isEnabled = currentTargetCases.cases.any { it.enabled } && runnable
        timeoutSpinner.isEnabled = !running
        ignoreTrailingWhitespace.isEnabled = !running
    }

    private inner class CaseTab(
        private val index: Int,
        private val testCase: CphTestCase,
    ) : JPanel(BorderLayout()) {
        private val enabledToggle = JCheckBox()
        private val title = JBLabel()
        private val detail = JBLabel()

        init {
            val status = statusFor(testCase)
            val displayStatus = displayStatusForTab(testCase, status)
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
            preferredSize = Dimension(140, 60)
            minimumSize = Dimension(116, 60)
            maximumSize = Dimension(184, 60)
            cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)

            enabledToggle.isSelected = testCase.enabled
            enabledToggle.isEnabled = !running
            enabledToggle.isOpaque = false
            enabledToggle.toolTipText = if (testCase.enabled) "Enabled" else "Disabled"
            enabledToggle.preferredSize = Dimension(22, 22)
            enabledToggle.maximumSize = enabledToggle.preferredSize
            enabledToggle.addActionListener {
                testCase.enabled = enabledToggle.isSelected
                selectCase(testCase)
                refreshTabs()
                updateActions()
            }

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
            content.add(textPanel, BorderLayout.CENTER)
            content.add(enabledToggle, BorderLayout.EAST)
            content.toolTipText = toolTipText
            title.toolTipText = toolTipText
            detail.toolTipText = toolTipText
            textPanel.toolTipText = toolTipText

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

    private class JSeparatorPanel : JPanel() {
        init {
            preferredSize = Dimension(1, 26)
            maximumSize = preferredSize
            background = BORDER
        }
    }

    private class LineNumberGutter(private val area: JBTextArea) : JComponent(), DocumentListener {
        init {
            font = area.font
            foreground = MUTED
            background = EDITOR
            border = MatteBorder(0, 0, 0, 1, BORDER)
            area.document.addDocumentListener(this)
            updatePreferredSize()
        }

        override fun insertUpdate(e: DocumentEvent) = update()
        override fun removeUpdate(e: DocumentEvent) = update()
        override fun changedUpdate(e: DocumentEvent) = update()

        override fun paintComponent(g: Graphics) {
            super.paintComponent(g)
            g.color = background
            g.fillRect(0, g.clipBounds.y, width, g.clipBounds.height)
            g.color = foreground
            g.font = font

            val metrics = area.getFontMetrics(area.font)
            val lineHeight = metrics.height.coerceAtLeast(1)
            val topInset = area.insets.top
            val clip = g.clipBounds
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

    private enum class TabStatus(
        val label: String,
        val icon: String,
    ) {
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
        private val DIFF_BACKGROUND = Color(0x3A1F25)
        private val WARN = Color(0xF2A93B)
        private val RUN = Color(0x6EA2FF)
        private val DIFF_LINE_PAINTER = LineBackgroundPainter(DIFF_BACKGROUND)
        private const val MAIN_VIEW_CARD = "main"
        private const val SETTINGS_VIEW_CARD = "settings"

        private fun fullWidth(component: JComponent, height: Int): JComponent {
            component.maximumSize = Dimension(Int.MAX_VALUE, height)
            component.alignmentX = Component.LEFT_ALIGNMENT
            return component
        }

        private fun tabTitle(index: Int, status: TabStatus): String {
            return if (status == TabStatus.NOT_RUN) {
                "$index CASE"
            } else {
                "$index ${status.icon} ${status.label}"
            }
        }

        private fun displayStatusForTab(testCase: CphTestCase, status: TabStatus): TabStatus {
            return when (status) {
                TabStatus.RUNNING,
                TabStatus.QUEUED -> completedStatusForTab(testCase)
                else -> status
            }
        }

        private fun completedStatusForTab(testCase: CphTestCase): TabStatus {
            return when (testCase.lastResult.verdict) {
                CphVerdict.AC -> TabStatus.AC
                CphVerdict.WA -> TabStatus.WA
                CphVerdict.TLE -> TabStatus.TLE
                CphVerdict.RE -> TabStatus.RE
                CphVerdict.ERROR -> TabStatus.ERROR
                CphVerdict.NOT_RUN -> TabStatus.NOT_RUN
            }
        }

        private fun tabDetail(testCase: CphTestCase, status: TabStatus): String {
            return when (status) {
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
                TabStatus.AC,
                TabStatus.WA,
                TabStatus.TLE,
                TabStatus.RE -> "${testCase.name}: ${testCase.lastResult.verdict} in ${formatDuration(testCase.lastResult.durationMillis)}"
                TabStatus.ERROR -> "${testCase.name}: error in ${formatDuration(testCase.lastResult.durationMillis)}"
                TabStatus.RUNNING -> "${testCase.name}: running"
                TabStatus.QUEUED -> "${testCase.name}: queued"
                TabStatus.NOT_RUN -> "${testCase.name}: not run"
            }
        }

        private fun formatDuration(durationMillis: Long): String {
            return if (durationMillis >= 1000) {
                val seconds = durationMillis / 1000.0
                "%.1fs".format(seconds)
            } else {
                "${durationMillis}ms"
            }
        }

        private fun styleFor(status: TabStatus): TabStyle {
            val color = when (status) {
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
