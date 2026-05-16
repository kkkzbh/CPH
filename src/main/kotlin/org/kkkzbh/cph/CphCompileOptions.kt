package org.kkkzbh.cph

import com.intellij.execution.ExecutionException

enum class CphCppStandard(
    val displayName: String,
    val flag: String?,
    val cmakeValue: String?,
) {
    FOLLOW_TARGET("跟随 Target", null, null),
    CPP11("C++11", "-std=c++11", "11"),
    CPP17("C++17", "-std=c++17", "17"),
    CPP20("C++20", "-std=c++20", "20"),
    CPP23("C++23", "-std=c++23", "23"),
    CPP26("C++26", "-std=c++26", "26");

    override fun toString(): String =
        if (this == FOLLOW_TARGET) CphText.current().cppFollowTarget else displayName
}

data class CphCompileSettings(
    val cppStandard: CphCppStandard = CphCppStandard.FOLLOW_TARGET,
    val compileOptions: String = "",
    val gccBitsPchEnabled: Boolean = false,
) {
    fun hasOverrides(): Boolean = cppStandard != CphCppStandard.FOLLOW_TARGET || compileOptions.isNotBlank()
}

internal object CphCompileOptions {
    fun mergeCompilerOptions(originalOptions: String, settings: CphCompileSettings): String {
        val original = parseShellLike(originalOptions)
        val merged = mergeCompilerArgs(original, additionalArgs(settings), settings.cppStandard)
        return merged.joinToString(" ") { quoteShellLike(it) }
    }

    fun renderCompilerOptions(args: List<String>): String =
        args.joinToString(" ") { quoteShellLike(it) }

    fun additionalArgs(settings: CphCompileSettings): List<String> {
        val additional = parseShellLike(settings.compileOptions)
        return if (settings.cppStandard == CphCppStandard.FOLLOW_TARGET) {
            additional
        } else {
            removeStandardFlags(additional)
        }
    }

    fun mergeCompilerArgs(
        originalArgs: List<String>,
        additionalArgs: List<String>,
        standard: CphCppStandard,
    ): List<String> {
        val baseArgs = if (standard.flag == null) originalArgs else removeStandardFlags(originalArgs)
        val userArgs = if (standard.flag == null) additionalArgs else removeStandardFlags(additionalArgs)
        return buildList {
            addAll(baseArgs)
            addAll(userArgs)
            standard.flag?.let(::add)
        }
    }

    fun parseShellLike(text: String): List<String> {
        if (text.isBlank()) return emptyList()
        val result = mutableListOf<String>()
        val current = StringBuilder()
        var quote: Char? = null
        var escaping = false
        var tokenStarted = false

        for (char in text) {
            when {
                escaping -> {
                    current.append(char)
                    escaping = false
                    tokenStarted = true
                }
                char == '\\' -> {
                    escaping = true
                    tokenStarted = true
                }
                quote != null -> {
                    if (char == quote) {
                        quote = null
                    } else {
                        current.append(char)
                    }
                    tokenStarted = true
                }
                char == '\'' || char == '"' -> {
                    quote = char
                    tokenStarted = true
                }
                char.isWhitespace() -> {
                    if (tokenStarted) {
                        result.add(current.toString())
                        current.clear()
                        tokenStarted = false
                    }
                }
                else -> {
                    current.append(char)
                    tokenStarted = true
                }
            }
        }

        if (escaping) {
            current.append('\\')
        }
        if (quote != null) {
            throw ExecutionException("Invalid compile options: unclosed quote.")
        }
        if (tokenStarted) {
            result.add(current.toString())
        }
        return result
    }

    fun removeStandardFlags(args: List<String>): List<String> {
        val result = mutableListOf<String>()
        var skipNext = false
        args.forEach { arg ->
            if (skipNext) {
                skipNext = false
                return@forEach
            }
            when {
                arg == "-std" || arg == "/std" -> skipNext = true
                arg.startsWith("-std=") -> Unit
                arg.startsWith("/std:") -> Unit
                else -> result.add(arg)
            }
        }
        return result
    }

    fun quoteShellLike(value: String): String {
        if (value.isEmpty()) return "''"
        if (value.all { it.isLetterOrDigit() || it in SAFE_UNQUOTED_CHARS }) return value
        return "'" + value.replace("'", "'\\''") + "'"
    }

    fun quoteCMakeArgument(value: String): String {
        val escaped = buildString {
            value.forEach { char ->
                when (char) {
                    '\\' -> append("\\\\")
                    '"' -> append("\\\"")
                    '$' -> append("\\$")
                    else -> append(char)
                }
            }
        }
        return "\"$escaped\""
    }

    private val SAFE_UNQUOTED_CHARS = setOf('_', '-', '.', '/', ':', '=', '+', ',')
}
