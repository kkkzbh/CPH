# 安装 CPH Target Runner 浏览器扩展

CPH 工具窗里的提交按钮会使用浏览器当前打开的 Codeforces 题面。CPH Target Runner 浏览器扩展会把当前活动标签页 URL 通过 `127.0.0.1` 发给 IDE，再从 IDE 接收一条待提交的源码任务，并使用当前浏览器里的 Codeforces 登录态完成提交。

提交时，扩展会保留当前题面标签页不动，另外打开同一场比赛的 **Submit Code** 标签页，填写 Codeforces 官方提交表单。练习、现场赛、虚拟赛的提交身份由 Codeforces 根据当前账号状态自动处理。

## Chrome / Edge / Brave

1. 打开 `chrome://extensions`。
2. 打开右上角的 **开发者模式**。
3. 点击 **加载已解压的扩展程序**。
4. 如果已经运行过 `./gradlew buildPlugin`，选择 `build/distributions/cph-target-runner-browser-1.0.0/`；如果是在当前源码仓库里开发，选择 `browser-extension/cph-target-runner/`。
5. 完成。扩展现在会向 IDE 上报当前活动的 CF 题面，并等待 IDE 下发提交任务。

## Firefox

当前扩展使用 Chromium Manifest V3 API（`chrome.scripting` 和 service worker）。一键提交请使用 Chrome、Edge、Brave 或其他 Chromium 浏览器。

## 验证是否可用

- 打开任意 CF 题面，例如 `https://codeforces.com/contest/4/problem/A`。
- 确认你已经在同一个浏览器里登录 Codeforces。
- 鼠标悬停在 CPH 工具窗的 📤 提交按钮上，提示文本应显示 `Submit current file → 4/A`。
- 扩展弹窗只显示简单说明；活动标签页、端口等实时状态在 IDE 的 CPH 工具窗里显示，不在扩展弹窗里显示。

## 自定义 IDE 端口

如果你在 `Settings / Tools / CPH 竞赛伴侣` 中修改了 CPH 监听端口，也需要把新端口告诉浏览器扩展：

1. 打开 `chrome://extensions/?id=<your-extension-id>` → **Service worker** → **Inspect** → DevTools 控制台。
2. 执行 `chrome.storage.local.set({ customPorts: [10044] })`，把 `10044` 换成你的端口。

扩展默认会依次尝试 `10043, 10044, 10045, 10046, 10047, 1327`。
