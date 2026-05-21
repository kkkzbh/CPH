package org.kkkzbh.cph.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.LocalDateTime

class CphCppTemplateRendererTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun rendersCodeforcesProblemVariables() {
        val payload = CompetitiveCompanionPayload(
            name = "A. Theatre Square",
            group = "Codeforces Round",
            url = "https://codeforces.com/contest/1/problem/A",
            timeLimit = 1000,
            memoryLimit = 256,
        )
        val coords = CphImportPaths.coordinates(payload)

        val rendered = CphCppTemplateRenderer.render(
            template = """
                // ${'$'}{name}
                // ${'$'}{url}
                // ${'$'}{source}/${'$'}{contest}/${'$'}{index}
                // ${'$'}{timeLimit} ${'$'}{memoryLimit}
            """.trimIndent(),
            payload = payload,
            coords = coords,
            fileName = "A.cpp",
            now = LocalDateTime.of(2026, 5, 20, 12, 30, 45),
        )

        assertEquals(
            """
                // A. Theatre Square
                // https://codeforces.com/contest/1/problem/A
                // codeforces/1/A
                // 1000 256
            """.trimIndent(),
            rendered.text,
        )
    }

    @Test
    fun rendersPlatformCoordinatesFromExistingImportParser() {
        val cases = listOf(
            CompetitiveCompanionPayload(
                name = "D - AABCC",
                url = "https://atcoder.jp/contests/abc300/tasks/abc300_d",
            ) to "atcoder/abc300/D",
            CompetitiveCompanionPayload(
                name = "P1000 超级玛丽游戏",
                url = "https://www.luogu.com.cn/problem/P1000",
            ) to "luogu/problems/P1000",
            CompetitiveCompanionPayload(
                name = "Hello World!",
                url = "https://open.kattis.com/problems/hello",
            ) to "kattis/problems/hello",
        )

        cases.forEach { (payload, expected) ->
            val coords = CphImportPaths.coordinates(payload)
            val rendered = CphCppTemplateRenderer.render(
                template = "${'$'}{source}/${'$'}{contest}/${'$'}{index}",
                payload = payload,
                coords = coords,
                fileName = "${coords.index}.cpp",
            )
            assertEquals(expected, rendered.text)
        }
    }

    @Test
    fun removesCursorMarkerAndReturnsOffset() {
        val payload = CompetitiveCompanionPayload(name = "A", url = "https://codeforces.com/contest/1/problem/A")
        val coords = CphImportPaths.coordinates(payload)

        val rendered = CphCppTemplateRenderer.render(
            template = "int main() {\n    ${'$'}{cursor}\n}\n",
            payload = payload,
            coords = coords,
            fileName = "A.cpp",
        )

        assertEquals("int main() {\n    \n}\n", rendered.text)
        assertEquals("int main() {\n    ".length, rendered.cursorOffset)
    }

    @Test
    fun keepsUnknownVariablesAndRendersDateFields() {
        val payload = CompetitiveCompanionPayload(name = "A", url = "https://codeforces.com/contest/1/problem/A")
        val coords = CphImportPaths.coordinates(payload)

        val rendered = CphCppTemplateRenderer.render(
            template = "${'$'}{date} ${'$'}{datetime} ${'$'}{fileName} ${'$'}{unknown}",
            payload = payload,
            coords = coords,
            fileName = "A.cpp",
            now = LocalDateTime.of(2026, 5, 20, 12, 30, 45),
        )

        assertEquals("2026-05-20 2026-05-20 12:30:45 A.cpp ${'$'}{unknown}", rendered.text)
    }

    @Test
    fun missingLimitsRenderAsEmptyStrings() {
        val payload = CompetitiveCompanionPayload(name = "A", url = "https://codeforces.com/contest/1/problem/A")
        val coords = CphImportPaths.coordinates(payload)

        val rendered = CphCppTemplateRenderer.render(
            template = "tl=${'$'}{timeLimit};ml=${'$'}{memoryLimit}",
            payload = payload,
            coords = coords,
            fileName = "A.cpp",
        )

        assertEquals("tl=;ml=", rendered.text)
    }

    @Test
    fun loaderUsesInlineTemplateWhenPathIsBlank() {
        val settings = CphImportSettingsState(
            cppTemplatePath = "",
            cppTemplate = "inline template",
        )

        assertEquals("inline template", CphCppTemplateLoader.load(settings, temp.root.path))
    }

    @Test
    fun loaderResolvesRelativeTemplatePathAgainstProjectBaseDir() {
        val templateFile = temp.newFile("template.cpp")
        templateFile.writeText("external template")
        val settings = CphImportSettingsState(
            cppTemplatePath = "template.cpp",
            cppTemplate = "inline template",
        )

        assertEquals("external template", CphCppTemplateLoader.load(settings, temp.root.path))
    }

    @Test
    fun loaderRejectsMissingTemplateFile() {
        val settings = CphImportSettingsState(
            cppTemplatePath = "missing.cpp",
            cppTemplate = "inline template",
        )

        val error = assertThrows(IllegalStateException::class.java) {
            CphCppTemplateLoader.load(settings, temp.root.path)
        }
        assertEquals(
            "C++ template file does not exist: ${temp.root.resolve("missing.cpp").path}",
            error.message,
        )
    }

    @Test
    fun loaderRejectsEmptyTemplateFile() {
        val templateFile = temp.newFile("empty.cpp")
        val settings = CphImportSettingsState(
            cppTemplatePath = "empty.cpp",
            cppTemplate = "inline template",
        )

        val error = assertThrows(IllegalStateException::class.java) {
            CphCppTemplateLoader.load(settings, temp.root.path)
        }
        assertEquals("C++ template file is empty: ${templateFile.path}", error.message)
    }
}
