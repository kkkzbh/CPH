# CPH Target Runner

[English](README.md) | 简体中文

CPH Target Runner 是一个面向 CLion 的 C++ 竞赛刷题插件。它把样例管理、运行结果、输出对比、单文件模式、题目导入和 Codeforces 提交集中到右侧 `CPH` 工具窗口，让 CLion 里的本地调试流程接近 VS Code CPH。

[在线文档](https://cph.kkkzbh.cn/) | [浏览器扩展安装说明](INSTALL_EXTENSION.md)

![CPH 主界面：样例运行和输出差异定位](docs/assets/marketplace-main.png)

## 核心功能

- 为当前 CMake Target 或单个 `.cpp` 文件保存独立样例。
- 管理多个 Case，支持启用/禁用、单独运行和一键运行全部样例。
- 自动对比标准输出和期望输出，高亮定位 WA 差异。
- 提供纯单文件模式，适合不想维护多个 CMake Target 的日常竞赛刷题。
- 支持工作目录、时间限制、C++ 标准、编译选项、GCC bits 预编译头和全局快捷键。
- 内置 Competitive Companion 接收服务，可从 Codeforces、AtCoder、洛谷、Kattis 等平台导入题目和样例。
- 配合 CPH Target Runner 浏览器扩展，可将当前 `.cpp` 提交到浏览器当前打开的 Codeforces 题目。

## 快速开始

1. 在 CLion 中打开右侧 `CPH` 工具窗口。
2. 点击 `启动 CPH` 启用当前项目。
3. 在 Case 中填写输入和期望输出。
4. 点击 `运行` 测试当前 Case，或点击顶部运行按钮执行全部启用 Case。

运行后，工具窗口会显示标准输出、期望输出和最近一次运行结果。结果不一致时，CPH 会高亮差异行，方便快速定位问题。

![CPH 设置界面：单文件模式、编译选项和快捷键](docs/assets/marketplace-settings.png)

## 单文件模式

如果你主要用 CLion 写竞赛题，推荐启用纯单文件模式。启用后，一个 `.cpp` 文件就是一个独立运行目标，CPH 会随着当前编辑文件自动切换对应样例，不需要手动维护多个 CMake Target。

常用配置都在 `Settings / Tools / CPH Target Runner` 中，包括工作目录、C++ 标准、编译选项、输出比较方式和快捷键。

## 导入题目

CPH 内置 Competitive Companion 接收服务，默认监听 `127.0.0.1:10043`。安装 Competitive Companion 后，在题面点击浏览器扩展按钮，插件会自动创建源码文件、创建单文件运行配置、填入样例并打开文件。

更详细的路径模板、变量和平台规则见 [在线文档](https://cph.kkkzbh.cn/)。

## 提交到 Codeforces

安装 [CPH Target Runner 浏览器扩展](INSTALL_EXTENSION.md) 后，可以从工具窗顶部的提交按钮把当前编辑器中的 `.cpp` 文件提交到浏览器当前打开的 Codeforces 题目。

插件不保存 Codeforces 账号、密码或 Cookie；提交使用浏览器当前登录态完成。

## License

MIT
