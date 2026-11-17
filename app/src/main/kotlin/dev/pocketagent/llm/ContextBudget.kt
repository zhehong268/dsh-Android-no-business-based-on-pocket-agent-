package dev.pocketagent.llm

import kotlinx.serialization.json.*

/** Estimates model input, not HTTP bytes. Base64 is an image payload, not millions of text tokens. */
internal object ContextBudget {
    fun estimate(element: JsonElement, imageTokens: Int = 4096): Int = when (element) {
        is JsonObject -> when ((element["type"] as? JsonPrimitive)?.content) {
            "image", "image_url" -> imageTokens
            else -> 4 + element.entries.sumOf { (key, value) -> key.length / 4 + 1 + estimate(value, imageTokens) }
        }
        is JsonArray -> element.sumOf { estimate(it, imageTokens) }
        is JsonPrimitive -> {
            val text = element.content
            val ascii = text.count { it.code < 128 }
            1 + (ascii + 3) / 4 + text.length - ascii
        }
    }

    fun compactImages(messages: List<JsonObject>): List<JsonObject> {
        var kept = 0
        return messages.asReversed().map { message ->
            if ((message["role"] as? JsonPrimitive)?.content != "user") message else {
                val content = message["content"] as? JsonArray
                if (content == null) message else JsonObject(message.toMutableMap().apply {
                    put("content", JsonArray(content.asReversed().map { part ->
                        val obj = part as? JsonObject
                        val type = (obj?.get("type") as? JsonPrimitive)?.content
                        if (type in setOf("image", "image_url") && ++kept > 2) buildJsonObject {
                            put("type", "text"); put("text", "[较早截图已省略；以最新观察为准]")
                        } else part
                    }.asReversed()))
                })
            }
        }.asReversed()
    }

    fun trimOldScreens(messages:List<JsonObject>,context:Int,imageTokens:Int):List<JsonObject> {
        if(messages.sumOf { estimate(it,imageTokens) }<=context*0.7)return messages
        val latest=messages.indexOfLast { (it["role"] as? JsonPrimitive)?.content=="user" }
        fun strip(value:JsonElement):JsonElement=when(value) {
            is JsonPrimitive->if(value.isString)JsonPrimitive(value.content.replace(SCREEN,"[较早界面已省略，以最新观察为准]")) else value
            is JsonArray->JsonArray(value.map(::strip))
            is JsonObject->JsonObject(value.mapValues { strip(it.value) })
        }
        return messages.mapIndexed { index,m ->
            if(index<latest && (m["role"] as? JsonPrimitive)?.content=="user")strip(m) as JsonObject else m
        }
    }
    private val SCREEN=Regex("<screen_data(?:\\s[^>]*)?>[\\s\\S]*?</screen_data>")
}
