package org.kkkzbh.cph

internal enum class CphUiLanguage(val id: String, val displayName: String) {
    ZH_CN("zh_cn", "简体中文"),
    ENGLISH("en", "English");

    override fun toString(): String = displayName

    companion object {
        fun normalize(languageId: String?): CphUiLanguage {
            return when (languageId) {
                ENGLISH.id -> ENGLISH
                ZH_CN.id -> ZH_CN
                else -> ZH_CN
            }
        }

        fun current(): CphUiLanguage =
            runCatching { normalize(CphPluginSettings.getInstance().state.uiLanguage) }.getOrDefault(ZH_CN)
    }
}

internal object CphText {
    private val zh = CphUiTexts(CphUiLanguage.ZH_CN)
    private val en = CphUiTexts(CphUiLanguage.ENGLISH)

    fun current(): CphUiTexts = forLanguage(CphUiLanguage.current())

    fun forLanguage(language: CphUiLanguage): CphUiTexts {
        return when (language) {
            CphUiLanguage.ZH_CN -> zh
            CphUiLanguage.ENGLISH -> en
        }
    }
}

internal class CphUiTexts(private val language: CphUiLanguage) {
    private val zh: Boolean
        get() = language == CphUiLanguage.ZH_CN

    val settingsTab = if (zh) "设置" else "Settings"
    val utilityTab = if (zh) "实用" else "Utilities"
    val themesTab = if (zh) "主题" else "Themes"
    val help = if (zh) "帮助" else "Help"
    val interfaceLanguage = if (zh) "界面语言:" else "Interface language:"
    val runSettings = if (zh) "运行设置" else "Run Settings"
    val outputSettings = if (zh) "输出设置" else "Output Settings"
    val general = if (zh) "常规" else "General"
    val compileAndRun = if (zh) "编译与运行" else "Compile & Run"
    val outputAndDisplay = if (zh) "输出与显示" else "Output & Display"
    val submitSettings = if (zh) "提交设置" else "Submit Settings"
    val shortcuts = if (zh) "快捷键" else "Shortcuts"
    val singleFileMode = if (zh) "纯单文件模式" else "Pure single-file mode"
    val ignoreTrailingWhitespace = if (zh) "忽略行尾空格和多余换行" else "Ignore trailing whitespace and extra newlines"
    val outputSplit = if (zh) "双栏显示标准输出 / 期望输出" else "Show Standard / Expected side by side"
    val noExpectedMode = if (zh) "无期望输出模式" else "No expected output mode"
    val showStderr = if (zh) "显示标准错误" else "Show standard error"
    val compactCaseTabs = if (zh) "紧凑样例栏" else "Compact case bar"
    val confidentSubmit = if (zh) "自信模式：本地全 AC 后自动提交 CF" else "Confident mode: auto-submit to CF after local AC"
    val parallelCaseRun = if (zh) "并行运行样例" else "Run cases in parallel"
    val parallelCaseRunTooltip = if (zh) {
        "并发运行可以更快看到多个测试点的结果是否正确，但抢占CPU可能会导致每个case消耗的时间存在较大误差"
    } else {
        "Parallel runs can show multiple case results faster, but CPU contention can make each case's runtime much less accurate."
    }
    val gccBitsPch = if (zh) "加速万能头编译" else "Accelerate bits/stdc++.h compilation"
    val gccBitsPchTooltip = if (zh) {
        """
        仅限GCC编译器使用
        GCC16及以上利用GCM为<bits/stdc++.h>加速，且代码可以使用import std
        GCC15及以下利用PCH对<bits/stdc++.h>加速
        """.trimIndent()
    } else {
        """
        GCC only.
        GCC 16+ uses GCM to accelerate <bits/stdc++.h>, and code may use import std.
        GCC 15 and below use PCH to accelerate <bits/stdc++.h>.
        """.trimIndent()
    }
    val workingDirectory = if (zh) "工作目录配置:" else "Working directory:"
    val timeLimits = if (zh) "时间限制:" else "Time limit:"
    val cppStandard = if (zh) "C++ 标准:" else "C++ standard:"
    val compileOptions = if (zh) "编译选项:" else "Compile options:"
    val fontSize = if (zh) "字体大小:" else "Font size:"
    val workMode = if (zh) "工作模式:" else "Work mode:"
    val outputComparison = if (zh) "输出比较:" else "Output comparison:"
    val displayMode = if (zh) "显示方式:" else "Display mode:"
    val displayFontSize = if (zh) "显示字体大小:" else "Display font size:"
    val input = if (zh) "输入" else "Input"
    val expectedOutput = if (zh) "期望输出" else "Expected output"
    val standardOutput = if (zh) "标准输出" else "Standard output"
    val standardError = if (zh) "标准错误" else "Standard error"
    val runAllShortcut = if (zh) "全局运行快捷键" else "Run all shortcut"
    val runSelectedShortcut = if (zh) "单CASE运行快捷键" else "Run case shortcut"
    val debugSelectedShortcut = if (zh) "单CASE调试快捷键" else "Debug case shortcut"
    val submitShortcut = if (zh) "提交 CF 快捷键" else "Submit CF shortcut"
    val unsetShortcut = if (zh) "未设置" else "Not set"
    val shortcutTooltip = if (zh) {
        "按下快捷键组合；Esc、Backspace 或 Delete 清空。"
    } else {
        "Press a shortcut; Esc, Backspace, or Delete clears it."
    }
    val settingsReturnHint = if (zh) "提示：再次点击设置按钮可回到主界面" else "Tip: click Settings again to return"
    val codeforcesSubmitTitle = if (zh) "Codeforces 远程提交" else "Codeforces Remote Submit"
    val codeforcesSubmitSummary = if (zh) {
        "使用浏览器当前 Codeforces 题面和登录态提交当前 C++ 文件。"
    } else {
        "Submit the current C++ file with the active Codeforces problem tab and browser login session."
    }
    val eapRepositoryTitle = if (zh) "EAP 版本推送" else "EAP Update Channel"
    val eapRepositorySummary = if (zh) {
        "订阅 JetBrains Marketplace EAP 仓库，接收 CPH 测试版更新。测试版有新功能，但很可能更不稳定。"
    } else {
        "Subscribe to the JetBrains Marketplace EAP repository for CPH test builds. Test builds include new features, but are likely less stable."
    }
    val classicThemeSummary = if (zh) {
        "沿用当前 CPH 深色界面与状态配色。"
    } else {
        "Use the classic CPH dark interface and status colors."
    }
    val aveMujicaThemeSummary = if (zh) {
        "Ave Mujica 暗色面板、金色操作高亮与角色功能图标。"
    } else {
        "Ave Mujica dark panel, gold action highlights, and character function icons."
    }
    val defaultThemeName = if (zh) "默认主题" else "Default"
    val enabled = if (zh) "已启用" else "Enabled"
    val enable = if (zh) "启用" else "Enable"
    val disable = if (zh) "禁用" else "Disable"
    val startCph = if (zh) "启动 CPH" else "Start CPH"
    val run = if (zh) "运行" else "Run"
    val debug = if (zh) "调试" else "Debug"
    val chooseWorkingDirectory = if (zh) "选择工作目录" else "Choose Working Directory"
    val dragToResize = if (zh) "拖拽调整大小" else "Drag to resize"
    val addCase = if (zh) "新增样例" else "Add case"
    val deleteCase = if (zh) "删除样例" else "Delete case"
    val cphDocs = if (zh) "打开 CPH 文档" else "Open CPH documentation"
    val settings = if (zh) "设置" else "Settings"
    val hideSettings = if (zh) "隐藏设置" else "Hide settings"
    val runAllEnabledCases = if (zh) "运行全部启用样例" else "Run all enabled cases"
    val resetCases = if (zh) "删除全部样例并创建 Case 1" else "Delete all cases and create Case 1"
    val runThisCase = if (zh) "运行当前样例" else "Run this case"
    val debugThisCase = if (zh) "用当前运行目标调试此样例" else "Debug this case with the selected run target"
    val singleFileTooltip = if (zh) {
        "自动为当前 .cpp 文件选择或创建 C/C++ File 运行目标"
    } else {
        "Automatically select or create the C/C++ File run target for the focused .cpp file"
    }
    val workingDirectoryTooltip = if (zh) "CPH 管理的 C/C++ File 运行目标工作目录" else "Working directory for CPH-managed C/C++ File run targets"
    val chooseWorkingDirectoryTooltip = if (zh) "选择工作目录" else "Choose working directory"
    val defaultThemeEnabled = if (zh) "默认主题已启用" else "Default theme is enabled"
    val enableDefaultTheme = if (zh) "启用默认主题" else "Enable default theme"
    val aveMujicaThemeEnabled = if (zh) "Ave Mujica 主题已启用" else "Ave Mujica theme is enabled"
    val enableAveMujicaTheme = if (zh) "启用 Ave Mujica 主题" else "Enable Ave Mujica theme"
    val install = if (zh) "安装" else "Install"
    val update = if (zh) "更新" else "Update"
    val retry = if (zh) "重试" else "Retry"
    val installing = if (zh) "安装中..." else "Installing..."
    val aveMujicaThemeNotInstalled = if (zh) {
        "首次使用需要下载 Ave Mujica 主题资源"
    } else {
        "Download the Ave Mujica theme assets before first use"
    }
    val aveMujicaThemeUpdateAvailable = if (zh) {
        "Ave Mujica 主题资源有可用更新"
    } else {
        "Ave Mujica theme assets have an update"
    }
    val installingAveMujicaTheme = if (zh) "安装 Ave Mujica 主题资源" else "Installing Ave Mujica theme assets"
    val aveMujicaThemeInstalled = if (zh) "Ave Mujica 主题资源已安装" else "Ave Mujica theme assets installed"
    val submitDisabledSingleFile = if (zh) "提交 Codeforces 前需启用纯单文件模式" else "Enable pure single-file mode before submitting to Codeforces"
    val activeTabNotCodeforces = if (zh) "活动标签页不是 Codeforces 题面" else "Active tab is not a Codeforces problem page"
    val noActiveCodeforcesTab = if (zh) {
        "没有活动的 Codeforces 标签页，请安装 CPH Target Runner 浏览器扩展"
    } else {
        "No active Codeforces tab - install the CPH Target Runner browser extension"
    }
    val codeforcesDisableTooltip = if (zh) "禁用 Codeforces 远程提交" else "Disable Codeforces remote submit"
    val codeforcesEnableTooltip = if (zh) "启用 Codeforces 远程提交" else "Enable Codeforces remote submit"
    val codeforcesSubmitDocs = if (zh) "打开 Codeforces 远程提交教程" else "Open Codeforces remote submit tutorial"
    val eapRepositoryDisableTooltip = if (zh) "取消订阅 EAP 版本推送" else "Unsubscribe from EAP updates"
    val eapRepositoryEnableTooltip = if (zh) "启用 EAP 版本推送" else "Enable EAP updates"
    val eapRepositoryEnabledNotification = if (zh) {
        "已启用 CPH EAP 版本推送。可在插件更新中检查测试版。"
    } else {
        "CPH EAP updates are enabled. Check plugin updates to receive test builds."
    }
    val eapRepositoryDisabledNotification = if (zh) {
        "已禁用 CPH EAP 版本推送。"
    } else {
        "CPH EAP updates are disabled."
    }
    fun eapRepositoryUpdateFailed(message: String): String {
        return if (zh) "EAP 版本推送设置失败：$message" else "Failed to update EAP repository: $message"
    }
    val cppFollowTarget = if (zh) "跟随 Target" else "Follow target"

    fun submitCurrentFile(displayId: String): String {
        return if (zh) "提交当前文件 -> $displayId" else "Submit current file -> $displayId"
    }

    fun duplicateShortcutMessage(actionNames: List<String>, shortcut: String): String {
        return if (zh) {
            "${actionNames.joinToString("、")} 使用了相同快捷键：$shortcut"
        } else {
            "${actionNames.joinToString(", ")} use the same shortcut: $shortcut"
        }
    }

    fun shortcutActionName(action: CphShortcutAction): String {
        return when (action) {
            CphShortcutAction.RUN_ALL -> runAllShortcut
            CphShortcutAction.RUN_SELECTED_CASE -> runSelectedShortcut
            CphShortcutAction.DEBUG_SELECTED_CASE -> debugSelectedShortcut
            CphShortcutAction.SUBMIT -> submitShortcut
        }
    }

    fun themeName(themeId: String): String {
        return when (themeId) {
            CphThemeId.CLASSIC -> defaultThemeName
            CphThemeId.AVE_MUJICA -> "Ave Mujica"
            else -> defaultThemeName
        }
    }

    fun aveMujicaThemeInstallFailed(message: String): String {
        return if (zh) "Ave Mujica 主题资源安装失败：$message" else "Failed to install Ave Mujica theme assets: $message"
    }

    fun caseRunning(name: String): String = if (zh) "$name：运行中" else "$name: running"
    fun caseQueued(name: String): String = if (zh) "$name：等待中" else "$name: queued"
    fun caseNotRun(name: String): String = if (zh) "$name：未运行" else "$name: not run"
    fun caseOk(name: String, duration: String): String = if (zh) "$name：OK，用时 $duration" else "$name: OK in $duration"
    fun caseVerdict(name: String, verdict: CphVerdict, duration: String): String =
        if (zh) "$name：$verdict，用时 $duration" else "$name: $verdict in $duration"

    fun autoSubmitAllAc(): String {
        return if (zh) "CPH 自信模式：本地全部 AC，自动提交 Codeforces" else "CPH confident mode: all local cases AC, submitting to Codeforces"
    }

    fun debugPreparingStatus(caseName: String): String =
        if (zh) "CPH 调试：准备 $caseName" else "CPH Debug: preparing $caseName"

    fun debugLaunchingStatus(caseName: String): String =
        if (zh) "CPH 调试：启动 $caseName" else "CPH Debug: launching $caseName"

    fun preparingDebugTask(): String =
        if (zh) "准备 CPH 调试" else "Preparing CPH debug"

    fun preparingCase(caseName: String): String =
        if (zh) "准备 $caseName" else "Preparing $caseName"

    fun runningSamplesTask(): String =
        if (zh) "运行 CPH 样例" else "Running CPH samples"

    fun preparingRunTarget(): String =
        if (zh) "准备 CPH 运行目标" else "Preparing CPH run target"

    fun runningSamples(count: Int): String =
        if (zh) "正在运行 $count 个 CPH 样例" else "Running $count CPH samples"

    fun completedSamples(done: Int, total: Int): String =
        if (zh) "已完成 $done/$total 个 CPH 样例" else "Completed $done/$total CPH samples"

    fun sampleFailedTitle(): String =
        if (zh) "CPH 样例失败" else "CPH sample failed"

    fun debugFailedTitle(): String =
        if (zh) "CPH 调试失败" else "CPH debug failed"

    fun debugStatus(message: String): String =
        if (zh) "CPH 调试：$message" else "CPH Debug: $message"

    val cphErrorTitle = if (zh) "CPH 错误" else "CPH error"
    val errorDetailsTitle = if (zh) "CPH 错误详情" else "CPH Error Details"
    val viewDetailedLog = if (zh) "查看详细日志" else "View detailed log"
    val reportError = if (zh) "报告错误" else "Report error"
    val copyLog = if (zh) "复制日志" else "Copy log"
    val copy = if (zh) "复制" else "Copy"
    val setExpectedShort = if (zh) "设置" else "Set"
    val doneShort = if (zh) "完成" else "Done"
    val setActualAsExpectedTooltip = if (zh) "将标准输出设置到期望输出" else "Set Standard output as Expected output"
    val actualSetAsExpected = if (zh) "已将标准输出设置到期望输出" else "Standard output set as Expected output"

    fun copySection(section: String): String {
        return if (zh) "复制$section" else "Copy $section"
    }

    fun copiedSection(section: String): String {
        return if (zh) "已复制${section}到剪贴板" else "$section copied to clipboard"
    }

    fun shortcutSettingsDuplicate(message: String): String {
        return if (zh) "CPH 快捷键设置：$message" else "CPH shortcut settings: $message"
    }

    fun submitAlreadyInFlight(): String =
        if (zh) "已有提交正在进行。" else "A submission is already in flight."

    fun submitRequiresSingleFile(): String =
        if (zh) "Codeforces 提交需要启用纯单文件模式。请先在 CPH 设置中启用。" else "Codeforces submit requires pure single-file mode. Enable pure single-file mode in CPH settings first."

    fun submitNoActiveTab(): String =
        if (zh) "没有活动的 Codeforces 标签页。请安装或打开 CPH Target Runner 浏览器扩展，并聚焦 CF 题面。" else "No active Codeforces tab. Install/open the CPH Target Runner browser extension and focus a CF problem page."

    fun submitActiveTabInvalid(url: String): String =
        if (zh) "活动标签页不是 Codeforces 题面：$url" else "Active tab is not a Codeforces problem page: $url"

    fun submitNoEditor(): String =
        if (zh) "当前没有打开编辑器。切换到 .cpp/.cc 文件后再提交。" else "No editor is open. Switch to a .cpp/.cc file and click Submit again."

    fun submitUnsupportedSource(extension: String): String =
        if (zh) "CPH 提交仅支持 C++ 文件（.cpp/.cc/.cxx/.cp），当前为 .$extension。" else "Only C++ files (.cpp/.cc/.cxx/.cp) are supported by CPH submit (got .$extension)."

    fun submitWaitingForBrowser(displayId: String): String =
        if (zh) "-> $displayId  等待 CPH Target Runner 浏览器扩展..." else "-> $displayId  Waiting for CPH Target Runner browser extension..."

    fun submitBrowserReceived(displayId: String): String =
        if (zh) "-> $displayId  浏览器已接收源码..." else "-> $displayId  Browser received source..."

    fun submitFailed(message: String): String =
        if (zh) "失败：$message" else "Failed: $message"

    fun submitPickupTimeout(): String =
        if (zh) "CPH Target Runner 浏览器扩展未接收提交请求。请聚焦 CF 题面并检查扩展。" else "CPH Target Runner browser extension did not pick up the submit request. Focus the CF problem tab and check the extension."

    fun submitVerdictTimeout(): String =
        if (zh) "提交在返回最终 Codeforces 评测结果前超时。" else "Submission timed out before a final Codeforces verdict."

    fun importSettings(): CphImportSettingsTexts = CphImportSettingsTexts(language)
}

internal class CphImportSettingsTexts(private val language: CphUiLanguage) {
    private val zh: Boolean
        get() = language == CphUiLanguage.ZH_CN

    val enableServer = if (zh) "启用 Competitive Companion 接收服务" else "Enable Competitive Companion receiver"
    val overwriteExisting = if (zh) "重新导入同一题目时覆盖已存在的源文件" else "Overwrite existing source files when re-importing the same problem"
    val importReceiver = if (zh) "导入服务" else "Import Receiver"
    val fileCreation = if (zh) "文件生成" else "File Creation"
    val port = if (zh) "监听端口：" else "Port:"
    val status = if (zh) "状态：" else "Status:"
    val sourceRoot = if (zh) "源代码根目录（相对项目）：" else "Source root (relative to project):"
    val pathTemplate = if (zh) "路径模板：" else "Path template:"
    val variables = if (zh) "路径模板变量" else "Path template variables"
    val cppTemplate = if (zh) "C++ 代码模板：" else "C++ code template:"
    val cppTemplatePath = if (zh) "模板文件路径（可选）：" else "Template file path (optional):"
    val cppTemplateGroup = if (zh) "C++ 代码模板" else "C++ Template"
    val cppTemplateVariables = if (zh) "C++ 模板变量" else "C++ template variables"
    val chooseCppTemplate = if (zh) "选择 C++ 模板文件" else "Choose C++ template file"
    val chooseCppTemplateDescription =
        if (zh) "选择导入题目时用于生成 cpp 文件的模板文件" else "Choose the template file used to create cpp files when importing problems"

    fun statusRunning(port: Int): String =
        if (zh) "运行中：127.0.0.1:$port" else "Running: 127.0.0.1:$port"

    fun statusDisabled(): String =
        if (zh) "已禁用" else "Disabled"

    fun statusStopped(): String =
        if (zh) "未启动" else "Stopped"

    fun statusError(port: Int, message: String): String =
        if (zh) "错误（端口 $port）：$message" else "Error (port $port): $message"

    fun pathVariableDescriptions(): List<Pair<String, String>> =
        if (zh) {
            listOf(
                "\${contest}" to "比赛 ID 或题单来源标识",
                "\${index}" to "题目编号，如 A、B1",
                "\${name}" to "原始题目标题",
                "\${slug}" to "适合作为文件名的题目标题",
                "\${source}" to "小写题目来源平台：codeforces、atcoder、luogu、kattis 或 generic",
            )
        } else {
            listOf(
                "\${contest}" to "Contest ID or problem-set source identifier",
                "\${index}" to "Problem index, such as A or B1",
                "\${name}" to "Original problem title",
                "\${slug}" to "Problem title normalized for file names",
                "\${source}" to "Lowercase source platform: codeforces, atcoder, luogu, kattis, or generic",
            )
        }

    fun cppTemplateVariableDescriptions(): List<Pair<String, String>> =
        if (zh) {
            listOf(
                "\${name}" to "原始题目标题",
                "\${url}" to "题目页面 URL",
                "\${group}" to "Competitive Companion 提供的分组或比赛名",
                "\${source}" to "小写题目来源平台",
                "\${contest}" to "比赛 ID 或题单来源标识",
                "\${index}" to "题目编号，如 A、B1",
                "\${slug}" to "适合作为文件名的题目标题",
                "\${timeLimit}" to "时间限制，单位 ms；缺失时为空",
                "\${memoryLimit}" to "内存限制；缺失时为空",
                "\${interactive}" to "是否为交互题：true 或 false",
                "\${date}" to "当前日期：yyyy-MM-dd",
                "\${datetime}" to "当前时间：yyyy-MM-dd HH:mm:ss",
                "\${fileName}" to "最终生成的 cpp 文件名",
                "\${cursor}" to "生成后光标位置，不会保留到文件中",
            )
        } else {
            listOf(
                "\${name}" to "Original problem title",
                "\${url}" to "Problem page URL",
                "\${group}" to "Group or contest name from Competitive Companion",
                "\${source}" to "Lowercase source platform",
                "\${contest}" to "Contest ID or problem-set source identifier",
                "\${index}" to "Problem index, such as A or B1",
                "\${slug}" to "Problem title normalized for file names",
                "\${timeLimit}" to "Time limit in ms; empty when unavailable",
                "\${memoryLimit}" to "Memory limit; empty when unavailable",
                "\${interactive}" to "Whether the problem is interactive: true or false",
                "\${date}" to "Current date: yyyy-MM-dd",
                "\${datetime}" to "Current time: yyyy-MM-dd HH:mm:ss",
                "\${fileName}" to "Final generated cpp file name",
                "\${cursor}" to "Caret position after creation; removed from the file",
            )
        }
}
