package org.kkkzbh.cph.server

import com.intellij.openapi.options.Configurable
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import org.kkkzbh.cph.CphCodeforcesSubmitFeature
import org.kkkzbh.cph.CphPluginSettings
import org.kkkzbh.cph.CphText
import org.kkkzbh.cph.CphUiLanguage
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import javax.swing.JComponent
import javax.swing.JComboBox
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JSpinner
import javax.swing.SpinnerNumberModel

internal class CphImportSettingsConfigurable : Configurable {
    private val enabledCheckBox = JBCheckBox()
    private val languageCombo = JComboBox(CphUiLanguage.entries.toTypedArray())
    private val portSpinner = JSpinner(
        SpinnerNumberModel(CPH_DEFAULT_PORT, CPH_MIN_PORT, CPH_MAX_PORT, 1)
    )
    private val sourceRootField = JBTextField()
    private val pathTemplateField = JBTextField()
    private val templateArea = JBTextArea(8, 60)
    private val overwriteCheckBox = JBCheckBox()
    private val statusLabel = JBLabel()

    override fun getDisplayName(): String = "CPH Target Runner"

    override fun createComponent(): JComponent {
        val text = CphText.current().importSettings()
        enabledCheckBox.text = text.enableServer
        overwriteCheckBox.text = text.overwriteExisting
        val panel = JPanel(GridBagLayout())
        panel.border = JBUI.Borders.empty(12)
        val gbc = GridBagConstraints().apply {
            gridx = 0
            gridy = 0
            anchor = GridBagConstraints.WEST
            insets = Insets(4, 4, 4, 4)
            fill = GridBagConstraints.HORIZONTAL
            weightx = 1.0
        }

        fun addRow(label: String, component: JComponent) {
            gbc.gridx = 0; gbc.weightx = 0.0; gbc.fill = GridBagConstraints.NONE; gbc.gridwidth = 1
            panel.add(JBLabel(label), gbc)
            gbc.gridx = 1; gbc.weightx = 1.0; gbc.fill = GridBagConstraints.HORIZONTAL
            panel.add(component, gbc)
            gbc.gridy++
        }

        fun addFullRow(component: JComponent) {
            gbc.gridx = 0; gbc.gridwidth = 2; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0
            panel.add(component, gbc)
            gbc.gridy++
            gbc.gridwidth = 1
        }

        addFullRow(enabledCheckBox)
        addRow(CphText.current().interfaceLanguage, languageCombo)
        addRow(text.port, portSpinner)
        addFullRow(
            JBLabel(text.defaultPorts)
                .apply { foreground = UIUtil.getInactiveTextColor() }
        )

        addFullRow(statusLabel)

        addFullRow(separator(8))

        addRow(text.sourceRoot, sourceRootField)
        addRow(text.pathTemplate, pathTemplateField)
        addFullRow(
            JBLabel(text.variables)
                .apply { foreground = UIUtil.getInactiveTextColor() }
        )
        addFullRow(overwriteCheckBox)

        addFullRow(JBLabel(text.cppTemplate))
        gbc.gridx = 0; gbc.gridwidth = 2; gbc.fill = GridBagConstraints.BOTH; gbc.weightx = 1.0; gbc.weighty = 1.0
        panel.add(JScrollPane(templateArea), gbc)
        gbc.gridy++; gbc.weighty = 0.0; gbc.gridwidth = 1

        if (CphCodeforcesSubmitFeature.isEnabled()) {
            addFullRow(separator(8))
            addFullRow(JBLabel(text.codeforcesSubmit).apply {
                font = font.deriveFont(font.style or java.awt.Font.BOLD, font.size + 1f)
            })
            addFullRow(
                JBLabel(text.submitLanguageNote)
                    .apply { foreground = UIUtil.getInactiveTextColor() }
            )
            addFullRow(
                JBLabel(text.submitClickNote)
                    .apply { foreground = UIUtil.getInactiveTextColor() }
            )
        }

        addFullRow(separator(8))
        addFullRow(JBLabel(text.tutorialTitle).apply {
            font = font.deriveFont(font.style or java.awt.Font.BOLD, font.size + 1f)
        })
        addFullRow(tutorialBox())

        return panel
    }

    private fun separator(height: Int): JComponent {
        return JBLabel(" ").apply {
            border = JBUI.Borders.empty(height, 0, height, 0)
        }
    }

    private fun tutorialBox(): JComponent {
        val area = JBTextArea(CphText.current().importSettings().tutorial())
        area.isEditable = false
        area.lineWrap = true
        area.wrapStyleWord = true
        area.background = UIUtil.getPanelBackground()
        area.border = JBUI.Borders.empty(4)
        return area
    }

    private fun refreshStatus() {
        val running = CphCompetitiveCompanionServer.getInstance().status
        val strings = CphText.current().importSettings()
        val (statusText, color) = when (running) {
            is CphServerStatus.Running ->
                strings.statusRunning(running.port) to JBColor.GREEN.darker()
            CphServerStatus.Disabled ->
                strings.statusDisabled() to UIUtil.getInactiveTextColor()
            CphServerStatus.Stopped ->
                strings.statusStopped() to UIUtil.getInactiveTextColor()
            is CphServerStatus.Error ->
                strings.statusError(running.port, running.message) to JBColor.RED
        }
        statusLabel.text = statusText
        statusLabel.foreground = color
    }

    override fun isModified(): Boolean {
        val state = CphImportSettings.getInstance().state
        return sourceRootField.text != state.sourceRoot ||
            pathTemplateField.text != state.pathTemplate ||
            (portSpinner.value as Int) != state.port ||
            templateArea.text != state.cppTemplate ||
            overwriteCheckBox.isSelected != state.overwriteExisting ||
            enabledCheckBox.isSelected != state.enabled ||
            (languageCombo.selectedItem as? CphUiLanguage ?: CphUiLanguage.ZH_CN).id !=
                CphPluginSettings.getInstance().state.uiLanguage
    }

    override fun apply() {
        val current = CphImportSettings.getInstance().state
        current.sourceRoot = sourceRootField.text.ifBlank { CPH_DEFAULT_SOURCE_ROOT }
        current.pathTemplate = pathTemplateField.text.ifBlank { CPH_DEFAULT_PATH_TEMPLATE }
        current.port = CphImportSettings.clampPort(portSpinner.value as Int)
        current.cppTemplate = templateArea.text.ifEmpty { CPH_DEFAULT_CPP_TEMPLATE }
        current.overwriteExisting = overwriteCheckBox.isSelected
        current.enabled = enabledCheckBox.isSelected
        CphPluginSettings.getInstance().setUiLanguage(languageCombo.selectedItem as? CphUiLanguage ?: CphUiLanguage.ZH_CN)
        CphCompetitiveCompanionServer.getInstance().reload()

        refreshStatus()
    }

    override fun reset() {
        val state = CphImportSettings.getInstance().state
        sourceRootField.text = state.sourceRoot
        pathTemplateField.text = state.pathTemplate
        portSpinner.value = state.port
        templateArea.text = state.cppTemplate
        overwriteCheckBox.isSelected = state.overwriteExisting
        enabledCheckBox.isSelected = state.enabled
        languageCombo.selectedItem = CphUiLanguage.current()

        refreshStatus()
    }
}
