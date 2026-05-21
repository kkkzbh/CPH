package org.kkkzbh.cph.server

import com.intellij.icons.AllIcons
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import org.kkkzbh.cph.CphPluginSettings
import org.kkkzbh.cph.CphText
import org.kkkzbh.cph.CphUiLanguage
import java.awt.BorderLayout
import java.awt.GridLayout
import javax.swing.JButton
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
    private val templatePathField = TextFieldWithBrowseButton()
    private val templateArea = JBTextArea(8, 60)
    private val overwriteCheckBox = JBCheckBox()
    private val statusLabel = JBLabel()
    private val pathVariablesButton = JButton(AllIcons.General.ContextHelp)
    private val cppVariablesButton = JButton(AllIcons.General.ContextHelp)
    private var pathVariablesButtonConfigured = false
    private var cppVariablesButtonConfigured = false
    private var templatePathFieldConfigured = false

    override fun getDisplayName(): String = "CPH Target Runner"

    override fun createComponent(): JComponent {
        val text = CphText.current().importSettings()
        enabledCheckBox.text = text.enableServer
        overwriteCheckBox.text = text.overwriteExisting
        configurePathVariablesButton()
        configureCppVariablesButton()
        configureTemplatePathField()

        return panel {
            group(text.importReceiver) {
                row {
                    cell(enabledCheckBox)
                }
                row(CphText.current().interfaceLanguage) {
                    cell(languageCombo)
                }
                row(text.port) {
                    cell(portSpinner)
                }
                row(text.status) {
                    cell(statusLabel)
                }
            }
            group(text.fileCreation) {
                row(text.sourceRoot) {
                    cell(sourceRootField).align(AlignX.FILL)
                }
                row(text.pathTemplate) {
                    cell(pathTemplateField).align(AlignX.FILL)
                    cell(pathVariablesButton)
                }
                row {
                    cell(overwriteCheckBox)
                }
            }
            group(text.cppTemplateGroup) {
                row(text.cppTemplatePath) {
                    cell(templatePathField).align(AlignX.FILL)
                    cell(cppVariablesButton)
                }
                row {
                    cell(JScrollPane(templateArea)).align(AlignX.FILL)
                }
            }
        }
    }

    private fun configurePathVariablesButton() {
        if (pathVariablesButtonConfigured) return
        pathVariablesButtonConfigured = true
        configureVariablesButton(
            button = pathVariablesButton,
            toolTip = CphText.current().importSettings().variables,
        ) { CphText.current().importSettings().pathVariableDescriptions() }
    }

    private fun configureCppVariablesButton() {
        if (cppVariablesButtonConfigured) return
        cppVariablesButtonConfigured = true
        configureVariablesButton(
            button = cppVariablesButton,
            toolTip = CphText.current().importSettings().cppTemplateVariables,
        ) { CphText.current().importSettings().cppTemplateVariableDescriptions() }
    }

    private fun configureVariablesButton(
        button: JButton,
        toolTip: String,
        variables: () -> List<Pair<String, String>>,
    ) {
        button.isFocusable = false
        button.isBorderPainted = false
        button.isContentAreaFilled = false
        button.isOpaque = false
        button.toolTipText = toolTip
        button.border = JBUI.Borders.empty(2)
        button.addActionListener {
            JBPopupFactory.getInstance()
                .createComponentPopupBuilder(variablesPanel(variables()), button)
                .setRequestFocus(false)
                .setCancelOnClickOutside(true)
                .setCancelOnWindowDeactivation(true)
                .createPopup()
                .showUnderneathOf(button)
        }
    }

    private fun configureTemplatePathField() {
        if (templatePathFieldConfigured) return
        templatePathFieldConfigured = true
        val text = CphText.current().importSettings()
        val descriptor = FileChooserDescriptorFactory.createSingleFileDescriptor()
            .withTitle(text.chooseCppTemplate)
        templatePathField.addBrowseFolderListener(
            text.chooseCppTemplate,
            text.chooseCppTemplateDescription,
            null,
            descriptor,
        )
    }

    private fun variablesPanel(variables: List<Pair<String, String>>): JComponent {
        val rows = JPanel(GridLayout(0, 1, 0, JBUI.scale(6))).apply {
            isOpaque = false
            variables.forEach { (variable, description) ->
                add(JBLabel("<html><b>$variable</b>&nbsp;&nbsp;$description</html>"))
            }
        }
        return JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(10, 12)
            background = UIUtil.getPanelBackground()
            add(rows, BorderLayout.CENTER)
        }
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
            templatePathField.text != state.cppTemplatePath ||
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
        current.cppTemplatePath = templatePathField.text.trim()
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
        templatePathField.text = state.cppTemplatePath
        portSpinner.value = state.port
        templateArea.text = state.cppTemplate
        overwriteCheckBox.isSelected = state.overwriteExisting
        enabledCheckBox.isSelected = state.enabled
        languageCombo.selectedItem = CphUiLanguage.current()

        refreshStatus()
    }
}
