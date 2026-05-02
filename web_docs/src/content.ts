export type DocGroup = "开始" | "工作流" | "配置" | "排错";

export interface DocPage {
  id: string;
  group: DocGroup;
  title: string;
  description: string;
  markdown: string;
}

export const docPages: DocPage[] = [
  {
    id: "overview",
    group: "开始",
    title: "快速了解",
    description: "CPH Target Runner 的定位、界面和核心能力。",
    markdown: `
# 快速了解

CPH Target Runner 是一个面向 CLion 的竞赛样例管理插件。它把样例输入、期望输出、运行结果、差异查看和单题导入收拢到右侧 **CPH** 工具窗口，适合 Codeforces、AtCoder、洛谷等题目本地调试。

![GPT Image 生成的 CPH 工作流示意](/assets/cph-workflow-gpt.png)

## 它解决什么问题

- 不需要反复在终端复制输入，样例直接保存在当前 CMake Target 或 C/C++ File 运行配置下。
- 一键运行当前样例或全部启用样例，结果会显示为 AC、WA、RE、TLE 或 ERROR。
- WA 时可以直接看 Actual / Expected，支持双栏输出和差异高亮。
- 可以通过 Competitive Companion 从浏览器题面自动创建源码文件、运行配置和样例。
- CMake Application 样例支持 Debug，插件会把当前样例输入接到 CLion 原生调试器。

## 界面速记

| 区域 | 用途 |
| --- | --- |
| 顶部工具栏 | Run All、重置样例、进入当前 Target 设置 |
| 样例 Tab | 每个 Case 的状态、启用状态和选择入口 |
| Input | 当前样例的标准输入 |
| Expected | 当前样例的期望输出 |
| Actual | 最近一次运行得到的真实输出 |
| Settings | 时间限制、C++ 标准、编译参数、比较和输出布局 |

## 推荐工作流

1. 选择或创建一个 CLion CMake Application / C/C++ File 运行配置。
2. 打开右侧 **CPH** 工具窗口。
3. 粘贴样例，或使用 Competitive Companion 自动导入。
4. 点击 Run All 查看整体结果。
5. 对 WA 样例检查 Actual / Expected，对 RE/TLE 样例看状态栏和通知。
6. CMake Target 需要断点时，选中样例后点击 Debug。

![CPH Target Runner 插件截图](/assets/plugin-demo.png)
`,
  },
  {
    id: "install",
    group: "开始",
    title: "安装与启动",
    description: "构建插件 ZIP，并在 CLion 中启用 CPH 工具窗口。",
    markdown: `
# 安装与启动

## 从源码构建

在仓库根目录运行：

\`\`\`bash
./gradlew buildPlugin
\`\`\`

构建完成后，插件 ZIP 会生成在：

\`\`\`text
build/distributions/clion-cph-target-runner-1.0.4.zip
\`\`\`

## 在 CLion 中安装

1. 打开 **Settings**。
2. 进入 **Plugins**。
3. 点击齿轮菜单。
4. 选择 **Install Plugin from Disk...**。
5. 选中上一步生成的 ZIP。
6. 重启 CLion。

## 打开 CPH 工具窗口

插件声明了一个右侧 Tool Window，名称是 **CPH**。安装后可以在右侧工具窗口栏直接打开。

如果没有看到窗口，先确认当前 IDE 是 CLion，并且插件依赖的 CLion 模块已加载。

## 基础验证

打开一个 C++ 项目后：

1. 选择一个已有 CMake Application 运行配置，或创建 C/C++ File 运行配置。
2. 打开 **CPH** 工具窗口。
3. 在 **Input** 填入样例输入，在 **Expected** 填入期望输出。
4. 点击当前样例的 Run，或点击顶部 Run All。

如果程序输出和 Expected 一致，样例会显示 AC。
`,
  },
  {
    id: "manual-cases",
    group: "工作流",
    title: "手动管理样例",
    description: "添加、编辑、运行和比较当前 Target 的 Cases。",
    markdown: `
# 手动管理样例

CPH 按当前运行配置保存样例。切换 CMake Target 或 C/C++ File 运行配置时，工具窗口会自动刷新到对应的 Cases。

## 添加和编辑

- 每个样例有独立的 Input、Expected 和 Last Result。
- Input、Expected 会跟随当前 Case 保存。
- 删除当前 Case 后，插件会自动选择相邻 Case。
- 重置按钮会清空当前 Target 下所有 Cases，并创建一个新的 Case 1。

## 启用和跳过

Run All 只运行启用的样例。临时不想跑的大样例可以在 Case 上关闭启用状态，保留数据但不参与本轮运行。

## 比较规则

默认会忽略行尾空格和多余换行，更符合竞赛平台的常见输出判定习惯。需要严格比较时，到 Settings 里关闭：

\`\`\`text
忽略行尾空格和多余换行
\`\`\`

## 输出布局

Settings 中的 **双栏显示 Actual / Expected** 可以把实际输出和期望输出并排放置，适合检查长输出和多行 WA。

当输出很多时，优先把视线放在：

| 现象 | 处理方式 |
| --- | --- |
| 行数不同 | 先检查循环边界和遗漏输出 |
| 只有空格不同 | 根据题面要求决定是否关闭忽略空白 |
| 前几行正确、后面错 | 检查状态复用、排序和溢出 |
| stderr 有内容 | 优先处理运行时错误或断言 |
`,
  },
  {
    id: "companion",
    group: "工作流",
    title: "导入题目",
    description: "通过 Competitive Companion 自动创建源码、运行配置和样例。",
    markdown: `
# 导入题目

插件内置了 Competitive Companion 接收服务，默认监听本机：

\`\`\`text
127.0.0.1:10043
\`\`\`

## 准备浏览器扩展

1. 在 Chrome / Chromium 浏览器中安装 **Competitive Companion**。
2. 打开扩展设置，确认 Custom ports 中包含 **10043**。
3. 打开 CLion 项目，并确保 CPH 接收服务处于启用状态。

## 导入一题

1. 打开 Codeforces 题面，例如 \`https://codeforces.com/contest/1/problem/A\`。
2. 点击浏览器工具栏中的 Competitive Companion 加号按钮。
3. 插件收到 payload 后会创建源码文件、创建单文件运行配置、填入样例并打开文件。

## 默认落盘规则

默认配置来自插件当前实现：

| 配置项 | 默认值 |
| --- | --- |
| 源代码根目录 | \`cf\` |
| 路径模板 | \`\${contest}/\${index}.cpp\` |
| 监听端口 | \`10043\` |
| 覆盖已有文件 | 关闭 |

默认导入路径会类似：

\`\`\`text
cf/1/A.cpp
\`\`\`

## 自定义路径模板

在 **Settings / Tools / CPH 竞赛伴侣** 中可以修改路径模板。可用变量包括：

\`\`\`text
\${contest} \${index} \${name} \${slug} \${source}
\`\`\`

例如：

\`\`\`text
problems/\${source}/\${contest}/\${index}-\${slug}.cpp
\`\`\`

## 导入后发生了什么

插件会按顺序完成这些动作：

1. 根据模板计算源码文件路径。
2. 必要时创建父目录。
3. 写入默认 C++ 模板。
4. 创建或复用 C/C++ File 运行配置。
5. 把题目样例写入当前运行配置的 CPH Cases。
6. 将该运行配置设为当前选择项。
7. 打开源码文件。
`,
  },
  {
    id: "run-debug",
    group: "工作流",
    title: "运行与调试",
    description: "理解 Run All、单样例运行、Verdict 和 Debug 的边界。",
    markdown: `
# 运行与调试

## Run All

Run All 会运行当前 Target 下所有启用样例。运行前插件会保存文档，并同步当前 CPH 编译设置。

样例状态会经过排队、运行、完成三个阶段，最终显示 Verdict。

## Verdict 含义

| Verdict | 含义 |
| --- | --- |
| AC | 输出和 Expected 匹配 |
| WA | 程序正常退出，但输出不匹配 |
| RE | 程序以非 0 退出码结束 |
| TLE | 运行超过当前样例时间限制 |
| ERROR | 构建、运行配置、保存或参数同步失败 |

## CMake Target 运行

对 CMake Application，插件会解析可执行文件，必要时先触发构建，然后把样例输入写入进程 stdin。

## C/C++ File 运行

对 C/C++ File 运行配置，插件会复用 CLion 的 run-file 构建与启动能力。Competitive Companion 导入题目后默认就是这种配置。

## Debug 单个样例

当前实现的 Debug 支持 **CMake Application**。插件会创建临时 Debug 配置，把当前样例输入写入临时文件，并通过 CLion 的输入重定向接入原生调试器。

如果当前运行配置不是 CMake Application，Debug 会提示不支持。此时可以：

- 手动创建 CMake Target 后切换到该 Target。
- 或先用 Run 定位问题，再在 CLion 原生运行配置中调试。

## 时间限制

默认时间限制是 1000ms。可在 Settings 中调整，当前实现会限制在：

\`\`\`text
100ms ~ 60000ms
\`\`\`
`,
  },
  {
    id: "settings",
    group: "配置",
    title: "编译与导入配置",
    description: "C++ 标准、编译选项、输出比较和 CC 服务设置。",
    markdown: `
# 编译与导入配置

CPH 有两类配置：工具窗口内的当前运行设置，以及 IDE Settings 中的 Competitive Companion 导入设置。

## 工具窗口 Settings

点击 CPH 顶部齿轮按钮进入当前运行设置。

| 设置 | 说明 |
| --- | --- |
| Time Limits | 当前 Target 的样例超时时间 |
| C++ 标准 | 跟随 Target，或强制 C++11 / C++17 / C++20 / C++23 / C++26 |
| Compile options | 附加编译参数，例如 \`-O2 -Wall\` |
| 输出比较 | 是否忽略行尾空格和多余换行 |
| 输出布局 | 是否双栏显示 Actual / Expected |

## C++ 标准合并规则

当选择具体标准时，插件会移除已有的 \`-std=\` / \`/std:\` 标准参数，再追加新的标准参数，避免重复标准互相覆盖。

示例：

\`\`\`text
原参数: -O2 -std=c++17 -Wall
选择: C++20
结果: -O2 -Wall -std=c++20
\`\`\`

## Shell-like 参数

Compile options 支持常见 shell 风格引号：

\`\`\`text
-DLOCAL -I "third party/include" -Wall
\`\`\`

未闭合引号会被判为配置错误，运行时会显示 ERROR。

## Competitive Companion 设置

入口在：

\`\`\`text
Settings / Tools / CPH 竞赛伴侣
\`\`\`

这里可以配置：

- 是否启用接收服务。
- 监听端口。
- 导入源码根目录。
- 路径模板。
- 默认 C++ 代码模板。
- 重新导入时是否覆盖已存在源码文件。

端口被占用时，优先换到 Competitive Companion 默认端口列表中的其他端口，例如 10044、10046、10047。
`,
  },
  {
    id: "markdown-demo",
    group: "配置",
    title: "Markdown 展示",
    description: "文档站支持表格、任务列表、代码块、HTML 和图片预览。",
    markdown: `
# Markdown 展示

这个文档站的正文由 Markdown 渲染。它支持 GFM 表格、任务列表、代码高亮、HTML 片段和图片预览。

## 任务列表

- [x] 左侧 Tab 切换文档章节
- [x] 右侧目录跟随当前 Tab
- [x] Markdown 渲染表格、代码和图片
- [x] 图片点击预览
- [x] 代码块复制

## 表格

| 功能 | 支持情况 | 备注 |
| --- | --- | --- |
| 标题锚点 | 支持 | 右侧目录自动生成 |
| 图片 | 支持 | Markdown 图片语法即可 |
| 代码块 | 支持 | 自动高亮和复制 |
| HTML | 支持 | 适合少量自定义提示 |

## 代码高亮

\`\`\`cpp
#include <bits/stdc++.h>
using namespace std;

int main() {
    ios::sync_with_stdio(false);
    cin.tie(nullptr);

    int n;
    cin >> n;
    cout << n * 2 << '\\n';
}
\`\`\`

## HTML 片段

<div class="markdown-callout">
  <strong>提示：</strong> 文档内容来自本仓库当前实现，发布前建议随着插件功能变动同步更新。
</div>
`,
  },
  {
    id: "troubleshooting",
    group: "排错",
    title: "常见问题",
    description: "导入、运行、比较和调试时最常见的定位路径。",
    markdown: `
# 常见问题

## 浏览器点加号没有反应

先检查 **Settings / Tools / CPH 竞赛伴侣** 中的当前状态。

| 状态 | 处理 |
| --- | --- |
| 已禁用 | 勾选启用接收服务并应用 |
| 未启动 | 保存设置，或重启 CLion |
| 启动失败 | 端口可能被占用，换一个端口 |
| 运行中 | 检查浏览器扩展的 Custom ports |

还要确认当前有打开的 CLion 项目。没有打开项目时，服务会返回 \`No open project to receive payload\`。

## 导入到了错误项目

多个 CLion 窗口同时打开时，插件会优先选择最近聚焦的项目；如果无法判断，就使用第一个可用项目。

导入前先聚焦目标 CLion 窗口，可以减少误导入。

## Run All 显示 ERROR

优先看错误消息里的关键词：

- \`Save failed\`：文件保存失败，检查只读文件或 IDE 状态。
- \`Failed to sync CPH compile settings\`：编译标准或参数同步失败。
- \`Cannot resolve executable\`：CMake Target 尚未配置好或未构建。
- \`Invalid compile options\`：Compile options 里可能有未闭合引号。

## TLE 但平台能过

本地默认时间限制是 1000ms。题目本身时限更高，或本地 Debug/未优化运行较慢时，需要在 CPH Settings 中调高 Time Limits。

## WA 但看不出差异

1. 打开双栏 Actual / Expected。
2. 检查末尾空格、空行和大小写。
3. 临时关闭“忽略行尾空格和多余换行”，做严格比较。
4. 用更小的自造样例复现差异。

## Debug 按钮不可用或失败

当前 Debug 只支持 CMake Application。C/C++ File 导入题可以正常 Run，但 Debug 需要切到 CMake Target，或使用 CLion 原生调试配置。
`,
  },
];

export const groups: DocGroup[] = ["开始", "工作流", "配置", "排错"];
