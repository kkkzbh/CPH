package org.kkkzbh.cph.server

import com.intellij.openapi.options.Configurable
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import org.kkkzbh.cph.submit.CphCfLanguage
import org.kkkzbh.cph.submit.CphSubmitSettings
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JSpinner
import javax.swing.SpinnerNumberModel

internal class CphImportSettingsConfigurable : Configurable {
    private val enabledCheckBox = JBCheckBox("启用 Competitive Companion 接收服务")
    private val portSpinner = JSpinner(
        SpinnerNumberModel(CPH_DEFAULT_PORT, CPH_MIN_PORT, CPH_MAX_PORT, 1)
    )
    private val sourceRootField = JBTextField()
    private val pathTemplateField = JBTextField()
    private val templateArea = JBTextArea(8, 60)
    private val overwriteCheckBox = JBCheckBox("重新导入同一题目时覆盖已存在的源文件")
    private val statusLabel = JBLabel()

    private val cfLanguageCombo = JComboBox(CphCfLanguage.entries.toTypedArray())
    private var cfLanguageInitial = CphCfLanguage.CPP_17

    override fun getDisplayName(): String = "CPH 竞赛伴侣"

    override fun createComponent(): JComponent {
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
        addRow("监听端口：", portSpinner)
        addFullRow(
            JBLabel("Competitive Companion 默认端口列表已包含 10043，通常无需在浏览器侧改动。")
                .apply { foreground = UIUtil.getInactiveTextColor() }
        )

        addFullRow(statusLabel)

        addFullRow(separator(8))

        addRow("源代码根目录（相对项目）：", sourceRootField)
        addRow("路径模板：", pathTemplateField)
        addFullRow(
            JBLabel("可用变量：\${contest} \${index} \${name} \${slug} \${source}")
                .apply { foreground = UIUtil.getInactiveTextColor() }
        )
        addFullRow(overwriteCheckBox)

        addFullRow(JBLabel("C++ 代码模板："))
        gbc.gridx = 0; gbc.gridwidth = 2; gbc.fill = GridBagConstraints.BOTH; gbc.weightx = 1.0; gbc.weighty = 1.0
        panel.add(JScrollPane(templateArea), gbc)
        gbc.gridy++; gbc.weighty = 0.0; gbc.gridwidth = 1

        addFullRow(separator(8))
        addFullRow(JBLabel("Codeforces 一键提交").apply {
            font = font.deriveFont(font.style or java.awt.Font.BOLD, font.size + 1f)
        })
        addRow("Language：", cfLanguageCombo)
        addFullRow(
            JBLabel("提交由 CPH Target Runner 浏览器扩展在当前 Codeforces 登录态中完成；CLion 不再保存 CF 账号或密码。")
                .apply { foreground = UIUtil.getInactiveTextColor() }
        )
        addFullRow(
            JBLabel("点击 CPH 工具栏 📤 即提交（无确认框）：当前编辑器文件 → 浏览器活动 CF Tab。需先在浏览器登录 CF 并安装 CPH Target Runner 浏览器扩展。")
                .apply { foreground = UIUtil.getInactiveTextColor() }
        )

        addFullRow(separator(8))
        addFullRow(JBLabel("使用说明").apply {
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
        val text = """
            1. 在 Chrome 应用商店搜索并安装 “Competitive Companion” 扩展。
            2. 在 CLion 项目里勾选上方“启用 Competitive Companion 接收服务”，保存设置。
            3. 打开任意 Codeforces 题面（例如 https://codeforces.com/contest/1/problem/A）。
            4. 点击浏览器工具栏上 Competitive Companion 的绿色加号按钮。
            5. 插件会自动：
               • 在 ${"$"}{源代码根目录}/${"$"}{contest}/${"$"}{index}.cpp 创建源文件并打开；
               • 注册一个单文件 (C/C++ File) 运行配置并设为当前运行目标；
               • 将题目样例写入 CPH 工具窗口中对应的 Cases。
            6. 在 CPH 工具窗口点击 Run All 即可一键评测。

            常见问题：
            • 浏览器点了没反应？检查上方“当前状态”是否为“运行中”，并确认 Competitive Companion
              选项页 → Custom ports 中包含此处显示的端口（默认列表已含 10043）。
            • 端口被占用？换一个上方端口（例如 10044/10046/10047 等 CC 默认列表中的其它端口）。
            • 多个 CLion 同时打开？请求会发送到最近聚焦的窗口。
        """.trimIndent()

        val area = JBTextArea(text)
        area.isEditable = false
        area.lineWrap = true
        area.wrapStyleWord = true
        area.background = UIUtil.getPanelBackground()
        area.border = JBUI.Borders.empty(4)
        return area
    }

    private fun refreshStatus() {
        val running = CphCompetitiveCompanionServer.getInstance().status
        val (text, color) = when (running) {
            is CphServerStatus.Running ->
                "当前状态：✓ 运行中（监听 127.0.0.1:${running.port}）" to JBColor.GREEN.darker()
            CphServerStatus.Disabled ->
                "当前状态：● 已禁用（勾选上方启用项以开启）" to UIUtil.getInactiveTextColor()
            CphServerStatus.Stopped ->
                "当前状态：● 未启动" to UIUtil.getInactiveTextColor()
            is CphServerStatus.Error ->
                "当前状态：✗ 启动失败（端口 ${running.port}）：${running.message}" to JBColor.RED
        }
        statusLabel.text = text
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
            (cfLanguageCombo.selectedItem as? CphCfLanguage ?: CphCfLanguage.CPP_17) != cfLanguageInitial
    }

    override fun apply() {
        val current = CphImportSettings.getInstance().state
        current.sourceRoot = sourceRootField.text.ifBlank { CPH_DEFAULT_SOURCE_ROOT }
        current.pathTemplate = pathTemplateField.text.ifBlank { CPH_DEFAULT_PATH_TEMPLATE }
        current.port = CphImportSettings.clampPort(portSpinner.value as Int)
        current.cppTemplate = templateArea.text.ifEmpty { CPH_DEFAULT_CPP_TEMPLATE }
        current.overwriteExisting = overwriteCheckBox.isSelected
        current.enabled = enabledCheckBox.isSelected
        CphCompetitiveCompanionServer.getInstance().reload()

        val cfSettings = CphSubmitSettings.getInstance()
        cfSettings.state.defaultLang = (cfLanguageCombo.selectedItem as? CphCfLanguage ?: CphCfLanguage.CPP_17).name
        cfLanguageInitial = cfSettings.language()
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

        val cfState = CphSubmitSettings.getInstance().state
        cfLanguageCombo.selectedItem = CphCfLanguage.fromKey(cfState.defaultLang)
        cfLanguageInitial = CphCfLanguage.fromKey(cfState.defaultLang)
        refreshStatus()
    }
}
