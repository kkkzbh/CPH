# CPH Target Runner

English | [简体中文](README.zh-CN.md)

CPH Target Runner is a CLion plugin for C++ competitive programming. It brings sample management, run results, output comparison, single-file mode, problem import, and Codeforces submission into the right-side `CPH` tool window, making the CLion local debugging workflow close to VS Code CPH.

[Documentation](https://cph.kkkzbh.cn/) | [Browser extension install guide](INSTALL_EXTENSION.md)

![CPH main UI: sample runs and output diff location](docs/assets/marketplace-main.png)

## Features

- Save independent samples for the current CMake target or a single `.cpp` file.
- Manage multiple cases with enable/disable, run-one, and run-all workflows.
- Compare stdout against expected output and highlight WA differences.
- Provide pure single-file mode for daily contest practice without maintaining many CMake targets.
- Configure working directory, time limit, C++ standard, compiler options, GCC bits precompiled header, and global shortcuts.
- Receive problems and samples from Codeforces, AtCoder, Luogu, Kattis, and other platforms through Competitive Companion.
- Submit the current `.cpp` file to the Codeforces problem open in the browser when used with the CPH Target Runner browser extension.

## Quick Start

1. Open the right-side `CPH` tool window in CLion.
2. Click `Start CPH` to enable the current project.
3. Fill in input and expected output for a case.
4. Click `Run` to test the current case, or use the top run button to execute all enabled cases.

After a run, the tool window shows stdout, expected output, and the latest result. When the output differs, CPH highlights the differing lines for quick debugging.

![CPH settings UI: single-file mode, compiler options, and shortcuts](docs/assets/marketplace-settings.png)

## Single-File Mode

If you mainly use CLion for contest problems, pure single-file mode is recommended. After it is enabled, each `.cpp` file becomes an independent run target, and CPH switches samples with the current editor file without requiring manual CMake target maintenance.

Common options are under `Settings / Tools / CPH Target Runner`, including working directory, C++ standard, compiler options, output comparison mode, and shortcuts.

## Import Problems

CPH includes a Competitive Companion receiver, listening on `127.0.0.1:10043` by default. After installing Competitive Companion, click the browser extension button on a problem page; the plugin creates the source file, creates a single-file run configuration, fills in samples, and opens the file.

See the [documentation](https://cph.kkkzbh.cn/) for path templates, variables, and platform rules.

## Submit to Codeforces

After installing the [CPH Target Runner browser extension](INSTALL_EXTENSION.md), use the submit button in the tool window to submit the current editor `.cpp` file to the Codeforces problem currently open in the browser.

The plugin does not store Codeforces accounts, passwords, or cookies. Submission uses the browser's current login session.

## License

MIT
