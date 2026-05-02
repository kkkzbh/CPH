package org.kkkzbh.cph.server

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonSyntaxException

internal data class CompetitiveCompanionTest(
    val input: String = "",
    val output: String = "",
)

internal data class CompetitiveCompanionPayload(
    val name: String = "",
    val group: String = "",
    val url: String = "",
    val interactive: Boolean = false,
    val memoryLimit: Long? = null,
    val timeLimit: Long? = null,
    val tests: List<CompetitiveCompanionTest> = emptyList(),
)

internal object CompetitiveCompanionParser {
    fun parse(json: String): CompetitiveCompanionPayload? {
        if (json.isBlank()) return null
        val element = runCatching { JsonParser.parseString(json) }
            .getOrElse { return if (it is JsonSyntaxException) null else throw it }
        if (element == null || !element.isJsonObject) return null
        val obj = element.asJsonObject

        val payload = CompetitiveCompanionPayload(
            name = obj.string("name"),
            group = obj.string("group"),
            url = obj.string("url"),
            interactive = obj.bool("interactive"),
            memoryLimit = obj.longOrNull("memoryLimit"),
            timeLimit = obj.longOrNull("timeLimit"),
            tests = parseTests(obj.get("tests")),
        )

        return payload.takeIf { it.name.isNotBlank() || it.tests.isNotEmpty() }
    }

    private fun parseTests(element: JsonElement?): List<CompetitiveCompanionTest> {
        if (element == null || !element.isJsonArray) return emptyList()
        return element.asJsonArray.mapNotNull { node ->
            if (node == null || !node.isJsonObject) return@mapNotNull null
            val obj = node.asJsonObject
            CompetitiveCompanionTest(
                input = obj.string("input"),
                output = obj.string("output"),
            )
        }
    }

    private fun JsonObject.string(name: String): String {
        val node = this.get(name) ?: return ""
        if (node.isJsonNull) return ""
        if (node.isJsonPrimitive) return node.asString
        return ""
    }

    private fun JsonObject.bool(name: String): Boolean {
        val node = this.get(name) ?: return false
        if (node.isJsonNull || !node.isJsonPrimitive) return false
        val prim = node.asJsonPrimitive
        return when {
            prim.isBoolean -> prim.asBoolean
            prim.isString -> prim.asString.equals("true", ignoreCase = true)
            else -> false
        }
    }

    private fun JsonObject.longOrNull(name: String): Long? {
        val node = this.get(name) ?: return null
        if (node.isJsonNull || !node.isJsonPrimitive) return null
        val prim = node.asJsonPrimitive
        return when {
            prim.isNumber -> runCatching { prim.asLong }.getOrNull()
            prim.isString -> runCatching { prim.asString.toLong() }.getOrNull()
            else -> null
        }
    }
}
