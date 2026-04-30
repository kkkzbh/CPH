package org.kkkzbh.cph

object CphComparator {
    fun compare(actual: String, expected: String, ignoreTrailingWhitespace: Boolean): Pair<Boolean, String> {
        val normalizedActual = normalize(actual, ignoreTrailingWhitespace)
        val normalizedExpected = normalize(expected, ignoreTrailingWhitespace)
        if (normalizedActual == normalizedExpected) return true to "Accepted"

        val index = firstDifference(normalizedActual, normalizedExpected)
        val message = if (index == null) {
            "Output differs."
        } else {
            "First difference at character ${index + 1}."
        }
        return false to message
    }

    fun differingActualLines(actual: String, expected: String, ignoreTrailingWhitespace: Boolean): Set<Int> {
        val actualLines = comparableLines(actual, ignoreTrailingWhitespace)
        val expectedLines = comparableLines(expected, ignoreTrailingWhitespace)
        return actualLines.indices
            .filter { index -> index >= expectedLines.size || actualLines[index] != expectedLines[index] }
            .toSet()
    }

    private fun normalize(value: String, ignoreTrailingWhitespace: Boolean): String {
        val lf = value.replace("\r\n", "\n").replace('\r', '\n').trimEnd('\n')
        if (!ignoreTrailingWhitespace) return lf
        return lf.lines().joinToString("\n") { it.trimEnd() }.trimEnd()
    }

    private fun comparableLines(value: String, ignoreTrailingWhitespace: Boolean): List<String> {
        val normalized = value.replace("\r\n", "\n").replace('\r', '\n').trimEnd('\n')
        if (normalized.isEmpty()) return emptyList()
        val lines = normalized.lines()
        return if (ignoreTrailingWhitespace) {
            lines.map { it.trimEnd() }
        } else {
            lines
        }
    }

    private fun firstDifference(left: String, right: String): Int? {
        val length = minOf(left.length, right.length)
        for (i in 0 until length) {
            if (left[i] != right[i]) return i
        }
        return if (left.length == right.length) null else length
    }
}
