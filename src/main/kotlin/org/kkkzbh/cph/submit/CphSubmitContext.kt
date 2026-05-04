package org.kkkzbh.cph.submit

internal enum class CphSubmitKind { CONTEST, GYM, PROBLEMSET, ACMSGURU }

internal data class CphSubmitContext(
    val kind: CphSubmitKind,
    val contestId: String,
    val problemIndex: String,
    val displayId: String,
    val problemPageUrl: String,
    val submitPageUrl: String,
) {
    fun submittedProblemFieldName(): String = when (kind) {
        CphSubmitKind.PROBLEMSET, CphSubmitKind.ACMSGURU -> "submittedProblemCode"
        else -> "submittedProblemIndex"
    }

    fun submittedProblemFieldValue(): String = when (kind) {
        CphSubmitKind.PROBLEMSET -> contestId + problemIndex
        CphSubmitKind.ACMSGURU -> problemIndex
        else -> problemIndex
    }
}

internal object CphSubmitContextResolver {
    private const val BASE = "https://codeforces.com"

    private val contestRe = Regex(
        """https?://(?:www\.)?codeforces\.com/contest/([^/]+)/problem/([^/?#]+)""",
        RegexOption.IGNORE_CASE,
    )
    private val gymRe = Regex(
        """https?://(?:www\.)?codeforces\.com/gym/([^/]+)/problem/([^/?#]+)""",
        RegexOption.IGNORE_CASE,
    )
    private val problemsetRe = Regex(
        """https?://(?:www\.)?codeforces\.com/problemset/problem/([^/]+)/([^/?#]+)""",
        RegexOption.IGNORE_CASE,
    )
    private val acmsguruRe = Regex(
        """https?://(?:www\.)?codeforces\.com/problemsets/acmsguru/problem/([^/]+)/([^/?#]+)""",
        RegexOption.IGNORE_CASE,
    )

    fun resolve(url: String): CphSubmitContext? {
        val u = url.trim()
        contestRe.find(u)?.let {
            val contest = it.groupValues[1]
            val index = it.groupValues[2]
            return CphSubmitContext(
                kind = CphSubmitKind.CONTEST,
                contestId = contest,
                problemIndex = index,
                displayId = "$contest/$index",
                problemPageUrl = "$BASE/contest/$contest/problem/$index",
                submitPageUrl = "$BASE/contest/$contest/submit",
            )
        }
        gymRe.find(u)?.let {
            val contest = it.groupValues[1]
            val index = it.groupValues[2]
            return CphSubmitContext(
                kind = CphSubmitKind.GYM,
                contestId = contest,
                problemIndex = index,
                displayId = "gym/$contest/$index",
                problemPageUrl = "$BASE/gym/$contest/problem/$index",
                submitPageUrl = "$BASE/gym/$contest/submit",
            )
        }
        problemsetRe.find(u)?.let {
            val contest = it.groupValues[1]
            val index = it.groupValues[2]
            return CphSubmitContext(
                kind = CphSubmitKind.PROBLEMSET,
                contestId = contest,
                problemIndex = index,
                displayId = "$contest/$index",
                problemPageUrl = "$BASE/problemset/problem/$contest/$index",
                submitPageUrl = "$BASE/problemset/submit",
            )
        }
        acmsguruRe.find(u)?.let {
            val index = it.groupValues[2]
            return CphSubmitContext(
                kind = CphSubmitKind.ACMSGURU,
                contestId = "acmsguru",
                problemIndex = index,
                displayId = "acmsguru/$index",
                problemPageUrl = "$BASE/problemsets/acmsguru/problem/99999/$index",
                submitPageUrl = "$BASE/problemsets/acmsguru/submit",
            )
        }
        return null
    }
}
