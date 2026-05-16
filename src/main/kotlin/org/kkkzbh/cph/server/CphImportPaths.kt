package org.kkkzbh.cph.server

import java.util.Locale

internal data class CphProblemCoordinates(
    val contest: String,
    val index: String,
    val name: String,
    val slug: String,
    val source: String,
)

internal object CphImportPaths {
    private val codeforcesContestProblem =
        Regex("""codeforces\.com/(?:contest|gym)/([^/]+)/problem/([^/?#]+)""", RegexOption.IGNORE_CASE)
    private val codeforcesProblemSet =
        Regex("""codeforces\.com/problemset/problem/([^/]+)/([^/?#]+)""", RegexOption.IGNORE_CASE)
    private val acmsguru =
        Regex("""codeforces\.com/problemsets/acmsguru/problem/([^/]+)/([^/?#]+)""", RegexOption.IGNORE_CASE)
    private val atCoderTask =
        Regex("""atcoder\.jp/contests/([^/]+)/tasks/([^/?#]+)""", RegexOption.IGNORE_CASE)
    private val luoguContestProblem =
        Regex("""luogu\.com\.cn/contest/([^/]+)/problem/([^/?#]+)""", RegexOption.IGNORE_CASE)
    private val luoguProblem =
        Regex("""luogu\.com\.cn/problem/([^/?#]+)""", RegexOption.IGNORE_CASE)
    private val kattisContestProblem =
        Regex("""open\.kattis\.com/contests/([^/]+)/problems/([^/?#]+)""", RegexOption.IGNORE_CASE)
    private val kattisProblem =
        Regex("""open\.kattis\.com/problems/([^/?#]+)""", RegexOption.IGNORE_CASE)
    private val nameLeader = Regex("""^\s*([A-Za-z0-9]+)\s*[.\-:]\s*(.*)$""")

    fun coordinates(payload: CompetitiveCompanionPayload): CphProblemCoordinates {
        val (rawContest, rawIndex, source) = parseUrl(payload) ?: parseFromName(payload)
        val contest = sanitize(rawContest, fallback = "misc")
        val index = sanitize(rawIndex, fallback = sanitize(payload.name, fallback = "problem"))
        val displayName = payload.name.trim()
        val slug = slugify(payload.name).ifBlank { index.lowercase(Locale.ROOT) }
        return CphProblemCoordinates(
            contest = contest,
            index = index,
            name = displayName,
            slug = slug,
            source = source,
        )
    }

    fun render(template: String, coords: CphProblemCoordinates): String {
        val mapping = mapOf(
            "contest" to coords.contest,
            "index" to coords.index,
            "name" to coords.name,
            "slug" to coords.slug,
            "source" to coords.source,
        )
        return template.replace(VARIABLE) { match ->
            val key = match.groupValues[1]
            mapping[key] ?: match.value
        }.let(::stripUnsafeSegments)
    }

    private fun parseUrl(payload: CompetitiveCompanionPayload): Triple<String, String, String>? {
        val url = payload.url
        if (url.isBlank()) return null
        codeforcesContestProblem.find(url)?.let {
            return Triple(it.groupValues[1], it.groupValues[2], "codeforces")
        }
        codeforcesProblemSet.find(url)?.let {
            return Triple(it.groupValues[1], it.groupValues[2], "codeforces")
        }
        acmsguru.find(url)?.let {
            return Triple("acmsguru", it.groupValues[2], "codeforces")
        }
        atCoderTask.find(url)?.let {
            return Triple(
                it.groupValues[1],
                atCoderIndex(payload.name, it.groupValues[1], it.groupValues[2]),
                "atcoder",
            )
        }
        luoguContestProblem.find(url)?.let {
            return Triple(it.groupValues[1], it.groupValues[2], "luogu")
        }
        luoguProblem.find(url)?.let {
            return Triple("problems", it.groupValues[1], "luogu")
        }
        kattisContestProblem.find(url)?.let {
            return Triple(it.groupValues[1], it.groupValues[2], "kattis")
        }
        kattisProblem.find(url)?.let {
            return Triple("problems", it.groupValues[1], "kattis")
        }
        return null
    }

    private fun parseFromName(payload: CompetitiveCompanionPayload): Triple<String, String, String> {
        val match = nameLeader.find(payload.name)
        val contest = payload.group.takeIf { it.isNotBlank() } ?: "misc"
        val index = match?.groupValues?.getOrNull(1) ?: payload.name
        return Triple(contest, index, "generic")
    }

    private fun atCoderIndex(name: String, contest: String, task: String): String {
        nameLeader.find(name)?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() }?.let { return it }
        val prefix = "${contest.lowercase(Locale.ROOT)}_"
        val normalizedTask = task.lowercase(Locale.ROOT)
        val suffix = if (normalizedTask.startsWith(prefix)) {
            task.substring(prefix.length)
        } else {
            task.substringAfterLast('_', task)
        }
        return suffix.uppercase(Locale.ROOT)
    }

    private fun sanitize(value: String, fallback: String): String {
        val cleaned = value.trim()
            .replace(Regex("""[\\/:*?"<>|\s]+"""), "_")
            .trim('_', '.')
        return cleaned.ifBlank { fallback }
    }

    private fun slugify(value: String): String {
        return value.lowercase(Locale.ROOT)
            .replace(Regex("""[^a-z0-9]+"""), "-")
            .trim('-')
    }

    private fun stripUnsafeSegments(path: String): String {
        return path.split('/')
            .map { it.replace("..", "_") }
            .filter { it.isNotEmpty() }
            .joinToString("/")
    }

    private val VARIABLE = Regex("""\$\{(contest|index|name|slug|source)\}""")
}
