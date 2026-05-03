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
    title: "快速开始",
    description: "设置入口、单文件模式和基础编译配置。",
    markdown: `
# 快速开始

点击设置按钮进入设置，再次点击则可关闭设置。

![CPH 设置按钮与设置面板](/assets/quick-start-settings-toggle.png)

对 CMake 不熟悉时，推荐使用纯单文件模式，行为与 VS Code CPH 一致。

- 工作目录配置：cpp 生成的 .exe 存放目录，相对路径起始为项目工作目录。
- C++ 标准：配置后会覆盖 CLion 自身的配置，全局生效。
- Compile options：可配置一些编译选项，覆盖 CLion 自身配置，全局生效。

![CPH 单文件模式相关设置](/assets/quick-start-single-file-settings.png)

## 单文件模式

不同的 CLion Target，CPH 测试数据独立。在单文件模式下，一个 cpp 文件即一个 CLion Target，每打开一个 cpp 文件，将自动为你切换至对应的 CLion Target。

![CPH 单文件模式自动切换 Target](/assets/quick-start-single-file-target.png)
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

![Codeforces 题面示例](/assets/companion-codeforces-page.png)

2. 点击浏览器工具栏中的 Competitive Companion 加号按钮。
3. 插件收到 payload 后会创建源码文件、创建单文件运行配置、填入样例并打开文件。

![CPH 导入题目后的 CLion 文件和样例](/assets/companion-import-result.png)

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

![CLion 中的 CPH 竞赛伴侣设置](/assets/companion-clion-settings.png)

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
];

export const groups: DocGroup[] = ["开始", "工作流"];
