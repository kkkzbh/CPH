package org.kkkzbh.cph.server

import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

internal data class CphRenderedCppTemplate(
    val text: String,
    val cursorOffset: Int?,
)

internal object CphCppTemplateRenderer {
    private val variable = Regex("""\$\{([A-Za-z][A-Za-z0-9_]*)\}""")
    private val dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    private val dateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    fun render(
        template: String,
        payload: CompetitiveCompanionPayload,
        coords: CphProblemCoordinates,
        fileName: String,
        now: LocalDateTime = LocalDateTime.now(),
    ): CphRenderedCppTemplate {
        val mapping = mapOf(
            "name" to payload.name,
            "url" to payload.url,
            "group" to payload.group,
            "source" to coords.source,
            "contest" to coords.contest,
            "index" to coords.index,
            "slug" to coords.slug,
            "timeLimit" to payload.timeLimit?.toString().orEmpty(),
            "memoryLimit" to payload.memoryLimit?.toString().orEmpty(),
            "interactive" to payload.interactive.toString(),
            "date" to now.format(dateFormatter),
            "datetime" to now.format(dateTimeFormatter),
            "fileName" to fileName,
        )
        val out = StringBuilder(template.length)
        var cursorOffset: Int? = null
        var cursor = 0

        variable.findAll(template).forEach { match ->
            out.append(template, cursor, match.range.first)
            val key = match.groupValues[1]
            when {
                key == "cursor" -> {
                    if (cursorOffset == null) cursorOffset = out.length
                }
                mapping.containsKey(key) -> out.append(mapping.getValue(key))
                else -> out.append(match.value)
            }
            cursor = match.range.last + 1
        }
        out.append(template, cursor, template.length)

        return CphRenderedCppTemplate(
            text = out.toString(),
            cursorOffset = cursorOffset,
        )
    }
}

internal object CphCppTemplateLoader {
    fun load(settings: CphImportSettingsState, baseDir: String): String {
        val path = settings.cppTemplatePath.trim()
        if (path.isBlank()) return settings.cppTemplate

        val file = resolveFile(path, baseDir)
        if (!file.isFile) error("C++ template file does not exist: ${file.path}")
        if (!file.canRead()) error("C++ template file is not readable: ${file.path}")

        val text = file.readText(Charsets.UTF_8)
        if (text.isBlank()) error("C++ template file is empty: ${file.path}")
        return text
    }

    fun resolveFile(path: String, baseDir: String): File {
        val file = File(path)
        return if (file.isAbsolute) file.normalize() else File(baseDir, path).normalize()
    }
}
