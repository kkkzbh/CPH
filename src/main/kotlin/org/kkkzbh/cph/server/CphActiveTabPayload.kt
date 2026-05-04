package org.kkkzbh.cph.server

import com.google.gson.JsonParser
import com.google.gson.JsonSyntaxException

internal data class CphActiveTabPayload(val url: String, val title: String) {
    companion object {
        fun parse(body: String): CphActiveTabPayload? {
            if (body.isBlank()) return null
            return try {
                val obj = JsonParser.parseString(body).asJsonObject
                val url = obj.get("url")?.takeIf { !it.isJsonNull }?.asString?.trim().orEmpty()
                if (url.isBlank()) return null
                val title = obj.get("title")?.takeIf { !it.isJsonNull }?.asString.orEmpty()
                CphActiveTabPayload(url = url, title = title)
            } catch (_: JsonSyntaxException) {
                null
            } catch (_: IllegalStateException) {
                null
            } catch (_: ClassCastException) {
                null
            }
        }
    }
}
